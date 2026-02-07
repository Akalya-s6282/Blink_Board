package com.example.accessibilitycaptions

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

class MyRecognitionService : RecognitionService() {
    private lateinit var speechRecognizer: SpeechRecognizer
    private val TAG = "MyRecognitionService"

    override fun onCreate() {
        super.onCreate()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        Log.d(TAG, "On-device recognizer created in custom service.")
    }

    override fun onStartListening(intent: Intent, listener: Callback) {
        speechRecognizer.setRecognitionListener(RecognitionListenerAdapter(listener))
        speechRecognizer.startListening(intent)
        Log.d(TAG, "Custom recognition service forwarded startListening call.")
    }

    override fun onCancel(listener: Callback) {
        speechRecognizer.cancel()
    }

    override fun onStopListening(listener: Callback) {
        speechRecognizer.stopListening()
    }

    override fun onDestroy() {
        speechRecognizer.destroy()
        super.onDestroy()
    }

    private class RecognitionListenerAdapter(private val callback: Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = callback.readyForSpeech(params)
        override fun onBeginningOfSpeech() = callback.beginningOfSpeech()
        override fun onRmsChanged(rmsdB: Float) = callback.rmsChanged(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) = callback.bufferReceived(buffer)
        override fun onEndOfSpeech() = callback.endOfSpeech()
        override fun onError(error: Int) = callback.error(error)
        override fun onResults(results: Bundle?) = callback.results(results)
        override fun onPartialResults(partialResults: Bundle?) = callback.partialResults(partialResults)
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
