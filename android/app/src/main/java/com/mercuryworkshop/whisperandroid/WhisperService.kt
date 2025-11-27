package com.mercuryworkshop.whisperandroid

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import engine.Engine
import engine.Key
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

class WhisperService : VpnService() {
    external fun startWisp2Socks(url: String?, port: Int, dohUrl: String?)
    external fun stopWisp2Socks()
    private val executors: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var isShuttingDown = false
    private val cleanupLock = Any()

    fun getFreePort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }
    private fun startVpn(wispServerUrl: String?, dohUrl: String?) {

        val wispServer = if (wispServerUrl.isNullOrBlank()) {
            "wss://nebulaservices.org/wisp/"
        } else {
            var url = wispServerUrl
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
                url = "wss://$url"
            }
            if (!url.endsWith("/")) {
                url += "/"
            }
            url
        }
        val dohToUse = if (dohUrl.isNullOrBlank()) {
            "https://cloudflare-dns.com/dns-query"
        } else {
            if (dohUrl.startsWith("https://", ignoreCase = true)) dohUrl
            else "https://$dohUrl"
        }

        if (tun != null) {
            Log.i("Whisper", "VPN already running")
            return
        }

        val serverPort = try {
            getFreePort()
        } catch (e: IOException) {
            Log.e("Whisper", "Failed to get free port", e)
            stopSelf()
            return
        }

        Log.i("Whisper", "Binding wisp2socks to port $serverPort")

        try {
            try {
                Log.i("WhisperService", "starting service with $wispServer and doh $dohToUse")
                val result = startWisp2Socks(
                    wispServer,
                    serverPort,
                    dohToUse
                )
                Log.i("Whisper", "startWisp2Socks returned: $result")
            } catch (e: Throwable) {
                Log.e("Whisper", "startWisp2Socks failed", e)
                stopSelf()
                return
            }

            val builder: VpnService.Builder = Builder()
                .setBlocking(false)
                .setMtu(1500)
                .addAddress(ADDRESS, 24)
                .addRoute(ROUTE, 0)
                .addDnsServer("10.0.0.144")
                .addDisallowedApplication(applicationContext.packageName)

            tun = try {
                builder.establish()
            } catch (e: Exception) {
                Log.e("Whisper", "Failed to establish TUN interface", e)
                stopSelf()
                return
            }

            if (tun == null) {
                Log.e("Whisper", "builder.establish() returned null")
                stopSelf()
                return
            }
            Log.i("Whisper", "TUN interface established successfully")

            val key = Key()
            try {
                key.setMark(0)
                key.setMTU(1500)
                val fdInt = tun?.detachFd() ?: run {
                    Log.e("Whisper", "TUN FD is invalid")
                    stopSelf()
                    return
                }
                key.setDevice("fd://$fdInt")
                key.setInterface("")
                key.setLogLevel("debug")
                key.setProxy("socks5://127.0.0.1:$serverPort")
                key.setRestAPI("")
                key.setTCPSendBufferSize("")
                key.setTCPReceiveBufferSize("")
                key.setTCPModerateReceiveBuffer(false)

                Engine.insert(key)
                executors.submit {
                    try {
                        Engine.start()
                        Log.i("Whisper", "Engine started successfully")
                    } catch (e: Throwable) {
                        Log.e("Whisper", "Engine failed (jvm)", e)
                    } catch (err: Error) {
                        Log.e("Whisper", "Engine failed (native)", err)
                    }
                }

            } catch (e: Throwable) {
                Log.e("Whisper", "something wrong with tun2socks...", e)
                stopSelf()
                return
            }

            try {
                createNotificationChannel()
                startForegroundService()
            } catch (e: Throwable) {
                Log.e("Whisper", "Failed to start foreground service", e)
                stopSelf()
                return
            }

            Log.i("Whisper", "Whisper started successfully")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e("Whisper", "Package not found", e)
            stopSelf()
        } catch (e: Throwable) {
            Log.e("Whisper", "Unexpected error in startVpn", e)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    private fun cleanupTun() {
        synchronized(cleanupLock) {
            if (tun == null || isShuttingDown) return
            isShuttingDown = true

            val localTun = tun
            tun = null

            // dont block main thread
            executors.submit {
                try {
                    localTun?.let { tunFd ->
                        try { tunFd.detachFd() } catch (_: IllegalStateException) { }

                        try { Engine.stop() } catch (e: Exception) { e.printStackTrace() }

                        try { stopWisp2Socks() } catch (e: Exception) { e.printStackTrace() }
                    }
                } finally {
                    isShuttingDown = false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun stopVpn() {
        synchronized(cleanupLock) {
            stopForeground(true)
            cleanupTun()

            try {
                executors.shutdown()
                if (!executors.awaitTermination(3000, TimeUnit.MILLISECONDS)) {
                    executors.shutdownNow()
                    executors.awaitTermination(1000, TimeUnit.MILLISECONDS)
                }
            } catch (e: InterruptedException) {
                executors.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        val vpnUrl = intent?.getStringExtra(EXTRA_VPN_URL)
        val dohUrl = intent?.getStringExtra(EXTRA_DOH_URL)
        startVpn(vpnUrl, dohUrl)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val notificationManager =
                getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VPN Service")
            .setContentText("VPN is running")
            .setSmallIcon(R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            )
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        const val ACTION_CONNECT: String = "com.mercuryworkshop.whisperandroid.CONNECT"
        const val ACTION_DISCONNECT: String = "com.mercuryworkshop.whisperandroid.STOP_VPN"

        const val EXTRA_VPN_URL = "extra_vpn_url"
        const val EXTRA_DOH_URL = "extra_doh_url"

        init {
            System.loadLibrary("wisp2socks")
        }

        private const val ADDRESS = "10.0.0.2"
        private const val ROUTE = "0.0.0.0"
        private const val CHANNEL_ID = "vpn_service"
    }
}
