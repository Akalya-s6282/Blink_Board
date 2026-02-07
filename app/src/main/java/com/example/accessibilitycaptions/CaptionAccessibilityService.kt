package com.example.accessibilitycaptions

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.os.bundleOf
import kotlin.math.min

class CaptionAccessibilityService : AccessibilityService() {

    private val tag = "CaptionAccessibility"
    private var windowManager: WindowManager? = null
    private var captionView: TextView? = null
    private var captionReceiver: BroadcastReceiver? = null
    private var commandReceiver: BroadcastReceiver? = null
    private var pendingAction: (() -> Unit)? = null
    private var suggestedAction: (() -> Unit)? = null

    // Action keywords
    private val ACTION_CLICK_COMMANDS = listOf("click", "press", "tap")
    private val ACTION_SHOW_ELEMENTS = "show elements"
    private val ACTION_TYPE = "type"
    private val ACTION_OPEN = "open"
    private val ACTION_SCROLL = "scroll"
    private val ACTION_GO_BACK = "go back"
    private val ACTION_CONFIRM_COMMANDS = listOf("confirm", "yes", "ok")
    private val ACTION_END_CALL = "end call"

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "Service connected. Setting up overlay and receivers.")
        setupCaptionOverlay()
        registerReceivers()
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
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor("#AA000000".toColorInt())
            setPadding(24, 24, 24, 24)
            text = "Accessibility Service Initialized"
        }
        try {
            windowManager?.addView(captionView, params)
        } catch (e: Exception) {
            Log.e(tag, "Overlay error", e)
        }
    }

    private fun registerReceivers() {
        Log.d(tag, "Attempting to register receivers.")
        captionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val text = intent?.getStringExtra("caption_text")
                Log.d(tag, "[SUCCESS] Caption broadcast received: '$text'")
                if (!text.isNullOrEmpty()) updateCaption(text, 5000)
            }
        }

        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val command = intent?.getStringExtra("command")
                Log.d(tag, "[SUCCESS] Command broadcast received: '$command'")
                if (!command.isNullOrEmpty()) processVoiceCommand(command.lowercase().trim())
            }
        }

        val captionFilter = IntentFilter("CAPTION_UPDATE")
        val commandFilter = IntentFilter("VOICE_COMMAND")

        ContextCompat.registerReceiver(this, captionReceiver, captionFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, commandReceiver, commandFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        Log.d(tag, "Receivers have been successfully registered.")
    }

    private fun updateCaption(text: String, duration: Long) {
        Log.d(tag, "Updating overlay with: '$text'")
        captionView?.post {
            captionView?.text = text
            captionView?.removeCallbacks(null)
            captionView?.postDelayed({ captionView?.text = "" }, duration)
        }
    }

    private fun processVoiceCommand(command: String) {
        Log.d(tag, "Processing command: '$command'")
        if (ACTION_CONFIRM_COMMANDS.any { command == it } && (suggestedAction != null || pendingAction != null)) {
            suggestedAction?.invoke()
            pendingAction?.invoke()
            suggestedAction = null
            pendingAction = null
            return
        }

        when {
            command == ACTION_SHOW_ELEMENTS -> handleShowElements()
            ACTION_CLICK_COMMANDS.any { command.startsWith(it) } -> {
                val clickCommand = ACTION_CLICK_COMMANDS.find { command.startsWith(it) }!!
                val target = command.substringAfter(clickCommand).trim()
                if (target.isNotEmpty()) handleInteraction(target)
            }
            command.startsWith(ACTION_TYPE) -> {
                val textToType = command.substringAfter(ACTION_TYPE).trim()
                if (textToType.isNotEmpty()) handleType(textToType)
            }
            command.startsWith(ACTION_OPEN) -> {
                val appName = command.substringAfter(ACTION_OPEN).trim()
                handleOpenCommand(appName)
            }
            command.startsWith(ACTION_SCROLL) -> {
                val direction = if (command.contains("up")) {
                    "up"
                } else if (command.contains("down")) {
                    "down"
                } else {
                    "down" // Default to down
                }
                handleScroll(direction)
            }
            command == ACTION_GO_BACK -> handleGoBack()
            command.contains(ACTION_END_CALL) -> performEndCall()
            else -> {
                suggestedAction = null // Clear suggestion if a new command is issued
                pendingAction = null
                Log.w(tag, "Unknown command: '$command'")
            }
        }
    }

    private fun handleShowElements() {
        val interactiveNodes = findInteractiveNodes(rootInActiveWindow)
        val elementTexts = interactiveNodes.mapNotNull { it.text ?: it.contentDescription }
        val displayText = if (elementTexts.isNotEmpty()) {
            "Interactive items: \n- ${elementTexts.joinToString("\n- ")}"
        } else {
            "No interactive items found."
        }
        updateCaption(displayText, 20000)
    }

    private fun handleOpenCommand(appName: String) {
        val intent = when (appName) {
            "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
            "chrome" -> packageManager.getLaunchIntentForPackage("com.android.chrome")
            "camera" -> Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            "notes" -> packageManager.getLaunchIntentForPackage("com.google.android.keep")
            "google" -> packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
            else -> null
        }
        intent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(it)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open $appName.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performEndCall() {
        val endButton = findNodeByTextOrDescription(rootInActiveWindow, "end")
        if (endButton != null) {
            pendingAction = { endButton.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            Toast.makeText(this, "Say 'confirm' to end call", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "End call button not found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleInteraction(target: String) {
        val interactiveNodes = findInteractiveNodes(rootInActiveWindow)
        val bestMatch = findBestMatch(target, interactiveNodes) { it.text ?: it.contentDescription }

        if (bestMatch != null) {
            val bestMatchLabel = (bestMatch.text ?: bestMatch.contentDescription).toString().lowercase()
            if (levenshtein(target, bestMatchLabel) < 3) { // Allow for small recognition errors
                bestMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val suggestionText = "Did you mean: '${bestMatch.text ?: bestMatch.contentDescription}'?"
                updateCaption(suggestionText, 10000)
                suggestedAction = { bestMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            }
        } else {
            Toast.makeText(this, "Element '$target' not found", Toast.LENGTH_SHORT).show()
            handleShowElements()
        }
    }

    private fun <T> findBestMatch(query: String, items: List<T>, labelExtractor: (T) -> CharSequence?): T? {
        if (items.isEmpty()) return null

        return items.minByOrNull { item ->
            val label = labelExtractor(item)?.toString() ?: ""
            levenshtein(query, label.lowercase())
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        val aLength = a.length
        val bLength = b.length
        val dp = Array(aLength + 1) { IntArray(bLength + 1) }

        for (i in 0..aLength) {
            for (j in 0..bLength) {
                when {
                    i == 0 -> dp[i][j] = j
                    j == 0 -> dp[i][j] = i
                    else -> {
                        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                        dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
                    }
                }
            }
        }
        return dp[aLength][bLength]
    }


    private fun handleType(textToType: String) {
        val inputNode = findFocusedNode(rootInActiveWindow) { it.isEditable }
        if (inputNode != null) {
            inputNode.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                bundleOf(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to textToType)
            )
        } else {
            Toast.makeText(this, "No text field selected.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleScroll(direction: String) {
        Log.d(tag, "Handling scroll command in direction: $direction")
        val scrollableNode = findScrollableNode(rootInActiveWindow)
        if (scrollableNode == null) {
            Log.e(tag, "Scroll failed: No scrollable node found on the current screen.")
            Toast.makeText(this, "Can't scroll here.", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(tag, "Found a scrollable node: ${scrollableNode.className}")
        val action = when (direction) {
            "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> {
                Log.w(tag, "Invalid scroll direction: '$direction'")
                null
            }
        }

        if (action != null) {
            if (scrollableNode.actionList.contains(AccessibilityNodeInfo.AccessibilityAction(action, null))) {
                val result = scrollableNode.performAction(action)
                Log.d(tag, "Scroll action performed. Success: $result")
            } else {
                Log.e(tag, "Scroll failed: The node does not support the action '$direction' ($action).")
                Toast.makeText(this, "Cannot scroll '$direction' here.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Can't scroll here.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleGoBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun findFocusedNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isFocused && predicate(node)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findInteractiveNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if ((node.isClickable || node.isCheckable) && (node.text != null || node.contentDescription != null)) {
                nodes.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return nodes
    }

    private fun findNodeByTextOrDescription(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)

        while (queue.isNotEmpty()) {
            val currentNode = queue.removeFirst()
            if (currentNode.text?.toString()?.contains(target, true) == true ||
                currentNode.contentDescription?.toString()?.contains(target, true) == true
            ) {
                return currentNode
            }
            for (i in 0 until currentNode.childCount) {
                currentNode.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)

        while (queue.isNotEmpty()) {
            val currentNode = queue.removeFirst()
            if (currentNode.isScrollable) {
                return currentNode
            }
            for (i in 0 until currentNode.childCount) {
                currentNode.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { Log.w(tag, "Service interrupted") }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "Service destroyed. Unregistering receivers.")
        try {
            captionReceiver?.let { unregisterReceiver(it) }
            commandReceiver?.let { unregisterReceiver(it) }
            if (captionView?.parent != null) {
                windowManager?.removeView(captionView)
            }
        } catch (e: IllegalArgumentException) {
            Log.w(tag, "Receiver was not registered or already unregistered.")
        } catch (e: Exception) {
            Log.e(tag, "Error during service cleanup", e)
        }
    }
}
