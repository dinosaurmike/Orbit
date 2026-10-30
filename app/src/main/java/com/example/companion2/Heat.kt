package com.example.companion2

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

// Keeps an eye on how warm the phone is. It's plugged in all day with the camera and screen
// on, so if it gets warm Orbit rests (dimmer eyes, fewer face checks, no homework), and if it
// gets hot it goes to sleep until it has cooled down. Uses the battery temperature and, on
// Android 10 and later, Android's own overall "thermal status".
class HeatWatch(private val context: Context) {
    @Volatile var celsius = 0f       // battery temperature
        private set
    @Volatile var level = 0          // 0 = fine, 1 = warm (resting), 2 = hot (sleeping to cool)
        private set

    fun check() {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        celsius = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: 0
        } else 0
        val now = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE || celsius >= 46f -> 2
            thermal >= PowerManager.THERMAL_STATUS_MODERATE || celsius >= 42f -> 1
            else -> 0
        }
        // Gets warmer straight away, but only counts as cooled down once it's properly cool,
        // so it doesn't flick back and forth around the limit
        level = if (now >= level || (celsius <= 39f && thermal < PowerManager.THERMAL_STATUS_MODERATE)) {
            now
        } else level
    }

    fun describe() = "%.0f°C · %s".format(
        celsius,
        when (level) {
            0 -> "fine"
            1 -> "warm, resting (dimmer eyes, fewer face checks)"
            else -> "hot, sleeping until it cools down"
        }
    )
}
