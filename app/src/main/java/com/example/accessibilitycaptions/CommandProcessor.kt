package com.example.accessibilitycaptions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

sealed class CommandResult {
    data class UpdateCaption(val text: String, val durationMs: Long = 5000) : CommandResult()
    data class PerformAction(val action: (root: AccessibilityNodeInfo?) -> Boolean) : CommandResult()
    data class ShowToast(val message: String) : CommandResult()
    object Handled : CommandResult()
}

class CommandProcessor(private val context: Context? = null) {

    private val clickCommands = listOf("click", "press", "tap")
    private val confirmCommands = listOf("confirm", "yes", "ok")

    private var pendingTargetText: String? = null
    private var pendingActionType: PendingActionType? = null

    enum class PendingActionType {
        CLICK_MATCH,
        END_CALL
    }

    suspend fun processCommand(
        command: String,
        rootNode: AccessibilityNodeInfo?
    ): CommandResult = withContext(Dispatchers.Default) {
        val trimmedCommand = command.lowercase().trim()

        if (confirmCommands.any { trimmedCommand == it }) {
            val target = pendingTargetText
            val actionType = pendingActionType
            pendingTargetText = null
            pendingActionType = null

            return@withContext when (actionType) {
                PendingActionType.CLICK_MATCH -> {
                    if (target != null && rootNode != null) {
                        val node = findMatchingNode(rootNode, target)
                        if (node != null) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            CommandResult.UpdateCaption("Confirmed: clicked '$target'")
                        } else {
                            CommandResult.ShowToast("Element '$target' is no longer visible.")
                        }
                    } else {
                        CommandResult.Handled
                    }
                }
                PendingActionType.END_CALL -> {
                    if (rootNode != null) {
                        val endButton = findNodeByTextOrDescription(rootNode, "end")
                        if (endButton != null) {
                            endButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            CommandResult.UpdateCaption("Ending call...")
                        } else {
                            CommandResult.ShowToast("End call button not found.")
                        }
                    } else {
                        CommandResult.Handled
                    }
                }
                null -> CommandResult.Handled
            }
        }

        // Reset pending target if new non-confirm command is issued
        pendingTargetText = null
        pendingActionType = null

        when {
            trimmedCommand == "show elements" -> {
                val interactiveNodes = findInteractiveNodes(rootNode)
                val elementTexts = interactiveNodes.mapNotNull { it.text ?: it.contentDescription }
                val displayText = if (elementTexts.isNotEmpty()) {
                    "Interactive items:\n- ${elementTexts.joinToString("\n- ")}"
                } else {
                    "No interactive items found."
                }
                CommandResult.UpdateCaption(displayText, 15000)
            }
            clickCommands.any { trimmedCommand.startsWith(it) } -> {
                val clickPrefix = clickCommands.find { trimmedCommand.startsWith(it) }!!
                val target = trimmedCommand.substringAfter(clickPrefix).trim()
                if (target.isNotEmpty()) {
                    handleInteraction(target, rootNode)
                } else {
                    CommandResult.ShowToast("Please specify what to click.")
                }
            }
            trimmedCommand.startsWith("type") -> {
                val textToType = trimmedCommand.substringAfter("type").trim()
                if (textToType.isNotEmpty()) {
                    CommandResult.PerformAction { root ->
                        val inputNode = findFocusedEditableNode(root)
                        if (inputNode != null) {
                            val arguments = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    textToType
                                )
                            }
                            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        } else {
                            false
                        }
                    }
                } else {
                    CommandResult.ShowToast("Please specify text to type.")
                }
            }
            trimmedCommand.startsWith("open") -> {
                val appName = trimmedCommand.substringAfter("open").trim()
                handleOpenCommand(appName)
            }
            trimmedCommand.startsWith("scroll") -> {
                val direction = if (trimmedCommand.contains("up")) "up" else "down"
                CommandResult.PerformAction { root ->
                    val scrollableNode = findScrollableNode(root)
                    if (scrollableNode != null) {
                        val action = if (direction == "up") {
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        } else {
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        }
                        scrollableNode.performAction(action)
                    } else {
                        false
                    }
                }
            }
            trimmedCommand == "go back" -> {
                CommandResult.PerformAction { false } // AccessibilityService performs GLOBAL_ACTION_BACK
            }
            trimmedCommand.contains("end call") -> {
                val endButton = findNodeByTextOrDescription(rootNode, "end")
                if (endButton != null) {
                    pendingTargetText = "end"
                    pendingActionType = PendingActionType.END_CALL
                    CommandResult.UpdateCaption("Say 'confirm' or 'yes' to end call", 8000)
                } else {
                    CommandResult.ShowToast("End call button not found.")
                }
            }
            else -> CommandResult.Handled
        }
    }

    private fun handleInteraction(target: String, rootNode: AccessibilityNodeInfo?): CommandResult {
        if (rootNode == null) return CommandResult.ShowToast("No active window found.")

        val interactiveNodes = findInteractiveNodes(rootNode)
        val bestMatch = findBestMatch(target, interactiveNodes) { it.text ?: it.contentDescription }

        return if (bestMatch != null) {
            val bestMatchLabel = (bestMatch.text ?: bestMatch.contentDescription).toString().lowercase()
            val distance = levenshtein(target, bestMatchLabel)
            if (distance < 3) {
                bestMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                CommandResult.UpdateCaption("Clicked '$bestMatchLabel'")
            } else {
                pendingTargetText = bestMatchLabel
                pendingActionType = PendingActionType.CLICK_MATCH
                CommandResult.UpdateCaption("Did you mean: '$bestMatchLabel'? Say 'confirm' to click.", 10000)
            }
        } else {
            CommandResult.ShowToast("Element '$target' not found.")
        }
    }

    private fun handleOpenCommand(appName: String): CommandResult {
        val ctx = context ?: return CommandResult.ShowToast("Context unavailable.")
        val intent = when (appName) {
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "chrome" -> ctx.packageManager.getLaunchIntentForPackage("com.android.chrome")
            "camera" -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            "notes" -> ctx.packageManager.getLaunchIntentForPackage("com.google.android.keep")
            "google" -> ctx.packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")
            else -> null
        }
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                CommandResult.UpdateCaption("Opening $appName...")
            } catch (e: Exception) {
                CommandResult.ShowToast("Could not open $appName.")
            }
        } else {
            CommandResult.ShowToast("App '$appName' not found.")
        }
    }

    fun findInteractiveNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
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

    fun findMatchingNode(root: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        val nodes = findInteractiveNodes(root)
        return findBestMatch(target, nodes) { it.text ?: it.contentDescription }
    }

    private fun <T> findBestMatch(query: String, items: List<T>, labelExtractor: (T) -> CharSequence?): T? {
        if (items.isEmpty()) return null
        return items.minByOrNull { item ->
            val label = labelExtractor(item)?.toString() ?: ""
            levenshtein(query, label.lowercase())
        }
    }

    fun levenshtein(a: String, b: String): Int {
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

    private fun findFocusedEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isFocused && node.isEditable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
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
}
