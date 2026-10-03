# 🌌 AURA-Agent

**دستیار هوشمند جارویس‌وار — اندروید + وب**

> *"At your service."*

**نسخه فعلی: 2.4.0-learning**

---

## سیستم یادگیری (کامل)

| فاز | قابلیت | وضعیت |
|-----|--------|--------|
| حافظه | Confirmation قبل از ذخیره | ✅ |
| بازخورد | 👍👎 روی هر پاسخ | ✅ |
| دانش | استخراج با LLM + تأیید | ✅ |
| آفلاین | WorkManager هر ۶ ساعت | ✅ |
| آموزش | export JSONL برای fine-tune | ✅ |

جزئیات: [`docs/learning.md`](docs/learning.md)

### دستورات یادگیری
- «یادت باشه …» / «اسم من …»
- «آماده‌سازی آموزش» — export داده fine-tune
- «پردازش صف آفلاین» — اجرای فوری صف

---

## سایر قابلیت‌ها
- گفتار مداوم + TTS کامل
- Paper trading + بک‌تست
- شبکه جهانی HTTPS

## اجرا

```bash
# وب
cd web && python -m http.server 8080
```

`local.properties`:
```properties
AURA_API_KEY=...
AURA_BASE_URL=https://api.x.ai/v1
AURA_MODEL=grok-beta
```

> LIVE trading خاموش است.

---

*"At your service." — AURA*
