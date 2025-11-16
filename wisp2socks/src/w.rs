use std::{env::args, net::SocketAddr, sync::Arc};

use anyhow::{Context, Result, bail};
use fast_socks5::{
    ReplyError, Socks5Command,
    server::{Socks5ServerProtocol, SocksServerError, run_udp_proxy_custom},
    util::target_addr::TargetAddr,
};
use futures::{AsyncWriteExt, StreamExt, TryFutureExt};
use tokio::{
    net::{TcpListener, TcpStream, UdpSocket},
    select,
};
use tokio_util::compat::FuturesAsyncReadCompatExt;
use tokio_websockets::ClientBuilder;
use wisp_mux::{
    ClientMux,
    packet::StreamType,
    ws::{TokioWebsocketsTransport, TransportExt, TransportWrite},
};

#[tokio::main]
async fn main() -> Result<()> {
    let wisp = args().nth(1).context("no wisp url provided")?;
    let socks = args()
        .nth(2)
        .context("no socks bind addr provided")?
        .parse::<SocketAddr>()
        .context("failed to parse socks addr as socketaddr")?;

    let listener = TcpListener::bind(socks)
        .await
        .context("failed to bind to socks addr")?;

    let (rx, tx) = TokioWebsocketsTransport(
        ClientBuilder::new()
            .uri(&wisp)
            .context("failed to add uri to ws builder")?
            .connect()
            .await
            .context("failed to connect to wisp server")?
            .0,
    )
    .split_fast();
    let (wisp, fut) = ClientMux::new(rx, tx, None)
        .await
        .context("failed to upgrade to wisp")?
        .with_no_required_extensions();
    let wisp = Arc::new(wisp);

    tokio::spawn(async move {
        println!("wisp result: {:?}", fut.await);
    });

	println!("serving on {socks:?}");

    while let Ok((socket, addr)) = listener.accept().await {
        let wisp = wisp.clone();
        tokio::spawn(async move {
            println!(
                "socks result on {addr:?}: {:?}",
                serve(wisp, socks, socket).await
            )
        });
    }

    Ok(())
}

async fn serve(
    wisp: Arc<ClientMux<impl TransportWrite>>,
    server_addr: SocketAddr,
    socket: TcpStream,
) -> Result<()> {
    let (proto, cmd, target_addr) = Socks5ServerProtocol::accept_no_auth(socket)
        .await
        .context("failed to upgrade to socks5")?
        .read_command()
        .await?;
    let (host, port) = target_addr.into_string_and_port();

    match cmd {
        Socks5Command::TCPBind => {
            proto
                .reply_error(&ReplyError::CommandNotSupported)
                .await
                .context("failed to reply")?;
            bail!("tcp bind not supported");
        }
        Socks5Command::TCPConnect => {
            let mut stream = match wisp.new_stream(StreamType::Tcp, host, port).await {
                Ok(stream) => stream,
                Err(err) => {
                    proto.reply_error(&ReplyError::NetworkUnreachable).await?;
                    return Err(err).context("failed to connect to tcp upstream");
                }
            }
            .into_async_rw()
            .compat();

            let mut socks = proto
                .reply_success(SocketAddr::from(([127, 0, 0, 1], 0)))
                .await
                .context("failed to reply")?;

            tokio::io::copy_bidirectional(&mut stream, &mut socks)
                .await
                .context("failed to forward tcp")?;
        }
        Socks5Command::UDPAssociate => {
            let stream = match wisp.new_stream(StreamType::Udp, host, port).await {
                Ok(stream) => stream,
                Err(err) => {
                    proto.reply_error(&ReplyError::NetworkUnreachable).await?;
                    return Err(err).context("failed to connect to tcp upstream");
                }
            };
            let (mut read, write) = stream.into_split();
            let mut write = write.into_async_write();

            run_udp_proxy_custom(
                proto,
                &TargetAddr::Ip(SocketAddr::from(([127, 0, 0, 1], 0))), /* unused */
                None,
                server_addr.ip(),
                move |inbound| {
                    async move {
                        let socks = UdpSocket::from_std(inbound.into())
                            .context("failed to wrap socks socket")?;
                        let mut data = vec![0u8; 65507];

                        loop {
                            select! {
                                size = socks.recv(&mut data) => {
                                    let size = size?;
                                    write.write_all(&data[..size]).await?;
                                }
                                data = read.next() => {
                                    if let Some(data) = data.transpose()? {
                                        socks.send(&data).await?;
                                    } else {
                                        break anyhow::Ok(());
                                    }
                                }
                            }
                        }
                    }
                    .map_err(|x| {
                        println!("socks udp proxy failed: {:?}", x);
                        SocksServerError::EOF
                    })
                },
            )
            .await
            .context("failed to forward udp")?;
        }
    }

    Ok(())
}
