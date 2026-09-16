# Voice Command Engine & NLP Processor

This document details the architecture, natural language algorithms, 4-tier matching strategy, Levenshtein fuzzy matching matrix, confirmation state machine, and accessibility node tree traversal logic implemented in `CommandProcessor`.

---

## ⚡ Overview of `CommandProcessor`

`CommandProcessor` parses raw voice transcriptions into system actions or UI interactions without requiring cloud NLP services. It evaluates screen structure via Android's `AccessibilityNodeInfo` hierarchy.

### Command Execution Result Hierarchy (`CommandResult`)
All command processing routines return a strongly-typed `CommandResult`:

```kotlin
sealed class CommandResult {
    data class UpdateCaption(val text: String, val durationMs: Long = 5000) : CommandResult()
    data class PerformAction(
        val failureMessage: String = "Action could not be performed.",
        val action: (root: AccessibilityNodeInfo?) -> Boolean
    ) : CommandResult()
    data class ShowToast(val message: String) : CommandResult()
    object GoBack : CommandResult()
    object Handled : CommandResult()
}
```

---

## 🎯 4-Tier Element Matching Engine

When a click command is issued (e.g., `"click submit"` or `"tap log in"`), `CommandProcessor.handleInteraction()` retrieves all interactive nodes (`isClickable || isCheckable` with text/contentDescription) and evaluates them using a 4-tier match strategy:

```
                  ┌───────────────────────────────┐
                  │    Incoming Click Command     │
                  └───────────────┬───────────────┘
                                  │
                   Tier 1: Case-Insensitive Exact Match?
                                  │
                     ┌────────────┴────────────┐
                    YES                        NO
                     │                         │
                     ▼                         ▼
            [Execute Action]     Tier 2: Case-Insensitive Substring Match?
                                               │
                                   ┌───────────┴───────────┐
                                  YES                      NO
                                   │                       │
                                   ▼                       ▼
                          [Execute Action]    Tier 3: Levenshtein Distance <= 2?
                                                           │
                                               ┌───────────┴───────────┐
                                              YES                      NO
                                               │                       │
                                               ▼                       ▼
                                   [Request Confirmation]   Tier 4: "Element Not Found"
```

### Tier 1: Case-Insensitive Exact Match
Matches target text directly against node text or content descriptions:

```kotlin
val exactMatch = interactiveNodes.find { node ->
    val label = (node.text ?: node.contentDescription)?.toString()?.trim()
    label.equals(target, ignoreCase = true)
}
```

### Tier 2: Substring Match
Matches partial string hits, selecting the node with the shortest total text length to avoid overly broad matches:

```kotlin
val substringMatches = interactiveNodes.filter { node ->
    val label = (node.text ?: node.contentDescription)?.toString()?.trim() ?: ""
    label.contains(target, ignoreCase = true)
}
if (substringMatches.isNotEmpty()) {
    val bestSubstring = substringMatches.minByOrNull { (it.text ?: it.contentDescription)?.length ?: Int.MAX_VALUE }!!
    // Click bestSubstring
}
```

### Tier 3: Fuzzy Levenshtein Distance Match (Distance $\le 2$)
Uses dynamic programming to catch minor speech recognition typos or mispronunciations. If the distance is $\le 2$, it prompts the user for confirmation:

```kotlin
val bestMatch = findBestMatch(target, interactiveNodes) { it.text ?: it.contentDescription }
if (bestMatch != null) {
    val bestMatchLabel = (bestMatch.text ?: bestMatch.contentDescription).toString()
    val distance = levenshtein(target, bestMatchLabel.lowercase())
    if (distance <= 2) {
        pendingTargetText = bestMatchLabel
        pendingActionType = PendingActionType.CLICK_MATCH
        pendingTimestamp = System.currentTimeMillis()
        return CommandResult.UpdateCaption("❓ Did you mean: '$bestMatchLabel'? Say 'confirm' to click.", 10000)
    }
}
```

### Tier 4: Graceful Fallback
If no match satisfies any tier, `CommandResult.UpdateCaption("❌ Element '$target' not found on screen.")` is returned.

---

## 🧮 Levenshtein Distance Algorithm Implementation

`CommandProcessor` implements an explicit 2D dynamic programming Levenshtein distance matrix calculation:

$$\text{lev}_{a,b}(i,j) = \begin{cases} 
\max(i,j) & \text{if } \min(i,j) = 0, \\
\min \begin{cases} 
\text{lev}_{a,b}(i-1,j) + 1 \\ 
\text{lev}_{a,b}(i,j-1) + 1 \\ 
\text{lev}_{a,b}(i-1,j-1) + 1_{(a_i \neq b_j)} 
\end{cases} & \text{otherwise.} 
\end{cases}$$

```kotlin
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
```

---

## 🛡️ Safety Confirmation State Machine

For high-risk or ambiguous actions (e.g. fuzzy matches or ending a phone call), `CommandProcessor` uses a state machine with a 10-second expiration guard:

```kotlin
enum class PendingActionType {
    CLICK_MATCH,
    END_CALL
}
```

```kotlin
if (confirmCommands.any { trimmedCommand == it }) { // "confirm", "yes", "ok"
    val target = pendingTargetText
    val actionType = pendingActionType
    val timestamp = pendingTimestamp
    
    // Clear pending state
    pendingTargetText = null
    pendingActionType = null
    pendingTimestamp = 0L

    val currentTime = System.currentTimeMillis()
    if (timestamp > 0 && currentTime - timestamp > 10_000L) { // CONFIRMATION_TIMEOUT_MS
        return CommandResult.UpdateCaption("⚠️ Confirmation request expired.")
    }

    return when (actionType) {
        PendingActionType.CLICK_MATCH -> { /* Perform pending click */ }
        PendingActionType.END_CALL -> { /* Click 'end' call button */ }
        null -> CommandResult.Handled
    }
}
```

---

## 🗣️ Supported Voice Commands Reference

| Voice Command Syntax | Execution Action | Node Tree Search Criteria |
| :--- | :--- | :--- |
| `"show elements"` | Displays all interactive UI items on screen overlay | Traverses tree for `isClickable \|\| isCheckable` nodes |
| `"click <target>"`<br>`"press <target>"`<br>`"tap <target>"` | Clicks target element | Evaluates 4-Tier matching strategy against interactive nodes |
| `"type <text>"` | Inputs text into active input field | Searches for `isFocused && isEditable` node, issues `ACTION_SET_TEXT` |
| `"open <app>"` | Launches app by name or setting | Maps `"settings"` & `"camera"` shortcuts; queries launcher activities via `PackageManager` |
| `"scroll up"`<br>`"scroll down"` | Scrolls screen content | Searches for `isScrollable` node, executes `ACTION_SCROLL_BACKWARD` / `FORWARD` |
| `"go back"` | System back navigation | Triggers AccessibilityService `GLOBAL_ACTION_BACK` |
| `"end call"` | Prompts safety confirmation before ending active call | Locates "end" text/description, sets `PendingActionType.END_CALL` |

---

## 🌳 Node Tree Traversal Algorithms

All tree traversals are implemented using Breadth-First Search (BFS) to prevent stack overflow issues on deeply nested layout trees:

```kotlin
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
```

---

Next: Proceed to [**Accessibility Overlay**](accessibility-overlay.md) for overlay window management details.
