package dev.phonecode.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The screen-navigation backend (round-3 feedback: "should be able to navigate screen as well").
 * The USER enables this explicitly in system Settings > Accessibility; until then [instance] is
 * null and the screen tool explains how to turn it on. Every agent action still passes the
 * per-action permission dialog before reaching this service.
 */
class NavAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** Serialize the visible view tree: interactive + textual nodes, depth-first, bounded. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        val out = StringBuilder()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (out.length > MAX_DUMP || depth > 18) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            val interactive = node.isClickable || node.isEditable || node.isCheckable || node.isScrollable
            if (text.isNotEmpty() || desc.isNotEmpty() || (interactive && id.isNotEmpty())) {
                val b = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                out.append("  ".repeat(depth.coerceAtMost(8)))
                out.append(node.className?.toString()?.substringAfterLast('.') ?: "View")
                if (id.isNotEmpty()) out.append(" #").append(id)
                if (text.isNotEmpty()) out.append(" \"").append(text.take(80)).append('"')
                if (desc.isNotEmpty() && desc != text) out.append(" [").append(desc.take(60)).append(']')
                if (node.isClickable) out.append(" (clickable)")
                if (node.isEditable) out.append(" (editable)")
                if (node.isChecked) out.append(" (checked)")
                out.append(" @").append(b.centerX()).append(',').append(b.centerY()).append('\n')
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return out.toString().ifBlank { "(screen has no readable content)" }
    }

    /** Click the first node matching [query] (text, content description, or view id). */
    fun tap(query: String): String {
        val root = rootInActiveWindow ?: return "no active window"
        val node = findNode(root, query) ?: return "nothing on screen matches \"$query\" - call screen_read first"
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            "tapped \"$query\""
        } else {
            // Fall back to a gesture at the node's center (covers non-clickable custom views).
            val b = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            if (gestureTap(b.centerX().toFloat(), b.centerY().toFloat())) "tapped \"$query\" (gesture)"
            else "found \"$query\" but couldn't click it"
        }
    }

    /** Type into the focused editable field (or the first editable on screen). */
    fun type(text: String): String {
        val root = rootInActiveWindow ?: return "no active window"
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: firstEditable(root)
            ?: return "no editable field on screen"
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "typed ${text.length} chars"
        else "field rejected the text"
    }

    fun swipe(direction: String): String {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val dx = dm.widthPixels * 0.3f
        val dy = dm.heightPixels * 0.3f
        val (from, to) = when (direction.lowercase()) {
            "up" -> (cx to cy + dy) to (cx to cy - dy)
            "down" -> (cx to cy - dy) to (cx to cy + dy)
            "left" -> (cx + dx to cy) to (cx - dx to cy)
            "right" -> (cx - dx to cy) to (cx + dx to cy)
            else -> return "swipe: up|down|left|right"
        }
        val path = Path().apply { moveTo(from.first, from.second); lineTo(to.first, to.second) }
        val done = CountDownLatch(1)
        var ok = false
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 260)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok = true; done.countDown() }
                override fun onCancelled(g: GestureDescription?) { done.countDown() }
            },
            null,
        )
        done.await(1, TimeUnit.SECONDS)
        return if (ok) "swiped $direction" else "swipe failed"
    }

    fun global(action: String): String {
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "unknown global action"
        }
        return if (performGlobalAction(id)) action else "$action failed"
    }

    private fun gestureTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val done = CountDownLatch(1)
        var ok = false
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok = true; done.countDown() }
                override fun onCancelled(g: GestureDescription?) { done.countDown() }
            },
            null,
        )
        done.await(1, TimeUnit.SECONDS)
        return ok
    }

    private fun findNode(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByText(query).firstOrNull()?.let { return it }
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val id = node.viewIdResourceName?.substringAfterLast('/')
            if (id != null && id.equals(query, ignoreCase = true)) return node
            if (node.contentDescription?.toString()?.contains(query, ignoreCase = true) == true) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { c -> walk(c)?.let { return it } }
            return null
        }
        return walk(root)
    }

    private fun firstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { c -> firstEditable(c)?.let { return it } }
        return null
    }

    companion object {
        @Volatile
        var instance: NavAccessibilityService? = null
            private set

        private const val MAX_DUMP = 12_000
    }
}
