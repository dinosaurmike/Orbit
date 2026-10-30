package com.example.companion2

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Today's events from the calendar already on this phone (whatever accounts it syncs with),
// read on the phone with your permission. Used for the morning rundown, so Orbit knows your
// day in conversation, and for a heads-up ring before each event.
class DayCalendar(private val context: Context, private val settings: Settings) {
    data class Event(val id: Long, val title: String, val start: Long, val end: Long, val allDay: Boolean)

    fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    // Today's events (all-day ones first), or nothing if it's off or not allowed
    fun today(): List<Event> {
        if (!settings.calendarOn || !allowed()) return emptyList()
        val zone = ZoneId.systemDefault()
        val from = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val to = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val events = mutableListOf<Event>()
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY
                ),
                null, null, CalendarContract.Instances.BEGIN
            )?.use { c ->
                while (c.moveToNext()) {
                    events.add(
                        Event(
                            id = c.getLong(0),
                            title = c.getString(1)?.trim().orEmpty().ifBlank { "(no title)" },
                            start = c.getLong(2),
                            end = c.getLong(3),
                            allDay = c.getInt(4) == 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w("Orbit", "Couldn't read the calendar", e)
        }
        return events.sortedWith(compareBy({ !it.allDay }, { it.start }))
    }

    // Today's events as short lines for Orbit, e.g. "2:00 PM–3:00 PM: Dentist"
    fun describeToday(): List<String> {
        val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val zone = ZoneId.systemDefault()
        return today().map { e ->
            if (e.allDay) "All day: ${e.title}"
            else "${clock.format(Instant.ofEpochMilli(e.start).atZone(zone))}–" +
                "${clock.format(Instant.ofEpochMilli(e.end).atZone(zone))}: ${e.title}"
        }
    }
}
