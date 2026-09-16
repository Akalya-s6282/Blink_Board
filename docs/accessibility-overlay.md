# Accessibility Service & System Floating Overlay

This document details the design, window manager layout, event integration, and security model of `CaptionAccessibilityService`.

---

## 🪟 Overview of `CaptionAccessibilityService`

`CaptionAccessibilityService` is an Android `AccessibilityService` responsible for two primary operations:
1. **Floating Caption Overlay**: Rendering real-time speech transcription and system feedback over all active applications without interfering with user touches.
2. **Global Action & Node Execution**: Fulfilling voice commands by performing node actions (e.g. clicks, scrolling, text typing, global back navigation).

---

## ⚙️ Service Manifest & Config Declarations

### Manifest Declaration
```xml
<service
    android:name=".CaptionAccessibilityService"
    android:enabled="true"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

### Accessibility Config Specification (`res/xml/accessibility_service_config.xml`)
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeViewClicked|typeViewFocused"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagIncludeNotImportantViews|flagRequestTouchExplorationMode|flagRetrieveInteractiveWindows|flagEnableAccessibilityVolume"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100"
    android:description="@string/accessibility_service_description"
    android:canRequestFilterKeyEvents="false"
    />
```

#### Key Config Flags Explained:
- **`canRetrieveWindowContent="true"`**: Enables reading accessibility node hierarchies (`rootInActiveWindow`) required for command execution.
- **`flagRetrieveInteractiveWindows`**: Allows searching across popups, dialogs, and non-standard application windows.
- **`flagIncludeNotImportantViews`**: Includes custom-rendered composables or custom views in the accessibility tree traversal.

---

## 🖼️ Floating Translucent Overlay Implementation

To display floating captions over third-party applications (e.g. Zoom, Meet, Chrome), `CaptionAccessibilityService` constructs a dedicated system window using `WindowManager`.

```
┌─────────────────────────────────────────────────────────────┐
│                   Active App Screen                         │
│                                                             │
│   (User can tap, swipe, and scroll through the underlying   │
│    app without any interference from the floating overlay)  │
│                                                             │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │  🗣️ "Welcome to the live caption floating display"     │ │ <- Floating TextView Overlay
│ └─────────────────────────────────────────────────────────┘ │    Gravity: BOTTOM
└─────────────────────────────────────────────────────────────┘    Type: TYPE_ACCESSIBILITY_OVERLAY
                                                                   Flags: NOT_FOCUSABLE | NOT_TOUCHABLE
```

### Window Manager Layout Construction
```kotlin
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
        setBackgroundColor("#CC111318".toColorInt()) // Semi-transparent dark background
        setPadding(32, 24, 32, 32)
        visibility = View.GONE
    }
    
    try {
        windowManager?.addView(captionView, params)
    } catch (e: Exception) {
        Log.e(tag, "Error attaching overlay view", e)
    }
}
```

### Critical Layout Parameters
- **`TYPE_ACCESSIBILITY_OVERLAY`**: Special window type assigned to Accessibility Services. Does not require `SYSTEM_ALERT_WINDOW` on newer Android versions when created by an active Accessibility Service.
- **`FLAG_NOT_FOCUSABLE`**: Prevents the overlay from hijacking soft keyboard input or key events from the active app.
- **`FLAG_NOT_TOUCHABLE`**: Passes all screen touches through the overlay directly to the underlying application.

---

## ⏳ Dynamic Caption Rendering & Auto-Dismiss

`CaptionAccessibilityService` manages overlay text updates and auto-clears captions after a specified duration:

```kotlin
private fun updateCaption(text: String, durationMs: Long) {
    captionView?.post {
        if (text.isNotBlank()) {
            captionView?.text = text
            captionView?.visibility = View.VISIBLE
            captionView?.removeCallbacks(null) // Cancel previous dismiss callbacks
            captionView?.postDelayed({
                captionView?.text = ""
                captionView?.visibility = View.GONE
            }, durationMs)
        } else {
            captionView?.visibility = View.GONE
        }
    }
}
```

---

## 📬 Event Bus Subscriber & Command Execution

The service observes `CaptionEventBus.events` asynchronously within its Coroutine scope:

```kotlin
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

private fun handleVoiceCommand(command: String) {
    serviceScope.launch {
        val root = rootInActiveWindow
        val result = commandProcessor.processCommand(command, root)
        when (result) {
            is CommandResult.UpdateCaption -> updateCaption(result.text, result.durationMs)
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
```

---

## 🔐 Security & Memory Lifecycle Management

1. **View Cleanup on Destroy**:
   When the service is disabled or destroyed, the overlay view is removed from `WindowManager` to prevent window leak exceptions:
   ```kotlin
   override fun onDestroy() {
       super.onDestroy()
       serviceScope.cancel()
       try {
           if (captionView?.parent != null) {
               windowManager?.removeView(captionView)
           }
       } catch (e: Exception) {
           Log.e(tag, "Error removing caption view on destroy", e)
       }
   }
   ```

2. **Data Privacy Guard**:
   `CaptionAccessibilityService` processes accessibility nodes strictly in-memory during voice command execution. No screen tree contents, window descriptions, or typed text are saved to disk or transmitted across network connections.

---

Next: Proceed to [**UI & State Management**](ui-and-state-management.md) for Jetpack Compose UI architecture.
