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
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"
    private val viewModel: MainViewModel by viewModels()
    private var speechRecognizer: SpeechRecognizer? = null

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
                Toast.makeText(this, "Microphone permission is required for captions.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener { checkAllPermissions() }
        findViewById<Button>(R.id.btnStartService).setOnClickListener { handleStartListeningClick() }
        findViewById<Button>(R.id.btnStopService).setOnClickListener { stopSpeechRecognition() }
        findViewById<ToggleButton>(R.id.toggleCommandMode).setOnCheckedChangeListener { _, isChecked ->
            viewModel.setCommandMode(isChecked)
            val mode = if (isChecked) "Command" else "Caption"
            Toast.makeText(this, "Switched to $mode Mode", Toast.LENGTH_SHORT).show()
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateStatusUi(state)
                }
            }
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
        viewModel.setListening(true)
        Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
    }

    private fun stopSpeechRecognition() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        viewModel.setListening(false)
        Toast.makeText(this, "Stopped listening.", Toast.LENGTH_SHORT).show()
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                if (spokenText.isNotEmpty()) {
                    viewModel.onSpokenTextRecognized(spokenText)
                }
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val spokenText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                if (spokenText.isNotEmpty()) {
                    viewModel.onSpokenTextRecognized(spokenText)
                }
            }

            override fun onError(error: Int) {
                val errorMessage = getErrorText(error)
                Log.e(tag, "Speech recognition error: $error - $errorMessage")

                val shouldRetry = viewModel.handleSpeechError()
                if (shouldRetry && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    restartListening()
                } else if (!shouldRetry) {
                    Toast.makeText(this@MainActivity, "Speech recognition stopped due to repeated errors.", Toast.LENGTH_SHORT).show()
                    stopSpeechRecognition()
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
        if (speechRecognizer != null && viewModel.uiState.value.isListening) {
            speechRecognizer?.startListening(speechRecognizerIntent)
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

    override fun onDestroy() {
        stopSpeechRecognition()
        super.onDestroy()
    }

    private fun checkAllPermissions() {
        if (!isAccessibilityEnabled()) {
            showProminentDisclosureDialog {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else if (!canDrawOverlays()) {
            requestOverlayPermission()
        } else {
            Toast.makeText(this, "All permissions are granted!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProminentDisclosureDialog(onAccepted: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.prominent_disclosure_title)
            .setMessage(R.string.prominent_disclosure_message)
            .setPositiveButton(R.string.action_agree) { dialog, _ ->
                dialog.dismiss()
                onAccepted()
            }
            .setNegativeButton(R.string.action_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun updateStatusUi(state: MainUiState) {
        val statusView = findViewById<TextView>(R.id.tvStatus)
        val accessibilityText = if (state.isAccessibilityEnabled) "Enabled" else "Disabled"
        val overlayText = if (state.isOverlayGranted) "Granted" else "Not Granted"
        val micText = if (state.isAudioPermissionGranted) "Granted" else "Not Granted"

        statusView.text = "Accessibility Service: $accessibilityText\nOverlay Permission: $overlayText\nMicrophone Permission: $micText"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val serviceId = "${packageName}/${CaptionAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false

        return enabledServices.contains(serviceId, ignoreCase = true)
    }

    private fun canDrawOverlays(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun getErrorText(errorCode: Int): String {
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
