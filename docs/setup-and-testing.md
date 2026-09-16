# Developer Setup, Build & Testing Guide

This document provides complete instructions for setting up the developer environment, building the project, running unit tests, configuring ProGuard/R8 rules, and debugging via Logcat.

---

## 💻 Developer Prerequisites

- **IDE**: Android Studio Ladybug (2024.2.1) or newer
- **JDK**: OpenJDK 17 (Java 17 JvmTarget)
- **Android SDK Version Configuration**:
  - `compileSdk`: 36
  - `targetSdk`: 34
  - `minSdk`: 29 (Android 10+, required for `AudioPlaybackCaptureConfiguration` in-call audio recording)

---

## 📦 Version Catalog & Dependencies (`libs.versions.toml`)

Dependencies are centrally managed via the Gradle Version Catalog:

| Library / Module | Purpose | Version Catalog Identifier |
| :--- | :--- | :--- |
| **AndroidX Core KTX** | Core Android extension functions | `libs.androidx.core.ktx` |
| **Compose BOM** | Jetpack Compose Bill of Materials | `libs.androidx.compose.bom` |
| **Compose Material 3** | Material Design 3 UI components | `libs.androidx.compose.material3` |
| **Compose Material Icons** | Material 3 Vector Icons | `libs.androidx.compose.material.icons` |
| **Lifecycle Runtime KTX** | Lifecycle Coroutines & Flow state collection | `libs.androidx.lifecycle.runtime.ktx` |
| **JUnit 4** | Unit testing framework | `libs.junit` |

---

## 🛠️ Build Configurations & Optimizations

### Build Features & Options (`app/build.gradle.kts`)
```kotlin
android {
    namespace = "com.blinkboard.accessibilitycaptions"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.blinkboard.accessibilitycaptions"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    aaptOptions {
        noCompress("tflite")
    }
}
```

---

## 🧪 Unit Testing Suite (`CommandProcessorTest`)

The project contains unit tests verifying the core Natural Language Processing engine, string algorithms, parameter extractors, and command state handling in `CommandProcessorTest.kt`.

### Executing Unit Tests via Gradle
```bash
# Run unit tests
./gradlew testDebugUnitTest
```

### Test Coverage Summary

```kotlin
class CommandProcessorTest {

    private val processor = CommandProcessor()

    @Test
    fun testLevenshteinExactMatch() {
        val distance = processor.levenshtein("submit", "submit")
        assertEquals(0, distance)
    }

    @Test
    fun testLevenshteinOneEdit() {
        val distance = processor.levenshtein("submit", "submi")
        assertEquals(1, distance)
    }

    @Test
    fun testLevenshteinTypo() {
        val distance = processor.levenshtein("submit", "sumbit")
        assertEquals(2, distance)
    }

    @Test
    fun testProcessClickCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("click submit", null)
        assertTrue(result is CommandResult.UpdateCaption)
        val caption = result as CommandResult.UpdateCaption
        assertTrue(caption.text.contains("No active window found"))
    }

    @Test
    fun testProcessClickWithoutTarget() = runBlocking {
        val result = processor.processCommand("click", null)
        assertTrue(result is CommandResult.ShowToast)
        val toast = result as CommandResult.ShowToast
        assertEquals("Please specify what to click.", toast.message)
    }

    @Test
    fun testProcessScrollCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("scroll up", null)
        assertTrue(result is CommandResult.PerformAction)
        val action = result as CommandResult.PerformAction
        assertEquals("No scrollable area found on screen.", action.failureMessage)
    }

    @Test
    fun testProcessTypeCommandWithoutRoot() = runBlocking {
        val result = processor.processCommand("type hello world", null)
        assertTrue(result is CommandResult.PerformAction)
        val action = result as CommandResult.PerformAction
        assertEquals("No focused editable input field found. Tap an input field first.", action.failureMessage)
    }

    @Test
    fun testProcessGoBackCommand() = runBlocking {
        val result = processor.processCommand("go back", null)
        assertTrue(result is CommandResult.GoBack)
    }

    @Test
    fun testProcessConfirmWithoutPendingTarget() = runBlocking {
        val result = processor.processCommand("confirm", null)
        assertTrue(result is CommandResult.Handled)
    }
}
```

---

## 🔍 Logcat Debugging & Tag Reference

To inspect real-time execution logs, filter Android Studio Logcat using the following tags:

| Log Tag | Source Class | Key Event Logs |
| :--- | :--- | :--- |
| `CaptionService` | `CaptionService.kt` | SpeechRecognizer creation, audio recording initialization, call state changes, buffer loop counts |
| `CaptionAccessibility` | `CaptionAccessibilityService.kt` | Accessibility service connection, overlay view creation, event consumption, action execution |
| `MainActivity` | `MainActivity.kt` | Permission checks, service start/stop triggers, UI state sync |

### Useful ADB Logcat Filter Command
```bash
adb logcat -s CaptionService CaptionAccessibility MainActivity
```

---

## ❓ Troubleshooting & Frequently Asked Questions

### 1. The floating overlay does not display over other apps
- **Cause**: The Accessibility Service is disabled or `TYPE_ACCESSIBILITY_OVERLAY` permission is restricted by OS settings.
- **Solution**: Open Android Settings -> Accessibility -> Downloaded Services / Installed Apps -> Enable **Accessibility Captions**.

### 2. Speech recognition is not returning results in background
- **Cause**: Microphone permission (`RECORD_AUDIO`) is not granted, or Google Speech Services is restricted in background.
- **Solution**: Ensure Microphone permission is set to "Allow all the time". Verify `SpeechRecognizer` availability via Logcat logs (`CaptionService`).

### 3. In-call audio is silent or not capturing call audio
- **Cause**: Call audio capture requires MediaProjection permission or device API is below Android 10 (API 29).
- **Solution**: Verify `minSdk = 29`. Ensure `AudioPlaybackCaptureConfiguration` matching usages include `USAGE_VOICE_COMMUNICATION`.

---

This concludes the complete technical documentation for **Blink Board**. Return to the [**Documentation Hub**](README.md) for navigation across all topics.
