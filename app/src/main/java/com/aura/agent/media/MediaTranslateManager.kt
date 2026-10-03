package com.aura.agent.media

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * مدیر یکپارچه: ترجمه متن / زیرنویس / عکس → فارسی.
 */
class MediaTranslateManager(private val context: Context) {

    companion object {
        private const val TAG = "AuraMediaTranslate"
    }

    private val translator = TranslateService()
    private val imageExtractor = ImageTextExtractor(context)

    suspend fun translateText(text: String, sourceLang: String? = null): String {
        return translator.toPersian(text, sourceLang).getOrElse {
            "❌ ترجمه ناموفق: ${it.message}"
        }
    }

    suspend fun translateSubtitles(rawSubtitle: String): String {
        val blocks = SubtitleParser.parse(rawSubtitle)
        if (blocks.size <= 1 && !rawSubtitle.contains("-->")) {
            val t = translator.toPersian(rawSubtitle, "زیرنویس").getOrElse {
                return "❌ ${it.message}"
            }
            return "🎬 زیرنویس فارسی:\n\n$t"
        }
        val translated = translator.translateSubtitleBlocks(blocks).getOrElse {
            return "❌ ${it.message}"
        }
        val srt = SubtitleParser.toSrt(translated)
        return "🎬 زیرنویس فارسی (SRT):\n\n$srt"
    }

    suspend fun translateImage(uri: Uri): String {
        Log.i(TAG, "Extracting text from image…")
        val extracted = imageExtractor.extractTextFromUri(uri).getOrElse {
            return "❌ استخراج متن از تصویر: ${it.message}"
        }
        if (extracted.startsWith("[مدل فعلی")) {
            return extracted
        }
        val persian = translator.toPersian(extracted, "متن داخل تصویر").getOrElse {
            return "📷 متن استخراج‌شده:\n$extracted\n\n❌ ترجمه: ${it.message}"
        }
        return buildString {
            append("📷 متن تصویر (اصلی):\n")
            append(extracted.take(1500))
            append("\n\n🇮🇷 ترجمه فارسی:\n")
            append(persian)
        }
    }

    suspend fun translateImageBitmap(bitmap: android.graphics.Bitmap): String {
        val extracted = imageExtractor.extractTextFromBitmap(bitmap).getOrElse {
            return "❌ ${it.message}"
        }
        val persian = translator.toPersian(extracted).getOrElse {
            return "📷 $extracted\n\n❌ ترجمه: ${it.message}"
        }
        return "📷 متن:\n$extracted\n\n🇮🇷 فارسی:\n$persian"
    }

    fun helpText(): String = """
🇮🇷 ترجمه و زیرنویس خودکار به فارسی

دستورات:
• ترجمه کن: <متن هر زبانی>
• زیرنویس فارسی: <متن SRT/VTT یا دیالوگ>
• ترجمه این متن به فارسی

عکس: از گالری انتخاب کنید یا بگویید «ترجمه عکس»
فیلم: فایل زیرنویس (SRT) را بفرستید تا به فارسی تبدیل شود.

همه زبان‌ها → فارسی روان.
    """.trim()
}
