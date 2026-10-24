package com.laststop.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.PowerManager
import android.app.ActivityManager
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var setupMessage by mutableStateOf<String?>(null)
    private var sourceId by mutableStateOf("PNVL")
    private var destinationId by mutableStateOf("VSH")
    private var selectedPlanId by mutableStateOf("")
    private var soundUri by mutableStateOf("")
    private var soundName by mutableStateOf("Phone default alarm")
    private var darkTheme by mutableStateOf<Boolean?>(null)
    private var settingsLoaded by mutableStateOf(false)
    private var importingSound by mutableStateOf(false)
    private var nowMs by mutableLongStateOf(SystemClock.elapsedRealtime())
    private lateinit var preferences: JourneyPreferences
    private lateinit var previewAlarm: AlarmPlayer
    private var previewJob: Job? = null
    private var pendingLeg = 0
    private var pendingStart = false
    private var pendingRecovery by mutableStateOf(false)
    private var backgroundNotice by mutableStateOf<String?>(null)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingStart) {
            pendingStart = false
            if (hasLocation() && hasNotifications()) startJourney(pendingLeg)
            else setupMessage = "Allow precise location and notifications to start. You can change these in App settings."
        }
    }
    private val phoneSoundPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            if (uri != null) {
                if (RingtoneManager.isDefault(uri)) resetSound()
                else {
                    val title = runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull()
                    importSound(uri, title?.let { "Phone · $it" })
                }
            }
        }
    }
    private val audioFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSound(uri, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferences = JourneyPreferences(applicationContext)
        previewAlarm = AlarmPlayer(this) { setupMessage = "The selected sound could not play. A fallback alarm is playing; choose another sound." }
        lifecycleScope.launch {
            preferences.settings.collect { settings ->
                sourceId = settings.sourceId; destinationId = settings.destinationId
                selectedPlanId = settings.planId
                soundUri = settings.soundUri; soundName = settings.soundName
                darkTheme = settings.darkTheme
                settingsLoaded = true
            }
        }
        lifecycleScope.launch {
            while (isActive) { nowMs = SystemClock.elapsedRealtime(); delay(1_000) }
        }
        setContent {
            val isDark = darkTheme ?: isSystemInDarkTheme()
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
            WakeMeUpTheme(isDark) {
                val state by JourneySession.state.collectAsStateWithLifecycle()
                val report by JourneySession.lastAlertReport.collectAsStateWithLifecycle()
                val monitoringReport by JourneySession.lastMonitoringReport.collectAsStateWithLifecycle()
                val running by JourneySession.isRunning.collectAsStateWithLifecycle()
                LaunchedEffect(state.status, state.message, running) {
                    if (!running && state.status == TrackingStatus.ENDED) setupMessage = state.message
                }
                WakeMeUpScreen(
                    sourceId = sourceId, destinationId = destinationId, selectedPlanId = selectedPlanId,
                    soundName = soundName, hasCustomSound = soundUri.isNotEmpty(),
                    settingsLoaded = settingsLoaded && !pendingRecovery, importingSound = importingSound, dark = isDark,
                    journey = state, active = running,
                    nowMs = nowMs, message = setupMessage,
                    lastAlertReport = report, lastMonitoringReport = monitoringReport, backgroundNotice = backgroundNotice,
                    actions = JourneyUiActions(
                        source = { sourceId = it; selectedPlanId = ""; saveSettings() },
                        destination = { destinationId = it; selectedPlanId = ""; saveSettings() },
                        swap = { val old = sourceId; sourceId = destinationId; destinationId = old; selectedPlanId = ""; saveSettings() },
                        plan = { selectedPlanId = it; saveSettings() },
                        theme = { dark -> darkTheme = dark; lifecycleScope.launch { preferences.saveTheme(dark) } },
                        start = ::requestJourney, stop = ::stopJourney,
                        phoneSound = ::choosePhoneSound,
                        audioFile = { stopPreview(); audioFilePicker.launch(arrayOf("audio/*")) },
                        defaultSound = ::resetSound, testSound = ::testAlarm, stopPreview = ::stopPreview,
                        clearMessage = { setupMessage = null },
                        appSettings = { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) },
                        batterySettings = {
                            runCatching { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                                .onFailure { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) }
                        },
                        locationSettings = { startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                        notificationSettings = {
                            startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
                        },
                        copyAlertReport = {
                            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                android.content.ClipData.newPlainText("wakemeup last alert", JourneySession.lastAlertReport.value))
                        },
                        copyMonitoringReport = {
                            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                android.content.ClipData.newPlainText("wakemeup monitoring details", JourneySession.lastMonitoringReport.value))
                        },
                        attribution = { startActivity(Intent(Intent.ACTION_VIEW, "https://www.openstreetmap.org/copyright".toUri())) },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val session = getSharedPreferences("session", MODE_PRIVATE)
        JourneySession.publishReport(session.getString("last_alert_report", "").orEmpty())
        JourneySession.publishMonitoringReport(session.getString("last_monitoring_report", "").orEmpty())
        val restricted = Build.VERSION.SDK_INT >= 28 && getSystemService(ActivityManager::class.java).isBackgroundRestricted
        val optimized = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        backgroundNotice = when {
            restricted -> "Android reports background restrictions for wakemeup. Allow background activity in App settings before resting."
            optimized -> "Battery optimization is enabled for wakemeup. Review battery settings for long journeys; Battery Saver being off does not disable app-specific restrictions."
            else -> null
        }
        if (!JourneySession.running && !pendingRecovery && session.getBoolean("unfinished", false)) {
            pendingRecovery = true
            lifecycleScope.launch {
                try {
                    val store = JourneyRecoveryStore(this@MainActivity)
                    val saved = store.load()
                    val exit = withContext(Dispatchers.IO) { JourneyDiagnostics.latestExit(this@MainActivity, saved) }
                    if (JourneySession.running) return@launch
                    withContext(Dispatchers.IO) {
                        JourneyDiagnostics.record(this@MainActivity, saved, "The app was reopened after monitoring was interrupted.", exit)
                    }
                    // Reopening is a visible, user-initiated opportunity to recover if the
                    // OS has not restarted the sticky service. Never undo an explicit stop.
                    if (saved != null && exit?.userStopped != true &&
                        saved.remainingMs(SystemClock.elapsedRealtime(), JourneyRecoveryStore.bootCount(this@MainActivity)) > 0 &&
                        saved.plan() != null && store.load()?.id == saved.id && !JourneySession.running &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        ContextCompat.startForegroundService(this@MainActivity,
                            Intent(this@MainActivity, JourneyService::class.java).setAction(JourneyService.ACTION_RESUME)
                                .putExtra("session_id", saved.id))
                        setupMessage = null
                    } else if (!JourneySession.running) {
                        setupMessage = "Monitoring was interrupted. You are not being monitored. Start a new journey before resting. See Monitoring details in the info menu."
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    setupMessage = "Monitoring could not resume. Check permissions and battery settings, then start your journey again."
                } finally { pendingRecovery = false }
            }
        }
    }
    private fun selectedPlan(): JourneyPlan? {
        val plans = MumbaiNetwork.plans(sourceId, destinationId)
        return plans.firstOrNull { it.id == selectedPlanId } ?: plans.firstOrNull()
    }
    private fun saveSettings() {
        val source = sourceId; val destination = destinationId; val plan = selectedPlanId
        lifecycleScope.launch { preferences.saveJourney(source, destination, plan) }
    }
    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasNotifications() = NotificationManagerCompat.from(this).areNotificationsEnabled()
    private fun requestJourney(legIndex: Int = 0) {
        setupMessage = null; pendingLeg = legIndex
        val missing = mutableListOf<String>()
        if (!hasLocation()) { missing += Manifest.permission.ACCESS_FINE_LOCATION; missing += Manifest.permission.ACCESS_COARSE_LOCATION }
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifications()) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) { pendingStart = true; permissions.launch(missing.toTypedArray()); return }
        startJourney(legIndex)
    }
    private fun startJourney(legIndex: Int) {
        val plan = selectedPlan() ?: return
        if (!settingsLoaded || importingSound || legIndex !in plan.legs.indices) return
        if (!hasLocation() || !hasNotifications()) { setupMessage = "Precise location and notifications must be enabled before starting."; return }
        if (!getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)) { setupMessage = "Switch on device location, then start your journey."; return }
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel("journey")?.importance == NotificationManager.IMPORTANCE_NONE || manager.getNotificationChannel("alarm")?.importance == NotificationManager.IMPORTANCE_NONE) {
            setupMessage = "Enable wakemeup’s journey and wake-up notification channels in App settings."; return
        }
        if (getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM) == 0) { setupMessage = "Raise your alarm volume and test the sound before starting."; return }
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) { setupMessage = "Turn off Do Not Disturb before starting so the wake-up alarm can be heard."; return }
        stopPreview(); setupMessage = null; saveSettings()
        try {
            ContextCompat.startForegroundService(this, Intent(this, JourneyService::class.java).setAction(JourneyService.ACTION_START)
                .putExtra("source_id", sourceId).putExtra("destination_id", destinationId).putExtra("plan_id", plan.id)
                .putExtra("leg_index", legIndex).putExtra("sound_uri", soundUri))
        } catch (_: Exception) { setupMessage = "Android could not start monitoring. Keep the app open and check its permissions." }
    }
    private fun testAlarm() {
        stopPreview()
        previewJob = lifecycleScope.launch { previewAlarm.start(soundUri); delay(5_000); previewAlarm.stop() }
    }
    private fun stopPreview() { previewJob?.cancel(); previewAlarm.stop() }
    private fun choosePhoneSound() {
        stopPreview()
        try {
            phoneSoundPicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "wakemeup alarm sound")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)))
        } catch (_: Exception) { setupMessage = "This phone has no alarm-sound picker. Use the default alarm or choose your own audio file." }
    }
    private fun resetSound() {
        stopPreview()
        lifecycleScope.launch {
            val old = soundUri
            preferences.saveSound("", "Phone default alarm")
            soundUri = ""; soundName = "Phone default alarm"
            deleteOwnedSound(old)
        }
    }
    private fun importSound(uri: Uri, suggestedName: String?) {
        if (JourneySession.running || importingSound) return
        stopPreview(); importingSound = true
        val folder = File(filesDir, "alarm_sounds").apply { mkdirs() }
        val file = File(folder, "${UUID.randomUUID()}.audio")
        lifecycleScope.launch {
            var saved = false
            try {
                val label = withContext(Dispatchers.IO) {
                    val name = suggestedName ?: contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "My audio file"
                    contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> AudioImport.copy(input, output) } }
                        ?: error("The selected file could not be opened.")
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(file.absolutePath)
                        require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "Choose a playable audio file." }
                        require((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0) { "The audio file has no playable duration." }
                    } finally { metadata.release() }
                    name
                }
                val old = soundUri
                preferences.saveSound(Uri.fromFile(file).toString(), label)
                soundUri = Uri.fromFile(file).toString(); soundName = label
                saved = true
                deleteOwnedSound(old)
                setupMessage = "Sound saved on this phone. Open Alarm sound to preview it."
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { setupMessage = error.message ?: "Could not import this sound. Your previous sound is still selected." }
            finally { if (!saved) file.delete(); importingSound = false }
        }
    }
    private fun deleteOwnedSound(uri: String) {
        if (uri.isBlank()) return
        runCatching {
            val parsed = uri.toUri()
            if (parsed.scheme == "file") {
                val file = File(parsed.path ?: return)
                if (file.canonicalFile.parentFile == File(filesDir, "alarm_sounds").canonicalFile) file.delete()
            }
        }
    }
    private fun stopJourney() {
        stopPreview()
        if (JourneySession.running) startService(Intent(this, JourneyService::class.java).setAction(JourneyService.ACTION_STOP))
    }
    override fun onStop() {
        super.onStop()
        stopPreview()
    }
    override fun onDestroy() { previewAlarm.stop(); super.onDestroy() }
}
