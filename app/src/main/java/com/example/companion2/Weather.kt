package com.example.companion2

import android.util.Log
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// Weather: in the eyes for a few seconds (a tint plus a little rain, snow, sun or starlight
// inside the rings), and in words when you ask ("what's the weather?", "will it rain
// tomorrow?"). Uses Open-Meteo (free, no key, no account). Only your town's name is sent, and
// only its rough position after that, never your phone's location.
class Weather(private val settings: Settings) {
    @Volatile var kind = ""        // clear, cloudy, fog, rain, snow, storm ("" = not known yet)
        private set
    @Volatile var isDay = true
        private set
    @Volatile var celsius = 0f
        private set
    @Volatile var shownAt = 0L     // when it last appeared in the eyes
        private set
    @Volatile var status = ""      // shown in settings
        private set
    @Volatile private var busy = false

    // The big "zoomed in" weather view (an icon in one eye, the temperature in the other),
    // shown while Orbit talks about it. viewAt = when it was asked for (0 = not showing).
    @Volatile var viewAt = 0L
        private set
    @Volatile var viewKind = ""
        private set
    @Volatile var viewDay = true
        private set
    @Volatile var viewNow = 0.0      // °C (big number; NaN for another day or the week: no "now")
        private set
    @Volatile var viewLabel = ""     // shown above the numbers for another day ("SATURDAY", "THIS WEEK")
        private set
    @Volatile var viewHigh = 0.0     // °C
        private set
    @Volatile var viewLow = 0.0      // °C
        private set
    private var lastAsked = ""        // what was asked last, and when, so it isn't said twice
    private var lastAskedAt = 0L

    // The view is done (Orbit finished talking about it)
    fun endView() {
        viewAt = 0L
    }
    private val client = OkHttpClient()

    // Fetches the current weather (on a background thread), then shows it in the eyes
    fun show() {
        if (busy) return
        busy = true
        status = "Checking the weather…"
        thread(name = "weather") {
            try {
                val now = forecast().getJSONObject("current")
                setLook(now.optInt("weather_code", 0), now.optInt("is_day", 1) == 1)
                celsius = now.optDouble("temperature_2m", 0.0).toFloat()
                shownAt = System.currentTimeMillis()
                status = "${describe(kind, isDay)}, ${temp(celsius.toDouble())}"
            } catch (e: Exception) {
                Log.w("Orbit", "Weather failed", e)
                status = "Couldn't get the weather: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    // How strongly to show it right now: fades in, holds for 10 seconds, fades out (0..1)
    fun strength(now: Long): Float {
        val t = (now - shownAt) / 1000f
        if (shownAt == 0L || t > 11f) return 0f
        return minOf(t / 0.8f, (11f - t) / 1.2f, 1f).coerceAtLeast(0f)
    }

    // ---- For the conversation ----

    // "Where are you?": finds the town, saves it, and says what was found (for Gemini to
    // confirm). Waits on the internet, so call from a background thread.
    fun setTown(town: String): String {
        val place = findPlace(town) ?: return "Couldn't find \"$town\". Ask them for the town and country or state."
        settings.weatherTown = town.trim()
        settings.weatherSpot = "${town.trim()}|${place.second}|${place.third}"
        return "Saved their location: ${place.first}. Use it for the weather from now on."
    }

    // The weather for Gemini to talk about, and the big weather view in the eyes. `wanted`: "now",
    // "today", "tomorrow", a day of the week ("saturday"), a date ("2026-11-01") or "week".
    // Whatever it says is exactly what the eyes show. Waits on the internet: call from a
    // background thread.
    fun report(wanted: String): String {
        if (settings.weatherTown.isBlank()) {
            return "You don't know where they are yet. Ask which town or city they're in, then call set_location."
        }
        // Asked the same thing again within half a minute (or called twice): don't say it twice
        val now = System.currentTimeMillis()
        val asked = wanted.trim().lowercase().let { if (it == "today" || it.isEmpty()) "now" else it }
        if (asked == lastAsked && now - lastAskedAt < 30_000) {
            return "You've just told them this. Don't repeat it; say nothing more about the weather."
        }
        val data = try {
            forecast()
        } catch (e: Exception) {
            Log.w("Orbit", "Weather report failed", e)
            return "Couldn't get the weather just now (${e.message}). Say so briefly, once."
        }
        lastAsked = asked
        lastAskedAt = now
        val current = data.getJSONObject("current")
        val hourly = data.getJSONObject("hourly")
        val daily = data.getJSONObject("daily")
        val unit = if (settings.tempUnit == "F") "°F" else "°C"
        celsius = current.optDouble("temperature_2m", 0.0).toFloat()
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val days = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val rain = daily.getJSONArray("precipitation_probability_max")
        // "Today" as the forecast sees it (the town's own date, not the phone's)
        val today = LocalDate.parse(days.getString(0))
        fun dayName(date: LocalDate) = when (date) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)
        }
        // The hottest and coldest hours among `hours`, with roughly when
        fun highLow(hours: List<Int>): Pair<Int, Int>? {
            if (hours.isEmpty()) return null
            return (hours.maxByOrNull { temps.optDouble(it) } ?: hours[0]) to (hours.minByOrNull { temps.optDouble(it) } ?: hours[0])
        }
        fun whenIs(i: Int, onDay: LocalDate?): String {
            val stamp = times.getString(i)
            val hour = stamp.substringAfter('T').substringBefore(':').toInt()
            val date = LocalDate.parse(stamp.substringBefore('T'))
            val day = if (onDay == null && date != today) " ${dayName(date)}" else ""
            return "around ${hourLabel(hour)}$day"
        }
        val text = StringBuilder("Weather in ${settings.weatherTown}, temperatures in $unit. ")

        // Which day was asked about (null = now and the next 24 hours, or the week)
        val day: LocalDate? = when (asked) {
            "now", "week" -> null
            "tomorrow" -> today.plusDays(1)
            "weekend", "this weekend" -> (0L..6L).map { today.plusDays(it) }.first { it.dayOfWeek == java.time.DayOfWeek.SATURDAY }
            else -> runCatching { LocalDate.parse(asked) }.getOrNull()
                ?: java.time.DayOfWeek.values().firstOrNull { it.name.lowercase() == asked }?.let { weekday ->
                    (0L..6L).map { today.plusDays(it) }.first { it.dayOfWeek == weekday }
                }
        }?.takeIf { it != today }  // "friday" on a Friday = today
        val index = if (day == null) 0 else (0 until days.length()).firstOrNull { days.getString(it) == day.toString() }
        if (index == null) {
            return "The forecast only goes 7 days ahead, so you can't tell them about that day. Say so briefly."
        }

        when {
            asked == "week" -> {
                text.append("Now: ${temp(current.optDouble("temperature_2m"))}, ${describe(kindOf(current.optInt("weather_code")), current.optInt("is_day", 1) == 1).lowercase()}. Coming days: ")
                for (i in 0 until days.length()) {
                    text.append(
                        "${dayName(LocalDate.parse(days.getString(i)))} ${describe(kindOf(codes.optInt(i)), true).lowercase()} " +
                            "high ${temp(daily.getJSONArray("temperature_2m_max").optDouble(i))} low ${temp(daily.getJSONArray("temperature_2m_min").optDouble(i))}, " +
                            "rain chance ${rain.optInt(i)}%; "
                    )
                }
                text.append(
                    "Say it ONCE in two or three short sentences: the overall picture of the week and any day that stands out. " +
                        "Don't list every day. The week's high and low are showing in your eyes."
                )
                // The eyes: the week's most common weather, its highest high and lowest low
                val kinds = (0 until days.length()).map { kindOf(codes.optInt(it)) }
                viewKind = kinds.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "cloudy"
                viewDay = true
                viewLabel = "THIS WEEK"
                viewNow = Double.NaN
                viewHigh = (0 until days.length()).maxOf { daily.getJSONArray("temperature_2m_max").optDouble(it) }
                viewLow = (0 until days.length()).minOf { daily.getJSONArray("temperature_2m_min").optDouble(it) }
            }
            day == null -> {
                // Now, and the next 24 hours
                val nowTime = current.optString("time")
                val start = (0 until times.length()).firstOrNull { times.getString(it) >= nowTime } ?: 0
                val (hi, lo) = highLow((start until minOf(start + 24, times.length())).toList()) ?: (start to start)
                val nowKind = kindOf(current.optInt("weather_code"))
                val isDayNow = current.optInt("is_day", 1) == 1
                text.append(
                    "Now: ${temp(current.optDouble("temperature_2m"))} and ${describe(nowKind, isDayNow).lowercase()}, " +
                        "wind ${speed(current.optDouble("wind_speed_10m"))}. Next 24 hours: high ${temp(temps.optDouble(hi))} ${whenIs(hi, null)}, " +
                        "low ${temp(temps.optDouble(lo))} ${whenIs(lo, null)}, rain chance ${rain.optInt(0)}%. " +
                        "Say it ONCE, in one or two short sentences: how it is now, then the high and the low with roughly when. " +
                        "Don't repeat anything, don't list hours. The temperatures are showing in your eyes as you speak."
                )
                viewKind = nowKind
                viewDay = isDayNow
                viewLabel = ""
                viewNow = current.optDouble("temperature_2m")
                viewHigh = temps.optDouble(hi)
                viewLow = temps.optDouble(lo)
            }
            else -> {
                // One day ahead (tomorrow, a weekday, a date): that day only
                val name = dayName(day)
                val hours = (0 until times.length()).filter { times.getString(it).startsWith(day.toString()) }
                val (hi, lo) = highLow(hours) ?: return "No hourly forecast for $name. Say so briefly."
                val kind = kindOf(codes.optInt(index))
                text.append(
                    "${name.replaceFirstChar { it.uppercase() }} (${day}): ${describe(kind, true).lowercase()}, " +
                        "high ${temp(temps.optDouble(hi))} ${whenIs(hi, day)}, low ${temp(temps.optDouble(lo))} ${whenIs(lo, day)}, " +
                        "rain chance ${rain.optInt(index)}%. " +
                        "Say it ONCE, in one or two short sentences: $name's weather, then the high and the low with roughly when. " +
                        "Talk only about $name, not about today. Don't repeat anything. $name's weather is showing in your eyes."
                )
                viewKind = kind
                viewDay = true
                viewLabel = name.uppercase()
                viewNow = Double.NaN
                viewHigh = temps.optDouble(hi)
                viewLow = temps.optDouble(lo)
            }
        }
        kind = viewKind
        isDay = viewDay
        viewAt = System.currentTimeMillis()
        status = "${describe(kind, isDay)}, ${temp(celsius.toDouble())}"
        return text.toString()
    }

    // A temperature as a bare number with a degree sign, in the chosen units ("14°")
    fun deg(c: Double): String = if (settings.tempUnit == "F") "${(c * 9 / 5 + 32).roundToInt()}°" else "${c.roundToInt()}°"

    // ---- Behind the scenes ----

    private fun setLook(code: Int, day: Boolean) {
        kind = kindOf(code)
        isDay = day
    }

    // World Meteorological Organization weather codes → the looks the eyes can show
    private fun kindOf(code: Int) = when (code) {
        0, 1 -> "clear"
        2, 3 -> "cloudy"
        45, 48 -> "fog"
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 95..99 -> "storm"
        else -> "cloudy"
    }

    private fun describe(kind: String, day: Boolean) = when (kind) {
        "clear" -> if (day) "Sunny" else "Clear"
        "cloudy" -> "Cloudy"
        "fog" -> "Foggy"
        "rain" -> "Rain"
        "snow" -> "Snow"
        "storm" -> "Thunderstorms"
        else -> "Unknown"
    }

    // A temperature in the chosen units, e.g. "14°C" or "57°F"
    fun temp(c: Double): String =
        if (settings.tempUnit == "F") "${(c * 9 / 5 + 32).roundToInt()}°F" else "${c.roundToInt()}°C"

    private fun speed(kmh: Double): String =
        if (settings.tempUnit == "F") "${(kmh / 1.609).roundToInt()} mph" else "${kmh.roundToInt()} km/h"

    // Current weather plus hourly and daily forecasts, always fetched in °C (converted for showing)
    private fun forecast(): JSONObject {
        val spot = spot()
        return get(
            "https://api.open-meteo.com/v1/forecast?latitude=${spot.first}&longitude=${spot.second}" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m" +
                "&hourly=temperature_2m,weather_code,precipitation_probability" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=7"
        )
    }

    // Where the town is (looked up once, until the town changes)
    private fun spot(): Pair<String, String> {
        val town = settings.weatherTown.trim()
        if (town.isEmpty()) throw IllegalStateException("enter your town first")
        val saved = settings.weatherSpot.split('|')
        if (saved.size == 3 && saved[0] == town) return saved[1] to saved[2]
        val place = findPlace(town) ?: throw IllegalStateException("couldn't find \"$town\"")
        settings.weatherSpot = "$town|${place.second}|${place.third}"
        return place.second to place.third
    }

    // "Portland, Maine" → ("Portland, Maine, United States", latitude, longitude). Uses the
    // words after the comma to pick between places with the same name.
    private fun findPlace(town: String): Triple<String, String, String>? {
        val name = URLEncoder.encode(town.substringBefore(',').trim(), "UTF-8")
        val hint = town.substringAfter(',', "").trim().lowercase()
        val results = get("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=10")
            .optJSONArray("results") ?: JSONArray()
        val list = (0 until results.length()).map { results.getJSONObject(it) }
        val best = list.firstOrNull { r ->
            hint.isNotEmpty() && listOf("admin1", "country", "country_code").any {
                r.optString(it).lowercase().startsWith(hint)
            }
        } ?: list.firstOrNull() ?: return null
        val label = listOf(best.optString("name"), best.optString("admin1"), best.optString("country"))
            .filter { it.isNotBlank() }.joinToString(", ")
        return Triple(label, best.getDouble("latitude").toString(), best.getDouble("longitude").toString())
    }

    private fun get(url: String): JSONObject {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("error ${response.code}")
            return JSONObject(response.body?.string().orEmpty())
        }
    }
}
