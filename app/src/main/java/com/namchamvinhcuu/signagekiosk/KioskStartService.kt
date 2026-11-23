package com.namchamvinhcuu.signagekiosk

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Experiment (plan: boot start on vendor ROMs that reset the overlay appop, e.g. TCL): a service the SYSTEM binds
 * at boot is exempt from the Android 10+ background-activity-start block, unlike [BootReceiver]. It registers for
 * no events and reads no content (res/xml/kiosk_start_service.xml); it only opens [MainActivity], and only on a
 * connect shortly after device boot ([BootStartPolicy]): the system also reconnects it after any process death,
 * which must not undo an operator "exit" or race a restart / update.
 * Enabled per TV with `settings put secure enabled_accessibility_services <pkg>/.KioskStartService` (this REPLACES
 * the whole list: append to the existing value if other services must stay) and `settings put secure
 * accessibility_enabled 1`. Behaviour on TCL: cold boot start measured once; rebind after process death UNVERIFIED.
 */
class KioskStartService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        KLog.init(this)
        val uptimeMs = SystemClock.elapsedRealtime()
        val inFront = FrontWatch.visible
        if (!BootStartPolicy.shouldStart(uptimeMs, inFront)) {
            KLog.i("kiosk start service connected, not a boot start (uptime_ms=$uptimeMs, kiosk_in_front=$inFront)")
            return
        }
        KLog.i("kiosk start service connected shortly after boot, starting MainActivity (uptime_ms=$uptimeMs)")
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_START, "boot"),
            )
        } catch (e: RuntimeException) {
            KLog.e("kiosk start service: start of MainActivity failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
