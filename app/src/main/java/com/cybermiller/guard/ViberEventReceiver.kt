package com.cybermiller.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives `com.viber.voip.liteapi.INCOMING` explicit broadcasts from the
 * custom Viber "api" build. The manifest declares
 * android:permission="com.viber.voip.permission.LITEAPI" (signature level), so
 * only packages signed with the same key as the Viber build can deliver here —
 * i.e. Viber itself.
 *
 * Works even when GuardService is not running: the broadcast wakes our process,
 * forwarding happens on a worker thread straight from the event payload.
 */
class ViberEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra("json") ?: return
        val appCtx = context.applicationContext
        Thread {
            try {
                ViberMonitor.handleEventJson(appCtx, json)
            } catch (t: Throwable) {
                android.util.Log.w("GuardViber", "receiver forward failed: ${t.message}")
            }
        }.start()
    }
}
