# Build و Gradle — AURA-Agent

## پیش‌نیاز توسعه محلی

- JDK **17**
- Android SDK (compileSdk 35)
- Gradle **8.7** (یا استفاده از `./gradlew`)

```bash
# یک‌بار برای ساخت wrapper کامل (jar)
gradle wrapper --gradle-version 8.7

# ساخت APK دیباگ
./gradlew :app:assembleDebug

# تست واحد
./gradlew :app:testDebugUnitTest
```

`local.properties` (gitignore):

```properties
sdk.dir=/path/to/Android/Sdk
AURA_API_KEY=
AURA_BASE_URL=https://api.x.ai/v1
AURA_MODEL=grok-4.7
```

## CI (GitHub Actions)

فایل `.github/workflows/android-build.yml` روی هر push به `main`:

1. JDK 17 + Gradle 8.7
2. Android SDK
3. `:app:assembleDebug`
4. آپلود APK به‌عنوان artifact

## Build داخل اپ (Self-Evolution)

روی دستگاه، JDK/Gradle کامل وجود ندارد. `BuildRunner` معادل خطاهای compiler را می‌گیرد:

- package ↔ path
- توازن `{}()[]`
- import و API ممنوع
- HTTP cleartext
- duplicate type names
- SafetyPolicy

خروجی موفق: `evolution/artifacts/build-report-*.txt` در sandbox.

`DeployGate` علاوه بر VersionRecord، `deploy-<tag>.txt` و در حالت غیر sandbox `apply-<tag>.list` می‌نویسد تا CI بتواند پچ‌ها را اعمال کند.

## پلاگین‌های Gradle (Kotlin 2.0)

- `org.jetbrains.kotlin.plugin.compose` (به‌جای `kotlinCompilerExtensionVersion` قدیمی)
- `org.jetbrains.kotlin.plugin.serialization`
- AGP 8.5.2

## رفع مشکل رایج

| مشکل | راه‌حل |
|------|--------|
| wrapper jar نیست | `gradle wrapper --gradle-version 8.7` |
| compose compiler | از plugin compose در Kotlin 2.0 استفاده شده |
| local.properties | فقط از `Properties` خوانده می‌شود (بدون API داخلی AGP) |
| Build روی گوشی | عمداً sandbox + compile-readiness؛ APK واقعی از CI |
