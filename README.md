# 🌌 AURA-Agent

**هسته فوق‌هوشمند مستقل و قابل‌کنترل**

> *"At your service."*

**نسخه: 3.2.1-super-independent**

---

## 📦 نصب (سریع‌ترین روش)

### روش ۱ — دانلود APK آماده (بدون نیاز به بیلد)

1. به صفحه [Actions](https://github.com/auram-z/AURA-Agent/actions) بروید.
2. آخرین workflow موفق (**Android Build**) را باز کنید.
3. در بخش **Artifacts** فایل `aura-debug-apk` را دانلود کنید.
4. فایل `.apk` را روی گوشی اندروید نصب کنید (ممکن است نیاز به فعال‌سازی «نصب از منابع ناشناس» باشد).

> هسته محلی **بدون کلید API** کامل کار می‌کند. کلید را بعداً از داخل اپ می‌توانید وارد کنید.

### روش ۲ — بیلد محلی با Android Studio

1. پروژه را کلون کنید:
   ```bash
   git clone https://github.com/auram-z/AURA-Agent.git
   cd AURA-Agent
   ```
2. پروژه را در **Android Studio** (نسخه‌های جدید) باز کنید.
3. صبر کنید Gradle sync کامل شود.
4. از منوی **Build → Build Bundle(s) / APK(s) → Build APK(s)** یا دکمه Run را بزنید.
5. APK در مسیر زیر ساخته می‌شود:
   `app/build/outputs/apk/debug/app-debug.apk`

### روش ۳ — خط فرمان

```bash
# اگر gradle-wrapper.jar ندارید (یک‌بار):
gradle wrapper --gradle-version 8.7

# بیلد
./gradlew :app:assembleDebug

# تست
./gradlew :app:testDebugUnitTest
```

فایل `local.properties` (اختیاری — فقط پیش‌فرض مدل/آدرس، **نه کلید**):

```properties
AURA_BASE_URL=https://api.x.ai/v1
AURA_MODEL=grok-4.7
```

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
| 🛠️ خودترمیمی (CoreSelfHealer) | ✅ |

جزئیات هسته: [`docs/core-architecture.md`](docs/core-architecture.md)  
جزئیات دانش: [`docs/knowledge-architecture.md`](docs/knowledge-architecture.md)  
همگام‌سازی آنلاین: [`docs/online-sync-architecture.md`](docs/online-sync-architecture.md)  
خودتکاملی: [`docs/self-evolution.md`](docs/self-evolution.md)

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
- `وضعیت تکامل` / `تکامل کن` / `Rollback`

---

## نسخه وب

```bash
cd web && python -m http.server 8080
```

سپس در مرورگر: http://localhost:8080

---

## لایسنس

MIT — فایل [`LICENSE`](LICENSE)

---

*"At your service." — AURA Super Independent Core*
