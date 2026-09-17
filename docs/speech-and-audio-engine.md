# Speech & Audio Processing Engine

This document provides a deep technical breakdown of the `CaptionService` foreground service, speech recognition lifecycle, telephony integration, and dual-mode raw audio capture architecture.

---

## 🎙️ Overview of `CaptionService`

`CaptionService` is an Android `ForegroundService` designed for continuous audio monitoring and speech recognition. It operates across target APIs up to targetSdk 34 (Android 14) and Android 15 ready (`compileSdk = 36`).

### Foreground Service Manifest Declaration
```xml
<service
    android:name=".CaptionService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="microphone|mediaProjection" />
```

### Foreground Service Type Support
On Android 14+ (API Level 34 / UpsideDownCake), foreground services require specific runtime types:
- **`FOREGROUND_SERVICE_TYPE_MICROPHONE`**: Required for standard microphone recording.
- **`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`**: Required when capturing in-call audio loopback via `MediaProjection`.

```kotlin
private fun startForegroundServiceNotification() {
    val notification = createNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mediaProjection != null) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        try {
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } catch (e: Exception) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }
    } else {
        startForeground(NOTIFICATION_ID, notification)
    }
}
```

---

## 🗣️ Speech Recognition Pipeline

### 1. Engine Initialization Strategy
`CaptionService` prefers on-device speech recognition to lower latency and allow offline operation, falling back to system-wide speech recognizer when unavailable:

```kotlin
speechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
) {
    Log.d(tag, "Using On-Device Speech Recognizer")
    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
} else {
    Log.d(tag, "Using System Speech Recognizer")
    SpeechRecognizer.createSpeechRecognizer(this)
}
```

### 2. Speech Recognizer Intent Configuration
```kotlin
private val speechRecognizerIntent by lazy {
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
    }
}
```

### 4. Package Visibility (API 30+)
To ensure the `SpeechRecognizer.createSpeechRecognizer(this)` call successfully finds the system speech service on Android 11+, the `AndroidManifest.xml` explicitly declares package visibility:

```xml
<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```
Because standard Android `SpeechRecognizer` instances automatically stop after silence or speech completion, `CaptionService` implements an continuous loop using `RecognitionListener`:

```kotlin
private fun createRecognitionListener(): RecognitionListener {
    return object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
            if (spokenText.isNotEmpty()) {
                CaptionEventBus.emitSpokenText(spokenText, isFinal = true)
            }
            restartListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val spokenText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
            if (spokenText.isNotEmpty()) {
                CaptionEventBus.emitSpokenText(spokenText, isFinal = false)
            }
        }

        override fun onError(error: Int) {
            Log.e(tag, "Speech recognition error: $error")
            mainHandler.postDelayed({
                if (CaptionEventBus.isListening.value) {
                    restartListening()
                }
            }, 1000)
        }
        // ...
    }
}
```

---

## 📞 Telephony Integration & Call Detection

To support video and phone call captioning (e.g., Zoom, Google Meet, WhatsApp, cellular calls), `CaptionService` listens for phone state changes and dynamically switches raw audio streams.

### API Version Compatibility
- **Android 12+ (API 31+)**: Uses `TelephonyCallback` with `TelephonyCallback.CallStateListener`.
- **Legacy Android**: Uses `PhoneStateListener` (deprecated in API 31).

```kotlin
private fun setupPhoneStateListener() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setupTelephonyCallback()
    } else {
        setupLegacyPhoneStateListener()
    }
}

private fun handleCallStateChange(state: Int) {
    val wasInCall = isInPhoneCall
    isInPhoneCall = when (state) {
        TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> true
        else -> false
    }
    if (wasInCall != isInPhoneCall) {
        switchCaptureMode(useInCallMode = isInPhoneCall)
        restartListening()
    }
}
```

---

## 🎧 Dual Audio Capture Architecture

`CaptionService` supports two distinct audio capture modes using `AudioRecord`:

```
                    ┌─────────────────────────┐
                    │      CaptionService     │
                    └────────────┬────────────┘
                                 │
                   Is Phone / Video Call Active?
                                 │
                     ┌───────────┴───────────┐
                    YES                      NO
                     │                       │
                     ▼                       ▼
      ┌─────────────────────────────┐ ┌─────────────────────────────┐
      │     In-Call Audio Mode      │ │   Microphone Audio Mode     │
      ├─────────────────────────────┤ ├─────────────────────────────┤
      │ MediaProjection +           │ │ Managed via                 │
      │ AudioPlaybackCaptureConfig  │ │ SpeechRecognizer internal   │
      │ USAGE_VOICE_COMMUNICATION   │ │ audio capture               │
      │ Format: PCM 16-bit Mono     │ │                             │
      └─────────────────────────────┘ └─────────────────────────────┘
```

### 1. Microphone Capture via SpeechRecognizer
Captures ambient voice directly via the device hardware microphone. In standard mode, we rely entirely on the system's `SpeechRecognizer` to manage microphone access and buffer processing. (Raw `AudioRecord` microphone capture was removed to prevent hardware lock contention with the speech recognition engine).

### 2. In-Call Audio Playback Capture (`startInCallCapture`)
Captures incoming voice audio during telephony or VoIP sessions using `AudioPlaybackCaptureConfiguration` (requires `minSdk = 29` / Android 10+):

```kotlin
val config = AudioPlaybackCaptureConfiguration.Builder(projection)
    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .build()

audioRecord = AudioRecord.Builder()
    .setAudioPlaybackCaptureConfig(config)
    .setAudioFormat(
        AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
    )
    .setBufferSizeInBytes(bufferSize)
    .build()
```

### 3. Audio Thread Execution Loop
Audio buffers are read asynchronously off the UI thread:

```kotlin
private fun processAudioStream(source: String) {
    val buffer = ByteArray(bufferSize)
    var readCounter = 0
    while (isCapturing.get()) {
        val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
        if (bytesRead > 0) {
            readCounter++
            // Process PCM buffer...
        }
    }
}
```

---

Next: Proceed to [**Voice Command Engine**](voice-command-processor.md) for details on the NLP engine and Levenshtein algorithm.
