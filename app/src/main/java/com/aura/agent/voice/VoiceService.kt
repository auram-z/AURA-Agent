package com.aura.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * سرویس گفتار بدون محدودیت
 * - شنیدن مداوم (Continuous Listening)
 * - TTS بدون سقف کاراکتر (chunk خودکار)
 * - پشتیبانی چندزبانه با اولویت فارسی
 */
class VoiceService(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false
    private var continuousMode = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ttsQueue = ArrayDeque<String>()
    private var ttsSpeaking = false

    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onListeningStateChanged: ((Boolean) -> Unit)? = null
    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val fa = Locale("fa", "IR")
                val result = tts?.setLanguage(fa)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                }
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        ttsSpeaking = true
                        onSpeakingStateChanged?.invoke(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { speakNextChunk() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { speakNextChunk() }
                    }
                })
            }
        }
    }

    /** فعال/غیرفعال کردن شنیدن مداوم بدون محدودیت زمانی */
    fun setContinuousMode(enabled: Boolean) {
        continuousMode = enabled
        if (!enabled && isListening) {
            stopListening()
        }
    }

    fun isContinuousMode(): Boolean = continuousMode

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("تشخیص گفتار در این دستگاه پشتیبانی نمی‌شود")
            return
        }

        // اگر TTS در حال پخش است، متوقف کن تا تداخل نداشته باشد
        stopSpeaking()

        ensureRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // بدون محدودیت زمانی سخت — حداکثر ممکن
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            onError?.invoke("خطا در شروع شنیدن: ${e.message}")
            isListening = false
            onListeningStateChanged?.invoke(false)
        }
    }

    fun stopListening() {
        continuousMode = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        isListening = false
        onListeningStateChanged?.invoke(false)
    }

    /**
     * پخش کامل متن بدون محدودیت طول
     * متن‌های بلند به صورت chunk پخش می‌شوند
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        tts ?: return

        stopSpeaking()

        // شکستن متن به قطعات قابل پخش (حدود ۴۰۰ کاراکتر در مرز جمله/فاصله)
        val chunks = chunkText(text, maxLen = 400)
        ttsQueue.clear()
        ttsQueue.addAll(chunks)
        speakNextChunk()
    }

    fun stopSpeaking() {
        ttsQueue.clear()
        tts?.stop()
        ttsSpeaking = false
        onSpeakingStateChanged?.invoke(false)
    }

    fun release() {
        continuousMode = false
        stopListening()
        stopSpeaking()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.shutdown()
        tts = null
    }

    fun isCurrentlyListening(): Boolean = isListening
    fun isCurrentlySpeaking(): Boolean = ttsSpeaking

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return

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
                // در حالت مداوم، isListening را فوری false نکن — بعد از نتیجه تصمیم می‌گیریم
                if (!continuousMode) {
                    isListening = false
                    onListeningStateChanged?.invoke(false)
                }
            }

            override fun onError(error: Int) {
                isListening = false
                onListeningStateChanged?.invoke(false)

                // در حالت مداوم، خطاهای موقتی را نادیده بگیر و دوباره شروع کن
                val recoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_CLIENT

                if (continuousMode && recoverable) {
                    mainHandler.postDelayed({ startListening() }, 400)
                    return
                }

                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "صدایی تشخیص نداد"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "زمان شنیدن به پایان رسید"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "خطای شبکه در تشخیص گفتار — اتصال اینترنت را بررسی کنید"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "مجوز میکروفون لازم است"
                    else -> "خطا در تشخیص گفتار (کد $error)"
                }
                if (!continuousMode) {
                    onError?.invoke(msg)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                onListeningStateChanged?.invoke(false)

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    onResult?.invoke(text)
                }

                // شنیدن مداوم: بلافاصله دوباره شروع کن
                if (continuousMode) {
                    mainHandler.postDelayed({ startListening() }, 600)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun speakNextChunk() {
        val next = ttsQueue.removeFirstOrNull()
        if (next == null) {
            ttsSpeaking = false
            onSpeakingStateChanged?.invoke(false)
            return
        }
        val id = UUID.randomUUID().toString()
        tts?.speak(next, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** شکستن متن بلند روی مرز جمله یا فاصله */
    private fun chunkText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)

        val chunks = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLen) {
                chunks.add(remaining)
                break
            }
            // ترجیح شکستن روی نقطه، علامت سؤال، خط جدید یا فاصله
            var cut = remaining.lastIndexOf('.', maxLen)
            if (cut < maxLen / 3) cut = remaining.lastIndexOf('؟', maxLen)
            if (cut < maxLen / 3) cut = remaining.lastIndexOf('\n', maxLen)
            if (cut < maxLen / 3) cut = remaining.lastIndexOf(' ', maxLen)
            if (cut < maxLen / 3) cut = maxLen

            chunks.add(remaining.substring(0, cut + 1).trim())
            remaining = remaining.substring(cut + 1).trim()
        }
        return chunks.filter { it.isNotBlank() }
    }
}
