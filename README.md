# 🌌 AURA-Agent

**دستیار هوشمند جارویس‌وار — اندروید + وب**

> *"At your service."*

---

## نسخه‌ها

| نسخه | مسیر |
|--------|--------|
| اندروید | پروژه Kotlin/Compose |
| وب | [`web/`](web/) |

**نسخه فعلی: 2.2 — گفتار بدون محدودیت + شبکه جهانی**

---

## قابلیت‌های جدید (v2.2)

### 🎙️ گفتار بدون محدودیت
- **شنیدن مداوم (Continuous Listening)** — بدون محدودیت زمانی
- **TTS کامل** — پاسخ‌های بلند به صورت chunk پخش می‌شوند (بدون سقف ۳۰۰ کاراکتر)
- دستورات:
  - «شنیدن مداوم» / «گفتار مداوم» / «صدا بدون محدودیت»
  - «توقف شنیدن»
- وب: دابل‌کلیک روی 🎙️ = شنیدن مداوم

### 🌐 اتصال به شبکه سراسری اینترنت جهانی
- تایم‌اوت‌های طولانی برای APIهای جهانی (xAI، Binance)
- Retry خودکار روی قطعی موقت
- ابزار «وضعیت شبکه / اینترنت»
- تشخیص آنلاین/آفلاین
- فقط HTTPS (امن)

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
