package com.jonathanfdr.dharma

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Foreground service: mantém o processo vivo e a CPU acordada com a tela bloqueada. */
class MeditationService : Service() {

    companion object {
        private const val CHANNEL = "meditation"
        private const val NOTIF_ID = 1
        private const val ACTION_START = "dharma.START"
        private const val ACTION_TOGGLE = "dharma.TOGGLE"
        private const val ACTION_STOP = "dharma.STOP"

        fun start(ctx: Context, sessions: Int, minutes: Int, fadeOut: Int) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MeditationService::class.java)
                .setAction(ACTION_START)
                .putExtra("sessions", sessions).putExtra("minutes", minutes).putExtra("fadeOut", fadeOut))
        }
        fun togglePause(ctx: Context) = ctx.startService(Intent(ctx, MeditationService::class.java).setAction(ACTION_TOGGLE))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, MeditationService::class.java).setAction(ACTION_STOP))
    }

    private val handler = Handler(Looper.getMainLooper())
    private var engine: AudioEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    private var lastSig = ""

    private val noisy = object : BroadcastReceiver() {          // fones desconectados => pausa
        override fun onReceive(c: Context, i: Intent) { if (SessionState.running) SessionState.paused = true; refresh() }
    }

    private val ticker = object : Runnable {
        override fun run() { if (SessionState.running) { refresh(); handler.postDelayed(this, 1000) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent.getIntExtra("sessions", 1), intent.getIntExtra("minutes", 1), intent.getIntExtra("fadeOut", 20))
            ACTION_TOGGLE -> { pausedByFocus = false; SessionState.paused = !SessionState.paused; refresh(force = true) }
            ACTION_STOP -> SessionState.stopRequested = true
        }
        if (!SessionState.running) { stopForegroundCompat(); stopSelf() }
        return START_NOT_STICKY
    }

    private fun begin(sessions: Int, minutes: Int, fadeOut: Int) {
        if (SessionState.running) return
        createChannel()
        SessionState.apply {
            this.sessions = sessions; this.minutes = minutes; fadeOutSec = fadeOut
            paused = false; stopRequested = false; completed = false; playedFrames = 0; running = true
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

        val total = SessionState.totalSeconds
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dharma:meditation").apply { acquire((total + 600) * 1000L) }
        requestFocus()
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)

        engine = AudioEngine(applicationContext) { completed -> handler.post { finish(completed) } }.also { it.start() }
        handler.postDelayed(ticker, 1000)
    }

    private fun finish(completed: Boolean) {
        SessionState.completed = completed
        SessionState.running = false
        SessionState.paused = false
        engine = null
        handler.removeCallbacks(ticker)
        try { unregisterReceiver(noisy) } catch (_: Exception) {}
        abandonFocus()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        SessionState.stopRequested = true
        handler.removeCallbacks(ticker)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---- foco de áudio: chamada telefônica etc. pausa e retoma sozinho
    private fun requestFocus() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                handler.post {
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            if (SessionState.running && !SessionState.paused) { SessionState.paused = true; pausedByFocus = true; refresh(true) }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; SessionState.paused = false; refresh(true) }
                    }
                }
            }.build()
        am.requestAudioFocus(focusRequest!!)
    }

    private fun abandonFocus() {
        focusRequest?.let { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // ---- notificação (visível na tela bloqueada, com contagem regressiva e botões)
    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Meditação", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Sessão de meditação em andamento"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            })
        }
    }

    private fun sessionIndex(): Int =
        (SessionState.playedSeconds / (SessionState.minutes * 60.0)).toInt().coerceIn(0, SessionState.sessions - 1)

    private fun refresh(force: Boolean = false) {
        val sig = "${sessionIndex()}|${SessionState.paused}"
        if (!force && sig == lastSig) return
        lastSig = sig
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    private fun action(text: String, act: String, code: Int) = NotificationCompat.Action.Builder(
        0, text, PendingIntent.getService(this, code, Intent(this, MeditationService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    ).build()

    private fun buildNotification(): Notification {
        val st = SessionState
        val remainingMs = ((st.totalSeconds - st.playedSeconds) * 1000).toLong().coerceAtLeast(0)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (st.sessions > 1) "Meditação · sessão ${sessionIndex() + 1} de ${st.sessions}" else "Meditação")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(action(if (st.paused) "Retomar" else "Pausar", ACTION_TOGGLE, 1))
            .addAction(action("Encerrar", ACTION_STOP, 2))
        if (st.paused) b.setContentText("Pausado")
        else b.setContentText("Tempo restante").setUsesChronometer(true).setChronometerCountDown(true)
            .setShowWhen(true).setWhen(System.currentTimeMillis() + remainingMs)
        return b.build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}
