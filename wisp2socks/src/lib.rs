#[allow(dead_code)]
mod android_logger;
use crate::android_logger::AndroidLogger;
use std::{net::SocketAddr, sync::Arc};
use std::sync::Mutex;
use tokio::runtime::Runtime;
use tokio::sync::oneshot;
use std::sync::OnceLock;
use anyhow::{Result, Context};
use jni::objects::JObject;
use jni::sys::jstring;
use jni::JNIEnv;
use tokio::select;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use wisp_mux::{ws::{TokioWebsocketsTransport, TransportExt}, packet::StreamType};
use reqwest::{Client, Proxy};
use jni::objects::JString;
use tokio_websockets::ClientBuilder;
use http::Uri;

static SERVICE: OnceLock<Mutex<Option<oneshot::Sender<()>>>> = OnceLock::new();
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

fn get_service() -> &'static Mutex<Option<oneshot::Sender<()>>> {
    SERVICE.get_or_init(|| Mutex::new(None))
}

fn get_runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| Runtime::new().unwrap())
}

// Todo: Maybe not using reqwest for doh.. but it works so whatever
async fn forward_dns_over_doh(query: &[u8], port: i32, doh_url: &str) -> Result<Vec<u8>> {
    let proxy_addr = format!("socks5://127.0.0.1:{}", port);

    let proxy = Proxy::all(&proxy_addr)
        .context(format!("Failed to create proxy for {}", proxy_addr))?;

    let client = Client::builder()
        .proxy(proxy)
        .build()
        .context("Failed to build reqwest client")?;

    let response = client
        .post(doh_url)
        .header("Content-Type", "application/dns-message")
        .body(query.to_vec())
        .send()
        .await
        .context(format!("Failed to send request to {}", doh_url))?;

    let bytes = response
        .bytes()
        .await
        .context("Failed to read response body")?;


    Ok(bytes.to_vec())
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "C" fn Java_com_mercuryworkshop_whisperandroid_WhisperService_startWisp2Socks(
    env: JNIEnv,
    _: JObject,
    url: JString,
    port: i32,
    doh_url: JString
) -> jstring {
    let empty = env.new_byte_array(0).unwrap_or_else(|_| std::ptr::null_mut());
    let logger = match AndroidLogger::new(&env, "WhisperService") {
        Ok(l) => l,
        Err(_) => return empty,
    };
    let result = get_runtime().block_on(async {
        let service = get_service();
        if service.lock().unwrap().is_some() {
            return "Service is already running".to_string();
        }

        let wisp_url: String = env.get_string(url).unwrap().into();
        let rust_doh_url: String = env.get_string(doh_url).unwrap().into();
        let socks_addr = format!("127.0.0.1:{}", port);

        match start_service(&wisp_url, &socks_addr, port, &rust_doh_url).await {
            Ok(_) => "Service started successfully".to_string(),
            Err(e) => format!("Failed to start service: {}", e),
        }
    });
    let _ = logger.i(&result);
    let output = env.new_string(&result).expect("Couldn't create java string!");
    output.into_inner()
}

#[unsafe(no_mangle)]
#[allow(non_snake_case)]
pub unsafe extern "C" fn Java_com_mercuryworkshop_whisperandroid_WhisperService_stopWisp2Socks(
    env: JNIEnv,
    _: JObject,
) -> jstring {
    let empty = env.new_byte_array(0).unwrap_or_else(|_| std::ptr::null_mut());
    let logger = match AndroidLogger::new(&env, "WhisperService") {
        Ok(l) => l,
        Err(_) => return empty,
    };
    let result = if let Some(shutdown_signal) = get_service().lock().unwrap().take() {
        shutdown_signal.send(()).ok();
        "Service stopped successfully".to_string()
    } else {
        "Service was not running".to_string()
    };

    let _ = logger.i(&result);
    let output = env.new_string(&result).expect("Couldn't create java string!");
    output.into_inner()
}

async fn start_service(wisp_url: &str, socks_addr: &str, port: i32, doh_url: &str) -> Result<()> {
    let socks_addr = socks_addr.parse::<SocketAddr>().context("Invalid socks address")?;
    let listener = tokio::net::TcpListener::bind(socks_addr)
        .await
        .context("Failed to bind to socks address")?;

    let (shutdown_tx, shutdown_rx) = oneshot::channel();
    *get_service().lock().unwrap() = Some(shutdown_tx);

    
    let uri: Uri = wisp_url
        .parse()
        .context("Invalid WebSocket URI")?;

    let builder = ClientBuilder::from_uri(uri);
    let (ws, _resp): (_, http::Response<()>) = builder
    .connect()
    .await
    .context("Failed to connect to Wisp server")?;


    let transport = TokioWebsocketsTransport(ws);
    let (rx, tx) = transport.split_fast();
    let wisp = wisp_mux::ClientMux::new(rx, tx, None)
        .await
        .context("Failed to upgrade to Wisp")?
        .with_no_required_extensions();

    let wisp_mux = Arc::new(wisp.0);
    let wisp_fut = wisp.1;

    tokio::spawn(async move {
        if let Err(e) = wisp_fut.await {
            eprintln!("WebSocket connection error: {}", e);
        }
    });
    
    let doh_url = doh_url.to_string();

    tokio::spawn(async move {        
        let mut shutdown_future = shutdown_rx;
        loop {
            select! {
                _ = &mut shutdown_future => break,
                Ok((socket, addr)) = listener.accept() => {
                    let wisp = wisp_mux.clone();
                    let doh_url = doh_url.clone();
                    tokio::spawn(async move {
                        if let Err(e) = serve(wisp, socks_addr, socket, port, &doh_url).await {
                            eprintln!("Connection error from {}: {}", addr, e);
                        }
                    });
                }
            }
        }
    });

    Ok(())
}

async fn serve(
    wisp: Arc<wisp_mux::ClientMux<impl wisp_mux::ws::TransportWrite>>,
    server_addr: SocketAddr,
    socket: tokio::net::TcpStream,
    serverport: i32,
    doh_url: &str
) -> Result<()> {
    use fast_socks5::{
        ReplyError, Socks5Command,
        server::{Socks5ServerProtocol, SocksServerError, run_udp_proxy_custom},
        util::target_addr::TargetAddr,
    };
    use tokio::net::UdpSocket;
    use tokio_util::compat::FuturesAsyncReadCompatExt;

    let accept_result = Socks5ServerProtocol::accept_no_auth(socket).await;
    if let Err(e) = &accept_result {
        eprintln!("SOCKS5 handshake failed: {:?}", e);
    }
    let (proto, cmd, target_addr) = accept_result?.read_command().await?;

    let (host, port) = target_addr.into_string_and_port();

    match cmd {
        Socks5Command::TCPConnect => {

            let stream = match wisp.new_stream(StreamType::Tcp, host, port).await {
                Ok(stream) => stream,
                Err(err) => {
                    proto.reply_error(&ReplyError::NetworkUnreachable).await?;
                    return Err(err).context("Failed to connect to TCP upstream");
                }
            }
            .into_async_rw()
            .compat();

            let mut socket = proto.reply_success(server_addr).await?;
            let mut stream = stream;

            let mut buf1 = [0u8; 8192];
            let mut buf2 = [0u8; 8192];
            let mut pos1 = 0;
            let mut pos2 = 0;
            
            loop {
                tokio::select! {
                    result = socket.read(&mut buf1[pos1..]) => {
                        match result {
                            Ok(0) => break,
                            Ok(n) => {
                                pos1 += n;
                                if pos1 > 0 {
                                    let bytes_written = stream.write(&buf1[..pos1]).await?;
                                    if bytes_written < pos1 {
                                        buf1.copy_within(bytes_written..pos1, 0);
                                    }
                                    pos1 -= bytes_written;
                                }
                            },
                            Err(e) => {
                                eprintln!("Error reading from socket: {}", e);
                                break;
                            }
                        }
                    }
                    result = stream.read(&mut buf2[pos2..]) => {
                        match result {
                            Ok(0) => break,
                            Ok(n) => {
                                pos2 += n;
                                if pos2 > 0 {
                                    let bytes_written = socket.write(&buf2[..pos2]).await?;
                                    if bytes_written < pos2 {
                                        buf2.copy_within(bytes_written..pos2, 0);
                                    }
                                    pos2 -= bytes_written;
                                }
                            },
                            Err(e) => {
                                eprintln!("Error reading from stream: {}", e);
                                break;
                            }
                        }
                    }
                }
            }

            Ok(())
        }
        
        Socks5Command::UDPAssociate => {
            let doh_url = doh_url.to_string();
            tokio::spawn(async move {
                if let Err(e) = run_udp_proxy_custom(
                    proto,
                    &TargetAddr::Ip(SocketAddr::from(([127, 0, 0, 1], 0))),
                    None,
                    server_addr.ip(),
                    move |inbound| async move {
                        let socks = match UdpSocket::from_std(inbound.into()) {
                            Ok(s) => s,
                            Err(e) => return Err(SocksServerError::Io { source: e, context: "creating UDP socket" }),
                        };
                        let mut buf = vec![0u8; 65507];

                        loop {
                            let doh_url = doh_url.clone();
                            let (size, peer) = socks.recv_from(&mut buf).await
                                .map_err(|e| SocksServerError::Io { source: e, context: "receiving from UDP socket" })?;
                            if size < 10 { continue; }

                            let frag = buf[2];
                            if frag != 0 { continue; }

                            let addr_type = buf[3];
                            let header_len = match addr_type {
                                1 => 10,                  // IPv4
                                3 => 7 + buf[4] as usize, // domain
                                _ => continue,
                            };
                            if size <= header_len { continue; }

                            if addr_type == 1 {
                                let dest_ip = std::net::Ipv4Addr::new(buf[4], buf[5], buf[6], buf[7]);
                                if dest_ip != std::net::Ipv4Addr::new(10, 0, 0, 144) {
                                    // eprintln!("UDP that is not DNS for 10.0.0.144 is not supported!");
                                    continue; // Skip packets not for 10.0.0.144, our fake DNS server
                                }
                            }

                            let dns_payload = &buf[header_len..size];

                            let doh_resp = match forward_dns_over_doh(dns_payload, serverport, &doh_url).await {
                                Ok(resp) => resp,
                                Err(_) => continue,
                            };

                            let mut resp_packet = Vec::with_capacity(header_len + doh_resp.len());
                            resp_packet.extend_from_slice(&buf[0..header_len]);
                            resp_packet.extend_from_slice(&doh_resp);

                            socks.send_to(&resp_packet, &peer)
                                .await
                                .map_err(|e| SocksServerError::Io { source: e, context: "sending to UDP socket" })?;
                        }

                    },
                ).await {
                    eprintln!("UDP proxy failed: {:?}", e);
                }
            });

            Ok(())
        }

        cmd => {
            proto.reply_error(&ReplyError::CommandNotSupported).await?;
            anyhow::bail!("Only TCP Connect is supported, not {:?}", cmd);
        }
    }
}