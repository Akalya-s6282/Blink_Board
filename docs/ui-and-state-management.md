# UI Architecture & State Management

This document details the presentation layer, Jetpack Compose Material 3 composable hierarchy, ViewModel state production, runtime permission handling, and Google Play compliance disclosure dialogs in **Blink Board**.

---

## 🎨 Overview of Presentation Layer

The presentation layer is built using **Jetpack Compose Material 3** adhering to modern Android Unidirectional Data Flow (UDF) patterns.

```
┌─────────────────────────────────────────────────────────────┐
│                      MainUiState Flow                       │
└──────────────┬──────────────────────────────▲───────────────┘
               │                              │
               ▼                              │
┌──────────────────────────────┐              │
│          MainScreen          │              │
│    (Material 3 Composables)  │              │
└──────────────┬───────────────┘              │
               │                              │
         User Actions                  State Updates
   (Click / Toggle Events)             (ViewModel.update)
               │                              │
               ▼                              │
┌──────────────────────────────┐              │
│        MainViewModel         ├──────────────┘
│      (StateFlow Producer)    │
└──────────────┬───────────────┘
               │
               ▼
┌──────────────────────────────┐
│       CaptionEventBus        │
│   (Central Reactive Stream)  │
└──────────────────────────────┘
```

---

## 📊 State Representation (`MainUiState`)

The entire UI state of `MainScreen` is encapsulated in an immutable data class:

```kotlin
data class MainUiState(
    val isCommandMode: Boolean = false,
    val isListening: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val isAudioPermissionGranted: Boolean = false,
    val showDisclosureDialog: Boolean = false,
    val speechErrorCount: Int = 0
)
```

---

## 🧠 ViewModel Implementation (`MainViewModel`)

`MainViewModel` subscribes to state flows from `CaptionEventBus` and exposes `uiState` via Kotlin `StateFlow`:

```kotlin
class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Collect command mode changes reactively
        viewModelScope.launch {
            CaptionEventBus.isCommandMode.collect { isCommand ->
                _uiState.update { it.copy(isCommandMode = isCommand) }
            }
        }
        // Collect listening state changes reactively
        viewModelScope.launch {
            CaptionEventBus.isListening.collect { listening ->
                _uiState.update {
                    it.copy(
                        isListening = listening,
                        speechErrorCount = if (listening) 0 else it.speechErrorCount
                    )
                }
            }
        }
    }

    fun setCommandMode(isCommand: Boolean) {
        CaptionEventBus.setCommandMode(isCommand)
    }

    fun updatePermissions(
        accessibilityEnabled: Boolean,
        overlayGranted: Boolean,
        audioGranted: Boolean
    ) {
        _uiState.update {
            it.copy(
                isAccessibilityEnabled = accessibilityEnabled,
                isOverlayGranted = overlayGranted,
                isAudioPermissionGranted = audioGranted
            )
        }
    }

    fun setShowDisclosureDialog(show: Boolean) {
        _uiState.update { it.copy(showDisclosureDialog = show) }
    }
}
```

---

## 📱 Activity Permission Lifecycle (`MainActivity`)

`MainActivity` acts as the permission orchestrator, syncing permission states during `onResume()` and registering system permission launchers:

```kotlin
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCaptionService()
            } else {
                Toast.makeText(this, "Microphone permission is required for captions.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onResume() {
        super.onResume()
        viewModel.updatePermissions(
            accessibilityEnabled = isAccessibilityEnabled(),
            overlayGranted = canDrawOverlays(),
            audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (service in enabledServices) {
                val serviceInfo = service.resolveInfo?.serviceInfo
                if (serviceInfo?.packageName == packageName &&
                    serviceInfo.name == CaptionAccessibilityService::class.java.name
                ) {
                    return true
                }
            }
        }
        val expectedFullId = "$packageName/${CaptionAccessibilityService::class.java.name}"
        val enabledServicesString = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabledServicesString.contains(expectedFullId, ignoreCase = true)
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)
}
```

---

## 🖼️ Jetpack Compose UI Breakdown (`MainScreen`)

`MainScreen` is composed of four primary Material 3 sections:

### 1. System Readiness Card
Displays real-time chips indicating the status of required system permissions:
- **Accessibility Service**
- **Overlay Permission** (`SYSTEM_ALERT_WINDOW`)
- **Microphone Permission** (`RECORD_AUDIO`)

```kotlin
ElevatedCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("System Readiness", style = MaterialTheme.typography.titleMedium)
        PermissionChipRow("Accessibility Service", uiState.isAccessibilityEnabled)
        PermissionChipRow("Overlay Permission", uiState.isOverlayGranted)
        PermissionChipRow("Microphone Permission", uiState.isAudioPermissionGranted)
    }
}
```

### 2. Operating Mode Segmented Card
Utilizes Material 3 `SingleChoiceSegmentedButtonRow` to allow instant switching between **Caption Mode** and **Command Mode**:

```kotlin
SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    SegmentedButton(
        selected = !uiState.isCommandMode,
        onClick = { onCommandModeToggled(false) },
        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
    ) { Text("Caption Mode") }
    
    SegmentedButton(
        selected = uiState.isCommandMode,
        onClick = { onCommandModeToggled(true) },
        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
    ) { Text("Command Mode") }
}
```

### 3. Live Speech Preview Card (`AnimatedVisibility`)
An animated status card that fades in (`fadeIn()` / `fadeOut()`) whenever `uiState.isListening == true`, giving immediate feedback that the background speech engine is active.

### 4. Adaptive Hero Call-To-Action Button
Contextually transforms based on current permission and listening states:

```
                  ┌───────────────────────────────┐
                  │ Are all permissions granted? │
                  └───────────────┬───────────────┘
                                  │
                     ┌────────────┴────────────┐
                    NO                        YES
                     │                         │
                     ▼                         ▼
      [Grant System Permissions]    Is Speech Recognition Listening?
                                               │
                                   ┌───────────┴───────────┐
                                  NO                      YES
                                   │                       │
                                   ▼                       ▼
                     [Start Speech Recognition]   [Stop Recognition]
```

---

## ⚖️ Prominent Disclosure Consent Dialog (Google Play Compliance)

Google Play Store policies strictly require a prominent disclosure before directing users to enable Accessibility Services:

```kotlin
if (uiState.showDisclosureDialog) {
    AlertDialog(
        onDismissRequest = onDisclosureDismissed,
        title = { Text(stringResource(R.string.prominent_disclosure_title)) },
        text = { Text(stringResource(R.string.prominent_disclosure_message)) },
        confirmButton = {
            Button(onClick = onDisclosureAccepted) { Text(stringResource(R.string.action_agree)) }
        },
        dismissButton = {
            TextButton(onClick = onDisclosureDismissed) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
```

#### Message Content (`strings.xml`):
> *"Accessibility Captions uses Accessibility Services and Microphone audio to display real-time video captions and execute voice commands (e.g. clicking buttons, scrolling, ending calls).*
> 
> - *No audio or screen content is stored or transmitted.*
> - *Data is processed entirely on-device.*
> - *You can disable this service at any time in Settings."*

---

Next: Proceed to [**Setup, Build & Testing**](setup-and-testing.md) for build, testing, and debugging instructions.
