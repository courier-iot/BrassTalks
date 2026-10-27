package com.example.p2pchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Is the screen open right now? Used to decide whether to show message notifications. */
object AppState {
    @Volatile var foreground = false
}

object Notifier {
    private const val CH_SESSION = "session"
    private const val CH_MESSAGES = "messages"
    private const val ID_MESSAGES = 2
    private var unread = 0

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SESSION, "Room connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while BrassTalks keeps your room connected"
            })
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New messages while the app is in the background"
            })
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun session(ctx: Context, text: String): Notification {
        val leave = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, SessionService::class.java).setAction(SessionService.ACTION_LEAVE),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CH_SESSION)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("BrassTalks")
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Leave room", leave)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @Synchronized
    fun message(ctx: Context, sender: String, text: String) {
        unread++
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hidden = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("BrassTalks")
            .setContentText("New message")
            .build()
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(if (unread == 1) sender else "$unread new messages")
            .setContentText(if (unread == 1) text else "Latest from $sender")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)   // text hidden on the lock screen
            .setPublicVersion(hidden)
            .build()
        runCatching { nm.notify(ID_MESSAGES, n) }
    }

    @Synchronized
    fun clearMessages(ctx: Context) {
        unread = 0
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID_MESSAGES)
    }
}

/**
 * Foreground service: keeps BrassTalks alive and connected while the screen is off or the app is in the
 * background. Holds a CPU wake lock and a WiFi lock so heartbeats keep flowing. If Android ever kills it,
 * START_STICKY brings it back and the saved room is rejoined automatically.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires us to go foreground quickly, so do it before anything else
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, Notifier.session(this, "Connecting…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        val vm = (application as BrassApp).chatVm()   // also restores the saved room if the app was restarted
        if (intent?.action == ACTION_LEAVE) {
            vm.leave(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!vm.wantsSession()) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        if (!collecting) {
            collecting = true
            scope.launch {
                combine(vm.room, vm.peers, vm.joining) { r, p, j -> Triple(r, p, j) }.collect { (r, p, j) ->
                    if (r == null && !j) {
                        stopSelf()
                    } else if (r != null) {
                        val text = "Room ${r.code} · ${p + 1} of ${Limits.MAX_ROOM} aboard"
                        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(NOTIF_ID, Notifier.session(this@SessionService, text))
                    }
                }
            }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "brasstalks:session")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "brasstalks:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { wake?.release() }
        runCatching { wifiLock?.release() }
        wake = null; wifiLock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_LEAVE = "com.example.p2pchat.LEAVE"
        private const val NOTIF_ID = 1
        @Volatile var running = false

        fun start(ctx: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, SessionService::class.java))
            } catch (e: Exception) { /* Android refused a background start; the screen-on path will retry */ }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }
    }
}
