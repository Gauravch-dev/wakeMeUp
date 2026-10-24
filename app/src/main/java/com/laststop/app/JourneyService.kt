package com.laststop.app

import android.Manifest
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
import java.text.DateFormat
import java.util.Date
import java.util.UUID

object JourneySession {
    private val mutable = MutableStateFlow(JourneyState())
    val state = mutable.asStateFlow()
    private val mutableReport = MutableStateFlow("")
    val lastAlertReport = mutableReport.asStateFlow()
    private val mutableRunning = MutableStateFlow(false)
    val isRunning = mutableRunning.asStateFlow()
    var running: Boolean
        get() = mutableRunning.value
        internal set(value) { mutableRunning.value = value }
    private val mutableMonitoringReport = MutableStateFlow("")
    val lastMonitoringReport = mutableMonitoringReport.asStateFlow()
    internal fun publish(state: JourneyState) { mutable.value = state }
    internal fun publishReport(report: String) { mutableReport.value = report }
    internal fun publishMonitoringReport(report: String) { mutableMonitoringReport.value = report }
}

class JourneyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var location: FusedLocationProviderClient
    private lateinit var alarm: AlarmPlayer
    private lateinit var notifications: NotificationManager
    private var engine: JourneyEngine? = null
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alertKind: AlarmKind? = null
    private val alerting get() = alertKind != null
    private var lastAlertSignature = ""
    private var soundUri = ""
    private var liveUpdateDismissed = false
    private lateinit var recoveryStore: JourneyRecoveryStore
    private var savedJourney: SavedJourney? = null
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
        recoveryStore = JourneyRecoveryStore(this)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_JOURNEY, "Journey monitoring", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ALARM, "Wake-up alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Confirmed station wake-up alarms"
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
            if (JourneySession.running) return START_STICKY
            // A dismissal can be delivered while Android is recreating the service.
            // Restore the saved journey below instead of cancelling its restart.
        }
        if (intent?.action == ACTION_STOP) {
            endJourney("Journey ended. Location monitoring is off.")
            return START_NOT_STICKY
        }
        if (JourneySession.running) return START_STICKY
        if (intent != null && intent.action !in listOf(ACTION_START, ACTION_RESUME, ACTION_DISMISS_LIVE_UPDATE)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val boot = JourneyRecoveryStore.bootCount(this)
        val previousSession = recoveryStore.load()
        val retryingStart = flags and START_FLAG_RETRY != 0 && previousSession != null &&
            intent?.getStringExtra("plan_id") == previousSession.planId &&
            intent.getStringExtra("source_id") == previousSession.sourceId &&
            intent.getStringExtra("destination_id") == previousSession.destinationId
        val restoring = intent?.action != ACTION_START || retryingStart
        val saved = if (restoring) previousSession else {
            val source = intent?.getStringExtra("source_id").orEmpty()
            val destination = intent?.getStringExtra("destination_id").orEmpty()
            val plan = MumbaiNetwork.plans(source, destination)
                .firstOrNull { it.id == intent?.getStringExtra("plan_id") }
            val legIndex = intent?.getIntExtra("leg_index", 0) ?: 0
            if (plan == null || legIndex !in plan.legs.indices) {
                endJourney("Choose a valid source, destination and route before starting.")
                return START_NOT_STICKY
            }
            SavedJourney(UUID.randomUUID().toString(), source, destination, plan.id, legIndex,
                intent?.getStringExtra("sound_uri").orEmpty(), now, wall, boot, Process.myPid(), wall)
        }
        if (saved == null || saved.remainingMs(now, boot) == 0L || saved.plan() == null) {
            endJourney("The saved journey expired or could not be restored. Start a new journey before resting.")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME && intent.getStringExtra("session_id") != saved.id) {
            stopSelf()
            return START_NOT_STICKY
        }
        savedJourney = if (restoring) saved.copy(processId = Process.myPid(), processStartedWallMs = wall,
            restartCount = saved.restartCount + 1) else saved
        soundUri = saved.soundUri
        if (intent?.action != ACTION_DISMISS_LIVE_UPDATE) liveUpdateDismissed = false
        engine = if (restoring) saved.newEngine(now) else JourneyEngine(saved.plan()!!, saved.legIndex, false, now)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_JOURNEY, notification(engine!!.state, false),
                foregroundTypes(false))
            JourneySession.running = true
            check(recoveryStore.save(savedJourney!!)) { "The active journey could not be saved" }
            if (restoring) JourneyDiagnostics.record(this, saved,
                "Monitoring restarted; fresh location confirmation is required unless the station alarm was already confirmed.")
            publish(engine!!.state)
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakemeup:Journey").apply {
                    // Bounded to the maximum supported session; released immediately on stop.
                    acquire(saved.remainingMs(SystemClock.elapsedRealtime(), boot) + 30_000)
                }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) throw SecurityException("Precise location permission is required")
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000)
                .setMinUpdateIntervalMillis(2_000).setMaxUpdateDelayMillis(0)
                .setMaxUpdateAgeMillis(0).setWaitForAccurateLocation(true).build()
            location.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { error ->
                    if (!JourneySession.running) return@addOnFailureListener
                    JourneyDiagnostics.record(this, savedJourney, "Location update registration failed.", error = error)
                    engine?.let { publish(it.alarm("Location monitoring failed. Open wakemeup and check permissions.", AlarmKind.SYSTEM)) }
                }
            ticker = scope.launch {
                while (isActive) {
                    delay(2_000)
                    val now = SystemClock.elapsedRealtime()
                    if (saved.remainingMs(now, boot) == 0L) {
                        endJourney("Six-hour monitoring limit reached. Start a new journey if you are still travelling.")
                        break
                    }
                    val manager = getSystemService(LocationManager::class.java)
                    engine?.let { current ->
                        val state = when {
                            !manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> current.alarm("Device location is switched off. Check your stop now.", AlarmKind.SYSTEM)
                            ContextCompat.checkSelfPermission(this@JourneyService, Manifest.permission.ACCESS_FINE_LOCATION)
                                != PackageManager.PERMISSION_GRANTED -> current.alarm("Location permission was removed. Check your stop now.", AlarmKind.SYSTEM)
                            else -> current.tick(now)
                        }
                        publish(state)
                    }
                    if (alertKind == AlarmKind.APPROACH) alarm.ensurePlaying()
                }
            }
        } catch (error: Exception) {
            JourneyDiagnostics.record(this, savedJourney, "Monitoring could not start or recover.", error = error)
            endJourney("Could not start monitoring. Enable precise location and notifications, then try again.")
        }
        return if (JourneySession.running) START_STICKY else START_NOT_STICKY
    }

    private fun publish(state: JourneyState) {
        if (!JourneySession.running) return
        savedJourney?.checkpoint(state)?.let { checkpoint ->
            if (checkpoint != savedJourney) {
                if (!recoveryStore.save(checkpoint)) JourneyDiagnostics.record(this, savedJourney,
                    "An alert checkpoint could not be saved. Recovery may not retain this alert.")
                savedJourney = checkpoint
            }
        }
        JourneySession.publish(state)
        val nextKind = state.alarmKind.takeIf { state.status == TrackingStatus.ALARM }
        if (nextKind != null && nextKind != alertKind) {
            alertKind = nextKind
            val signature = "$nextKind:${state.alarmReason}"
            if (signature != lastAlertSignature) {
                lastAlertSignature = signature
                val report = JourneyAlertReport.format(state, SystemClock.elapsedRealtime(),
                    DateFormat.getDateTimeInstance().format(Date()),
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown")
                getSharedPreferences("session", MODE_PRIVATE).edit { putString("last_alert_report", report) }
                JourneySession.publishReport(report)
            }
            // Add mediaPlayback while audio is actually playing.
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_JOURNEY, notification(state, false),
                    foregroundTypes(state.isArrivalAlarm))
            }
            alarm.stop()
            if (state.isArrivalAlarm) {
                alarm.start(soundUri)
                notifications.notify(NOTIFICATION_ALARM, notification(state, true))
            } else {
                // All monitoring/direction/system problems stay visible in the
                // ongoing notification, without playing audio or vibrating.
                notifications.cancel(NOTIFICATION_ALARM)
            }
        } else if (nextKind == null && alerting) {
            // Two trusted recovery readings cleared the tracking warning. Keep the
            // journey alive and restore the normal monitoring notification.
            alertKind = null
            lastAlertSignature = ""
            alarm.stop()
            notifications.cancel(NOTIFICATION_ALARM)
            runCatching {
                ServiceCompat.startForeground(this, NOTIFICATION_JOURNEY, notification(state, false), foregroundTypes(false))
            }
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
        val stationAlert = isAlarm && state.isArrivalAlarm
        val silentWarning = state.status == TrackingStatus.ALARM && !state.isArrivalAlarm
        val builder = NotificationCompat.Builder(this, if (stationAlert) CHANNEL_ALARM else CHANNEL_JOURNEY)
            .setSmallIcon(R.drawable.ic_notification_train)
            .setContentTitle(content.title)
            .setContentText(content.summary)
            .setSubText("Wake near ${state.leg.wakeStation.name}")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(!isAlarm)
            .setShowWhen(false)
            .setCategory(if (stationAlert) NotificationCompat.CATEGORY_ALARM
                else if (silentWarning) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(if (stationAlert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .addAction(0, if (state.alarmKind == AlarmKind.APPROACH && state.hasNextLeg) "I’m awake · End leg"
                else if (state.alarmKind == AlarmKind.APPROACH) "I’m awake · End journey" else "End journey", stop)
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
        if (silentWarning) builder.setSilent(true)
        return builder.build()
    }

    private fun endJourney(message: String) {
        recoveryStore.clear()
        savedJourney = null
        JourneySession.running = false
        JourneySession.publish((engine?.state ?: JourneyState()).copy(status = TrackingStatus.ENDED, message = message))
        ticker?.cancel()
        runCatching { location.removeLocationUpdates(callback) }
        alarm.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        notifications.cancel(NOTIFICATION_ALARM)
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
            JourneyDiagnostics.record(this, savedJourney, "Android destroyed the service while a journey was active; waiting for system recovery.")
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

    companion object {
        const val ACTION_START = "com.laststop.START"
        const val ACTION_STOP = "com.laststop.STOP"
        const val ACTION_RESUME = "com.laststop.RESUME"
        private const val ACTION_DISMISS_LIVE_UPDATE = "com.laststop.DISMISS_LIVE_UPDATE"
        private const val CHANNEL_JOURNEY = "journey"
        private const val CHANNEL_ALARM = "alarm"
        private const val NOTIFICATION_JOURNEY = 101
        private const val NOTIFICATION_ALARM = 102
    }
}
