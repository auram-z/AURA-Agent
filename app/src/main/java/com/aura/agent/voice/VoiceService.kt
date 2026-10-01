package com.aura.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class VoiceService(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false

    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("fa")
                // Fallback to English if Persian not available
                if (tts?.isLanguageAvailable(Locale("fa")) == TextToSpeech.LANG_MISSING_DATA ||
                    tts?.isLanguageAvailable(Locale("fa")) == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                }
            }
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("تشخیص گفتار در این دستگاه پشتیبانی نمی‌شود")
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    onListeningStateChanged?.invoke(true)
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    onListeningStateChanged?.invoke(false)
                }

                override fun onError(error: Int) {
                    isListening = false
                    onListeningStateChanged?.invoke(false)
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "صدایی تشخیص نداد"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "زمان شنیدن به پایان رسید"
                        else -> "خطا در تشخیص گفتار"
                    }
                    onError?.invoke(msg)
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    onListeningStateChanged?.invoke(false)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        onResult?.invoke(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        onListeningStateChanged?.invoke(false)
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AURA_TTS")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.shutdown()
        tts = null
    }

    fun isCurrentlyListening(): Boolean = isListening
}
