package com.blinkboard.accessibilitycaptions

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CaptionAccessibilityService : AccessibilityService() {

    private val tag = "CaptionAccessibility"
    private var windowManager: WindowManager? = null
    private var captionView: TextView? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var commandProcessor: CommandProcessor

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "Service connected. Initializing overlay and event listeners.")
        commandProcessor = CommandProcessor(this)
        setupCaptionOverlay()
        observeEvents()
        Toast.makeText(this, "Caption & Voice Command Service Active", Toast.LENGTH_SHORT).show()
    }

    private fun setupCaptionOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
        try {
            windowManager?.addView(captionView, params)
        } catch (e: Exception) {
            Log.e(tag, "Error attaching overlay view", e)
        }
    }

    private fun observeEvents() {
        serviceScope.launch {
            CaptionEventBus.events.collectLatest { event ->
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

    private fun updateCaption(text: String, durationMs: Long) {
        captionView?.post {
            if (text.isNotBlank()) {
                captionView?.text = text
                captionView?.visibility = View.VISIBLE
                captionView?.removeCallbacks(null)
                captionView?.postDelayed({
                    captionView?.text = ""
                    captionView?.visibility = View.GONE
                }, durationMs)
            } else {
                captionView?.visibility = View.GONE
            }
        }
    }

    private fun handleVoiceCommand(command: String) {
        serviceScope.launch {
            val root = rootInActiveWindow
            val result = commandProcessor.processCommand(command, root)
            when (result) {
                is CommandResult.UpdateCaption -> {
                    updateCaption(result.text, result.durationMs)
                }
                is CommandResult.PerformAction -> {
                    val performed = result.action(rootInActiveWindow)
                    if (!performed) {
                        updateCaption("⚠️ ${result.failureMessage}", 4000)
                    }
                }
                is CommandResult.GoBack -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    updateCaption("◀️ Navigated Back", 3000)
                }
                is CommandResult.ShowToast -> {
                    Toast.makeText(this@CaptionAccessibilityService, result.message, Toast.LENGTH_SHORT).show()
                }
                CommandResult.Handled -> {}
            }
        }
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
