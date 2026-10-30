package com.example.companion2

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------- Welcome: the first-time setup, a step at a time ----------------
//
// Shown once, after Orbit's very first wake-up. Every step can be skipped, and so can the
// whole thing ("Skip setup", top right); everything here is also in settings.

private class Step(val title: String, val text: String, val next: String = "Next")

@Composable
fun Welcome(settings: Settings, gemini: GeminiLive, onDone: () -> Unit) {
    val name = settings.companionName.ifBlank { "Orbit" }
    val steps = listOf(
        Step(
            "Hi, I'm $name.",
            "I'm a little companion for your desk. I'll keep an eye out for you, chat when you " +
                "want, and keep you company. Let's get me set up. It takes about a minute.",
            next = "Let's go"
        ),
        Step(
            "First, a key for my voice",
            "I talk using Google's Gemini, and keys are free. Tap Get a free key, sign in and " +
                "copy your key, then come back here and tap Paste."
        ),
        Step("What should I sound like?", "Choose my name and voice. Tap a voice to hear it."),
        Step(
            "And who are you?",
            "What should I call you? You can leave this; I'll ask when we first talk."
        ),
        Step(
            "Make me feel at home",
            "I work best as a little appliance: plugged in, sideways, always on. You can change " +
                "these any time in settings (Device)."
        ),
        Step(
            "All set!",
            "Tap my eyes to talk, or just say \"Hey Orby\". Double-tap to put me to sleep, and " +
                "press and hold for settings.",
            next = "Say hello"
        )
    )
    var step by remember { mutableStateOf(0) }

    MaterialTheme(colorScheme = SettingsColors) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF0E1620), Backdrop), radius = 1400f))
        ) {
            Row(Modifier.fillMaxSize().padding(24.dp)) {
                // Orbit itself, looking about, while it talks you through it
                EyePreview(settings, Modifier.weight(0.4f).fillMaxSize().clip(MaterialTheme.shapes.extraLarge))
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(0.6f).fillMaxSize()) {
                    // Progress dots, and the way out
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            steps.indices.forEach { i ->
                                val wide by animateDpAsState(if (i == step) 22.dp else 7.dp, tween(250), label = "dot")
                                Box(
                                    Modifier
                                        .height(7.dp)
                                        .width(wide)
                                        .clip(CircleShape)
                                        .background(if (i <= step) Accent else Hairline)
                                )
                            }
                        }
                        if (step < steps.lastIndex) {
                            TextButton(onClick = onDone) { Text("Skip setup", color = Muted, fontSize = 15.sp) }
                        }
                    }

                    AnimatedContent(
                        targetState = step,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        modifier = Modifier.weight(1f),
                        label = "step"
                    ) { shown ->
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                steps[shown].title, fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
                                color = Ink, letterSpacing = (-0.4).sp
                            )
                            Text(steps[shown].text, fontSize = 16.sp, color = Muted, lineHeight = 23.sp)
                            when (shown) {
                                1 -> KeySetup(settings, gemini)
                                2 -> {
                                    OutlinedTextField(
                                        value = settings.companionName,
                                        onValueChange = { settings.companionName = it },
                                        singleLine = true,
                                        label = { Text("Name") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    VoicePicker(settings, gemini)
                                }
                                3 -> OutlinedTextField(
                                    value = settings.userName,
                                    onValueChange = { settings.userName = it },
                                    singleLine = true,
                                    label = { Text("Your name") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                4 -> {
                                    ApplianceSettings(settings)
                                    Text(
                                        "Tip: turn on your phone's battery protection (Samsung: \"Protect " +
                                            "battery\") so it stays healthy while plugged in all day.",
                                        color = Muted, fontSize = 14.sp
                                    )
                                }
                                5 -> if (!gemini.hasKey()) {
                                    Text(
                                        "No key yet, so I can't talk. Add one any time: press and hold " +
                                            "my eyes → Conversation.",
                                        color = Accent, fontSize = 15.sp
                                    )
                                }
                            }
                        }
                    }

                    // Back and Next
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (step > 0) {
                            Text(
                                "Back", color = Muted, fontSize = 16.sp,
                                modifier = Modifier.clip(CircleShape).clickable { step-- }.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        val label = when {
                            step == 1 && !gemini.hasKey() -> "Later"
                            else -> steps[step].next
                        }
                        Button(
                            onClick = { if (step < steps.lastIndex) step++ else onDone() },
                            modifier = Modifier.height(54.dp).width(200.dp)
                        ) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}
