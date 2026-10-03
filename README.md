# 🌌 AURA-Agent

**هسته هوش مصنوعی اختصاصی و قابل‌کنترل**

> *"At your service."*

**نسخه: 3.0.0-core**

---

## اصول

| اصل | وضعیت |
|-----|--------|
| 🔒 بدون وابستگی اجباری به API خارجی | ✅ Local Core |
| 📡 آنلاین + آفلاین | ✅ |
| 💾 حافظه بلندمدت + پایگاه دانش | ✅ |
| 📈 یادگیری و بازخورد | ✅ |
| 📱 اندروید + وب (iOS در نقشه راه) | ✅ / 🗺️ |
| ⚙️ تشخیص سخت‌افزار و مسیریابی اجرا | ✅ |

جزئیات: [`docs/core-architecture.md`](docs/core-architecture.md)

---

## دستورات هسته

- `وضعیت هسته` — Local / OnDevice / Remote
- `سخت‌افزار` — RAM، CPU، شبکه
- `فقط محلی` / `حالت ترکیبی` / `ترجیح ابری`

---

## اجرا

```bash
# وب
cd web && python -m http.server 8080
```

`local.properties` (اختیاری برای Remote):
```properties
AURA_API_KEY=...
AURA_MODEL=grok-4.7
```

بدون کلید هم هسته محلی کار می‌کند.

---

*"At your service." — AURA*
