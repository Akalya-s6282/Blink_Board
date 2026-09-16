package com.example.accessibilitycaptions

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.accessibilitycaptions.ui.MainScreen
import com.example.accessibilitycaptions.ui.theme.AccessibilityCaptionsTheme

class MainActivity : ComponentActivity() {

    private val tag = "MainActivity"
    private val viewModel: MainViewModel by viewModels()

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCaptionService()
            } else {
                Toast.makeText(this, "Microphone permission is required for captions.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            AccessibilityCaptionsTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                MainScreen(
                    uiState = uiState,
                    onEnableAccessibilityClicked = { checkAllPermissions() },
                    onStartListeningClicked = { handleStartListeningClick() },
                    onStopListeningClicked = { stopCaptionService() },
                    onCommandModeToggled = { isChecked ->
                        viewModel.setCommandMode(isChecked)
                        val mode = if (isChecked) "Command" else "Caption"
                        Toast.makeText(this, "Switched to $mode Mode", Toast.LENGTH_SHORT).show()
                    },
                    onDisclosureAccepted = {
                        viewModel.setShowDisclosureDialog(false)
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onDisclosureDismissed = {
                        viewModel.setShowDisclosureDialog(false)
                    }
                )
            }
        }
    }

    private fun handleStartListeningClick() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                startCaptionService()
            }
            else -> {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startCaptionService() {
        try {
            val intent = Intent(this, CaptionService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Caption & Voice Command Service Started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(tag, "Error starting CaptionService", e)
            Toast.makeText(this, "Failed to start service.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopCaptionService() {
        try {
            val intent = Intent(this, CaptionService::class.java).apply {
                action = CaptionService.ACTION_STOP_SERVICE
            }
            startService(intent)
            CaptionEventBus.setListening(false)
            Toast.makeText(this, "Stopped service.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping CaptionService", e)
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

    private fun checkAllPermissions() {
        if (!isAccessibilityEnabled()) {
            viewModel.setShowDisclosureDialog(true)
        } else if (!canDrawOverlays()) {
            requestOverlayPermission()
        } else {
            Toast.makeText(this, "All permissions are granted!", Toast.LENGTH_SHORT).show()
        }
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
}
