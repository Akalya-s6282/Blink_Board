package com.example.accessibilitycaptions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private var speechRecognizer: SpeechRecognizer? = null
    private var isCommandMode = false

    private val speechRecognizerIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }
    }

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startSpeechRecognition()
            } else {
                Toast.makeText(this, "Microphone permission is required for voice control.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener { checkAllPermissions() }
        findViewById<Button>(R.id.btnStartService).setOnClickListener { handleStartListeningClick() }
        findViewById<Button>(R.id.btnStopService).setOnClickListener { stopSpeechRecognition() }
        findViewById<ToggleButton>(R.id.toggleCommandMode).setOnCheckedChangeListener { _, isChecked ->
            isCommandMode = isChecked
            val mode = if (isChecked) "Command" else "Caption"
            Toast.makeText(this, "Switched to $mode Mode", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleStartListeningClick() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                startSpeechRecognition()
            }
            else -> {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startSpeechRecognition() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener())
            }
        }
        speechRecognizer?.startListening(speechRecognizerIntent)
        Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
    }

    private fun stopSpeechRecognition() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        Toast.makeText(this, "Stopped listening.", Toast.LENGTH_SHORT).show()
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                if (spokenText.isNotEmpty()) sendBroadcastForProcessing(spokenText)
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val spokenText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                if (spokenText.isNotEmpty()) sendBroadcastForProcessing(spokenText)
            }

            override fun onError(error: Int) {
                val errorMessage = getErrorText(error)
                Log.e(TAG, "Speech recognition error: $error - $errorMessage")
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    restartListening()
                }
            }

            override fun onEndOfSpeech() {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun restartListening() {
        if (speechRecognizer != null) {
            speechRecognizer?.startListening(speechRecognizerIntent)
        }
    }

    private fun sendBroadcastForProcessing(text: String) {
        val intentAction = if (isCommandMode) "VOICE_COMMAND" else "CAPTION_UPDATE"
        val extraName = if (isCommandMode) "command" else "caption_text"

        val intent = Intent(intentAction).apply {
            putExtra(extraName, text)
            setPackage(this@MainActivity.packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Explicit broadcast sent: Action=$intentAction, Text='$text'")
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        stopSpeechRecognition()
        super.onDestroy()
    }

    private fun checkAllPermissions() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Please enable the accessibility service", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else if (!canDrawOverlays()) {
            requestOverlayPermission()
        } else {
            Toast.makeText(this, "All permissions are granted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val statusView = findViewById<TextView>(R.id.tvStatus)
        val accessibilityEnabled = isAccessibilityEnabled()
        val overlayEnabled = canDrawOverlays()
        statusView.text = "Accessibility Service: ${if (accessibilityEnabled) "Enabled" else "Disabled"}\nOverlay Permission: ${if (overlayEnabled) "Granted" else "Not Granted"}"
    }

    private fun isAccessibilityEnabled(): Boolean {
        // Rewritten to be extremely explicit to avoid IDE parser errors.
        val serviceId = "${packageName}/${CaptionAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

        if (enabledServices == null) {
            return false
        }

        return enabledServices.contains(serviceId, ignoreCase = true)
    }

    private fun canDrawOverlays(): Boolean {
        // This is a standard call and should not cause issues.
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun getErrorText(errorCode: Int): String {
        // Rewritten to be extremely explicit to avoid IDE parser errors.
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy"
            SpeechRecognizer.ERROR_SERVER -> "Error from server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown speech recognition error"
        }
    }
}
