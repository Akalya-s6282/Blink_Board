package com.example.accessibilitycaptions

import android.media.AudioRecord
import java.io.InputStream

/**
 * An adapter class that makes an AudioRecord instance look like an InputStream.
 * This is required to feed the audio from AudioPlaybackCapture into the SpeechRecognizer.
 */
class AudioRecordInputStream(private val audioRecord: AudioRecord) : InputStream() {

    // This method is intentionally not supported as it's not used by the SpeechRecognizer.
    override fun read(): Int {
        throw UnsupportedOperationException("Single byte read is not supported.")
    }

    /**
     * Reads audio data from the AudioRecord buffer into the provided byte array.
     * This is the primary method used by the recognition framework.
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        // Reads from the audioRecord into the buffer `b` starting at offset `off` up to `len` bytes.
        return audioRecord.read(b, off, len)
    }
}
