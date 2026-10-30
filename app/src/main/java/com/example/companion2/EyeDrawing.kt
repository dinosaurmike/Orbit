package com.example.companion2

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// The size everything about the eyes is measured from: the screen height, but limited by the
// width so both eyes still fit if the screen is ever upright (Android 16 can ignore landscape
// on tablets and foldables)
fun DrawScope.eyeBase() = minOf(size.height, size.width * 0.76f)

// One eye's outline for a single frame: center, width and height. `sweep` is how much of the
// ring is drawn, in degrees, centered on the top: 360 = a full ring, about 160 = a happy arch.
data class EyeShape(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val sweep: Float = 360f
) {
    val whole get() = sweep >= 359.5f
    val startAngle get() = 270f - sweep / 2f  // 270 = straight up
}

// Draws one eye's ring. dx/dy shift it; grow makes it bigger (or smaller if negative).
fun DrawScope.ring(
    eye: EyeShape,
    color: Color,
    width: Float,
    alpha: Float,
    dx: Float = 0f,
    dy: Float = 0f,
    grow: Float = 0f,
    blend: BlendMode = BlendMode.Plus  // Plus adds light, like overlapping projectors
) {
    val w = max(eye.width + 2f * grow, 1f)
    val h = max(eye.height + 2f * grow, 1f)
    val topLeft = Offset(eye.x + dx - w / 2f, eye.y + dy - h / 2f)
    if (eye.whole) {
        drawOval(
            color = color,
            topLeft = topLeft,
            size = Size(w, h),
            alpha = alpha.coerceIn(0f, 1f),
            style = Stroke(width),
            blendMode = blend
        )
    } else {
        // Part of the ring (happy arch), with rounded ends
        drawArc(
            color = color,
            startAngle = eye.startAngle,
            sweepAngle = eye.sweep,
            useCenter = false,
            topLeft = topLeft,
            size = Size(w, h),
            alpha = alpha.coerceIn(0f, 1f),
            style = Stroke(width, cap = StrokeCap.Round),
            blendMode = blend
        )
    }
}

// Soft pastel rainbow for the iridescent sheen; position 0..1 goes around the colors once
private val SHEEN = listOf(
    Color(0xFFFFD1F4), Color(0xFFD1E8FF), Color(0xFFD6FFF0), Color(0xFFFFF4CF), Color(0xFFE4D4FF)
)

private fun sheenAt(position: Float): Color {
    val p = ((position % 1f) + 1f) % 1f * SHEEN.size
    val i = p.toInt() % SHEEN.size
    return lerp(SHEEN[i], SHEEN[(i + 1) % SHEEN.size], p - p.toInt())
}

// Draws one eye in the style chosen in settings
fun DrawScope.drawEye(
    eye: EyeShape,
    trail: List<EyeShape>,
    ringWidth: Float,
    brightness: Float,
    time: Float,
    side: Int,
    settings: Settings
) {
    if (settings.holographic) {
        drawHoloEye(eye, trail, ringWidth, brightness, time, side, settings)
    } else {
        // Classic: bright, nearly white cyan; dims when asleep
        ring(eye, Color(0xFFF2FFFF), ringWidth, brightness, blend = BlendMode.SrcOver)
    }
}

// Holographic eye: white ring with drifting cyan/magenta color fringes, a soft glow,
// a slowly turning rainbow sheen, bright edges, a gliding glint, and a short trail.
// Each effect can be switched off or adjusted on the Eyes tab in settings.
fun DrawScope.drawHoloEye(
    eye: EyeShape,
    trail: List<EyeShape>,
    ringWidth: Float,
    brightness: Float,
    time: Float,
    side: Int,
    look: Settings
) {
    val cyan = Color(0xFF00F0FF)
    val magenta = Color(0xFFFF2BD6)

    // 1. Trail: faint colored ghosts where the eye just was (only when it's moving)
    if (look.trail) {
        trail.forEachIndexed { i, old ->
            if (hypot(old.x - eye.x, old.y - eye.y) > ringWidth * 0.1f) {
                val fade = (i + 1f) / (trail.size + 1f)  // oldest is faintest
                val color = if (i % 2 == 0 || !look.fringes) cyan else magenta
                ring(old, color, ringWidth * 0.7f, 0.25f * fade * brightness)
            }
        }
    }

    // 2. Soft glow around the ring
    if (look.glow > 0f) {
        ring(eye, Color.White, ringWidth * 2.6f, 0.05f * look.glow * brightness)
        ring(eye, Color(0xFFCFFBFF), ringWidth * 1.7f, 0.09f * look.glow * brightness)
    }

    // 3. Chromatic aberration: cyan and magenta copies drift around the ring in opposite
    //    directions, and lag behind when the eye moves
    if (look.fringes) {
        val previous = trail.lastOrNull() ?: eye
        val maxLag = ringWidth * 0.5f
        val lagX = ((previous.x - eye.x) * 0.5f).coerceIn(-maxLag, maxLag)
        val lagY = ((previous.y - eye.y) * 0.5f).coerceIn(-maxLag, maxLag)
        val t = time * look.fringeSpeed
        val drift = ringWidth * look.fringeSize * (0.12f + 0.05f * sin(t * 2.3f + side))
        val angle = t * 1.6f + side * 1.1f
        val dx = cos(angle) * drift
        val dy = sin(angle * 0.7f) * drift
        ring(eye, cyan, ringWidth * 0.9f, 0.95f * brightness, dx + lagX, dy + lagY)
        ring(eye, magenta, ringWidth * 0.9f, 0.95f * brightness, -dx + lagX * 0.5f, -dy + lagY * 0.5f)
    }

    // 4. White core (full width when there are no color fringes around it)
    val core = if (look.fringes) ringWidth * 0.72f else ringWidth
    ring(eye, Color.White, core, brightness)

    // 5. Iridescent sheen: a pastel rainbow that slowly turns, tinting the white
    if (look.sheen > 0f) {
        val phase = time * 0.08f * side
        val stops = Array(13) { k -> k / 12f to sheenAt(k / 12f + phase) }
        drawArc(
            brush = Brush.sweepGradient(*stops, center = Offset(eye.x, eye.y)),
            startAngle = eye.startAngle,
            sweepAngle = eye.sweep,
            useCenter = false,
            topLeft = Offset(eye.x - eye.width / 2f, eye.y - eye.height / 2f),
            size = Size(eye.width, eye.height),
            alpha = (0.6f * look.sheen * brightness).coerceIn(0f, 1f),
            style = Stroke(core, cap = if (eye.whole) StrokeCap.Butt else StrokeCap.Round),
            blendMode = BlendMode.Multiply
        )
    }

    // 6. Luminous edges: thin bright lines along the outer and inner edge
    if (look.edges) {
        ring(eye, Color.White, ringWidth * 0.08f, 0.85f * brightness, grow = ringWidth * 0.44f)
        ring(eye, Color.White, ringWidth * 0.08f, 0.55f * brightness, grow = -ringWidth * 0.44f)
    }

    // 7. A small glint gliding around the outside of the ring (not on a happy arch)
    if (!look.glint || !eye.whole) return
    val glintW = eye.width + ringWidth
    val glintH = max(eye.height + ringWidth, 1f)
    drawArc(
        color = Color.White,
        startAngle = (time * 50f + side * 120f) % 360f,
        sweepAngle = 30f,
        useCenter = false,
        topLeft = Offset(eye.x - glintW / 2f, eye.y - glintH / 2f),
        size = Size(glintW, glintH),
        alpha = 0.7f * brightness,
        style = Stroke(ringWidth * 0.18f, cap = StrokeCap.Round),
        blendMode = BlendMode.Plus
    )
}

// CRT look: dark lines across the whole picture, `spacing` pixels apart. `thickness` is how
// much of each stripe is dark. The lit band between lines fades softly at its edges, like a
// CRT's glowing beam, which shows as rounded edges once the lines are fat. Drawn as one
// repeating gradient, so it costs almost nothing.
// The spacing is rounded to whole screen pixels: with a fraction (say 2.7 px) the stripes
// drift against the pixel grid and add up to a wide, faint dark bar (a moiré band).
fun DrawScope.drawScanLines(spacing: Float, thickness: Float) {
    val step = Math.round(spacing).coerceAtLeast(2).toFloat()
    val dark = Color.Black.copy(alpha = 0.8f)
    val half = Color.Black.copy(alpha = 0.3f)
    val edge = thickness.coerceIn(0.05f, 0.9f) / 2f  // dark part on each side of the stripe
    val fade = minOf(0.12f, (1f - 2f * edge) / 3f)   // width of the soft, rounded edge
    drawRect(
        Brush.verticalGradient(
            0f to dark,
            edge to dark,
            edge + fade * 0.5f to half,
            edge + fade to Color.Transparent,
            1f - edge - fade to Color.Transparent,
            1f - edge - fade * 0.5f to half,
            1f - edge to dark,
            1f to dark,
            startY = 0f,
            endY = step,
            tileMode = TileMode.Repeated
        )
    )
}

// Countdown ring: a thin ring around the eye that drains as a timer or reminder runs down.
// `left` is how much time is left (1 = just set, 0 = due). The bright end moves back toward
// the top; in the last moments it pulses.
fun DrawScope.drawTimerRing(
    eye: EyeShape, ringWidth: Float, left: Float, time: Float, brightness: Float, color: Color = RING_COLOR
) {
    val space = ringWidth * 1.6f
    val w = eye.width + space * 2f
    val h = max(eye.height, eye.width * 0.85f) + space * 2f
    val topLeft = Offset(eye.x - w / 2f, eye.y - h / 2f)
    val line = ringWidth * 0.16f
    val pulse = if (left < 0.05f) 0.6f + 0.4f * sin(time * 10f) else 1f
    val alpha = brightness.coerceIn(0.4f, 1f) * pulse
    // The faint track of the whole time, then what's left of it, bright, with a soft glow
    drawOval(Color.White, topLeft, Size(w, h), alpha = 0.07f * alpha, style = Stroke(line))
    val sweep = 360f * left
    drawArc(
        color, -90f, sweep, false, topLeft, Size(w, h),
        alpha = 0.22f * alpha, style = Stroke(line * 3f, cap = StrokeCap.Round), blendMode = BlendMode.Plus
    )
    drawArc(
        lerp(color, Color.White, 0.5f), -90f, sweep, false, topLeft, Size(w, h),
        alpha = 0.85f * alpha, style = Stroke(line, cap = StrokeCap.Round), blendMode = BlendMode.Plus
    )
    // A small bright bead at the moving end
    val angle = Math.toRadians((-90f + sweep).toDouble())
    val bead = Offset(eye.x + w / 2f * cos(angle).toFloat(), eye.y + h / 2f * sin(angle).toFloat())
    drawCircle(Color.White, line * 1.3f, bead, alpha = 0.9f * alpha, blendMode = BlendMode.Plus)
}

// Music playing: four little bars dancing in the top corner, like a level meter. Each bar
// moves at its own pace; `beat` (0..1, from the beat it hears) gives them all a kick. The icon
// drifts a few pixels over the minutes so the same pixels aren't lit for hours (burn-in).
fun DrawScope.drawMusicPlaying(c: Offset, r: Float, time: Float, strength: Float, beat: Float, color: Color) {
    val drift = Offset(sin(time * 0.013f) * r * 0.3f, cos(time * 0.017f) * r * 0.3f)
    val bottom = c.y + drift.y + r
    val width = r * 0.32f
    for (k in 0 until 4) {
        val wave = 0.5f + 0.5f * sin(time * (5.1f + k * 1.7f) + k * 2.1f) * sin(time * (2.3f + k * 0.9f) + k)
        val level = (0.25f + 0.55f * wave + 0.35f * beat).coerceIn(0.15f, 1f)
        val x = c.x + drift.x + (k - 1.5f) * r * 0.55f
        val top = bottom - 2f * r * level
        drawLine(color, Offset(x, bottom), Offset(x, top), width * 2.2f, StrokeCap.Round, alpha = 0.18f * strength, blendMode = BlendMode.Plus)
        drawLine(color, Offset(x, bottom), Offset(x, top), width, StrokeCap.Round, alpha = 0.85f * strength, blendMode = BlendMode.Plus)
    }
}

// The colour each kind of weather gives the eyes
fun weatherTint(kind: String, isDay: Boolean): Color? = when (kind) {
    "clear" -> if (isDay) Color(0xFFFFC857) else Color(0xFFBFD4FF)
    "cloudy" -> Color(0xFFB9C3CE)
    "fog" -> Color(0xFFD5DADF)
    "rain" -> Color(0xFF5AA9FF)
    "snow" -> Color(0xFFDDEFFF)
    "storm" -> Color(0xFFA98BFF)
    else -> null
}

// A big animated weather icon (the zoomed-in weather view), drawn in colour as glowing lines
// with faint cyan and magenta fringes, like the eyes. c = centre, r = radius it fits in.
fun DrawScope.drawWeatherIcon(c: Offset, r: Float, kind: String, isDay: Boolean, time: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    val line = r * 0.075f
    val sunColor = Color(0xFFFFC23A)
    val moonColor = Color(0xFFE6EDFF)
    val starColor = Color(0xFFFFF0A8)
    val rainColor = Color(0xFF4FA8FF)
    val snowColor = Color(0xFFC4EAFF)
    val boltColor = Color(0xFFFFE34A)
    val cloudColor = when (kind) {
        "storm" -> Color(0xFFB3A6E0)
        "rain" -> Color(0xFFC6D6EA)
        "fog" -> Color(0xFFC9CFD8)
        else -> Color(0xFFEDF3FA)
    }
    // Draws a shape four times: a faint cyan and magenta copy just off it, a wide soft glow, then
    // the coloured line itself
    fun holo(core: Color, draw: DrawScope.(dx: Float, dy: Float, color: Color, a: Float, width: Float) -> Unit) {
        val drift = line * 0.45f
        draw(-drift, -drift * 0.5f, Color(0xFF00F0FF), 0.22f * alpha, line)
        draw(drift, drift * 0.5f, Color(0xFFFF2BD6), 0.18f * alpha, line)
        draw(0f, 0f, core, 0.25f * alpha, line * 2.6f)
        draw(0f, 0f, core, alpha, line * 0.85f)
    }
    fun strokePath(path: Path, core: Color) = holo(core) { dx, dy, color, a, w ->
        translate(dx, dy) {
            drawPath(path, color, alpha = a, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        }
    }
    // A cloud: three bumps on a flat base, drifting gently
    fun cloud(cx: Float, cy: Float, scale: Float): Path {
        val k = r * scale
        val bumps = Path().apply {
            addOval(Rect(Offset(cx - k * 0.3f, cy + k * 0.02f), k * 0.24f))
            addOval(Rect(Offset(cx + k * 0.02f, cy - k * 0.12f), k * 0.32f))
            addOval(Rect(Offset(cx + k * 0.34f, cy + k * 0.06f), k * 0.2f))
            addRoundRect(RoundRect(Rect(cx - k * 0.5f, cy + k * 0.02f, cx + k * 0.52f, cy + k * 0.26f), CornerRadius(k * 0.12f)))
        }
        return Path().apply { op(bumps, Path(), PathOperation.Union) }
    }
    val sway = sin(time * 0.7f) * r * 0.04f
    when (kind) {
        "clear" -> if (isDay) {
            // The sun: a warm glowing disc with rays slowly turning and breathing
            drawCircle(
                Brush.radialGradient(listOf(sunColor.copy(alpha = 0.45f * alpha), Color.Transparent), c, r * 0.42f),
                r * 0.42f, c, blendMode = BlendMode.Plus
            )
            holo(sunColor) { dx, dy, color, a, w ->
                drawCircle(color, r * 0.3f, Offset(c.x + dx, c.y + dy), alpha = a, style = Stroke(w), blendMode = BlendMode.Plus)
                for (k in 0 until 8) {
                    val angle = k * PI.toFloat() / 4f + time * 0.35f
                    val pulse = 0.5f + 0.5f * sin(time * 2.2f + k)
                    val from = r * 0.45f
                    val to = r * (0.62f + 0.08f * pulse)
                    drawLine(
                        color, Offset(c.x + dx + cos(angle) * from, c.y + dy + sin(angle) * from),
                        Offset(c.x + dx + cos(angle) * to, c.y + dy + sin(angle) * to),
                        w, StrokeCap.Round, alpha = a, blendMode = BlendMode.Plus
                    )
                }
            }
        } else {
            // A crescent moon and two twinkling stars
            val moon = Path().apply {
                op(
                    Path().apply { addOval(Rect(c, r * 0.38f)) },
                    Path().apply { addOval(Rect(Offset(c.x + r * 0.2f, c.y - r * 0.12f), r * 0.33f)) },
                    PathOperation.Difference
                )
            }
            strokePath(moon, moonColor)
            for (k in 0..1) {
                val p = Offset(c.x + r * (0.3f + 0.15f * k), c.y - r * (0.4f - 0.5f * k))
                val tw = 0.4f + 0.6f * abs(sin(time * 2f + k * 1.7f))
                holo(starColor) { dx, dy, color, a, w ->
                    val s = r * 0.06f * tw
                    drawLine(color, Offset(p.x + dx - s, p.y + dy), Offset(p.x + dx + s, p.y + dy), w * 0.7f, StrokeCap.Round, alpha = a * tw, blendMode = BlendMode.Plus)
                    drawLine(color, Offset(p.x + dx, p.y + dy - s), Offset(p.x + dx, p.y + dy + s), w * 0.7f, StrokeCap.Round, alpha = a * tw, blendMode = BlendMode.Plus)
                }
            }
        }
        "cloudy" -> {
            // A little sun peeking out behind the cloud in the day
            if (isDay) holo(sunColor) { dx, dy, color, a, w ->
                drawArc(
                    color, 160f, 200f, false, Offset(c.x - r * 0.05f + dx, c.y - r * 0.5f + dy), Size(r * 0.44f, r * 0.44f),
                    alpha = a * 0.8f, style = Stroke(w, cap = StrokeCap.Round), blendMode = BlendMode.Plus
                )
            }
            strokePath(cloud(c.x + sway, c.y - r * 0.05f, 1f), cloudColor)
        }
        "fog" -> holo(cloudColor) { dx, dy, color, a, w ->
            // Three soft wavy lines sliding past
            for (k in 0..2) {
                val y = c.y + (k - 1) * r * 0.22f
                val path = Path()
                val left = c.x - r * 0.5f
                for (i in 0..20) {
                    val x = left + i * r / 20f
                    val yy = y + sin(i * 0.6f + time * 1.5f + k) * r * 0.035f
                    if (i == 0) path.moveTo(x + dx, yy + dy) else path.lineTo(x + dx, yy + dy)
                }
                drawPath(path, color, alpha = a * (0.6f + 0.2f * k), style = Stroke(w, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
            }
        }
        else -> {  // rain, snow, storm: a cloud with something falling from it
            val cy = c.y - r * 0.2f
            strokePath(cloud(c.x + sway, cy, 0.95f), cloudColor)
            val top = cy + r * 0.32f
            val fall = r * 0.42f
            when (kind) {
                "snow" -> holo(snowColor) { dx, dy, color, a, w ->
                    for (k in 0 until 6) {
                        val f = frac(time * 0.35f + k / 6f)
                        val x = c.x + (k - 2.5f) * r * 0.15f + sin(time * 1.6f + k) * r * 0.04f
                        val y = top + f * fall
                        drawCircle(color, w * 0.9f, Offset(x + dx, y + dy), alpha = a * (1f - f), blendMode = BlendMode.Plus)
                    }
                }
                else -> holo(rainColor) { dx, dy, color, a, w ->
                    for (k in 0 until 5) {
                        val f = frac(time * 0.9f + k / 5f)
                        val x = c.x + (k - 2f) * r * 0.17f
                        val y = top + f * fall
                        drawLine(
                            color, Offset(x + dx, y + dy), Offset(x + dx - r * 0.03f, y + dy + r * 0.1f),
                            w * 0.8f, StrokeCap.Round, alpha = a * (1f - f * 0.7f), blendMode = BlendMode.Plus
                        )
                    }
                }
            }
            if (kind == "storm") {
                // A lightning bolt that flashes every couple of seconds
                val flash = frac(time * 0.45f)
                val bright = if (flash < 0.12f) 1f else 0.25f
                val bolt = Path().apply {
                    moveTo(c.x + r * 0.05f, top - r * 0.02f)
                    lineTo(c.x - r * 0.08f, top + r * 0.2f)
                    lineTo(c.x + r * 0.04f, top + r * 0.2f)
                    lineTo(c.x - r * 0.1f, top + r * 0.44f)
                }
                holo(boltColor) { dx, dy, color, a, w ->
                    translate(dx, dy) {
                        drawPath(bolt, color, alpha = a * bright, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
                    }
                }
            }
        }
    }
}

// Today's weather in the eye: the ring takes on its color, with a little weather inside it.
// `strength` fades it in and out (0..1).
fun DrawScope.drawWeather(
    eye: EyeShape, ringWidth: Float, kind: String, isDay: Boolean, strength: Float, time: Float, side: Int
) {
    val tint = weatherTint(kind, isDay) ?: return
    // Color the white ring, and give it a glow of that color
    ring(eye, tint, ringWidth * 1.05f, 0.9f * strength, blend = BlendMode.Multiply)
    ring(eye, tint, ringWidth * 2.4f, 0.16f * strength)
    // A thunderstorm flashes now and then (with a bolt inside, below)
    val flash = kind == "storm" && frac(time * 0.23f + side * 0.1f) < 0.03f
    if (flash) ring(eye, Color.White, ringWidth * 3f, 0.35f * strength)
    // Sunshine: soft rays turning slowly around the outside of the ring
    if (kind == "clear" && isDay) {
        val rays = 10
        for (k in 0 until rays) {
            val angle = (k * 2f * PI.toFloat() / rays) + time * 0.25f * side
            val pulse = 0.6f + 0.4f * sin(time * 2f + k)
            val reach = ringWidth * (1.2f + 0.5f * pulse)
            val ax = eye.width / 2f + ringWidth * 0.9f
            val ay = eye.height / 2f + ringWidth * 0.9f
            val from = Offset(eye.x + cos(angle) * ax, eye.y + sin(angle) * ay)
            val to = Offset(eye.x + cos(angle) * (ax + reach), eye.y + sin(angle) * (ay + reach))
            drawLine(
                tint, from, to, strokeWidth = ringWidth * 0.18f, alpha = 0.55f * pulse * strength,
                cap = StrokeCap.Round, blendMode = BlendMode.Plus
            )
        }
    }

    // Inside the ring: rain, snow, starlight, sunshine or drifting cloud
    val inset = ringWidth * 0.6f
    val inner = Rect(
        eye.x - eye.width / 2f + inset, eye.y - eye.height / 2f + inset,
        eye.x + eye.width / 2f - inset, eye.y + eye.height / 2f - inset
    )
    if (inner.width <= 0f || inner.height <= 0f) return
    clipPath(Path().apply { addOval(inner) }) {
        // The bolt, during a flash
        if (flash) {
            val bolt = Path().apply {
                moveTo(inner.center.x + inner.width * 0.08f, inner.top)
                lineTo(inner.center.x - inner.width * 0.1f, inner.center.y + inner.height * 0.02f)
                lineTo(inner.center.x + inner.width * 0.04f, inner.center.y)
                lineTo(inner.center.x - inner.width * 0.12f, inner.bottom)
            }
            drawPath(bolt, Color.White, alpha = 0.9f * strength, style = Stroke(ringWidth * 0.16f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        }
        when (kind) {
            "rain", "storm" -> for (k in 0 until if (kind == "storm") 11 else 8) {
                val seed = k * 0.618f + side * 0.31f
                val x = inner.left + frac(seed * 7.13f) * inner.width
                val y = inner.top + frac(time * 0.9f + seed) * (inner.height + ringWidth) - ringWidth
                drawLine(
                    tint, Offset(x, y), Offset(x - ringWidth * 0.12f, y + ringWidth * 0.7f),
                    strokeWidth = ringWidth * 0.12f, alpha = 0.8f * strength,
                    cap = StrokeCap.Round, blendMode = BlendMode.Plus
                )
            }
            "snow" -> for (k in 0 until 10) {
                val seed = k * 0.618f + side * 0.27f
                val sway = sin(time * 1.3f + k) * ringWidth * 0.4f
                val x = inner.left + frac(seed * 5.31f) * inner.width + sway
                val y = inner.top + frac(time * 0.22f + seed) * inner.height
                drawCircle(tint, ringWidth * 0.13f, Offset(x, y), alpha = 0.9f * strength, blendMode = BlendMode.Plus)
            }
            "clear" -> if (isDay) {
                // A warm glow breathing at the center
                val r = inner.width * (0.35f + 0.05f * sin(time * 1.5f))
                drawCircle(
                    Brush.radialGradient(
                        listOf(tint.copy(alpha = 0.45f * strength), Color.Transparent),
                        center = inner.center, radius = r
                    ),
                    r, inner.center, blendMode = BlendMode.Plus
                )
            } else {
                // A few twinkling stars
                for (k in 0 until 6) {
                    val seed = k * 0.618f + side * 0.2f
                    val p = Offset(inner.left + frac(seed * 3.7f) * inner.width, inner.top + frac(seed * 9.1f) * inner.height)
                    val twinkle = 0.5f + 0.5f * sin(time * 2.5f + k * 1.7f)
                    drawCircle(tint, ringWidth * 0.09f, p, alpha = twinkle * strength, blendMode = BlendMode.Plus)
                }
            }
            else -> for (k in 0 until 2) {  // cloudy and fog: soft shapes drifting across
                val x = inner.left + frac(time * 0.05f + k * 0.5f + side * 0.2f) * (inner.width * 1.6f) - inner.width * 0.3f
                val c = Offset(x, inner.top + inner.height * (0.4f + 0.2f * k))
                val r = inner.width * 0.3f
                drawCircle(
                    Brush.radialGradient(listOf(tint.copy(alpha = 0.35f * strength), Color.Transparent), c, r),
                    r, c, blendMode = BlendMode.Plus
                )
            }
        }
    }
}

// The part after the decimal point (0..1), for repeating motions
private fun frac(x: Float) = x - kotlin.math.floor(x)

// Occasions in the eyes: confetti (birthday, new year), snow (Christmas), a pumpkin-orange
// glow (Halloween), soft pink with little rising hearts (Valentine's). `strength` fades it.
fun DrawScope.drawOccasion(eye: EyeShape, ringWidth: Float, kind: String, strength: Float, time: Float, side: Int) {
    when (kind) {
        "christmas" -> drawWeather(eye, ringWidth, "snow", true, strength, time, side)
        "halloween" -> {
            val orange = Color(0xFFFF9A3C)
            val flicker = 0.85f + 0.15f * sin(time * 9f + side) * sin(time * 3.7f)
            ring(eye, orange, ringWidth * 1.05f, 0.9f * strength, blend = BlendMode.Multiply)
            ring(eye, orange, ringWidth * 2.6f, 0.2f * strength * flicker)
        }
        "valentine" -> {
            val pink = Color(0xFFFF8FC8)
            ring(eye, pink, ringWidth * 1.05f, 0.8f * strength, blend = BlendMode.Multiply)
            ring(eye, pink, ringWidth * 2.2f, 0.14f * strength)
            for (k in 0 until 3) {  // little hearts floating up beside the eye
                val p = frac(time * 0.25f + k / 3f + side * 0.17f)
                val c = Offset(
                    eye.x + side * eye.width * 0.55f + sin(p * 6f + k) * ringWidth * 0.5f,
                    eye.y + eye.height * 0.3f - p * eye.height * 0.9f
                )
                drawHeart(c, ringWidth * (0.5f + 0.4f * p), pink, sin(p * PI_F) * strength)
            }
        }
        else -> {  // confetti for birthdays and new year
            val colors = listOf(
                Color(0xFFFF6B9A), Color(0xFFFFD166), Color(0xFF06D6A0), Color(0xFF4CC9F0), Color(0xFFB388FF)
            )
            val spread = eye.width * 0.9f
            for (k in 0 until 14) {
                val seed = k * 0.618f + side * 0.37f
                val x = eye.x + (frac(seed * 5.3f) - 0.5f) * spread * 2f + sin(time * 2f + k) * ringWidth * 0.3f
                val y = eye.y - eye.height * 0.9f + frac(time * 0.18f + seed) * eye.height * 1.8f
                val size = ringWidth * 0.22f
                rotate(time * 120f + k * 40f, pivot = Offset(x, y)) {
                    drawRect(
                        colors[k % colors.size], Offset(x - size, y - size / 2f), Size(size * 2f, size),
                        alpha = 0.85f * strength, blendMode = BlendMode.Plus
                    )
                }
            }
        }
    }
}

private const val PI_F = 3.14159265f

// A small soft heart centered at c
private fun DrawScope.drawHeart(c: Offset, size: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f) return
    val h = Path().apply {
        moveTo(c.x, c.y + size * 0.35f)
        cubicTo(c.x - size, c.y - size * 0.2f, c.x - size * 0.45f, c.y - size * 0.9f, c.x, c.y - size * 0.35f)
        cubicTo(c.x + size * 0.45f, c.y - size * 0.9f, c.x + size, c.y - size * 0.2f, c.x, c.y + size * 0.35f)
        close()
    }
    drawPath(h, color, alpha = alpha.coerceIn(0f, 1f), blendMode = BlendMode.Plus)
}

// State colors for the screen border (see Face): only for states where a color means something
val STATE_RED = Color(0xFFFF5A6A)     // not listening: privacy mode, or after a "shh"
val STATE_GREEN = Color(0xFF5CFF9D)   // a timer is running
val STATE_AMBER = Color(0xFFFFC857)   // a reminder or calendar event is coming up
val STATE_VIOLET = Color(0xFFB794FF)  // a focus session
val RING_COLOR = Color(0xFFBFEFFF)    // the countdown ring: the eyes' own soft cyan

// A soft glow around the edge of the whole screen in a state color: several rounded outlines,
// fading inward. `corner` matches the phone's rounded screen corners.
fun DrawScope.drawScreenBorder(color: Color, strength: Float, width: Float, corner: Float) {
    val steps = 8
    val step = width / steps
    for (k in 0 until steps) {
        val inset = step * (k + 0.5f)
        val fade = 1f - k / steps.toFloat()
        drawRoundRect(
            color, Offset(inset, inset), Size(size.width - inset * 2f, size.height - inset * 2f),
            CornerRadius((corner - inset).coerceAtLeast(0f)),
            alpha = (if (k == 0) 0.7f else 0.22f * fade * fade) * strength, style = Stroke(step)
        )
    }
}

// Music playing: the eye takes on the album art's colour (the white core is tinted, with a
// soft glow of the colour around it). `strength` fades it in and out (0..1).
fun DrawScope.drawTint(eye: EyeShape, ringWidth: Float, color: Color, strength: Float) {
    ring(eye, color, ringWidth * 1.05f, 0.7f * strength, blend = BlendMode.Multiply)
    ring(eye, color, ringWidth * 2.2f, 0.14f * strength)
}

// Waiting for Gemini: a small comet of light circling in the top corner, its tail fading
// behind it. `strength` fades it in and out (0..1).
fun DrawScope.drawThinking(c: Offset, r: Float, time: Float, strength: Float) {
    val box = Offset(c.x - r, c.y - r)
    val line = r * 0.24f
    // the faint track it runs round
    drawCircle(Color.White, r, c, alpha = 0.07f * strength, style = Stroke(line * 0.6f))
    val head = (time * 330f) % 360f
    val tail = 220f
    val pieces = 18
    for (k in 0 until pieces) {
        val f = (k + 1) / pieces.toFloat()  // 0 at the end of the tail, 1 at the head
        drawArc(
            RING_COLOR, head - tail + tail * k / pieces, tail / pieces + 1f, false, box, Size(r * 2f, r * 2f),
            alpha = strength * f * f * 0.9f, style = Stroke(line, cap = StrokeCap.Butt), blendMode = BlendMode.Plus
        )
    }
    val angle = Math.toRadians(head.toDouble())
    val dot = Offset(c.x + r * cos(angle).toFloat(), c.y + r * sin(angle).toFloat())
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f * strength), Color.Transparent), dot, line * 2.4f),
        line * 2.4f, dot, blendMode = BlendMode.Plus
    )
    drawCircle(Color.White, line * 0.7f, dot, alpha = strength, blendMode = BlendMode.Plus)
}

// A Wi-Fi symbol with a line through it (no internet), centered at c, about 2 × r wide
fun DrawScope.drawOfflineIcon(c: Offset, r: Float) {
    val gray = Color(0xFFB0B8C0)
    val line = r * 0.16f
    val base = Offset(c.x, c.y + r * 0.6f)
    for (k in 1..3) {  // three arcs fanning up from the base point
        val radius = r * 0.45f * k
        drawArc(
            gray, 225f, 90f, false,
            Offset(base.x - radius, base.y - radius), Size(radius * 2f, radius * 2f),
            alpha = 0.75f, style = Stroke(line, cap = StrokeCap.Round)
        )
    }
    drawCircle(gray, line * 0.9f, base, alpha = 0.75f)
    // the line through it
    drawLine(
        Color(0xFFFF5A6A), Offset(c.x - r, c.y - r * 0.8f), Offset(c.x + r, c.y + r * 0.9f),
        strokeWidth = line, alpha = 0.85f, cap = StrokeCap.Round
    )
}
