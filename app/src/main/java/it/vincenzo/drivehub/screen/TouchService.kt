package it.vincenzo.drivehub.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import it.vincenzo.drivehub.core.Reports

/** Solo per lo specchio senza Shizuku: riproduce sul telefono i tocchi fatti sul display dell'auto */
class TouchService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Reports.log("Tocco specchio: servizio attivo")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    companion object {
        @Volatile var instance: TouchService? = null
    }
}
