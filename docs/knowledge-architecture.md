# معماری دانش AURA (Knowledge 2.0)

نسخه: **2.0** — ۲۵ اکتبر ۲۰۲۶

## هدف

تبدیل AURA از یک چت‌بات با حافظه ساده به یک **هسته دانش مستقل، چندزبانه، خودیادگیرنده و قابل‌اعتماد** که هم آنلاین و هم آفلاین کار می‌کند.

---

## اصول طراحی

| اصل | توضیح |
|-----|--------|
| **منبع‌محور** | هر دانش باید منبع، URL و تاریخ دریافت داشته باشد |
| **سطح اطمینان** | هر تکه دانش `confidence` (۰–۱) دارد |
| **چندزبانه** | پشتیبانی از فارسی، انگلیسی و زبان‌های دیگر |
| **یادگیری هوشمند** | به‌جای کراول بی‌هدف، موضوعات مرتبط به‌روز می‌شوند |
| **تشخیص تناقض** | مقایسه ادعاها و کاهش اطمینان در صورت تضاد |
| **آفلاین اول** | دانش روی دستگاه ذخیره می‌شود و بدون اینترنت قابل استفاده است |
| **ارتقای کنترل‌شده** | تغییرات روی نسخه آزمایشی تست می‌شوند |

---

## ساختار KnowledgeChunk

```kotlin
data class KnowledgeChunk(
    val id: String,
    val title: String,
    val content: String,
    val source: String,          // conversation | wikipedia | duckduckgo | ...
    val sourceUrl: String,
    val language: String,        // fa | en | ar | ...
    val tags: List<String>,
    val confidence: Float,       // 0.0 – 1.0
    val claims: List<Claim>,     // ادعاهای استخراج‌شده
    val timestamp: Long,
    val lastVerifiedAt: Long,
    val receivedAt: Long
)
```

---

## اجزای اصلی

### 1. KnowledgeStore
- ذخیره و بازیابی روی DataStore
- جستجوی ترکیبی (کلمه کلیدی + تازگی + اطمینان)
- پشتیبانی از زبان و به‌روزرسانی confidence

### 2. CredibilityScorer
- امتیازدهی بر اساس دامنه منبع (wikipedia, arxiv, gov, …)
- تازگی اطلاعات (نیمه‌عمر تقریبی ۹۰ روز)
- تقویت با تعداد تأییدهای مستقل

### 3. ContradictionDetector
- مقایسه ادعاها بین chunkها
- تشخیص نفی / عدم قطعیت
- کاهش confidence در صورت تناقض شدید

### 4. WebSearchService (ارتقا یافته)
- DuckDuckGo + Wikipedia چندزبانه (fa / en / ar)
- تشخیص ساده زبان
- تجمیع و حذف تکراری

### 5. ContinuousLearner
- Worker دوره‌ای (پیش‌فرض ۱۲ ساعت)
- انتخاب موضوعات کم‌اطمینان یا قدیمی
- جستجو → امتیازدهی → ذخیره فقط منابع معتبر
- اجرای تحلیل تناقض پس از هر چرخه

### 6. LearningManager + Offline Queue
- یادگیری از مکالمه و بازخورد کاربر
- صف آفلاین برای زمانی که شبکه قطع است

---

## جریان داده

```
کاربر / جستجو / ContinuousLearner
        ↓
   WebSearchService / Extractor
        ↓
   CredibilityScorer  →  confidence
        ↓
   KnowledgeStore.add()
        ↓
   ContradictionDetector (دوره‌ای)
        ↓
   کاهش confidence در صورت تضاد
        ↓
   استفاده در buildLearnedContext()
```

---

## نقشه راه بعدی

- [ ] Semantic Index محلی (embedding سبک یا TF-IDF پیشرفته)
- [ ] منبع اخبار و مستندات فنی بیشتر
- [ ] پنل UI برای مشاهده confidence و تناقض‌ها
- [ ] همگام‌سازی اختیاری با فضای ذخیره‌سازی تحت کنترل پروژه
- [ ] آزمایش ارتقا روی نسخه آزمایشی قبل از Deploy

---

*At your service. — AURA*
