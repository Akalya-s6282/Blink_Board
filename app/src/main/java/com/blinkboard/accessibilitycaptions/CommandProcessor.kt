package com.blinkboard.accessibilitycaptions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class CommandResult {
    data class UpdateCaption(val text: String, val durationMs: Long = 5000) : CommandResult()
    data class PerformAction(
        val failureMessage: String = "Action could not be performed.",
        val action: (root: AccessibilityNodeInfo?) -> Boolean
    ) : CommandResult()
    data class ShowToast(val message: String) : CommandResult()
    object GoBack : CommandResult()
    object GoHome : CommandResult()
    object OpenRecents : CommandResult()
    object OpenNotifications : CommandResult()
    object OpenQuickSettings : CommandResult()
    object TakeScreenshot : CommandResult()
    object ShowNumbers : CommandResult()
    data class ClickBadgeNumber(val number: Int) : CommandResult()
    object Handled : CommandResult()
}

class CommandProcessor(private val context: Context? = null) {

    private val clickCommands = listOf("click", "press", "tap")
    private val confirmCommands = listOf("confirm", "yes", "ok")

    private var pendingTargetText: String? = null
    private var pendingActionType: PendingActionType? = null
    private var pendingTimestamp: Long = 0L

    companion object {
        private const val CONFIRMATION_TIMEOUT_MS = 10_000L
        private val FILLER_WORDS = setOf(
            "click", "tap", "press", "on", "the", "a", "an", "button", "icon", "item", "option",
            "link", "field", "text", "to", "at", "please"
        )
    }

    enum class PendingActionType {
        CLICK_MATCH,
        END_CALL
    }

    suspend fun processCommand(
        command: String,
        rootNode: AccessibilityNodeInfo?
    ): CommandResult = withContext(Dispatchers.Default) {
        val trimmedCommand = command.lowercase().trim()

        // Check if input is a spoken number ("1", "one", "number 2", "click three")
        val spokenNumber = parseNumberFromSpeech(trimmedCommand)
        if (spokenNumber != null && (trimmedCommand.length <= 15 || trimmedCommand.startsWith("click") || trimmedCommand.startsWith("tap") || trimmedCommand.startsWith("number") || trimmedCommand.startsWith("badge"))) {
            return@withContext CommandResult.ClickBadgeNumber(spokenNumber)
        }

        if (confirmCommands.any { trimmedCommand == it }) {
            val target = pendingTargetText
            val actionType = pendingActionType
            val timestamp = pendingTimestamp
            pendingTargetText = null
            pendingActionType = null
            pendingTimestamp = 0L

            val currentTime = System.currentTimeMillis()
            if (timestamp > 0 && currentTime - timestamp > CONFIRMATION_TIMEOUT_MS) {
                return@withContext CommandResult.UpdateCaption("⚠️ Confirmation request expired.")
            }

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

        // Reset pending target only if a recognized new command is issued
        val isKnownCommand = trimmedCommand in listOf("show elements", "show numbers", "show badges", "home", "recents", "notifications", "quick settings", "screenshot") ||
                clickCommands.any { trimmedCommand.startsWith(it) } ||
                trimmedCommand.startsWith("type") ||
                trimmedCommand.startsWith("open") ||
                trimmedCommand.contains("scroll") ||
                trimmedCommand.contains("swipe") ||
                trimmedCommand.contains("back") ||
                trimmedCommand.contains("end call")

        if (isKnownCommand) {
            pendingTargetText = null
            pendingActionType = null
            pendingTimestamp = 0L
        }

        when {
            trimmedCommand in listOf("show numbers", "show badges", "numbers", "badges") -> {
                CommandResult.ShowNumbers
            }
            trimmedCommand in listOf("home", "go home", "home screen") -> {
                CommandResult.GoHome
            }
            trimmedCommand in listOf("recents", "recent apps", "show recents", "open recents") -> {
                CommandResult.OpenRecents
            }
            trimmedCommand in listOf("notifications", "open notifications", "show notifications") -> {
                CommandResult.OpenNotifications
            }
            trimmedCommand in listOf("quick settings", "open quick settings") -> {
                CommandResult.OpenQuickSettings
            }
            trimmedCommand in listOf("screenshot", "take screenshot", "capture screen") -> {
                CommandResult.TakeScreenshot
            }
            trimmedCommand in listOf("back", "go back", "previous") -> {
                CommandResult.GoBack
            }
            trimmedCommand in listOf("show elements", "elements") -> {
                val interactiveNodes = findInteractiveNodes(rootNode)
                val elementTexts = interactiveNodes.mapNotNull { getNodeLabel(it) }
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
                    CommandResult.PerformAction(
                        failureMessage = "No focused editable input field found. Tap an input field first."
                    ) { root ->
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
            trimmedCommand.contains("scroll") || trimmedCommand.contains("swipe") || trimmedCommand in listOf("down", "up", "page down", "page up") -> {
                val direction = if (trimmedCommand.contains("up") || trimmedCommand.contains("page up")) "up" else "down"
                CommandResult.PerformAction(
                    failureMessage = "No scrollable area found on screen."
                ) { root ->
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
            trimmedCommand.contains("end call") -> {
                val endButton = findNodeByTextOrDescription(rootNode, "end")
                if (endButton != null) {
                    pendingTargetText = "end"
                    pendingActionType = PendingActionType.END_CALL
                    pendingTimestamp = System.currentTimeMillis()
                    CommandResult.UpdateCaption("Say 'confirm' or 'yes' to end call", 8000)
                } else {
                    CommandResult.ShowToast("End call button not found.")
                }
            }
            else -> CommandResult.Handled
        }
    }

    private fun handleInteraction(target: String, rootNode: AccessibilityNodeInfo?): CommandResult {
        if (rootNode == null) return CommandResult.UpdateCaption("⚠️ No active window found.")

        val interactiveNodes = findInteractiveNodes(rootNode)
        if (interactiveNodes.isEmpty()) {
            return CommandResult.UpdateCaption("⚠️ No interactive elements found on screen.")
        }

        // Support Ordinal / Number Indexing ("click item 1", "click first button", "tap option 2")
        val index = parseTargetIndex(target)
        if (index != null && index in interactiveNodes.indices) {
            val selectedNode = interactiveNodes[index]
            val label = getNodeLabel(selectedNode) ?: "item ${index + 1}"
            selectedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return CommandResult.UpdateCaption("✅ Clicked '$label'")
        }

        val cleanTargetStr = cleanTarget(target)

        // Tier 1: Case-insensitive exact match
        val exactMatch = interactiveNodes.find { node ->
            val label = getNodeLabel(node)?.trim() ?: ""
            label.equals(target, ignoreCase = true) || label.equals(cleanTargetStr, ignoreCase = true)
        }
        if (exactMatch != null) {
            val label = getNodeLabel(exactMatch) ?: target
            exactMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return CommandResult.UpdateCaption("✅ Clicked '$label'")
        }

        // Tier 2: Case-insensitive substring match
        val substringMatches = interactiveNodes.filter { node ->
            val label = getNodeLabel(node)?.trim() ?: ""
            label.contains(target, ignoreCase = true) || (cleanTargetStr.isNotEmpty() && label.lowercase().contains(cleanTargetStr))
        }
        if (substringMatches.isNotEmpty()) {
            val bestSubstring = substringMatches.minByOrNull { getNodeLabel(it)?.length ?: Int.MAX_VALUE }!!
            val label = getNodeLabel(bestSubstring) ?: target
            bestSubstring.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return CommandResult.UpdateCaption("✅ Clicked '$label'")
        }

        // Tier 3: Allocation-free Levenshtein distance match
        val query = if (cleanTargetStr.isNotEmpty()) cleanTargetStr else target.lowercase().trim()
        val bestMatch = findBestMatch(query, interactiveNodes) { getNodeLabel(it) }
        if (bestMatch != null) {
            val bestMatchLabel = getNodeLabel(bestMatch) ?: ""
            val distance = levenshtein(query, bestMatchLabel.lowercase())
            
            // Fast execution for distance == 1 or high confidence on long targets
            if (distance <= 1 || (distance <= 2 && bestMatchLabel.length >= 5)) {
                bestMatch.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return CommandResult.UpdateCaption("✅ Clicked '$bestMatchLabel'")
            } else if (distance <= 2) {
                pendingTargetText = bestMatchLabel
                pendingActionType = PendingActionType.CLICK_MATCH
                pendingTimestamp = System.currentTimeMillis()
                return CommandResult.UpdateCaption("❓ Did you mean: '$bestMatchLabel'? Say 'confirm' to click.", 10000)
            }
        }

        // Tier 4: Target not found
        return CommandResult.UpdateCaption("❌ Element '$target' not found on screen.")
    }

    private fun handleOpenCommand(appName: String): CommandResult {
        if (appName.isEmpty()) return CommandResult.ShowToast("Please specify an app to open.")
        val ctx = context ?: return CommandResult.ShowToast("Context unavailable.")

        val cleanApp = appName.lowercase().trim()
        if (cleanApp == "settings") {
            return launchIntent(Intent(Settings.ACTION_SETTINGS), "Settings")
        } else if (cleanApp == "camera") {
            return launchIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "Camera")
        }

        val pm = ctx.packageManager

        // Fast package lookup for popular voice targets
        val knownPackages = mapOf(
            "google" to "com.google.android.googlequicksearchbox",
            "chrome" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps"
        )
        val knownPackage = knownPackages[cleanApp]
        if (knownPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(knownPackage)
            if (launchIntent != null) {
                return launchIntent(launchIntent, cleanApp.replaceFirstChar { it.uppercase() })
            }
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

        val matchedApp = resolveInfos.find { resolveInfo ->
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            label == cleanApp || label.contains(cleanApp)
        }

        if (matchedApp != null) {
            val packageName = matchedApp.activityInfo.packageName
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                val appLabel = matchedApp.loadLabel(pm).toString()
                return launchIntent(launchIntent, appLabel)
            }
        }

        return CommandResult.ShowToast("App '$appName' not found.")
    }

    private fun launchIntent(intent: Intent, label: String): CommandResult {
        val ctx = context ?: return CommandResult.ShowToast("Context unavailable.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            CommandResult.UpdateCaption("🚀 Opening $label...")
        } catch (e: Exception) {
            CommandResult.ShowToast("Could not open $label.")
        }
    }

    fun parseNumberFromSpeech(input: String): Int? {
        val clean = input.lowercase().trim()

        clean.toIntOrNull()?.let { if (it >= 1) return it }

        val match = Regex("(?:number|badge|item|option|click|tap|press|#)?\\s*(\\d+)(?:st|nd|rd|th)?").find(clean)
        if (match != null) {
            val num = match.groupValues[1].toIntOrNull()
            if (num != null && num >= 1) return num
        }

        val wordMap = mapOf(
            "one" to 1, "first" to 1, "1st" to 1,
            "two" to 2, "second" to 2, "2nd" to 2,
            "three" to 3, "third" to 3, "3rd" to 3,
            "four" to 4, "fourth" to 4, "4th" to 4,
            "five" to 5, "fifth" to 5, "5th" to 5,
            "six" to 6, "sixth" to 6, "6th" to 6,
            "seven" to 7, "seventh" to 7, "7th" to 7,
            "eight" to 8, "eighth" to 8, "8th" to 8,
            "nine" to 9, "ninth" to 9, "9th" to 9,
            "ten" to 10, "tenth" to 10, "10th" to 10,
            "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
            "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20
        )

        for ((word, num) in wordMap) {
            if (clean == word || clean.endsWith(" $word") || clean.startsWith("$word ") || clean.contains(" $word ")) {
                return num
            }
        }

        return null
    }

    fun cleanTarget(target: String): String {
        val tokens = target.lowercase().trim().split("\\s+".toRegex()).filter { it !in FILLER_WORDS }
        return if (tokens.isNotEmpty()) tokens.joinToString(" ") else target.lowercase().trim()
    }

    private fun parseTargetIndex(target: String): Int? {
        val num = parseNumberFromSpeech(target)
        return if (num != null && num >= 1) num - 1 else null
    }

    fun getNodeLabel(node: AccessibilityNodeInfo): String? {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { id ->
            val shortId = id.substringAfter(":id/").substringAfter("/")
            if (shortId.isNotBlank()) return shortId.replace("_", " ")
        }
        
        // Fast depth-1 check of immediate children (prevents O(N^2) Binder IPC lockups)
        if (node.childCount > 0) {
            val sb = StringBuilder()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val childText = child.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                if (childText != null) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(childText)
                }
            }
            if (sb.isNotBlank()) {
                return sb.toString()
            }
        }
        return null
    }

    fun findInteractiveNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isClickable || node.isCheckable) {
                if (getNodeLabel(node) != null) {
                    nodes.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return nodes
    }

    fun findMatchingNode(root: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        val nodes = findInteractiveNodes(root)
        return findBestMatch(target, nodes) { getNodeLabel(it) }
    }

    private fun <T> findBestMatch(query: String, items: List<T>, labelExtractor: (T) -> CharSequence?): T? {
        if (items.isEmpty()) return null
        return items.minByOrNull { item ->
            val label = labelExtractor(item)?.toString() ?: ""
            levenshtein(query, label.lowercase())
        }
    }

    /**
     * O(min(M, N)) allocation-optimized Levenshtein Distance implementation.
     */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val s1 = a.lowercase()
        val s2 = b.lowercase()

        var p = IntArray(s2.length + 1) { it }
        var d = IntArray(s2.length + 1)

        for (i in 1..s1.length) {
            d[0] = i
            val char1 = s1[i - 1]
            for (j in 1..s2.length) {
                val cost = if (char1 == s2[j - 1]) 0 else 1
                d[j] = minOf(
                    d[j - 1] + 1,
                    p[j] + 1,
                    p[j - 1] + cost
                )
            }
            val temp = p
            p = d
            d = temp
        }
        return p[s2.length]
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
            val hasScrollAction = currentNode.actionList.any { 
                it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || 
                it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD 
            }
            if (currentNode.isScrollable || hasScrollAction) {
                return currentNode
            }
            for (i in 0 until currentNode.childCount) {
                currentNode.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}
