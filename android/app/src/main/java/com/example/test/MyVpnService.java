package com.example.test;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.FileDescriptor;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import engine.Engine;

public class MyVpnService extends VpnService {
    public static final String ACTION_CONNECT = "com.example.test.CONNECT";
    public static final String ACTION_DISCONNECT = "com.example.test.STOP_VPN";

    static {
        System.loadLibrary("wisp2socks"); // libwisp2socks.so
    }

    // native wisp2socks fns
    public static native void startWisp2Socks(String url, int port, String dohUrl);
    public static native void stopWisp2Socks();

    private static final String ADDRESS = "10.0.0.2";
    private static final String ROUTE = "0.0.0.0";
    private static final String CHANNEL_ID = "vpn_service";

    private final ExecutorService executors = Executors.newFixedThreadPool(2);
    private volatile ParcelFileDescriptor tun;
    private volatile boolean isShuttingDown = false;
    private final Object cleanupLock = new Object();

    private int getFreePort() {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }
    private void startVpn() {
        if (tun != null) {
            return;
        }

        int serverPort = getFreePort();

        Log.i("Whisper", "Binding wisp2socks to port" + serverPort);

        try {
            startWisp2Socks("wss://anura.pro/", serverPort, "https://cloudflare-dns.com/dns-query");
            Builder builder = new Builder()
                    .setBlocking(false)
                    .setMtu(1500)
                    .addAddress(ADDRESS, 24)
                    .addRoute(ROUTE, 0)
                    .addDnsServer("10.0.0.144")
                    .addDisallowedApplication(this.getApplication().getPackageName());

            tun = builder.establish();

            if (tun == null) {
                stopSelf();
                return;
            }

            engine.Key key = new engine.Key();
            key.setMark(0);
            key.setMTU(1500);
            key.setDevice("fd://" + tun.getFd());
            key.setInterface("");
            key.setLogLevel("debug");
            key.setProxy("socks5://127.0.0.1:" + serverPort);
            key.setRestAPI("");
            key.setTCPSendBufferSize("");
            key.setTCPReceiveBufferSize("");
            key.setTCPModerateReceiveBuffer(false);

            engine.Engine.insert(key);
            executors.submit(Engine::start);

            // Create notification
            createNotificationChannel();
            startForegroundService();
        } catch (PackageManager.NameNotFoundException e) {
            stopSelf();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    private void cleanupTun() {
        synchronized (cleanupLock) {
            if (tun == null || isShuttingDown) {
                return;
            }
            isShuttingDown = true;

            ParcelFileDescriptor localTun = tun;
            tun = null;

            try {
                if (localTun != null) {
                    int fd = localTun.detachFd();

                    try {
                        Engine.stop();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    try {
                        stopWisp2Socks();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    // Let wisp2socks finish its cleanup
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } finally {
                isShuttingDown = false;
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }

    @Override
    public void onRevoke() {
        stopVpn();
        super.onRevoke();
    }

    private void stopVpn() {
        synchronized (cleanupLock) {
            stopForeground(true);

            cleanupTun();

            // wait for cleanup
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (executors != null && !executors.isShutdown()) {
                try {
                    executors.shutdown();
                    if (!executors.awaitTermination(3000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        executors.shutdownNow();
                        executors.awaitTermination(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException e) {
                    executors.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

        startVpn();
        return START_STICKY;
    }


    /**
     * Computes the inverted subnet, routing all traffic except to the specified subnet. Use prefixLength
     * of 32 or 128 for a single address.
     *
     * @see <a href="https://stackoverflow.com/a/41289228"></a>
        private void addRoutesExcept(Builder builder, String address, int prefixLength) {
            try {
                byte[] bytes = InetAddress.getByName(address).getAddress();
                for (int i = 0; i < prefixLength; i++) { // each entry
                    byte[] res = new byte[bytes.length];
                    for (int j = 0; j <= i; j++) { // each prefix bit
                        res[j / 8] = (byte) (res[j / 8] | (bytes[j / 8] & (1 << (7 - (j % 8)))));
                    }
                    res[i / 8] ^= (1 << (7 - (i % 8)));

                    builder.addRoute(InetAddress.getByAddress(res), i + 1);
                }
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        }
     */

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "VPN Service",
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN Service")
                .setContentText("VPN is running")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pendingIntent)
                .build();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
        } else {
            startForeground(1, notification);
        }
    }
}
