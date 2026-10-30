package com.example.companion2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Appliance mode: when the phone finishes turning on, open Orbit (if "Start when the phone
// turns on" is switched on). Android only lets an app open itself like this when it has the
// "Display over other apps" permission, which the setting asks for.
class BootStart : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!Settings(context).autoStart) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w("Orbit", "Couldn't open at start-up", e)
        }
    }
}
