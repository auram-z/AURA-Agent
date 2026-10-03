# 🌌 AURA-Agent

**دستیار هوشمند جارویس‌وار — اندروید + وب**

> *"At your service."*

---

## نسخه‌ها

| نسخه | مسیر |
|--------|--------|
| اندروید | پروژه Kotlin/Compose |
| وب | [`web/`](web/) |

**نسخه فعلی: 2.3 — سیستم یادگیری کامل (اسکلت + طراحی)**

---

## قابلیت‌های جدید (v2.3) — سیستم یادگیری

AURA اکنون دارای معماری یادگیری است:

| نوع | وضعیت | توضیح |
|-----|--------|--------|
| **حافظه (Memory)** | اسکلت + Confirmation | ذخیره با تأیید صریح کاربر |
| **بازخورد (Feedback)** | اسکلت | rating، correction، thumbs |
| **دانش (Knowledge)** | اسکلت | استخراج و نگهداری دانش |
| **آموزش مدل** | اسکلت | pipeline آفلاین برای fine-tune |

- **آنلاین + آفلاین**: صف `OfflineLearningQueue` برای کار در حالت آفلاین
- **کنترل حافظه**: هیچ اطلاعات مهمی بدون تأیید شما ذخیره نمی‌شود (`ConfirmationGate`)

جزئیات کامل طراحی: [`docs/learning.md`](docs/learning.md)

کلاس‌های جدید:
```
app/.../learning/
├── ConfirmationGate.kt
├── LearningManager.kt
├── FeedbackStore.kt
├── KnowledgeStore.kt
├── OfflineLearningQueue.kt
└── TrainingPipeline.kt
```

---

## قابلیت‌های v2.2

### 🎙️ گفتار بدون محدودیت
- **شنیدن مداوم (Continuous Listening)** — بدون محدودیت زمانی
- **TTS کامل** — پاسخ‌های بلند به صورت chunk پخش می‌شوند
- دستورات: «شنیدن مداوم» / «توقف شنیدن»

### 🌐 اتصال به شبکه سراسری اینترنت جهانی
- تایم‌اوت‌های طولانی + Retry خودکار
- ابزار «وضعیت شبکه / اینترنت»
- فقط HTTPS

---

## امنیت (v2.1+)

- `.gitignore` قوی برای secretها و APK
- اندروید: `usesCleartextTraffic=false`، `allowBackup=false`، Keystore/`SecureStore`
- وب: CSP + اجبار HTTPS + پاک‌سازی کامل داده
- جزئیات: [SECURITY.md](SECURITY.md)

---

## اجرای وب

```bash
cd web && python -m http.server 8080
```

## اجرای اندروید

`local.properties` (commit نشود):

```properties
AURA_API_KEY=...
AURA_BASE_URL=https://api.x.ai/v1
AURA_MODEL=grok-beta
```

> معامله واقعی (LIVE) خاموش است — فقط Paper Trading.

---

*"At your service." — AURA*
