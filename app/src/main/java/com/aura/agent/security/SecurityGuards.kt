package com.aura.agent.security

object SecurityGuards {

    /** فقط HTTPS مجاز است */
    fun requireHttps(url: String): Result<String> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(SecurityException("آدرس API خالی است."))
        }
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            return Result.failure(
                SecurityException("فقط HTTPS مجاز است. آدرس ناامن رد شد.")
            )
        }
        return Result.success(trimmed.trimEnd('/'))
    }

    fun maskSecret(secret: String): String {
        if (secret.length < 8) return "****"
        return secret.take(3) + "…" + secret.takeLast(3)
    }
}
