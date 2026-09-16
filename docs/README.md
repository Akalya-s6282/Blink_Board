# Blink Board: Technical Documentation Hub

Welcome to the technical developer documentation for **Blink Board** (`com.blinkboard.accessibilitycaptions`).

Blink Board is an Android accessibility platform designed to provide real-time speech-to-text live captions on a system-wide floating overlay and enable hands-free voice command control for Android devices during video calls (e.g., Zoom, Google Meet, WhatsApp), phone calls, and general OS navigation.

---

## 📚 Documentation Index

| Topic | Description | Target Component / Classes |
| :--- | :--- | :--- |
| 🏗️ [**System Architecture**](architecture.md) | High-level system design, service interaction, reactive event pipeline, and component relationships. | `CaptionEventBus`, System Services, Lifecycle |
| 🎙️ [**Speech & Audio Engine**](speech-and-audio-engine.md) | Foreground Service implementation, speech recognition engines, raw PCM audio capture, and call state transition handling. | `CaptionService`, `SpeechRecognizer`, `AudioRecord` |
| ⚡ [**Voice Command Engine**](voice-command-processor.md) | Intent parsing, 4-tier element matching, Levenshtein distance algorithm, node tree traversal, and confirmation workflow. | `CommandProcessor`, `CommandResult`, `AccessibilityNodeInfo` |
| 🪟 [**Accessibility Overlay**](accessibility-overlay.md) | Accessibility Service integration, system window manager overlay (`TYPE_ACCESSIBILITY_OVERLAY`), node actions, and security permissions. | `CaptionAccessibilityService`, `WindowManager`, `AccessibilityNodeInfo` |
| 🎨 [**UI & State Management**](ui-and-state-management.md) | Jetpack Compose Material 3 presentation layer, `StateFlow` state streams, ViewModel architecture, and Google Play compliance disclosure dialog. | `MainActivity`, `MainViewModel`, `MainScreen`, `MainUiState` |
| 🛠️ [**Setup, Build & Testing**](setup-and-testing.md) | Environment setup, Gradle dependencies, ProGuard/R8 configuration, unit testing suite, and developer debugging guide. | Gradle, `CommandProcessorTest`, ADB Logcat |

---

## 🚀 Key Technical Highlights

- **Decoupled Reactive Event Bus**: In-memory thread-safe `SharedFlow` / `StateFlow` bus (`CaptionEventBus`) decoupling Speech Recognition from Accessibility Services without dynamic global broadcast receivers.
- **On-Device & System Speech Recognition**: Automatic fallback between `createOnDeviceSpeechRecognizer` (Android 13+) and system speech engines for low-latency live captions.
- **Dual Audio Capture Modes**: Real-time microphone audio capture (`AudioSource.MIC`) and system audio loopback capture (`AudioPlaybackCaptureConfiguration`) via `MediaProjection` during active telephony/VoIP calls.
- **Multi-Tier Voice Interaction Engine**: Intelligent node resolution utilizing exact string matching, substring search, dynamic programming Levenshtein fuzzy matching (distance $\le$ 2), and interactive node discovery (`show elements`).
- **Non-Blocking Accessibility Overlay**: System window overlay rendered via `TYPE_ACCESSIBILITY_OVERLAY` with `FLAG_NOT_FOCUSABLE` and `FLAG_NOT_TOUCHABLE`, guaranteeing zero touch-blocking or focus-hijacking during app interactions.
- **Modern Jetpack Compose M3 UI**: Fully declarative Material 3 interface featuring stateful system readiness indicators, mode selectors, live speech test cards, and context-aware action triggers.

---

## 📁 Repository Structure

```
Blink_Board/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/blinkboard/accessibilitycaptions/
│       │   │   ├── CaptionAccessibilityService.kt   # System Accessibility Service & Overlay Manager
│       │   │   ├── CaptionEventBus.kt               # Central Reactive Event Stream & State Bus
│       │   │   ├── CaptionService.kt                # Foreground Audio Capture & Speech Recognizer
│       │   │   ├── CommandProcessor.kt              # Voice Command Processing & NLP Fuzzy Engine
│       │   │   ├── MainActivity.kt                  # Entry Point & Permission Handler
│       │   │   ├── MainViewModel.kt                 # UI State Producer & Bus Adapter
│       │   │   └── ui/
│       │   │       ├── MainScreen.kt                # Jetpack Compose Material 3 UI Layout
│       │   │       └── theme/                       # Design Tokens, Typography & Color Schemes
│       │   └── res/
│       │       ├── xml/accessibility_service_config.xml
│       │       └── values/strings.xml
│       └── test/
│           └── java/com/blinkboard/accessibilitycaptions/
│               └── CommandProcessorTest.kt          # Unit Tests for NLP Engine & Algorithms
├── docs/                                            # Technical Developer Documentation
├── build.gradle.kts
└── gradle/libs.versions.toml                        # Centralized Version Catalog
```

---

## ⚙️ Quick Start for Developers

### Prerequisites
- **Android Studio**: Ladybug / 2024.2.1 or newer
- **JDK**: Version 17
- **Min SDK**: 29 (Android 10+)
- **Target SDK**: 34 / Compile SDK: 36

### Build & Run
```bash
# Clean and assemble debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew testDebugUnitTest
```

For detailed architecture breakdowns and implementation details, proceed to [**System Architecture**](architecture.md).
