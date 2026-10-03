# 🌌 AURA-Agent

**دستیار هوشمند جارویس‌وار — اندروید + وب**

> *"At your service."*

---

## نسخه‌ها

| نسخه | مسیر |
|--------|--------|
| اندروید | پروژه Kotlin/Compose |
| وب | [`web/`](web/) |

**نسخه فعلی: 2.3.0-learning — سیستم یادگیری کامل**

---

## قابلیت‌های جدید (v2.3) — سیستم یادگیری

| نوع | وضعیت | توضیح |
|-----|--------|--------|
| **حافظه (Memory)** | ✅ + Confirmation | ذخیره با تأیید صریح کاربر |
| **بازخورد (Feedback)** | ✅ | rating، correction |
| **دانش (Knowledge)** | ✅ | استخراج و نگهداری دانش |
| **آموزش مدل** | ✅ اسکلت | pipeline آفلاین برای fine-tune |

- **آنلاین + آفلاین**: صف `OfflineLearningQueue`
- **کنترل حافظه**: هیچ اطلاعات مهمی بدون تأیید شما ذخیره نمی‌شود

جزئیات: [`docs/learning.md`](docs/learning.md)

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
- شنیدن مداوم + TTS کامل

### 🌐 شبکه جهانی
- تایم‌اوت طولانی + Retry + فقط HTTPS

---

## امنیت

جزئیات: [SECURITY.md](SECURITY.md)

---

## اجرا

**وب:** `cd web && python -m http.server 8080`

**اندروید** — `local.properties`:
```properties
AURA_API_KEY=...
AURA_BASE_URL=https://api.x.ai/v1
AURA_MODEL=grok-beta
```

> معامله واقعی (LIVE) خاموش است — فقط Paper Trading.

---

*"At your service." — AURA*
