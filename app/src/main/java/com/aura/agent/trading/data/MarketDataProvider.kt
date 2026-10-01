package com.aura.agent.trading.data

import com.aura.agent.trading.models.MarketType
import com.aura.agent.trading.models.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class Candle(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val timestamp: Long
)

data class MarketQuote(
    val symbol: Symbol,
    val price: Double,
    val changePercent24h: Double = 0.0,
    val volume24h: Double = 0.0,
    val candles: List<Candle> = emptyList()
)

/**
 * تامین‌کننده داده بازار
 * کریپتو: Binance Public API (بدون نیاز به API Key)
 * بورس ایران: ساختار آماده + داده نمونه/پشتیبان (تا زمان وصل API رسمی)
 */
class MarketDataProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getCryptoQuote(ticker: String): MarketQuote? = withContext(Dispatchers.IO) {
        try {
            val pair = normalizeCryptoSymbol(ticker)
            val price = fetchBinancePrice(pair) ?: return@withContext null
            val candles = fetchBinanceKlines(pair, limit = 50)
            val change = if (candles.size >= 2) {
                val old = candles.first().close
                if (old > 0) ((price - old) / old) * 100 else 0.0
            } else 0.0
            val vol = candles.sumOf { it.volume }

            MarketQuote(
                symbol = Symbol(pair, pair, MarketType.CRYPTO, price),
                price = price,
                changePercent24h = change,
                volume24h = vol,
                candles = candles
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getCryptoWatchlist(): List<MarketQuote> = withContext(Dispatchers.IO) {
        val pairs = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT")
        pairs.mapNotNull { getCryptoQuote(it) }
    }

    /**
     * بورس ایران — فعلاً داده نمونه‌ای/ساختاریافته
     * برای اتصال واقعی باید API کارگزاری یا تامین‌کننده معتبر وصل شود.
     */
    suspend fun getIranWatchlist(): List<MarketQuote> = withContext(Dispatchers.IO) {
        val samples = listOf(
            Triple("وامید", "بانک ملت", 3520.0),
            Triple("شپنا", "گروه پتروشیمی", 12950.0),
            Triple("خودرو", "خودرو", 4180.0),
            Triple("فولاد", "فولاد مبارکه", 8750.0),
            Triple("وبصادر", "بصادر", 2050.0)
        )
        samples.map { (ticker, name, base) ->
            val noise = base * (0.98 + Math.random() * 0.04)
            val candles = syntheticCandles(noise, 50)
            MarketQuote(
                symbol = Symbol(ticker, name, MarketType.IRAN_STOCK, noise),
                price = noise,
                changePercent24h = ((noise - base) / base) * 100,
                volume24h = 1_000_000 + Math.random() * 5_000_000,
                candles = candles
            )
        }
    }

    private fun fetchBinancePrice(symbol: String): Double? {
        val req = Request.Builder()
            .url("https://api.binance.com/api/v3/ticker/price?symbol=$symbol")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val parsed = json.decodeFromString<BinancePrice>(body)
            return parsed.price.toDoubleOrNull()
        }
    }

    private fun fetchBinanceKlines(symbol: String, limit: Int = 50): List<Candle> {
        val req = Request.Builder()
            .url("https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1h&limit=$limit")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                // klines: array of arrays
                val raw = json.decodeFromString<List<List<String>>>(body)
                raw.mapNotNull { row ->
                    if (row.size < 6) return@mapNotNull null
                    Candle(
                        open = row[1].toDoubleOrNull() ?: return@mapNotNull null,
                        high = row[2].toDoubleOrNull() ?: return@mapNotNull null,
                        low = row[3].toDoubleOrNull() ?: return@mapNotNull null,
                        close = row[4].toDoubleOrNull() ?: return@mapNotNull null,
                        volume = row[5].toDoubleOrNull() ?: 0.0,
                        timestamp = row[0].toLongOrNull() ?: 0L
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeCryptoSymbol(ticker: String): String {
        val t = ticker.uppercase().replace("/", "").replace("-", "")
        return if (t.endsWith("USDT")) t else "${t}USDT"
    }

    private fun syntheticCandles(lastPrice: Double, n: Int): List<Candle> {
        val list = mutableListOf<Candle>()
        var p = lastPrice * 0.97
        val now = System.currentTimeMillis()
        for (i in 0 until n) {
            val open = p
            val close = open * (0.995 + Math.random() * 0.01)
            val high = maxOf(open, close) * (1 + Math.random() * 0.005)
            val low = minOf(open, close) * (1 - Math.random() * 0.005)
            list.add(Candle(open, high, low, close, 1000 + Math.random() * 5000, now - (n - i) * 3600_000))
            p = close
        }
        return list
    }

    @Serializable
    private data class BinancePrice(
        val symbol: String,
        val price: String
    )
}
