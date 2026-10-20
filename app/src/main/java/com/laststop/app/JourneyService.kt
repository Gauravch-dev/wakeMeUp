package com.laststop.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.IconCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object JourneySession {
    private val mutable = MutableStateFlow(JourneyState())
    val state = mutable.asStateFlow()
    var running = false
        internal set
    internal fun publish(state: JourneyState) { mutable.value = state }
}

class JourneyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var location: FusedLocationProviderClient
    private lateinit var alarm: AlarmPlayer
    private lateinit var notifications: NotificationManager
    private var engine: JourneyEngine? = null
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alerting = false
    private var soundUri = ""
    private var liveUpdateDismissed = false
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach { reading ->
                val fix = Fix(reading.latitude, reading.longitude, reading.accuracy.toDouble(),
                    reading.elapsedRealtimeNanos / 1_000_000)
                engine?.let { publish(it.accept(fix, SystemClock.elapsedRealtime())) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        location = LocationServices.getFusedLocationProviderClient(this)
        alarm = AlarmPlayer(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_JOURNEY, "Journey monitoring", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ALARM, "Wake-up alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Approaching-station and tracking-loss alerts"
                // The foreground service plays a looping alarm; avoid a duplicate channel sound.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS_LIVE_UPDATE) {
            // Respect dismissal for this leg; monitoring and audible alerts keep running.
            liveUpdateDismissed = true
            if (!JourneySession.running) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            endJourney("Journey ended. Location monitoring is off.")
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || JourneySession.running) {
            if (!JourneySession.running) stopSelf()
            return START_NOT_STICKY
        }
        val source = intent.getStringExtra("source_id") ?: "PNVL"
        val destination = intent.getStringExtra("destination_id") ?: "VSH"
        val plans = MumbaiNetwork.plans(source, destination)
        val plan = plans.firstOrNull { it.id == intent.getStringExtra("plan_id") } ?: plans.firstOrNull()
        if (plan == null) {
            endJourney("Choose different source and destination stations on the supported network.")
            return START_NOT_STICKY
        }
        val legIndex = intent.getIntExtra("leg_index", 0).coerceIn(plan.legs.indices)
        soundUri = intent.getStringExtra("sound_uri").orEmpty()
        liveUpdateDismissed = false
        engine = JourneyEngine(plan, legIndex, false, SystemClock.elapsedRealtime())
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_JOURNEY, notification(engine!!.state, false),
                foregroundTypes(false))
            JourneySession.running = true
            markUnfinished(true)
            publish(engine!!.state)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakemeup:Journey").apply {
                    // Bounded to the maximum supported session; released immediately on stop.
                    acquire(MAX_SESSION_MS + 30_000)
                }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) throw SecurityException("Precise location permission is required")
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000)
                .setMinUpdateIntervalMillis(2_000).setMaxUpdateDelayMillis(5_000)
                .setMaxUpdateAgeMillis(0).setWaitForAccurateLocation(false).build()
            location.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    engine?.let { publish(it.alarm("Location monitoring failed. Open wakemeup and check permissions.")) }
                }
            ticker = scope.launch {
                val started = SystemClock.elapsedRealtime()
                while (isActive) {
                    delay(2_000)
                    val now = SystemClock.elapsedRealtime()
                    val manager = getSystemService(LocationManager::class.java)
                    engine?.let { current ->
                        val state = when {
                            now - started >= MAX_SESSION_MS -> current.alarm("Six-hour monitoring limit reached. Check your stop and end this journey.")
                            !manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> current.alarm("Device location is switched off. Check your stop now.")
                            ContextCompat.checkSelfPermission(this@JourneyService, Manifest.permission.ACCESS_FINE_LOCATION)
                                != PackageManager.PERMISSION_GRANTED -> current.alarm("Location permission was removed. Check your stop now.")
                            else -> current.tick(now)
                        }
                        publish(state)
                    }
                    if (alerting) alarm.ensurePlaying()
                }
            }
        } catch (error: Exception) {
            endJourney("Could not start monitoring. Enable precise location and notifications, then try again.")
        }
        return START_NOT_STICKY
    }

    private fun publish(state: JourneyState) {
        JourneySession.publish(state)
        if (state.status == TrackingStatus.ALARM && !alerting) {
            alerting = true
            // Add mediaPlayback while audio is actually playing.
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_JOURNEY, notification(state, false),
                    foregroundTypes(true))
            }
            alarm.start(soundUri)
            notifications.notify(NOTIFICATION_ALARM, notification(state, true))
        }
        notifications.notify(NOTIFICATION_JOURNEY, notification(state, false))
    }

    private fun notification(state: JourneyState, isAlarm: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, JourneyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismissed = PendingIntent.getService(this, 2,
            Intent(this, JourneyService::class.java).setAction(ACTION_DISMISS_LIVE_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val content = JourneyNotificationContent.from(state, SystemClock.elapsedRealtime())
        val builder = NotificationCompat.Builder(this, if (isAlarm) CHANNEL_ALARM else CHANNEL_JOURNEY)
            .setSmallIcon(R.drawable.ic_notification_train)
            .setContentTitle(content.title)
            .setContentText(content.summary)
            .setSubText("Wake near ${state.leg.wakeStation.name}")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(!isAlarm)
            .setShowWhen(false)
            .setCategory(if (isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(if (isAlarm) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (alerting && state.hasNextLeg) "I’m awake · End leg"
                else if (alerting) "I’m awake · End journey" else "End journey", stop)
        if (!isAlarm && content.promote && Build.VERSION.SDK_INT >= 36) {
            val progress = NotificationCompat.ProgressStyle()
                .setProgressIndeterminate(content.fraction == null)
                .setProgress(((content.fraction ?: 0f) * 10_000).toInt())
                .addProgressSegment(NotificationCompat.ProgressStyle.Segment(10_000))
            state.leg.route.let { route ->
                route.cumulative.drop(1).dropLast(1).forEach { metres ->
                    progress.addProgressPoint(NotificationCompat.ProgressStyle.Point(
                        (metres / route.cumulative.last() * 10_000).toInt()))
                }
            }
            if (content.livePosition) progress.setProgressTrackerIcon(
                IconCompat.createWithResource(this, R.drawable.ic_notification_train))
            builder.setStyle(progress)
                .setRequestPromotedOngoing(!liveUpdateDismissed)
                .setShortCriticalText(content.chip)
                .setDeleteIntent(dismissed)
            // Keep route and warning context available in the expanded system template.
            builder.setSubText(if (state.hasNextLeg) "Wake near ${state.leg.wakeStation.name} · Change at ${state.destination.name}"
                else "Wake near ${state.leg.wakeStation.name} · Before ${state.destination.name}")
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.detail))
            if (!isAlarm && content.promote && content.fraction != null) {
                builder.setProgress(10_000, (content.fraction * 10_000).toInt(), false)
            }
        }
        return builder.build()
    }

    private fun endJourney(message: String) {
        JourneySession.publish((engine?.state ?: JourneyState()).copy(status = TrackingStatus.ENDED, message = message))
        markUnfinished(false)
        JourneySession.running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        runCatching { location.removeLocationUpdates(callback) }
        alarm.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        notifications.cancel(NOTIFICATION_ALARM)
        notifications.cancel(NOTIFICATION_JOURNEY)
        if (JourneySession.running) {
            JourneySession.publish((engine?.state ?: JourneyState()).copy(status = TrackingStatus.ENDED,
                message = "Monitoring stopped unexpectedly. Restart your journey before resting."))
        }
        JourneySession.running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun foregroundTypes(withAudio: Boolean): Int = if (Build.VERSION.SDK_INT >= 29) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            if (withAudio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
    } else 0

    @SuppressLint("ApplySharedPref") // A tiny synchronous marker must survive abrupt process death.
    private fun markUnfinished(value: Boolean) {
        getSharedPreferences("session", MODE_PRIVATE).edit(commit = true) { putBoolean("unfinished", value) }
    }

    companion object {
        const val ACTION_START = "com.laststop.START"
        const val ACTION_STOP = "com.laststop.STOP"
        private const val ACTION_DISMISS_LIVE_UPDATE = "com.laststop.DISMISS_LIVE_UPDATE"
        private const val CHANNEL_JOURNEY = "journey"
        private const val CHANNEL_ALARM = "alarm"
        private const val NOTIFICATION_JOURNEY = 101
        private const val NOTIFICATION_ALARM = 102
        private const val MAX_SESSION_MS = 6 * 60 * 60 * 1000L
    }
}
