@file:OptIn(ExperimentalLayoutApi::class)

package com.example.companion2

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.random.Random
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

// ---------------- Settings screen (long-press the eyes to open) ----------------
//
// Layout: a side list of every section on the left (all visible at once, no hunting), and the
// chosen section on the right, under its title and a big Back button.

// Settings colors: deep near-black, soft holographic cyan, quiet gray text
val Accent = Color(0xFF5CE1FF)
val AccentSoft = Color(0xFFB794FF)  // the violet half of the holographic sheen
val Muted = Color(0xFF8A93A3)
val Ink = Color(0xFFF2F4F7)
val Backdrop = Color(0xFF08090C)
val Panel = Color(0xFF111319)
val Hairline = Color(0xFF232731)

val SettingsColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00141B),
    primaryContainer = Color(0xFF0E3A47),
    onPrimaryContainer = Ink,
    secondary = Accent,
    onSecondary = Color(0xFF00141B),
    secondaryContainer = Color(0xFF16323B),
    onSecondaryContainer = Ink,
    background = Backdrop,
    onBackground = Ink,
    surface = Panel,
    onSurface = Ink,
    surfaceVariant = Color(0xFF1A1D25),
    onSurfaceVariant = Muted,
    outline = Color(0xFF39404D),
    outlineVariant = Hairline
)

// The sections, with a line under each title saying what's in it
private class Section(val name: String, val about: String)

private val SECTIONS = listOf(
    Section("Orbit", "Its name, voice and personality, and how lively it is."),
    Section("Eyes", "How Orbit looks. Your changes show live."),
    Section("Music", "Just ask. Tap Orbit, hold up a hand or cover its eyes to stop."),
    Section("Privacy", "What it sees and what it remembers."),
    Section("Device", "Your key, starting by itself, the neck, and phone care.")
)

@Composable
fun SettingsScreen(
    settings: Settings,
    mic: Microphone,
    gemini: GeminiLive,
    wake: WakeWord,
    life: InnerLife,
    neck: Neck,
    heat: HeatWatch,
    sounds: Sounds,
    weather: Weather,
    desk: DeskLink,
    gestures: Gestures,
    people: People,
    calendar: DayCalendar,
    player: MusicControl,
    onHatch: () -> Unit,
    onTryMove: (IdleMove) -> Unit,
    onFindScreen: () -> Unit,
    onClose: () -> Unit
) {
    // Opens on Orbit, or on Device if there's no key yet (that's where it goes)
    var tab by remember { mutableStateOf(if (gemini.hasKey()) 0 else SECTIONS.lastIndex) }

    MaterialTheme(colorScheme = SettingsColors) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0D0F14), Backdrop)))
        ) {
            Row(Modifier.fillMaxSize().padding(16.dp)) {
                SectionList(tab) { tab = it }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).fillMaxSize()) {
                    // Title of the section, and the way back to the eyes
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                SECTIONS[tab].name, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                                color = Ink, letterSpacing = (-0.3).sp
                            )
                            Text(SECTIONS[tab].about, fontSize = 14.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        BackButton(onClose)
                    }
                    Crossfade(targetState = tab, animationSpec = tween(180), label = "section") { shown ->
                        val name = SECTIONS[shown].name
                        if (name == "Eyes") {
                            // Eyes: live preview on the left, options scrolling on the right
                            Row(Modifier.fillMaxSize().padding(top = 12.dp)) {
                                EyePreview(
                                    settings,
                                    Modifier.weight(0.42f).fillMaxSize().padding(bottom = 8.dp)
                                        .clip(RoundedCornerShape(24.dp)).background(Color.Black)
                                )
                                Spacer(Modifier.width(20.dp))
                                SettingsPage(Modifier.weight(0.58f)) { EyesPage(settings) }
                            }
                        } else {
                            SettingsPage(Modifier.fillMaxSize()) {
                                when (name) {
                                    "Orbit" -> OrbitPage(settings, mic, gemini, wake, life, sounds, weather, gestures, onHatch, onTryMove)
                                    "Music" -> MusicPage(settings, player)
                                    "Privacy" -> PrivacyPage(settings, people, calendar)
                                    else -> DevicePage(settings, gemini, heat, neck, desk, player, onFindScreen)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// The list of sections down the left side. Every section fits without scrolling on a phone
// held sideways; on a very short screen the list scrolls rather than hiding anything.
@Composable
private fun SectionList(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        Modifier
            .width(212.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(24.dp))
            .background(Panel)
            .border(1.dp, Hairline, RoundedCornerShape(24.dp))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
    ) {
        SECTIONS.forEachIndexed { i, section ->
            val on = i == selected
            val tint = if (on) Accent else Muted
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) Accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionIcon(section.name, tint)
                Spacer(Modifier.width(12.dp))
                Text(
                    section.name, fontSize = 15.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) Ink else Muted
                )
            }
        }
    }
}

// Back to the eyes: big and easy to hit, with a soft holographic edge
@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .height(56.dp)
            .width(176.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Accent.copy(alpha = 0.22f), AccentSoft.copy(alpha = 0.22f))))
            .border(1.dp, Brush.horizontalGradient(listOf(Accent, AccentSoft)), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("Back", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

// Small line icons for the sections, drawn in code (no icon library needed)
@Composable
private fun SectionIcon(name: String, color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val line = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (name) {
            "Orbit" -> {  // a speech bubble
                drawRoundRect(color, Offset(w * 0.08f, h * 0.14f), Size(w * 0.84f, h * 0.58f), CornerRadius(w * 0.2f), style = line)
                drawPath(Path().apply {
                    moveTo(w * 0.3f, h * 0.72f); lineTo(w * 0.24f, h * 0.92f); lineTo(w * 0.5f, h * 0.72f)
                }, color, style = line)
            }
            "Eyes" -> {  // Orbit's two eyes
                drawCircle(color, w * 0.19f, Offset(w * 0.28f, h * 0.5f), style = line)
                drawCircle(color, w * 0.19f, Offset(w * 0.72f, h * 0.5f), style = line)
            }
            "Sound & life" -> {  // a little sound wave
                listOf(0.35f, 0.7f, 1f, 0.55f).forEachIndexed { i, tall ->
                    val x = w * (0.2f + 0.2f * i)
                    drawLine(color, Offset(x, h * (0.5f - 0.38f * tall)), Offset(x, h * (0.5f + 0.38f * tall)), line.width, StrokeCap.Round)
                }
            }
            "Music" -> {  // a music note
                drawCircle(color, w * 0.15f, Offset(w * 0.32f, h * 0.74f), style = line)
                drawLine(color, Offset(w * 0.47f, h * 0.74f), Offset(w * 0.47f, h * 0.14f), line.width, StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(w * 0.47f, h * 0.14f)
                    cubicTo(w * 0.55f, h * 0.22f, w * 0.7f, h * 0.3f, w * 0.8f, h * 0.32f)
                }, color, style = line)
            }
            "Privacy" -> {  // a padlock
                drawRoundRect(color, Offset(w * 0.2f, h * 0.44f), Size(w * 0.6f, h * 0.44f), CornerRadius(w * 0.08f), style = line)
                drawArc(color, 180f, 180f, false, Offset(w * 0.32f, h * 0.12f), Size(w * 0.36f, h * 0.5f), style = line)
            }
            "Memory" -> {  // a heart
                drawPath(Path().apply {
                    moveTo(w * 0.5f, h * 0.86f)
                    cubicTo(w * 0.02f, h * 0.55f, w * 0.12f, h * 0.06f, w * 0.5f, h * 0.3f)
                    cubicTo(w * 0.88f, h * 0.06f, w * 0.98f, h * 0.55f, w * 0.5f, h * 0.86f)
                    close()
                }, color, style = line)
            }
            "Device" -> {  // a phone on its side, like Orbit's
                drawRoundRect(color, Offset(w * 0.06f, h * 0.24f), Size(w * 0.88f, h * 0.52f), CornerRadius(w * 0.1f), style = line)
                drawCircle(color, w * 0.08f, Offset(w * 0.38f, h * 0.5f), style = line)
                drawCircle(color, w * 0.08f, Offset(w * 0.62f, h * 0.5f), style = line)
            }
            "Moon" -> {  // a crescent moon (spare)
                val moon = Path().apply {
                    op(
                        Path().apply { addOval(Rect(w * 0.12f, h * 0.12f, w * 0.88f, h * 0.88f)) },
                        Path().apply { addOval(Rect(w * 0.36f, h * 0.02f, w * 1.06f, h * 0.72f)) },
                        PathOperation.Difference
                    )
                }
                drawPath(moon, color, style = line)
            }
            "Neck" -> {  // a head on a neck
                drawCircle(color, w * 0.2f, Offset(w * 0.5f, h * 0.3f), style = line)
                drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.82f), line.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.24f, h * 0.86f), Offset(w * 0.76f, h * 0.86f), line.width, StrokeCap.Round)
            }
            "Computer" -> {  // a monitor
                drawRoundRect(color, Offset(w * 0.08f, h * 0.16f), Size(w * 0.84f, h * 0.52f), CornerRadius(w * 0.08f), style = line)
                drawLine(color, Offset(w * 0.5f, h * 0.68f), Offset(w * 0.5f, h * 0.84f), line.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.32f, h * 0.86f), Offset(w * 0.68f, h * 0.86f), line.width, StrokeCap.Round)
            }
            else -> {  // "i" in a circle
                drawCircle(color, w * 0.42f, Offset(w * 0.5f, h * 0.5f), style = line)
                drawCircle(color, line.width * 0.7f, Offset(w * 0.5f, h * 0.31f))
                drawLine(color, Offset(w * 0.5f, h * 0.46f), Offset(w * 0.5f, h * 0.7f), line.width, StrokeCap.Round)
            }
        }
    }
}

// A scrolling column of settings, kept to a comfortable reading width
@Composable
fun SettingsPage(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 40.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}

// The start of a setting: its name, and a short explanation in plain words
@Composable
fun SettingTitle(title: String, help: String) {
    Column(Modifier.padding(top = 18.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(2.dp))
        Text(help, fontSize = 14.sp, color = Muted, lineHeight = 20.sp)
    }
}

// A row of choices that wraps onto the next line instead of running off the screen
@Composable
fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

// A pill you pick: glowing outline and tint when chosen, a quiet outline when not
@Composable
fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (selected) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) Accent else Color(0xFF39404D), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 15.sp, color = if (selected) Accent else Ink,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// The voices as a grid of cards: tap one to choose it and hear it say hello
@Composable
fun VoicePicker(settings: Settings, gemini: GeminiLive) {
    val context = LocalContext.current
    val samples = remember { VoiceSamples(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { samples.stop() } }
    // The sample player works in the background; check on it a few times a second
    var loading by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf("") }
    var tick by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            loading = samples.loading
            playing = samples.playing
            problem = samples.problem
            tick = (System.currentTimeMillis() % 100_000L) / 1000f
            delay(60)
        }
    }
    VOICE_KINDS.forEach { kind ->
        // Grouped: female, male, in between
        Text(kind.uppercase(), fontSize = 12.sp, color = Muted, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VOICES.filter { it.kind == kind }.forEach { voice ->
                val name = voice.name
                val sound = voice.sound
                val chosen = settings.voice == name
                Row(
                    Modifier
                        .width(164.dp)
                        .height(58.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (chosen) Accent.copy(alpha = 0.14f) else Color(0xFF15181F))
                        .border(1.dp, if (chosen) Accent else Hairline, RoundedCornerShape(16.dp))
                        .clickable {
                            settings.voice = name
                            samples.play(gemini.key(), name, settings.companionName)
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold, color = if (chosen) Accent else Ink
                        )
                        Text(sound, fontSize = 12.sp, color = Muted, maxLines = 1)
                    }
                    // Little bars dance while it plays; a pulsing dot while the sample is fetched
                    when (name) {
                        playing -> Canvas(Modifier.size(18.dp)) {
                            for (i in 0..2) {
                                val tall = 0.35f + 0.65f * abs(sin(tick * 9f + i * 1.3f))
                                val x = size.width * (0.2f + 0.3f * i)
                                drawLine(
                                    Accent, Offset(x, size.height * (1f - tall)), Offset(x, size.height),
                                    2.5.dp.toPx(), StrokeCap.Round
                                )
                            }
                        }
                        loading -> Canvas(Modifier.size(18.dp)) {
                            drawCircle(Accent, size.width * 0.18f, alpha = 0.4f + 0.6f * abs(sin(tick * 5f)))
                        }
                        else -> Canvas(Modifier.size(18.dp)) {  // a small "play" triangle
                            drawPath(Path().apply {
                                moveTo(size.width * 0.3f, size.height * 0.2f)
                                lineTo(size.width * 0.82f, size.height * 0.5f)
                                lineTo(size.width * 0.3f, size.height * 0.8f)
                                close()
                            }, Muted)
                        }
                    }
                }
            }
        }
    }
    when {
        problem.isNotEmpty() -> Text(problem, color = Accent, fontSize = 14.sp)
        loading != null -> Text("Getting $loading's voice (the first time takes a few seconds)…", color = Muted, fontSize = 14.sp)
    }
}

// Entering and testing the Gemini key (settings, and the welcome setup)
@Composable
fun KeySetup(settings: Settings, gemini: GeminiLive) {
    val links = LocalUriHandler.current
    val context = LocalContext.current
    var showKey by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    if (!gemini.hasKey()) {
        Text("Add a key to start talking.", color = Accent, fontSize = 16.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = settings.apiKey,
            onValueChange = { settings.apiKey = it.trim() },
            singleLine = true,
            placeholder = { Text("Paste your key here (it starts with AIza)") },
            visualTransformation =
                if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { links.openUri("https://aistudio.google.com/apikey") }) {
            Text("Get a free key")
        }
        OutlinedButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val pasted = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!pasted.isNullOrBlank()) {
                settings.apiKey = pasted
                testResult = ""
            }
        }) { Text("Paste") }
        Button(
            onClick = {
                testResult = "Testing…"
                testKey(settings.apiKey.ifBlank { BuildConfig.GEMINI_API_KEY }) { testResult = it }
            },
            enabled = gemini.hasKey()
        ) { Text("Test") }
        Text(testResult, color = if (testResult.startsWith("✓")) Accent else Color.Gray)
    }
    if (settings.apiKey.isBlank() && gemini.hasKey()) {
        Text(
            "No key entered, so this test build is using your key from local.properties. " +
                "Published versions won't have it.",
            color = Muted
        )
    }
}

// Live microphone loudness bar, with a white line at the "counts as talking" level
@Composable
fun LevelMeter(mic: Microphone, threshold: Float) {
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            level = mic.level
            delay(50)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val scale = 0.5f  // the bar's full width = loudness 0.5
        drawRect(Color(0xFF2A2A33))
        drawRect(
            if (level > threshold) Accent else Color.Gray,
            size = Size(size.width * (level / scale).coerceIn(0f, 1f), size.height)
        )
        drawRect(
            Color.White,
            topLeft = Offset(size.width * (threshold / scale) - 2f, 0f),
            size = Size(4f, size.height)
        )
    }
}

// A setting with a title, a short explanation, and an on/off switch on the right
@Composable
fun SwitchSetting(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(end = 16.dp)) { SettingTitle(title, help) }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(top = 18.dp))
    }
}

@Composable
fun EyeSettings(settings: Settings) {
    val context = LocalContext.current
    var shareResult by remember { mutableStateOf("") }
    SettingTitle("Style", "How the eyes look. The preview shows your changes live.")
    ChoiceRow {
        Choice("Holographic", settings.holographic) { settings.holographic = true }
        Choice("Classic", !settings.holographic) { settings.holographic = false }
        TextButton(onClick = { settings.resetEyeLook() }) { Text("Reset all") }
    }
    // Share a look with other Orbit owners (or try theirs): it's just a line of text
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Orbit eyes", settings.eyeLookCode()))
            shareResult = "Copied! Paste it anywhere to share your look."
        }) { Text("Copy my look") }
        OutlinedButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val pasted = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            shareResult = if (settings.applyEyeLookCode(pasted)) "✓ New look applied" else
                "That isn't an Orbit look (it starts with ORBIT-EYES:)"
        }) { Text("Paste a look") }
    }
    if (shareResult.isNotEmpty()) Text(shareResult, color = Accent, fontSize = 14.sp)

    SettingTitle("Eye size", "")
    Slider(value = settings.eyeSize, onValueChange = { settings.eyeSize = it }, valueRange = 0.6f..1.3f)
    SettingTitle("Ring thickness", "")
    Slider(
        value = settings.ringThickness,
        onValueChange = { settings.ringThickness = it },
        valueRange = 0.4f..2f
    )

    SwitchSetting(
        "Scan lines", "Very fine lines like an old CRT screen. You'll mostly notice them up close.",
        settings.scanLines
    ) { settings.scanLines = it }
    if (settings.scanLines) {
        SettingTitle(
            "Number of lines: ${settings.scanCount.toInt()}",
            "How many lines from the top of the screen to the bottom. Fewer lines are bigger."
        )
        Slider(
            value = settings.scanCount,
            onValueChange = { settings.scanCount = it },
            valueRange = 30f..400f
        )
        SettingTitle(
            "Line thickness",
            "How fat the dark lines are compared to the glow between them. " +
                "On big lines, the glow gets rounded edges."
        )
        Slider(
            value = settings.scanThickness,
            onValueChange = { settings.scanThickness = it },
            valueRange = 0.1f..0.8f
        )
    }

    if (!settings.holographic) return

    SwitchSetting(
        "Color fringes", "Cyan and magenta edges that drift around the ring.",
        settings.fringes
    ) { settings.fringes = it }
    if (settings.fringes) {
        SettingTitle("Fringe spread", "How far the colors sit from the white ring.")
        Slider(value = settings.fringeSize, onValueChange = { settings.fringeSize = it }, valueRange = 0.2f..3f)
        SettingTitle("Fringe movement", "How fast the colors drift. All the way left stops them.")
        Slider(value = settings.fringeSpeed, onValueChange = { settings.fringeSpeed = it }, valueRange = 0f..3f)
    }

    SwitchSetting("Trail", "Faint colored ghosts left behind when the eyes move.", settings.trail) {
        settings.trail = it
    }
    if (settings.trail) {
        SettingTitle("Trail length", "")
        Slider(
            value = settings.trailLength,
            onValueChange = { settings.trailLength = it },
            valueRange = 2f..15f
        )
    }

    SettingTitle("Glow", "Soft halo around the ring. All the way left turns it off.")
    Slider(value = settings.glow, onValueChange = { settings.glow = it }, valueRange = 0f..3f)

    SettingTitle("Rainbow sheen", "Pastel shimmer over the white. All the way left turns it off.")
    Slider(value = settings.sheen, onValueChange = { settings.sheen = it }, valueRange = 0f..1.6f)

    SwitchSetting("Bright edges", "Thin bright lines along the ring's edges.", settings.edges) {
        settings.edges = it
    }
    SwitchSetting("Glint", "A small light gliding around the rim.", settings.glint) {
        settings.glint = it
    }
}

// The eyes on their own (settings preview, welcome setup): they look about like real eyes:
// short glances, now and then a bigger look around or straight at you, landing with a tiny
// overshoot, and blinking every few seconds
@Composable
fun EyePreview(settings: Settings, modifier: Modifier) {
    val screenHeight = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    var time by remember { mutableFloatStateOf(0f) }
    var lookX by remember { mutableFloatStateOf(0f) }
    var lookY by remember { mutableFloatStateOf(0f) }
    var open by remember { mutableFloatStateOf(1f) }
    val trails = remember { mapOf(-1 to ArrayDeque<EyeShape>(), 1 to ArrayDeque<EyeShape>()) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        var last = start
        var targetX = 0f
        var targetY = 0f
        var speedX = 0f
        var speedY = 0f
        var nextLook = 600L
        var nextBlink = 1500L
        var blinkAt = -1000L
        while (true) {
            val now = withFrameNanos { it }
            val ms = (now - start) / 1_000_000L
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            last = now
            time = ms / 1000f
            if (ms >= nextLook) {
                val pick = Random.nextFloat()
                when {
                    pick < 0.3f -> { targetX = 0f; targetY = 0f }  // back to you
                    pick < 0.8f -> {  // a small glance nearby
                        targetX = (targetX + (Random.nextFloat() - 0.5f) * 0.6f).coerceIn(-0.8f, 0.8f)
                        targetY = (targetY + (Random.nextFloat() - 0.5f) * 0.4f).coerceIn(-0.5f, 0.5f)
                    }
                    else -> {  // a bigger look around, often with a blink, like real eyes
                        targetX = (Random.nextFloat() - 0.5f) * 1.8f
                        targetY = (Random.nextFloat() - 0.5f) * 1f
                        if (Random.nextBoolean()) blinkAt = ms
                    }
                }
                nextLook = ms + Random.nextLong(700, 2800)
            }
            // Springy follow: about 2.6 bounces a second, damped to a small overshoot
            speedX += (267f * (targetX - lookX) - 20f * speedX) * dt
            speedY += (267f * (targetY - lookY) - 20f * speedY) * dt
            lookX += speedX * dt
            lookY += speedY * dt
            if (ms >= nextBlink) {
                blinkAt = ms
                nextBlink = ms + Random.nextLong(2000, 6000)
            }
            val sinceBlink = ms - blinkAt
            open = when {
                sinceBlink < 90 -> 1f - sinceBlink / 90f
                sinceBlink < 230 -> (sinceBlink - 90) / 140f
                else -> 1f
            }.coerceIn(0.05f, 1f)
        }
    }
    Canvas(modifier.background(Color.Black)) {
        val unit = size.minDimension
        val eyeSize = unit * 0.32f * settings.eyeSize
        val ringWidth = unit * 0.32f * 0.15f * settings.ringThickness
        val turn = abs(lookX)
        for (side in listOf(-1, 1)) {
            // The far eye a little smaller; both narrower to the side, shorter up or down
            val sideScale = 1f - 0.12f * lookX * side
            val eye = EyeShape(
                x = size.width / 2f + lookX * size.width * 0.16f + side * eyeSize * (0.85f - 0.2f * turn),
                y = size.height / 2f + lookY * size.height * 0.16f,
                width = eyeSize * sideScale * (1f - 0.25f * turn),
                height = eyeSize * sideScale * (1f - 0.3f * abs(lookY)) * open
            )
            val trail = trails.getValue(side)
            drawEye(eye, trail, ringWidth, 1f, time, side, settings)
            trail.addLast(eye)
            while (trail.size > settings.trailLength.toInt()) trail.removeFirst()
        }
        // Same line spacing as the real eyes, which fill the whole screen height
        if (settings.scanLines) drawScanLines(screenHeight / settings.scanCount, settings.scanThickness)
    }
}

// Appliance mode: start by itself, stay on screen, come back after a problem.
// Also shown in the welcome setup.
@Composable
fun ApplianceSettings(settings: Settings) {
    val context = LocalContext.current
    val activity = context as? Activity
    // Android's "Display over other apps" permission, checked again when you come back from
    // Android's settings page
    var canOpenItself by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            canOpenItself = android.provider.Settings.canDrawOverlays(context)
            delay(1000)
        }
    }
    fun askToOpenItself() {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    SwitchSetting(
        "Start when the phone turns on",
        "Orbit opens by itself after the phone restarts. Android only allows this with " +
            "\"Display over other apps\", so it asks for that; it's used for nothing else.",
        settings.autoStart
    ) { on ->
        settings.autoStart = on
        if (on && !canOpenItself) askToOpenItself()
    }
    if (settings.autoStart && !canOpenItself) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Allow \"Display over other apps\" for Orbit, or it can't open by itself.",
                color = Accent, fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { askToOpenItself() }) { Text("Allow") }
        }
    }

    SwitchSetting(
        "Lock to Orbit",
        "Keeps Orbit on screen, so Home and Back can't leave it by accident (Android's " +
            "\"pin app\"). To leave, hold Back and Recents together, or follow Android's tip. " +
            "Samsung: first turn on Settings → Security and privacy → More security settings → Pin app.",
        settings.pinned
    ) { on ->
        settings.pinned = on
        try {
            if (on) activity?.startLockTask() else activity?.stopLockTask()
        } catch (e: Exception) {
            Log.w("Orbit", "Pinning failed", e)
        }
    }

    SwitchSetting(
        "Come back after a problem",
        "If Orbit ever crashes, it reopens by itself (unless it crashes again within a minute).",
        settings.comeBack
    ) { settings.comeBack = it }
}

@Composable
fun NeckSettings(settings: Settings, neck: Neck, gemini: GeminiLive, onFindScreen: () -> Unit) {
    // Live view of what the neck is doing, refreshed 10 times a second
    var status by remember { mutableStateOf(neck.link.status) }
    var pan by remember { mutableFloatStateOf(neck.pan) }
    var tilt by remember { mutableFloatStateOf(neck.tilt) }
    LaunchedEffect(Unit) {
        while (true) {
            status = neck.link.status
            pan = neck.pan
            tilt = neck.tilt
            delay(100)
        }
    }
    // Leaving this tab always lets go of "hold straight ahead" (and ends the voice test)
    DisposableEffect(Unit) {
        onDispose {
            neck.holdStill = false
            neck.voice.stop()
        }
    }
    var holding by remember { mutableStateOf(false) }

    val askBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            neck.link.start()
        } else {
            settings.neckOn = false
            status = "Bluetooth permission wasn't allowed, so the neck can't connect"
        }
    }

    SwitchSetting(
        "Neck",
        "Orbit can sit on a pan/tilt neck (two servos and an ESP32 board) that turns its head " +
            "toward you and acts out its moods. Turn this on once the neck is powered; the phone " +
            "finds it over Bluetooth by itself, no pairing needed. Even with this off, the moves " +
            "are worked out and shown below.",
        settings.neckOn
    ) { on ->
        settings.neckOn = on
        if (!on) {
            neck.link.stop()
        } else if (neck.link.hasPermission()) {
            neck.link.start()
        } else {
            askBluetooth.launch(NeckLink.permissions().toTypedArray())
        }
    }
    Text(status, color = Accent, fontSize = 16.sp)
    if (settings.neckOn) {
        OutlinedButton(onClick = { neck.link.forget() }) { Text("Find the neck again") }
        NeckUpdate(neck.link)
    }
    VoiceDirectionTest(neck.voice, settings)

    SettingTitle(
        "What the head is doing",
        "The dot is where it's pointing; the cross is straight ahead. Try the moves below."
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(240.dp).height(120.dp).background(Color.Black)) {
            // The box is the whole allowed range; the cross is straight ahead
            val range = settings.panRange
            val middleY = size.height * settings.tiltUp / (settings.tiltUp + settings.tiltDown)
            drawLine(Color.DarkGray, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height))
            drawLine(Color.DarkGray, Offset(0f, middleY), Offset(size.width, middleY))
            drawRect(Color.Gray, style = Stroke(2f))
            val x = size.width / 2f + pan / range * size.width / 2f
            val y = middleY + if (tilt >= 0f) {
                tilt / settings.tiltDown * (size.height - middleY)
            } else {
                tilt / settings.tiltUp * middleY
            }
            drawCircle(Accent, radius = 10f, center = Offset(x, y))
        }
        Spacer(Modifier.width(24.dp))
        Column {
            Text("Turn: ${pan.roundToInt()}°", color = Color.White, fontSize = 18.sp)
            Text(
                "Tilt: ${(-tilt).roundToInt()}° ${if (tilt > 0.5f) "(down)" else if (tilt < -0.5f) "(up)" else ""}",
                color = Color.White,
                fontSize = 18.sp
            )
        }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((label, move) in listOf(
            "Nod" to "nod", "Shake head" to "shake_head", "Happy" to "happy",
            "Sad" to "sad", "Curious" to "curious", "Look away" to "look_away"
        )) {
            OutlinedButton(onClick = { gemini.showExpression(move) }) { Text(label) }
        }
    }
    SettingTitle(
        "How closely it watches you",
        "Like a dog: when you're near it keeps your face centred, with small smooth moves; across " +
            "the room it keeps a looser eye on you. Closely re-centres at the smallest move."
    )
    ChoiceRow {
        Choice("Closely", settings.watchZone < 0.08f) { settings.watchZone = 0.06f }
        Choice("Natural", settings.watchZone in 0.08f..0.14f) { settings.watchZone = 0.10f }
        Choice("Loosely", settings.watchZone > 0.14f) { settings.watchZone = 0.18f }
    }
    SettingTitle(
        "Turn speed: ${"%.1f".format(settings.neckFollow)}",
        "How quickly the head turns when it does."
    )
    Slider(value = settings.neckFollow, onValueChange = { settings.neckFollow = it }, valueRange = 0.3f..2f)

    SettingTitle("Turn range: ±${settings.panRange.roundToInt()}°", "Furthest it turns either way.")
    Slider(value = settings.panRange, onValueChange = { settings.panRange = it }, valueRange = 15f..90f)
    SettingTitle("Look up: ${settings.tiltUp.roundToInt()}°", "Furthest it tilts back.")
    Slider(value = settings.tiltUp, onValueChange = { settings.tiltUp = it }, valueRange = 5f..40f)
    SettingTitle("Look down: ${settings.tiltDown.roundToInt()}°", "Furthest it bows forward.")
    Slider(value = settings.tiltDown, onValueChange = { settings.tiltDown = it }, valueRange = 5f..45f)

    SettingTitle(
        "Setting it up",
        "1) Tap Hold straight ahead. 2) Move the two sliders below until the phone faces " +
            "straight forward and level. 3) Tap Hold again to let go. If the head turns away " +
            "from you instead of toward you, switch on Flip turn (or Flip tilt)."
    )
    Choice(if (holding) "Holding straight ahead · tap to let go" else "Hold straight ahead", holding) {
        holding = !holding
        neck.holdStill = holding
    }
    SettingTitle("Straight ahead, turn servo: ${settings.panCenter.roundToInt()}°", "")
    Slider(value = settings.panCenter, onValueChange = { settings.panCenter = it }, valueRange = 90f..180f)
    SettingTitle("Straight ahead, tilt servo: ${settings.tiltCenter.roundToInt()}°", "")
    Slider(value = settings.tiltCenter, onValueChange = { settings.tiltCenter = it }, valueRange = 45f..135f)
    SwitchSetting("Flip turn", "Turn on if the head turns away from you.", settings.flipPan) {
        settings.flipPan = it
    }
    SwitchSetting("Flip tilt", "Turn on if the head tilts the wrong way.", settings.flipTilt) {
        settings.flipTilt = it
    }

    ScreenSpotSettings(settings, neck, gemini, onFindScreen)
}

// "Your screen": show Orbit where your computer screen is, so it can turn and look at it
@Composable
fun ScreenSpotSettings(settings: Settings, neck: Neck, gemini: GeminiLive, onFindScreen: () -> Unit) {
    var aiming by remember { mutableStateOf(false) }
    var aim by remember { mutableStateOf(settings.screenPan to settings.screenTilt) }
    var view by remember { mutableStateOf<ImageBitmap?>(null) }
    DisposableEffect(Unit) { onDispose { neck.manualAim = null } }

    SettingTitle(
        "Your screen",
        "So Orbit can turn and look at your screen when you ask \"what do you think of this?\" " +
            "(with its own camera; nothing to install on the computer). Tap Find my screen, or say " +
            "\"Orby, find my screen\": it looks around by itself (about half a minute), turns to the " +
            "screen, then asks you to fine-tune it by voice (\"a bit left\", \"up\", \"perfect\"). " +
            "Put it beside your screen, not in front of it."
    )
    Button(onClick = onFindScreen, enabled = settings.neckOn) { Text("Find my screen") }
    Text(
        if (settings.screenSpotSet) "Saved: turn ${settings.screenPan.toInt()}°, tilt ${(-settings.screenTilt).toInt()}°"
        else "Not set yet",
        color = Accent
    )
    if (!aiming) {
        OutlinedButton(onClick = {
            aiming = true
            aim = settings.screenPan to settings.screenTilt
            neck.manualAim = aim
        }) { Text("Or aim by hand") }
        return
    }
    fun nudge(dPan: Float, dTilt: Float) {
        aim = (aim.first + dPan).coerceIn(-settings.panRange, settings.panRange) to
            (aim.second + dTilt).coerceIn(-settings.tiltUp, settings.tiltDown)
        neck.manualAim = aim
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { nudge(-5f, 0f) }) { Text("◀") }
        OutlinedButton(onClick = { nudge(0f, -4f) }) { Text("▲") }
        OutlinedButton(onClick = { nudge(0f, 4f) }) { Text("▼") }
        OutlinedButton(onClick = { nudge(5f, 0f) }) { Text("▶") }
        Text("turn ${aim.first.toInt()}°, tilt ${(-aim.second).toInt()}°", color = Color.White)
    }
    Text(
        "Screen further round than the arrows go? Raise Turn range above. If ◀ turns the " +
            "wrong way, that's fine: just follow what the head does.",
        color = Muted, fontSize = 14.sp
    )
    ChoiceRow {
        OutlinedButton(onClick = {
            gemini.onLook?.invoke(true) { photo ->
                view = photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
            }
        }) { Text("Check the view") }
        Button(onClick = {
            settings.screenPan = aim.first
            settings.screenTilt = aim.second
            settings.screenSpotSet = true
            neck.manualAim = null
            aiming = false
        }) { Text("Save") }
        TextButton(onClick = {
            neck.manualAim = null
            aiming = false
        }) { Text("Cancel") }
    }
    view?.let {
        Image(
            it, contentDescription = "What Orbit's camera sees",
            modifier = Modifier.width(320.dp).height(180.dp)
        )
    }
}

@Composable
fun ComputerSettings(settings: Settings, desk: DeskLink) {
    val name = settings.companionName.ifBlank { "Orbit" }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var testResult by remember { mutableStateOf("") }

    SwitchSetting(
        "Look at my computer screen (when I ask)",
        "Ask \"what do you think of this?\" or \"can you help with this error?\" and $name turns " +
            "its head to your screen, takes one photo with its camera and talks about it. Nothing " +
            "to install on the computer. It never looks on its own. Needs the neck: show it where " +
            "your screen in Device → Neck (Your screen).",
        settings.screenLookOn
    ) { settings.screenLookOn = it }
    if (settings.screenLookOn) {
        Text(
            when {
                !settings.neckOn -> "Waiting for the neck (Device → Neck)."
                !settings.screenSpotSet -> "Next: say \"Orby, find my screen\" (or Device → Neck → Find my screen)."
                else -> "✓ Ready: it knows where your screen is."
            },
            color = Accent
        )
    }

    SwitchSetting(
        "Optional: sharper text with Orbit Desk",
        "For crisp text, or without a neck: run Orbit Desk, a small free program, on the " +
            "computer (it's in the desk folder on Orbit's GitHub page). $name then gets a real " +
            "screenshot instead of a photo. Same home network needed.",
        settings.deskOn
    ) { settings.deskOn = it }

    if (settings.screenLookOn && settings.deskOn) {
        SettingTitle(
            "Setting it up",
            "1) On the computer, run Orbit Desk (the desk folder on the Orbit GitHub page has it " +
                "and the steps). 2) Tap Find my computer. 3) Type the pairing code it shows. 4) Test."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                searching = true
                found = null
                desk.find {
                    found = it
                    searching = false
                }
            }, enabled = !searching) { Text(if (searching) "Looking…" else "Find my computer") }
            if (settings.deskName.isNotBlank()) Text("Paired: ${settings.deskName}", color = Color.White)
        }
        found?.let { list ->
            if (list.isEmpty()) {
                Text(
                    "None found. Is Orbit Desk running, and is the phone on the same Wi-Fi? " +
                        "You can also type the address it shows.",
                    color = Muted
                )
            }
            list.forEach { (computer, address) ->
                OutlinedButton(onClick = {
                    settings.deskAddress = address
                    settings.deskName = computer
                    found = null
                }) { Text("$computer  ($address)") }
            }
        }
        OutlinedTextField(
            value = settings.deskAddress,
            onValueChange = { settings.deskAddress = it.trim() },
            singleLine = true,
            label = { Text("Computer address") },
            placeholder = { Text("e.g. 192.168.1.23:$DESK_PORT") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = settings.deskCode,
                onValueChange = { settings.deskCode = it.filter { c -> c.isDigit() }.take(6) },
                singleLine = true,
                label = { Text("Pairing code") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                testResult = "Testing…"
                desk.test { testResult = it }
            }, enabled = settings.deskAddress.isNotBlank()) { Text("Test") }
        }
        if (testResult.isNotEmpty()) Text(testResult, color = Accent)

        SettingTitle("Where's the computer?", "$name glances that way when it looks at your screen.")
        ChoiceRow {
            Choice("On its left", settings.deskOnLeft) { settings.deskOnLeft = true }
            Choice("On its right", !settings.deskOnLeft) { settings.deskOnLeft = false }
        }
        SettingTitle(
            "Privacy",
            "With Orbit Desk, the picture goes from your computer to this phone over your home network, then to " +
                "Gemini, only when you ask. The computer lists every look, and you can pause it " +
                "there any time. Think twice on a work computer: your employer's rules apply."
        )
    }
}

// 0 -> "midnight", 7 -> "7 AM", 12 -> "noon", 22 -> "10 PM"
fun hourLabel(hour: Int) = when {
    hour == 0 -> "midnight"
    hour < 12 -> "$hour AM"
    hour == 12 -> "noon"
    else -> "${hour - 12} PM"
}

@Composable
fun AboutPage() {
    SettingTitle("Orbit", "An open-source AI desk companion. Your phone is its face.")
    Text("Made by Mike Warren (dinosaurmike) and contributors · MIT license", color = Muted)
    Text("github.com/dinosaurmike/Orbit", color = Accent)
    Text(
        "Version ${BuildConfig.VERSION_NAME}",
        color = Muted
    )

    SettingTitle("Gestures", "On the eyes screen:")
    Text("•  Tap: start or end a conversation", color = Color.White)
    Text("••  Double-tap: sleep or wake (it wakes by itself when it sees you come back)", color = Color.White)
    Text("—  Long-press: open these settings", color = Color.White)
    Text("Stroke across the eyes: it purrs", color = Color.White)
    Text("Hand gestures (if on): wave, thumbs up/down, ✌, 🤟, and a finger up for \"shh\"", color = Color.White)

    SettingTitle("Things you can say", "During a conversation:")
    Text("\"Goodnight\" or \"go to sleep\": sleep (microphone off; it wakes when you come back)", color = Color.White)
    Text("\"Privacy mode\": camera and microphone fully off until you double-tap", color = Color.White)
    Text("\"Bye\" or \"that's all\": stop listening", color = Color.White)
    Text("\"Louder\" / \"quieter\": change its volume", color = Color.White)
    Text("\"Remind me in 20 minutes to…\" / \"set a 5 minute timer\": it chimes and tells you", color = Color.White)
    Text("\"What's on my desk?\": it looks down (tilts its head if the neck is connected)", color = Color.White)
    Text("\"What do you think of this?\": it turns to your computer screen and looks", color = Color.White)
    Text("\"Find my screen\": it looks around for your computer screen (needs the neck)", color = Color.White)
    Text("\"Help me focus for 25 minutes\": calm, quiet company and a celebration at the end", color = Color.White)
    Text("\"This is Sarah\": it learns her face (with Know who's who on)", color = Color.White)
    Text("\"What did we do this week?\": a little recap", color = Color.White)
    Text("\"My birthday is…\": it'll celebrate", color = Color.White)
    Text("\"Hi Orby\" or \"Hey Orbit\": either name works", color = Color.White)
    Text("\"Don't say hi when I come back\": turn the greeting off (or back on)", color = Color.White)

    SettingTitle("Privacy", "What stays on the phone and what doesn't.")
    Text(
        "Asleep, it only checks for a face once a second, on the phone, to wake when you come " +
            "back; the microphone is off. Say \"privacy mode\" to switch the camera off too.",
        color = Color.White
    )
    Text("Face tracking happens entirely on this phone; camera images never leave it.", color = Color.White)
    Text(
        "Your voice is sent to Google's Gemini only during a conversation (after you tap).",
        color = Color.White
    )
    Text(
        "Photos are only taken when you ask it to look (or once a day when you arrive, if " +
            "you switch on \"Notice things\"), one at a time, and sent to Gemini.",
        color = Color.White
    )
    Text(
        "Your computer screen is only seen when you ask (with Orbit Desk set up), one picture " +
            "at a time; the computer lists every look and can pause sharing.",
        color = Color.White
    )
    Text(
        "Memory notes are stored on this phone and sent to Gemini at the start of each " +
            "conversation. See or wipe them on the Privacy page.",
        color = Color.White
    )
}

// The neck's firmware: which version it runs, and a one-tap update when the app has a newer one
@Composable
private fun NeckUpdate(link: NeckLink) {
    var firmware by remember { mutableStateOf(link.firmware) }
    var progress by remember { mutableFloatStateOf(0f) }
    var updating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var available by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            firmware = link.firmware
            progress = link.updateProgress
            updating = link.updating
            message = link.updateStatus
            available = link.updateAvailable()
            delay(200)
        }
    }
    if (!link.connected && message.isEmpty()) return
    val bundled = link.bundledVersion()
    Text(
        "Neck firmware: " + (if (firmware > 0) "version $firmware" else "checking…") +
            when {
                bundled == 0 -> ""
                bundled > firmware -> "  ·  version $bundled is ready to install"
                else -> "  ·  up to date"
            },
        color = Muted, fontSize = 14.sp
    )
    if (link.bundledNote.isNotEmpty()) Text(link.bundledNote, color = Muted, fontSize = 13.sp)
    if (firmware == 1 && bundled > 1) {
        Text(
            "This first version can't update over Bluetooth: flash version $bundled once with the USB " +
                "cable (neck/NECK_SETUP.md), then later updates come from here.",
            color = Muted, fontSize = 14.sp
        )
    }
    if (available) {
        Button(onClick = { link.update() }) { Text("Update the neck (about a minute)") }
    }
    if (updating) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
    if (message.isNotEmpty()) Text(message, color = Accent, fontSize = 14.sp)
}

// Test: can this phone tell which way a voice comes from? (Its two microphones, one at each end.)
@Composable
private fun VoiceDirectionTest(voice: VoiceDirection, settings: Settings) {
    var running by remember { mutableStateOf(false) }
    var direction by remember { mutableFloatStateOf(0f) }
    var fresh by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var stereo by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            running = voice.running
            direction = voice.direction
            fresh = System.currentTimeMillis() - voice.directionAt < 1500
            level = voice.level
            stereo = voice.stereo
            status = voice.status
            voice.flip = settings.voiceFlip
            delay(80)
        }
    }
    SettingTitle(
        "Turn toward voices (test)",
        "Can this phone tell which way a voice comes from? Start, then talk or clap from your left, " +
            "straight ahead and your right (an arm's length away or more). The dot should follow you. " +
            "The usual microphone (and the wake word) pauses while this runs."
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { if (running) voice.stop() else voice.start() }) { Text(if (running) "Stop" else "Start the test") }
        if (running) Text(status, color = Muted, fontSize = 14.sp)
    }
    if (running) {
        Text(
            when (stereo) {
                null -> "Checking for real stereo… make some sound"
                true -> "✓ Real stereo: the two sides hear differently"
                false -> "✗ Both sides are identical: this phone can't tell direction this way"
            },
            color = if (stereo == false) Color(0xFFFF8A80) else Accent, fontSize = 14.sp
        )
        // Left … right, with the dot where it thinks you are (bright when it just heard you)
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val y = size.height / 2f
            drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.4f), Offset(size.width / 2f, y - 8.dp.toPx()), Offset(size.width / 2f, y + 8.dp.toPx()), 2.dp.toPx())
            val x = size.width / 2f + direction * size.width / 2f * 0.95f
            drawCircle(Accent.copy(alpha = if (fresh) 1f else 0.3f), 9.dp.toPx(), Offset(x, y))
            drawRect(Accent.copy(alpha = 0.5f), Offset(0f, size.height - 3.dp.toPx()), Size(size.width * level, 3.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("your left", color = Muted, fontSize = 12.sp)
            Text("your right", color = Muted, fontSize = 12.sp)
        }
    }
    SwitchSetting("Swap left and right", "If the dot goes the opposite way from you.", settings.voiceFlip) {
        settings.voiceFlip = it
    }
}
