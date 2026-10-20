package com.laststop.app

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

@Composable fun WakeMeUpTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFC7B5F4), onPrimary = Color(0xFF211A31),
        primaryContainer = Color(0xFF332C45), onPrimaryContainer = Color(0xFFEADFFF),
        secondary = Color(0xFFA8D6B6), background = Color(0xFF131418),
        onBackground = Color(0xFFF2F0F7), surface = Color(0xFF1C1D23),
        onSurface = Color(0xFFF2F0F7), onSurfaceVariant = Color(0xFFB5B1C0),
        surfaceVariant = Color(0xFF25252D), outline = Color(0xFF77727E), outlineVariant = Color(0xFF37363F),
        error = Color(0xFFFFC995), errorContainer = Color(0xFF3C2C22), onErrorContainer = Color(0xFFFFD8B1)
    ) else lightColorScheme(
        primary = Color(0xFF2E5D43), onPrimary = Color(0xFFFAFCF6),
        primaryContainer = Color(0xFFE4EDDF), onPrimaryContainer = Color(0xFF214831),
        secondary = Color(0xFF476D4E), background = Color(0xFFF9F6EF),
        onBackground = Color(0xFF183C2F), surface = Color(0xFFFDFBF6),
        onSurface = Color(0xFF183C2F), onSurfaceVariant = Color(0xFF626A61),
        surfaceVariant = Color(0xFFEFEEE6), outline = Color(0xFF898D81), outlineVariant = Color(0xFFDDDCD2),
        error = Color(0xFF904520), errorContainer = Color(0xFFFFE8D7), onErrorContainer = Color(0xFF6D3219)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

data class JourneyUiActions(
    val source: (String) -> Unit = {}, val destination: (String) -> Unit = {}, val swap: () -> Unit = {},
    val plan: (String) -> Unit = {}, val theme: (Boolean) -> Unit = {},
    val start: (Int) -> Unit = {}, val stop: () -> Unit = {},
    val phoneSound: () -> Unit = {}, val audioFile: () -> Unit = {}, val defaultSound: () -> Unit = {},
    val testSound: () -> Unit = {}, val stopPreview: () -> Unit = {}, val clearMessage: () -> Unit = {},
    val appSettings: () -> Unit = {}, val locationSettings: () -> Unit = {}, val attribution: () -> Unit = {},
    val notificationSettings: () -> Unit = {},
)

@Composable fun WakeMeUpScreen(
    sourceId: String, destinationId: String, selectedPlanId: String,
    soundName: String, hasCustomSound: Boolean, settingsLoaded: Boolean, importingSound: Boolean,
    dark: Boolean, journey: JourneyState, active: Boolean, nowMs: Long, message: String?, actions: JourneyUiActions,
) {
    val colors = MaterialTheme.colorScheme
    val plans = remember(sourceId, destinationId) { MumbaiNetwork.plans(sourceId, destinationId) }
    val chosen = plans.firstOrNull { it.id == selectedPlanId } ?: plans.firstOrNull()
    val plan = if (active) journey.plan else chosen
    val ready = settingsLoaded && !importingSound && !active
    val canContinue = !active && !journey.demo && journey.status == TrackingStatus.ENDED && journey.alarmKind == AlarmKind.APPROACH &&
        journey.hasNextLeg && chosen?.id == journey.plan.id
    val nextLeg = if (canContinue) journey.plan.legs[journey.legIndex + 1] else chosen?.legs?.firstOrNull()
    var stationPicker by remember { mutableStateOf<Boolean?>(null) }
    var soundPicker by remember { mutableStateOf(false) }
    var routeDetails by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }

    stationPicker?.let { source -> StationPicker(if (source) "Starting station" else "Destination",
        onDismiss = { stationPicker = null }, onSelected = {
            if (source) actions.source(it.id) else actions.destination(it.id)
            stationPicker = null
        }) }
    if (soundPicker) AlertDialog(onDismissRequest = { soundPicker = false; actions.stopPreview() },
        title = { Text("Alarm sound") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (importingSound) "Preparing your sound…" else soundName, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { soundPicker = false; actions.phoneSound() }, modifier = Modifier.fillMaxWidth(), enabled = ready) { Text("Phone alarm sounds") }
            OutlinedButton(onClick = { soundPicker = false; actions.audioFile() }, modifier = Modifier.fillMaxWidth(), enabled = ready) { Text("Choose my audio file") }
            if (hasCustomSound) TextButton(onClick = actions.defaultSound, enabled = ready) { Text("Use phone default") }
            Button(onClick = actions.testSound, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Play a 5-second preview") }
            Text("Your audio is saved on this phone for offline use. Up to 32 MB.", fontSize = 12.sp, color = colors.onSurfaceVariant)
        } }, confirmButton = { TextButton(onClick = { soundPicker = false; actions.stopPreview() }) { Text("Done") } })
    if (routeDetails && plan != null) RouteDetails(plan, if (active) listOf(plan) else plans, ready,
        onChoose = { actions.plan(it); routeDetails = false }, onDismiss = { routeDetails = false })
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Before you rest") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Check your train follows the displayed route and stops at your destination.")
            Text("The alarm sounds as you approach the station before your destination on the selected route. For a train change, it uses the station before that interchange. Station order does not describe which stops a fast train will make.")
            Text("Test your alarm with your usual headphones. Keep alarm volume audible and Do Not Disturb off.")
            Text("On OnePlus, allow background activity in wakemeup’s app battery settings if available. Keep the journey notification visible.")
            Text("The train marker shows the last confirmed position. If location is lost, it pauses and the app warns you. Force-stopping the app or switching off the phone stops protection.")
            TextButton(onClick = actions.appSettings) { Text("App settings") }
            TextButton(onClick = actions.locationSettings) { Text("Location settings") }
            if (Build.VERSION.SDK_INT >= 36) {
                Text("Live journey updates can appear on the lock screen and as a pill. Enable Live updates in notification settings if available. Your phone controls the appearance.")
                TextButton(onClick = actions.notificationSettings) { Text("Live updates & notifications") }
            }
            TextButton(onClick = actions.attribution) { Text("Route data & attribution") }
        } }, confirmButton = { TextButton(onClick = { help = false }) { Text("Got it") } })

    Scaffold(containerColor = colors.background, bottomBar = {
        Surface(color = colors.background) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (active) {
                    if (journey.status == TrackingStatus.ALARM) Button(onClick = actions.stop,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.background)) {
                        Text("I’m awake · End ${if (journey.hasNextLeg) "leg" else "journey"}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    } else OutlinedButton(onClick = actions.stop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                        Text("End journey", fontSize = 16.sp)
                    }
                } else {
                    Button(onClick = {
                        actions.start(if (canContinue) journey.legIndex + 1 else 0)
                    }, enabled = ready && plan != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                        Text(if (canContinue) "Start next leg" else "Start journey",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("wakemeup", fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { help = true }, modifier = Modifier.semantics { contentDescription = "Travel checklist and settings" }) { Glyph(Symbol.INFO, colors.onSurfaceVariant) }
                        FilledTonalIconToggleButton(checked = dark, onCheckedChange = actions.theme, enabled = settingsLoaded,
                            modifier = Modifier.semantics { contentDescription = "Dark theme"; stateDescription = if (dark) "On" else "Off" }) {
                            Glyph(if (dark) Symbol.MOON else Symbol.SUN)
                        }
                    }
                }
            }
            message?.let { text -> item {
                Surface(color = colors.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = actions.clearMessage, modifier = Modifier.size(32.dp).semantics { contentDescription = "Dismiss message" }) { Glyph(Symbol.CLOSE, modifier = Modifier.size(16.dp)) }
                        }
                        if (text.contains("location", true) || text.contains("permission", true) || text.contains("notification", true))
                            TextButton(onClick = actions.appSettings) { Text("Open app settings") }
                    }
                }
            } }
            if (active) {
                item { ActiveJourney(journey, nowMs) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { routeDetails = true }) { Text("Route & stops") }
                    }
                }
            } else {
                item { Text(if (canContinue) "Ready for your\nnext train?" else "Where are you\nheaded?",
                    fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp) }
                if (canContinue) item {
                    Text("Board your next train at ${journey.destination.name}, then start the next leg.", color = colors.primary, fontSize = 14.sp)
                }
                item { StationFields(MumbaiNetwork.station(sourceId).name, MumbaiNetwork.station(destinationId).name, ready,
                    onSource = { stationPicker = true }, onDestination = { stationPicker = false }, onSwap = actions.swap) }
                if (plan == null) item { Text("Choose different source and destination stations.", color = colors.error) }
                plan?.let { route -> item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (route.changes.isEmpty()) route.legs.first().lineName else "Change at ${route.changes.joinToString(" → ") { it.name }}",
                                color = if (route.changes.isEmpty()) colors.onSurfaceVariant else colors.error, fontSize = 13.sp)
                        }
                        TextButton(onClick = { routeDetails = true }) { Text("Route & stops", fontSize = 12.sp) }
                    }
                } }
                item {
                    Column {
                        nextLeg?.let { leg ->
                            WakeUpSummary(leg)
                            HorizontalDivider(color = colors.outlineVariant)
                        }
                        SettingRow(Symbol.BELL, "Alarm sound", if (importingSound) "Preparing sound…" else soundName, ready) { soundPicker = true }
                    }
                }
                if (plan != null) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Glyph(Symbol.CHECK, colors.secondary, Modifier.size(18.dp))
                        Text("Offline route ready", fontSize = 13.sp, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable private fun StationFields(source: String, destination: String, enabled: Boolean, onSource: () -> Unit, onDestination: () -> Unit, onSwap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(20.dp).height(124.dp)) {
                val x = size.width / 2
                drawLine(colors.outlineVariant, Offset(x, 28.dp.toPx()), Offset(x, size.height - 28.dp.toPx()), 1.5.dp.toPx())
                drawCircle(colors.primary, 4.5.dp.toPx(), Offset(x, 28.dp.toPx()))
                drawCircle(colors.surface, 4.5.dp.toPx(), Offset(x, size.height - 28.dp.toPx()))
                drawCircle(colors.primary, 4.5.dp.toPx(), Offset(x, size.height - 28.dp.toPx()), style = Stroke(1.8.dp.toPx()))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Column(Modifier.fillMaxWidth().clickable(enabled, onClick = onSource).padding(vertical = 18.dp)) {
                    Text("From", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    Text(source, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = colors.outlineVariant)
                Column(Modifier.fillMaxWidth().clickable(enabled, onClick = onDestination).padding(vertical = 18.dp)) {
                    Text("To", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    Text(destination, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            IconButton(onClick = onSwap, enabled = enabled, modifier = Modifier.padding(start = 6.dp)
                .background(colors.primaryContainer, CircleShape).semantics { contentDescription = "Swap source and destination" }) { Glyph(Symbol.SWAP) }
        }
    }
}

@Composable private fun SettingRow(symbol: Symbol, title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(enabled, onClick = onClick).padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Glyph(symbol, colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.onSurfaceVariant, fontSize = 12.sp)
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Glyph(Symbol.CHEVRON, colors.onSurfaceVariant, Modifier.size(18.dp))
    }
}

@Composable private fun WakeUpSummary(leg: JourneyLeg) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Glyph(Symbol.PIN, colors.onSurfaceVariant, Modifier.padding(top = 4.dp).size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Wake me one station before", color = colors.onSurfaceVariant, fontSize = 12.sp)
            Text(leg.wakeStation.name, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Text(if (leg.wakeAtSource) "Your destination is next. The alarm sounds once location is confirmed. Stay awake."
                else "As you approach ${leg.wakeStation.name}, before ${leg.route.stations.last().name}.",
                color = if (leg.wakeAtSource) colors.error else colors.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable private fun ActiveJourney(state: JourneyState, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    val fresh = JourneyVisuals.fresh(state, nowMs)
    val warning = state.status == TrackingStatus.UNCERTAIN || state.status == TrackingStatus.ALARM || state.status == TrackingStatus.TRACKING && !fresh
    val status = when {
        state.status == TrackingStatus.ALARM -> "Wake up & check your stop"
        state.status == TrackingStatus.ACQUIRING -> "Finding your location"
        !fresh -> "Checking location · train paused"
        else -> "Journey active"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(8.dp).background(if (warning) colors.error else colors.secondary, CircleShape))
            Text(status, fontSize = 13.sp, color = if (warning) colors.error else colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Text(if (state.hasNextLeg) "Your next change is" else "Your stop is", color = colors.onSurfaceVariant, fontSize = 17.sp)
        Text(state.destination.name, fontSize = 46.sp, lineHeight = 51.sp, letterSpacing = (-2).sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        val remaining = state.remainingMeters
        if (remaining != null) {
            Text((if (fresh) "~" else "Last known: ~") + kilometres(remaining) + if (fresh) " away" else "",
                fontSize = if (fresh) 29.sp else 21.sp, fontWeight = FontWeight.SemiBold,
                color = if (fresh) colors.primary else colors.onSurfaceVariant, textAlign = TextAlign.Center)
        } else Text("Waiting for a reliable position", color = colors.onSurfaceVariant, fontSize = 16.sp)
        Text(if (state.leg.wakeAtSource) "Next-station trip · Stay awake"
            else "Wake near ${state.leg.wakeStation.name} · One station before",
            color = colors.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (state.status != TrackingStatus.TRACKING || !fresh) Surface(color = colors.errorContainer, shape = RoundedCornerShape(16.dp)) {
            Text(if (state.status == TrackingStatus.TRACKING) "Location is stale. The train marker is paused while tracking recovers." else state.message,
                color = colors.onErrorContainer, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        TrainJourneyProgress(state, nowMs)
        state.lastFixMs?.let { time -> Text("Location updated ${((nowMs - time) / 1000).coerceAtLeast(0)}s ago",
            color = colors.onSurfaceVariant, fontSize = 12.sp) }
    }
}

@Composable fun TrainJourneyProgress(state: JourneyState, nowMs: Long) {
    val colors = MaterialTheme.colorScheme
    val route = state.leg.route
    val fraction = JourneyVisuals.fraction(state)
    val fresh = JourneyVisuals.fresh(state, nowMs)
    val position = remember(state.plan.id, state.legIndex) { Animatable(fraction ?: 0f) }
    LaunchedEffect(fraction, fresh) {
        if (fraction != null && fresh) position.animateTo(fraction, tween(850, easing = FastOutSlowInEasing))
        else position.stop()
    }
    val sampled = remember(route) {
        val stride = max(1, (route.stations.size - 1 + 5) / 6)
        route.stations.indices.filter { it == 0 || it == route.stations.lastIndex || it % stride == 0 }
    }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
        contentDescription = "Journey from ${route.stations.first().name} to ${route.stations.last().name}"
        stateDescription = if (fraction == null) "Waiting for location" else if (!fresh) "Paused at last confirmed location" else "Confirmed journey progress"
        if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
    }) {
        Canvas(Modifier.fillMaxWidth().height(114.dp)) {
            val start = 34.dp.toPx()
            val end = size.width - start
            val width = end - start
            val y = 76.dp.toPx()
            val shown = position.value
            val x = start + width * shown
            var sleeper = start
            while (sleeper <= end) {
                drawLine(if (fraction != null && sleeper <= x) colors.primary.copy(alpha = .5f) else colors.outlineVariant,
                    Offset(sleeper, y - 4.dp.toPx()), Offset(sleeper, y + 11.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
                sleeper += 13.dp.toPx()
            }
            listOf(y, y + 7.dp.toPx()).forEach { rail ->
                drawLine(colors.outlineVariant, Offset(start, rail), Offset(end, rail), 2.dp.toPx(), StrokeCap.Round)
                if (fraction != null) drawLine(colors.primary, Offset(start, rail), Offset(x, rail), 2.dp.toPx(), StrokeCap.Round)
            }
            sampled.forEach { index ->
                val stationX = start + width * (route.cumulative[index] / route.cumulative.last()).toFloat()
                drawCircle(colors.background, 5.dp.toPx(), Offset(stationX, y + 3.5.dp.toPx()))
                drawCircle(if (fraction != null && stationX <= x) colors.primary else colors.outline,
                    5.dp.toPx(), Offset(stationX, y + 3.5.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
            if (fraction != null) {
                val moving = fresh && abs(shown - fraction) > .0005f
                val bob = if (moving) sin(shown * 220f) * .8.dp.toPx() else 0f
                val top = y - 32.dp.toPx() + bob
                val left = x - 28.dp.toPx()
                // A little two-car train; wheel motion comes only from actual marker movement.
                drawRoundRect(colors.primary, Offset(left, top + 4.dp.toPx()), Size(23.dp.toPx(), 22.dp.toPx()), CornerRadius(5.dp.toPx()))
                drawRoundRect(colors.primary, Offset(left + 26.dp.toPx(), top), Size(30.dp.toPx(), 26.dp.toPx()), CornerRadius(7.dp.toPx()))
                drawLine(colors.primary, Offset(left + 21.dp.toPx(), top + 20.dp.toPx()), Offset(left + 28.dp.toPx(), top + 20.dp.toPx()), 3.dp.toPx())
                listOf(4f, 14f, 31f, 43f).forEach { wx ->
                    drawRoundRect(colors.background, Offset(left + wx.dp.toPx(), top + 7.dp.toPx()), Size(6.dp.toPx(), 8.dp.toPx()), CornerRadius(1.5.dp.toPx()))
                }
                drawLine(colors.background, Offset(left + 51.dp.toPx(), top + 20.dp.toPx()), Offset(left + 54.dp.toPx(), top + 20.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                listOf(6f, 18f, 33f, 48f).forEach { wheel ->
                    val centre = Offset(left + wheel.dp.toPx(), top + 28.dp.toPx())
                    drawCircle(colors.onBackground, 4.dp.toPx(), centre)
                    rotate(shown * 7200f, centre) {
                        drawLine(colors.background, Offset(centre.x - 2.dp.toPx(), centre.y), Offset(centre.x + 2.dp.toPx(), centre.y), 1.dp.toPx())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(route.stations.first().name, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 2,
                modifier = Modifier.weight(1f).padding(end = 20.dp))
            Text(route.stations.last().name, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 2,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        JourneyVisuals.nextStation(state)?.let { next ->
            Text(if (fresh) "Next on your route · ${next.name}" else "Last confirmed route · next stop ${next.name}",
                color = colors.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
        }
    }
}

@Composable private fun StationPicker(title: String, onDismiss: () -> Unit, onSelected: (Station) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { MumbaiNetwork.search(query) }
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(.84f)) {
            Column(Modifier.padding(20.dp)) {
                Text(title, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Station name or code") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                if (results.isEmpty()) Text("No matching station.", color = colors.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f)) {
                    items(results, key = { it.id }) { station ->
                        Column(Modifier.fillMaxWidth().clickable { onSelected(station) }.padding(vertical = 14.dp)) {
                            Text(station.name, fontSize = 17.sp)
                            Text(station.id, color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

@Composable private fun RouteDetails(plan: JourneyPlan, options: List<JourneyPlan>, editable: Boolean, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(.84f)) {
            Column(Modifier.padding(22.dp)) {
                Text("Route & stops", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
                    if (editable && options.size > 1) items(options, key = { it.id }) { option ->
                        Row(Modifier.fillMaxWidth().clickable { onChoose(option.id) }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = option.id == plan.id, onClick = { onChoose(option.id) })
                            Text(option.description, fontSize = 13.sp)
                        }
                    }
                    item { Text("Confirm your train follows this route and stops where needed. Distances are approximate.", color = colors.onSurfaceVariant, fontSize = 13.sp) }
                    plan.legs.forEachIndexed { index, leg ->
                        item {
                            Text("${if (plan.legs.size > 1) "${index + 1}. " else ""}${leg.lineName}", fontWeight = FontWeight.SemiBold)
                            if (index < plan.legs.lastIndex) Text("Wake up to change at ${leg.route.stations.last().name}", color = colors.error, fontSize = 12.sp)
                        }
                        items(leg.route.stations, key = { "$index:${it.id}" }) { station -> Text("•  ${station.name}", fontSize = 14.sp) }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

private enum class Symbol { SUN, MOON, INFO, SWAP, PIN, BELL, CHEVRON, CHECK, CLOSE }
@Composable private fun Glyph(symbol: Symbol, tint: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val s = size.minDimension
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(tint, Offset(a * s, b * s), Offset(c * s, d * s), 1.7.dp.toPx(), StrokeCap.Round)
        when (symbol) {
            Symbol.SUN -> {
                drawCircle(tint, s * .20f, Offset(s / 2, s / 2), style = Stroke(1.7.dp.toPx()))
                line(.5f,.03f,.5f,.14f); line(.5f,.86f,.5f,.97f); line(.03f,.5f,.14f,.5f); line(.86f,.5f,.97f,.5f)
                line(.17f,.17f,.25f,.25f); line(.75f,.75f,.83f,.83f); line(.17f,.83f,.25f,.75f); line(.75f,.25f,.83f,.17f)
            }
            Symbol.MOON -> {
                val path = Path().apply { moveTo(s * .68f,s * .08f); cubicTo(-s * .12f,s * .06f,-s * .08f,s * .93f,s * .52f,s * .94f)
                    cubicTo(s * .80f,s * .95f,s * .97f,s * .72f,s * .95f,s * .57f); cubicTo(s * .48f,s * .77f,s * .27f,s * .30f,s * .68f,s * .08f); close() }
                drawPath(path, tint, style = Stroke(1.7.dp.toPx()))
            }
            Symbol.INFO -> { drawCircle(tint,s * .39f,Offset(s/2,s/2),style=Stroke(1.7.dp.toPx())); line(.5f,.45f,.5f,.70f); drawCircle(tint,s * .035f,Offset(s/2,s * .29f)) }
            Symbol.SWAP -> { line(.32f,.15f,.32f,.83f); line(.15f,.32f,.32f,.15f); line(.32f,.15f,.49f,.32f); line(.70f,.17f,.70f,.85f); line(.53f,.68f,.70f,.85f); line(.70f,.85f,.87f,.68f) }
            Symbol.PIN -> {
                val p=Path().apply { moveTo(s/2,s * .94f); cubicTo(s * .38f,s * .72f,s * .14f,s * .48f,s * .16f,s * .31f)
                    cubicTo(s * .21f,-s * .02f,s * .79f,-s * .02f,s * .84f,s * .31f); cubicTo(s * .86f,s * .48f,s * .62f,s * .72f,s/2,s * .94f) }
                drawPath(p,tint,style=Stroke(1.7.dp.toPx())); drawCircle(tint,s * .10f,Offset(s/2,s * .32f),style=Stroke(1.7.dp.toPx()))
            }
            Symbol.BELL -> {
                val p=Path().apply { moveTo(s * .16f,s * .76f); lineTo(s * .24f,s * .65f); lineTo(s * .24f,s * .38f)
                    cubicTo(s * .24f,s * .04f,s * .76f,s * .04f,s * .76f,s * .38f); lineTo(s * .76f,s * .65f); lineTo(s * .84f,s * .76f); close() }
                drawPath(p,tint,style=Stroke(1.7.dp.toPx())); line(.42f,.90f,.58f,.90f); line(.5f,.06f,.5f,.02f)
            }
            Symbol.CHEVRON -> { line(.35f,.2f,.65f,.5f); line(.65f,.5f,.35f,.8f) }
            Symbol.CHECK -> { line(.12f,.50f,.40f,.76f); line(.40f,.76f,.90f,.22f) }
            Symbol.CLOSE -> { line(.2f,.2f,.8f,.8f); line(.2f,.8f,.8f,.2f) }
        }
    }
}

private fun kilometres(meters: Double) = String.format(Locale.US, "%.1f km", meters / 1000.0)

@Preview(name = "Warm light", widthDp = 390, heightDp = 844)
@Composable private fun LightHomePreview() { HomePreview(false) }
@Preview(name = "Quiet dark", widthDp = 390, heightDp = 844)
@Composable private fun DarkHomePreview() { HomePreview(true) }
@Composable private fun HomePreview(dark: Boolean) {
    WakeMeUpTheme(dark) { WakeMeUpScreen("PNVL", "VSH", "", "Phone default", false, true, false,
        dark, JourneyState(), false, 0, null, JourneyUiActions()) }
}
@Preview(name = "Train in motion", widthDp = 390, heightDp = 844)
@Composable private fun ActivePreview() {
    val state = JourneyState(status = TrackingStatus.TRACKING, remainingMeters = 4200.0,
        progress = 11000.0, trustedProgress = 11000.0, lastFixMs = 1000)
    WakeMeUpTheme(false) { WakeMeUpScreen("PNVL", "VSH", "", "Phone default", false, true, false,
        false, state, true, 6000, null, JourneyUiActions()) }
}
