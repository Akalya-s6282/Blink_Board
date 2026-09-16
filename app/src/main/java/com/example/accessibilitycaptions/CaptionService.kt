package com.example.accessibilitycaptions

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RequiresApi(Build.VERSION_CODES.Q)
class CaptionService : Service() {

    private val tag = "CaptionService"
    private val isCapturing = AtomicBoolean(false)
    private var audioCaptureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var telephonyManager: TelephonyManager? = null
    private var isInPhoneCall = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0
    private var mediaProjection: MediaProjection? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val speechRecognizerIntent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }
    }

    companion object {
        const val CHANNEL_ID = "CaptionServiceChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP_SERVICE = "com.example.accessibilitycaptions.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        setupPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceNotification()
        Log.d(tag, "Service starting...")

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            intent?.getParcelableExtra("data")
        }

        if (resultCode != -1 && data != null) {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        }

        if (checkPermissions()) {
            startInitialCapture()
            startSpeechRecognition()
        } else {
            Log.e(tag, "Permissions not granted")
            stopSelf()
        }
        return START_STICKY
    }

    private fun startSpeechRecognition() {
        mainHandler.post {
            if (speechRecognizer == null) {
                speechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                ) {
                    Log.d(tag, "Using On-Device Speech Recognizer in Service")
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                } else {
                    Log.d(tag, "Using System Speech Recognizer in Service")
                    SpeechRecognizer.createSpeechRecognizer(this)
                }.apply {
                    setRecognitionListener(createRecognitionListener())
                }
            }
            speechRecognizer?.startListening(speechRecognizerIntent)
            CaptionEventBus.setListening(true)
            Log.d(tag, "Speech recognition listening started in background service")
        }
    }

    private fun stopSpeechRecognition() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(tag, "Error destroying SpeechRecognizer", e)
            }
            speechRecognizer = null
            CaptionEventBus.setListening(false)
            Log.d(tag, "Speech recognition stopped in background service")
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                if (spokenText.isNotEmpty()) {
                    CaptionEventBus.emitSpokenText(spokenText)
                }
                restartListening()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val spokenText = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                if (spokenText.isNotEmpty()) {
                    CaptionEventBus.emitSpokenText(spokenText)
                }
            }

            override fun onError(error: Int) {
                Log.e(tag, "Service speech recognition error: $error")
                mainHandler.postDelayed({
                    if (CaptionEventBus.isListening.value) {
                        restartListening()
                    }
                }, 1000)
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
        mainHandler.post {
            if (speechRecognizer != null && CaptionEventBus.isListening.value) {
                try {
                    speechRecognizer?.startListening(speechRecognizerIntent)
                } catch (e: Exception) {
                    Log.e(tag, "Error restarting speech recognition", e)
                }
            }
        }
    }

    private fun startForegroundServiceNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } catch (e: Exception) {
                Log.e(tag, "Error starting foreground service with type", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupPhoneStateListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(tag, "READ_PHONE_STATE permission not granted. Cannot detect phone calls.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setupTelephonyCallback()
        } else {
            setupLegacyPhoneStateListener()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setupTelephonyCallback() {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state)
            }
        }
        telephonyManager?.registerTelephonyCallback(ContextCompat.getMainExecutor(this), callback)
    }

    @Suppress("DEPRECATION")
    private fun setupLegacyPhoneStateListener() {
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChange(state)
            }
        }
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun handleCallStateChange(state: Int) {
        val wasInCall = isInPhoneCall
        isInPhoneCall = when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> true
            else -> false
        }
        if (wasInCall != isInPhoneCall) {
            Log.d(tag, "Call state changed. In call: $isInPhoneCall")
            switchCaptureMode(useInCallMode = isInPhoneCall)
            restartListening()
        }
    }

    private fun switchCaptureMode(useInCallMode: Boolean) {
        stopCapture()
        mainHandler.postDelayed({
            if (useInCallMode) {
                startInCallCapture()
            } else {
                startMicrophoneCapture()
            }
        }, 1000)
    }

    private fun startInitialCapture() {
        if (isInPhoneCall) {
            startInCallCapture()
        } else {
            startMicrophoneCapture()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneCapture(): Boolean {
        Log.d(tag, "Attempting to start microphone capture...")
        try {
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "Microphone AudioRecord not initialized")
                return false
            }
            audioRecord?.startRecording()
            isCapturing.set(true)
            audioCaptureThread = thread { processAudioStream("MIC") }
            Log.d(tag, "Microphone capture started successfully")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start microphone capture: ", e)
            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startInCallCapture(): Boolean {
        Log.d(tag, "Attempting to start in-call audio capture...")
        val projection = mediaProjection
        if (projection == null) {
            Log.e(tag, "MediaProjection is not available for in-call capture.")
            return false
        }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()
        try {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "In-call AudioRecord not initialized")
                return false
            }
            audioRecord?.startRecording()
            isCapturing.set(true)
            audioCaptureThread = thread { processAudioStream("CALL") }
            Log.d(tag, "In-call audio capture started successfully")
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start in-call audio capture: ", e)
            return false
        }
    }

    private fun processAudioStream(source: String) {
        val buffer = ByteArray(bufferSize)
        Log.d(tag, "Audio processing thread started for $source")
        var readCounter = 0
        while (isCapturing.get()) {
            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (bytesRead > 0) {
                readCounter++
                if (readCounter % 200 == 0) { // Log once every ~10 seconds instead of flooding logcat
                    Log.d(tag, "Actively processing audio buffer ($source, sample count: $readCounter)")
                }
            }
        }
        Log.d(tag, "Audio processing thread stopped for $source")
    }

    private fun stopCapture() {
        isCapturing.set(false)
        audioCaptureThread?.interrupt()
        audioCaptureThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(tag, "Error stopping audio capture: ", e)
        }
        Log.d(tag, "Audio capture stopped.")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpeechRecognition()
        stopCapture()
        mediaProjection?.stop()
        mediaProjection = null
        Log.d(tag, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_notification_title),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val statusText = if (isInPhoneCall) "Capturing call audio & voice commands" else "Listening for speech & voice commands"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
