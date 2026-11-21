package com.namchamvinhcuu.signagekiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * FrontWatch (field data from the sibling Youngmin kiosk): while MainActivity is stopped — the customer opened
 * another app, or the system killed the cached process — one inexact alarm ([FrontWatchPolicy.DELAY_MS]) fires
 * [FrontWatchReceiver], which brings the kiosk back. `onStop` arms, `onStart` cancels. The alarm survives a
 * process kill but not a force-stop, a reboot (BootReceiver covers that) or a crash while in front.
 *
 * The two flags are process-level because the receiver reads them; both are touched on the main thread only.
 */
object FrontWatch {
    /** True between MainActivity.onStart and onStop. An alarm already in flight finds it true and does nothing. */
    @Volatile var visible = false

    /** The [FrontStartMark] was taken for the start in progress; consumed by onStart, cleared by onStop. */
    @Volatile var takenForStart = false

    private const val REQUEST_CODE = 15

    fun arm(context: Context) {
        try {
            alarmManager(context).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + FrontWatchPolicy.DELAY_MS,
                pendingIntent(context),
            )
        } catch (e: RuntimeException) {
            KLog.e("front watch: arming the alarm failed", e)
        }
    }

    fun cancel(context: Context) {
        try {
            alarmManager(context).cancel(pendingIntent(context))
        } catch (e: RuntimeException) {
            KLog.e("front watch: cancelling the alarm failed", e)
        }
    }

    private fun alarmManager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Explicit, immutable; arm and cancel must build the identical one (same component + request code). */
    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, FrontWatchReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
