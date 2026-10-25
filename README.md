# 🌌 AURA-Agent

**هسته هوش مصنوعی اختصاصی و قابل‌کنترل**

> *"At your service."*

**نسخه: 3.1.0-knowledge**

---

## اصول

| اصل | وضعیت |
|-----|--------|
| 🔒 بدون وابستگی اجباری به API خارجی | ✅ Local Core |
| 📡 آنلاین + آفلاین | ✅ |
| 💾 حافظه بلندمدت + پایگاه دانش | ✅ Knowledge 2.0 |
| 📈 یادگیری و بازخورد | ✅ |
| 🌐 چندزبانه + ارزیابی اعتبار | ✅ |
| 🔄 یادگیری مستمر هوشمند | ✅ ContinuousLearner |
| 📱 اندروید + وب (iOS در نقشه راه) | ✅ / 🗺️ |
| ⚙️ تشخیص سخت‌افزار و مسیریابی اجرا | ✅ |

جزئیات هسته: [`docs/core-architecture.md`](docs/core-architecture.md)  
جزئیات دانش: [`docs/knowledge-architecture.md`](docs/knowledge-architecture.md)

---

## Knowledge 2.0 (جدید)

- **KnowledgeChunk** با فیلدهای `confidence`، `language`، `sourceUrl`، `claims`، `lastVerifiedAt`
- **CredibilityScorer** — امتیازدهی اعتبار منبع و تازگی
- **ContradictionDetector** — تشخیص تناقض بین ادعاها
- **WebSearchService** چندزبانه (فارسی / انگلیسی / عربی)
- **ContinuousLearner** — به‌روزرسانی دوره‌ای هوشمند موضوعات مرتبط

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
