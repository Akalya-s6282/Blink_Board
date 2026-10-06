package com.blinkboard.accessibilitycaptions

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import com.blinkboard.accessibilitycaptions.ui.BadgedElement
import com.blinkboard.accessibilitycaptions.ui.NumberOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CaptionAccessibilityService : AccessibilityService() {

    private val tag = "CaptionAccessibility"
    private var windowManager: WindowManager? = null
    private var captionView: TextView? = null
    private var numberOverlayView: NumberOverlayView? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var commandProcessor: CommandProcessor
    private val commandMutex = Mutex()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "Service connected. Initializing overlay and event listeners.")
        commandProcessor = CommandProcessor(this)
        setupCaptionOverlay()
        observeEvents()
        Toast.makeText(this, "Tovact Voice Command Service Active", Toast.LENGTH_SHORT).show()
    }

    private fun setupCaptionOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Floating Caption Bar Parameters
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        captionView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor("#CC111318".toColorInt())
            setPadding(32, 24, 32, 32)
            visibility = View.GONE
        }

        // Full Screen Canvas Number Overlay Parameters
        val fullOverlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        numberOverlayView = NumberOverlayView(this)

        try {
            windowManager?.addView(numberOverlayView, fullOverlayParams)
            windowManager?.addView(captionView, params)
        } catch (e: Exception) {
            Log.e(tag, "Error attaching overlay views", e)
        }
    }

    private fun observeEvents() {
        serviceScope.launch {
            CaptionEventBus.events.collect { event ->
                when (event) {
                    is CaptionEvent.CaptionUpdate -> {
                        updateCaption(event.text, 5000)
                    }
                    is CaptionEvent.VoiceCommand -> {
                        handleVoiceCommand(event.command)
                    }
                }
            }
        }
    }

    private val dismissRunnable = Runnable {
        captionView?.text = ""
        captionView?.visibility = View.GONE
    }

    private fun updateCaption(text: String, durationMs: Long) {
        captionView?.post {
            captionView?.removeCallbacks(dismissRunnable)
            if (text.isNotBlank()) {
                captionView?.text = text
                captionView?.visibility = View.VISIBLE
                captionView?.postDelayed(dismissRunnable, durationMs)
            } else {
                captionView?.visibility = View.GONE
            }
        }
    }

    private fun triggerHapticFeedback(isSuccess: Boolean = true) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                val effect = if (isSuccess) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } else {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                }
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            Log.w(tag, "Haptic feedback unavailable", e)
        }
    }

    private fun handleVoiceCommand(command: String) {
        serviceScope.launch {
            try {
                commandMutex.withLock {
                    var root = rootInActiveWindow
                    if (root == null) {
                        delay(100L)
                        root = rootInActiveWindow
                    }
                    val result = commandProcessor.processCommand(command, root)
                    when (result) {
                        is CommandResult.ShowNumbers -> {
                            triggerHapticFeedback(true)
                            val interactiveNodes = commandProcessor.findInteractiveNodes(root)
                            if (interactiveNodes.isNotEmpty()) {
                                val displayMetrics = resources.displayMetrics
                                val badgedElements = mutableListOf<BadgedElement>()
                                val seenRects = mutableListOf<Rect>()

                                for (node in interactiveNodes) {
                                    val rect = Rect()
                                    node.getBoundsInScreen(rect)

                                    if (rect.isEmpty || rect.width() < 10 || rect.height() < 10) continue
                                    if (rect.bottom < 0 || rect.top > displayMetrics.heightPixels || rect.right < 0 || rect.left > displayMetrics.widthPixels) continue

                                    val isDuplicate = seenRects.any { existing ->
                                        Math.abs(existing.left - rect.left) < 25 &&
                                        Math.abs(existing.top - rect.top) < 25 &&
                                        Math.abs(existing.right - rect.right) < 25 &&
                                        Math.abs(existing.bottom - rect.bottom) < 25
                                    }

                                    if (!isDuplicate) {
                                        seenRects.add(rect)
                                        badgedElements.add(BadgedElement(number = badgedElements.size + 1, bounds = rect, node = node))
                                    }
                                }

                                numberOverlayView?.setBadgedElements(badgedElements)
                                updateCaption("🔢 Numbered badges displayed (${badgedElements.size} items). Say a number to click.", 10000)
                            } else {
                                updateCaption("⚠️ No interactive items found for badging.", 4000)
                            }
                        }
                        is CommandResult.ClickBadgeNumber -> {
                            val badgedElements = numberOverlayView?.getBadgedElements() ?: emptyList()
                            val targetItem = badgedElements.find { it.number == result.number }
                            if (targetItem != null) {
                                triggerHapticFeedback(true)
                                targetItem.node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                val label = commandProcessor.getNodeLabel(targetItem.node) ?: "item ${result.number}"
                                numberOverlayView?.clearBadges()
                                updateCaption("✅ Clicked [$label]", 3000)
                            } else {
                                // Fallback to node index match if badges were not currently drawn
                                val interactiveNodes = commandProcessor.findInteractiveNodes(root)
                                val targetIndex = result.number - 1
                                if (targetIndex in interactiveNodes.indices) {
                                    triggerHapticFeedback(true)
                                    val node = interactiveNodes[targetIndex]
                                    node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                                    val label = commandProcessor.getNodeLabel(node) ?: "item ${result.number}"
                                    numberOverlayView?.clearBadges()
                                    updateCaption("✅ Clicked [$label]", 3000)
                                } else {
                                    triggerHapticFeedback(false)
                                    updateCaption("❌ Badge #${result.number} not found on screen.", 4000)
                                }
                            }
                        }
                        is CommandResult.GoHome -> {
                            triggerHapticFeedback(true)
                            numberOverlayView?.clearBadges()
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            updateCaption("🏠 Navigated Home", 3000)
                        }
                        is CommandResult.OpenRecents -> {
                            triggerHapticFeedback(true)
                            numberOverlayView?.clearBadges()
                            performGlobalAction(GLOBAL_ACTION_RECENTS)
                            updateCaption("📱 Opened Recent Apps", 3000)
                        }
                        is CommandResult.OpenNotifications -> {
                            triggerHapticFeedback(true)
                            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                            updateCaption("🔔 Opened Notifications", 3000)
                        }
                        is CommandResult.OpenQuickSettings -> {
                            triggerHapticFeedback(true)
                            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                            updateCaption("⚙️ Opened Quick Settings", 3000)
                        }
                        is CommandResult.TakeScreenshot -> {
                            triggerHapticFeedback(true)
                            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                            updateCaption("📸 Took Screenshot", 3000)
                        }
                        is CommandResult.UpdateCaption -> {
                            triggerHapticFeedback(true)
                            updateCaption(result.text, result.durationMs)
                        }
                        is CommandResult.PerformAction -> {
                            val performed = result.action(rootInActiveWindow)
                            if (performed) {
                                triggerHapticFeedback(true)
                            } else {
                                val cleanCmd = command.lowercase().trim()
                                if (cleanCmd.contains("scroll") || cleanCmd.contains("swipe") || cleanCmd in listOf("down", "up")) {
                                    val direction = if (cleanCmd.contains("up")) "up" else "down"
                                    val gesturePerformed = performScrollGesture(direction)
                                    if (gesturePerformed) {
                                        triggerHapticFeedback(true)
                                        updateCaption("📜 Scrolled $direction", 2000)
                                    } else {
                                        triggerHapticFeedback(false)
                                        updateCaption("⚠️ ${result.failureMessage}", 4000)
                                    }
                                } else {
                                    triggerHapticFeedback(false)
                                    updateCaption("⚠️ ${result.failureMessage}", 4000)
                                }
                            }
                        }
                        is CommandResult.GoBack -> {
                            triggerHapticFeedback(true)
                            numberOverlayView?.clearBadges()
                            val success = performGlobalAction(GLOBAL_ACTION_BACK)
                            if (success) {
                                updateCaption("◀️ Navigated Back", 3000)
                            } else {
                                updateCaption("⚠️ Could not perform back navigation", 3000)
                            }
                        }
                        is CommandResult.ShowToast -> {
                            triggerHapticFeedback(false)
                            updateCaption("⚠️ ${result.message}", 4000)
                        }
                        CommandResult.Handled -> {}
                    }
                    delay(100L)
                }
            } catch (e: Exception) {
                Log.e(tag, "Error handling voice command '$command'", e)
                updateCaption("⚠️ Error executing command", 3000)
            }
        }
    }

    private fun performScrollGesture(direction: String): Boolean {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val startX = width / 2f
        val startY = if (direction == "down") height * 0.7f else height * 0.3f
        val endY = if (direction == "down") height * 0.3f else height * 0.7f

        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(startX, endY)
        }

        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(tag, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "Service destroyed. Cleaning up scope and views.")
        serviceScope.cancel()
        try {
            if (captionView?.parent != null) {
                windowManager?.removeView(captionView)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error removing caption view on destroy", e)
        }
    }
}
