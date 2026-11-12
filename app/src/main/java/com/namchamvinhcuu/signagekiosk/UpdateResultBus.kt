package com.namchamvinhcuu.signagekiosk

/**
 * Lets [UpdateResultReceiver] tell the live activity that an install result was stored, so the activity can lift its
 * install block and report the result. The receiver may also run in a fresh process with no activity: then nothing
 * listens and the stored result is reported at the next start. Main thread only (the receiver and the activity both
 * run there).
 */
object UpdateResultBus {
    @Volatile
    var listener: (() -> Unit)? = null

    fun notifyStored() {
        listener?.invoke()
    }
}
