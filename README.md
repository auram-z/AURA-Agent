# 🌌 AURA-Agent

**هسته فوق‌هوشمند مستقل و قابل‌کنترل**

> *"At your service."*

**نسخه: 3.2.0-super-independent**

---

## اصول

| اصل | وضعیت |
|-----|--------|
| 🔒 بدون وابستگی اجباری به API خارجی | ✅ Local Core |
| 🧠 استدلال با سطح اطمینان + تشخیص تناقض | ✅ |
| 📡 آنلاین + آفلاین | ✅ |
| 💾 حافظه بلندمدت + پایگاه دانش | ✅ Knowledge 2.0 |
| 📈 یادگیری و بازخورد | ✅ |
| 🌐 چندزبانه + ارزیابی اعتبار | ✅ |
| 🔄 یادگیری مستمر ۲۴ ساعته | ✅ ContinuousLearner |
| 🔗 همگام با نسخه آنلاین | ✅ KnowledgeSync |
| 📱 اندروید + وب | ✅ |
| ⚙️ تشخیص سخت‌افزار و مسیریابی اجرا | ✅ |
| 🔐 کلید API فقط در SecureStore (نه داخل APK) | ✅ |
| 🧪 تست واحد پایه | ✅ |

جزئیات هسته: [`docs/core-architecture.md`](docs/core-architecture.md)  
جزئیات دانش: [`docs/knowledge-architecture.md`](docs/knowledge-architecture.md)  
همگام‌سازی آنلاین: [`docs/online-sync-architecture.md`](docs/online-sync-architecture.md)

---

## هسته فوق‌هوشمند مستقل

- پاسخ آفلاین از حافظه + دانش با **اعلام confidence**
- ترکیب چند منبع دانش
- تشخیص تناقض بین ادعاها
- در حالت Hybrid: اگر اطمینان محلی کم باشد → لایه ابری (اختیاری)
- نسخه وب: جستجوی اینترنت آزاد + بسته دانش برای گوشی

---

## امنیت کلید API

کلید API **هرگز** داخل APK پخته نمی‌شود.

- اندروید: فقط از `SecureStore` (EncryptedSharedPreferences + Android Keystore) در زمان اجرا.
- وب: در `localStorage` مرورگر (فقط روی دستگاه خودتان).
- اگر Keystore دستگاه پاک شود، اسرار غیرقابل بازیابی‌اند (رفتار عمدی).

---

## دستورات هسته

- `وضعیت هسته` — Local / OnDevice / Remote + تعداد دانش
- `سخت‌افزار` — RAM، CPU، شبکه
- `فقط محلی` / `حالت ترکیبی` / `ترجیح ابری`
- `وضعیت دانش` / `تناقض`

---

## بیلد و Gradle Wrapper

اگر `gradle/wrapper/gradle-wrapper.jar` وجود نداشت:

```bash
# یک‌بار روی ماشین توسعه (نیاز به Gradle سیستمی یا Android Studio)
gradle wrapper --gradle-version 8.7
# یا از Android Studio: File → Settings → Build → Gradle → استفاده از wrapper
```

سپس:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

CI خودش Gradle 8.7 را نصب می‌کند و نیازی به jar در مخزن ندارد.

`local.properties` (اختیاری — فقط پیش‌فرض مدل/آدرس، **نه کلید**):

```properties
AURA_BASE_URL=https://api.x.ai/v1
AURA_MODEL=grok-4.7
```

بدون کلید هم هسته محلی کامل کار می‌کند. کلید را از داخل اپ وارد کنید.

```bash
# وب
cd web && python -m http.server 8080
```

---

## لایسنس

MIT — فایل [`LICENSE`](LICENSE)

---

*"At your service." — AURA Super Independent Core*
