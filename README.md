# 🌌 AURA-Agent

**دستیار هوشمند جارویس‌وار — اندروید + وب**

> *"At your service."*

---

## نسخه‌ها

| نسخه | مسیر |
|--------|--------|
| اندروید | پروژه Kotlin/Compose |
| وب | [`web/`](web/) |

## امنیت (v2.1)

- `.gitignore` قوی برای secretها و APK
- اندروید: `usesCleartextTraffic=false`، `allowBackup=false`، Keystore/`SecureStore`
- وب: CSP + اجبار HTTPS + پاک‌سازی کامل داده
- جزئیات: [SECURITY.md](SECURITY.md)

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
