package com.example.autofarmbs

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/** Один палец: от (x1,y1) к (x2,y2), в реальных пикселях экрана. */
data class Touch(
    val x1: Double, val y1: Double,
    val x2: Double = x1, val y2: Double = y1,
    val start: Long = 0, val dur: Long = 40
)

class GameInputService : AccessibilityService() {
    companion object { @Volatile var inst: GameInputService? = null }

    override fun onServiceConnected() { inst = this }
    override fun onUnbind(intent: Intent?): Boolean { inst = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Несколько пальцев одновременно (джойстик + атака + супер). Новый жест отменяет текущий. */
    fun dispatch(touches: List<Touch>) {
        if (touches.isEmpty()) return
        val b = GestureDescription.Builder()
        for (t in touches.take(9)) {
            val p = Path().apply {
                moveTo(t.x1.toFloat(), t.y1.toFloat())
                if (t.x1 != t.x2 || t.y1 != t.y2) lineTo(t.x2.toFloat(), t.y2.toFloat())
            }
            b.addStroke(GestureDescription.StrokeDescription(p, t.start, t.dur.coerceAtLeast(1)))
        }
        dispatchGesture(b.build(), null, null)
    }
}
