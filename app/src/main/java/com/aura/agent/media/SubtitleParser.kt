package com.aura.agent.media

/**
 * پارس فرمت‌های رایج زیرنویس: SRT و VTT ساده.
 */
object SubtitleParser {

    fun parse(raw: String): List<SubtitleBlock> {
        val trimmed = raw.trim()
        return when {
            trimmed.contains("-->") && (trimmed.startsWith("WEBVTT") || trimmed.contains(".")) ->
                parseVtt(trimmed)
            trimmed.contains("-->") -> parseSrt(trimmed)
            else -> listOf(SubtitleBlock(1, 0, 0, trimmed))
        }
    }

    fun toSrt(blocks: List<SubtitleBlock>): String = buildString {
        blocks.forEach { b ->
            append(b.index).append('\n')
            append(formatTs(b.startMs)).append(" --> ").append(formatTs(b.endMs)).append('\n')
            append(b.text).append("\n\n")
        }
    }

    private fun parseSrt(raw: String): List<SubtitleBlock> {
        val blocks = mutableListOf<SubtitleBlock>()
        val parts = raw.replace("\r\n", "\n").split("\n\n")
        var autoIdx = 1
        for (part in parts) {
            val lines = part.trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val idxLine = lines.first()
            val idx = idxLine.toIntOrNull()
            val timeLineIdx = if (idx != null) 1 else 0
            if (lines.size <= timeLineIdx) continue
            val timeLine = lines[timeLineIdx]
            val times = parseTimeLine(timeLine)
            val text = lines.drop(timeLineIdx + 1).joinToString(" ")
            if (text.isBlank()) continue
            blocks.add(
                SubtitleBlock(
                    index = idx ?: autoIdx++,
                    startMs = times.first,
                    endMs = times.second,
                    text = text
                )
            )
        }
        return blocks
    }

    private fun parseVtt(raw: String): List<SubtitleBlock> {
        val withoutHeader = raw.lineSequence()
            .filterNot { it.startsWith("WEBVTT") || it.startsWith("NOTE") || it.startsWith("Style") }
            .joinToString("\n")
        return parseSrt(withoutHeader.replace('.', ','))
    }

    private fun parseTimeLine(line: String): Pair<Long, Long> {
        val parts = line.split("-->").map { it.trim().substringBefore(' ') }
        if (parts.size < 2) return 0L to 0L
        return parseTs(parts[0]) to parseTs(parts[1])
    }

    private fun parseTs(ts: String): Long {
        // 00:00:01,000 or 00:00:01.000
        val norm = ts.replace(',', '.').trim()
        val segs = norm.split(':')
        return try {
            when (segs.size) {
                3 -> {
                    val h = segs[0].toLong()
                    val m = segs[1].toLong()
                    val secParts = segs[2].split('.')
                    val s = secParts[0].toLong()
                    val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
                    ((h * 3600 + m * 60 + s) * 1000) + ms
                }
                2 -> {
                    val m = segs[0].toLong()
                    val secParts = segs[1].split('.')
                    val s = secParts[0].toLong()
                    val ms = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
                    ((m * 60 + s) * 1000) + ms
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatTs(ms: Long): String {
        val totalSec = ms / 1000
        val milli = (ms % 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }
}
