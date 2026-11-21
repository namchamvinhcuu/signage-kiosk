package com.namchamvinhcuu.signagekiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/**
 * Fired by the alarm [FrontWatch] armed in onStop. Re-arms (unless the kiosk is in front again) and starts
 * MainActivity only when the screen is on and the overlay appop is granted ([FrontWatchPolicy]); the same
 * SYSTEM_ALERT_WINDOW exemption BootReceiver relies on (`adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow`).
 * A skipped fire is logged locally only. Wakes a cold process too: MainActivity then labels `start=front_watch`
 * from [FrontStartMark], not from the stale launch intent a surviving task record replays.
 */
class FrontWatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        KLog.init(context)
        val visible = FrontWatch.visible
        if (FrontWatchPolicy.shouldRearm(visible)) FrontWatch.arm(context)
        val interactive = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val overlay = Settings.canDrawOverlays(context)
        if (!FrontWatchPolicy.shouldStart(interactive, overlay, visible)) {
            KLog.i("front watch fired: skipped (interactive=$interactive, overlay=$overlay, visible=$visible)")
            return
        }
        KLog.i("front watch fired: starting MainActivity")
        try {
            // No extras: onNewIntent applies nothing. The mark is set only after the start call returned, so a
            // failed start cannot leave a stale mark that would mislabel a later manual launch.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            FrontStartMark.set(SystemClock.elapsedRealtime())
        } catch (e: RuntimeException) {
            FrontStartMark.clear()
            KLog.e("front watch start of MainActivity failed", e)
        }
    }
}
