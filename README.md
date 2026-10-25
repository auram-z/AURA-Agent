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

## دستورات هسته

- `وضعیت هسته` — Local / OnDevice / Remote + تعداد دانش
- `سخت‌افزار` — RAM، CPU، شبکه
- `فقط محلی` / `حالت ترکیبی` / `ترجیح ابری`
- `وضعیت دانش` / `تناقض`

---

## اجرا

```bash
# وب (جستجوی آزاد + همگام‌سازی)
cd web && python -m http.server 8080
```

`local.properties` (اختیاری برای Remote):
```properties
AURA_API_KEY=...
AURA_MODEL=grok-4.7
```

بدون کلید هم هسته محلی کامل کار می‌کند.

---

*"At your service." — AURA Super Independent Core*
