# AURA Learning System — طراحی کامل

> نسخه: 1.0  
> تاریخ: ۱۴۰۴/۰۷/۱۱ (۳ اکتبر ۲۰۲۶)  
> وضعیت: اسکلت پیاده‌سازی + طراحی کامل + یکپارچه‌سازی با ViewModel و UI

---

## ۱. اهداف

AURA باید بتواند **یاد بگیرد** از:

| نوع یادگیری | توضیح | اولویت |
|-------------|--------|--------|
| **حافظه (Memory)** | ذخیره حقایق پایدار کاربر (نام، ترجیحات، یادداشت‌ها، اهداف) | بالا |
| **بازخورد (Feedback)** | یادگیری از like/dislike، correction، rating صریح و ضمنی | بالا |
| **دانش (Knowledge)** | استخراج و نگهداری دانش ساختاریافته + RAG ساده | متوسط |
| **آموزش مدل (Model Training)** | fine-tuning / continual learning آفلاین (در آینده) | پایین (اسکلت) |

### اصول کلیدی
- **تأیید صریح کاربر** برای ذخیره هر اطلاعات مهم (Confirmation Gate)
- پشتیبانی همزمان **آنلاین** (real-time) و **آفلاین** (batch queue)
- امنیت و حریم خصوصی: داده فقط روی دستگاه (یا با رمزنگاری)
- امکان حذف / ویرایش کامل توسط کاربر

---

## ۲. معماری کلی

```
┌─────────────────────────────────────────────────────────────┐
│                        AuraViewModel                         │
│  (نقطه ورود — تشخیص نیاز به یادگیری + فراخوانی LearningManager) │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                     LearningManager                          │
│  - تصمیم‌گیری: آیا این پیام ارزش ذخیره دارد؟                 │
│  - فراخوانی ConfirmationGate                                 │
│  - توزیع به Memory / Feedback / Knowledge                    │
│  - صف‌بندی برای offline                                      │
└──────┬──────────────┬──────────────┬──────────────┬─────────┘
       │              │              │              │
       ▼              ▼              ▼              ▼
┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────────┐
│MemoryStore │ │FeedbackStore│ │KnowledgeStore│ │OfflineQueue   │
│ (DataStore │ │ (DataStore  │ │ (Room +    │ │ (WorkManager / │
│  + Confirm)│ │  + ratings) │ │  embeddings)│ │  pending jobs) │
└────────────┘ └────────────┘ └────────────┘ └────────────────┘
       │              │              │              │
       └──────────────┴──────────────┴──────────────┘
                              │
                              ▼
                    ┌──────────────────┐
                    │ TrainingPipeline │  (آفلاین — اسکلت)
                    │ fine-tune / LoRA │
                    └──────────────────┘
```

---

## ۳. کنترل حافظه — Confirmation Gate (الزامی)

هر ذخیره **مهم** باید از این مسیر بگذرد:

1. سیستم پیشنهاد می‌دهد: «آیا می‌خواهید این را به خاطر بسپارم؟»
2. خلاصه اطلاعات نمایش داده می‌شود.
3. کاربر یکی از گزینه‌ها را انتخاب می‌کند:
   - ✅ تأیید و ذخیره
   - ✏️ ویرایش و سپس ذخیره
   - ❌ رد (ذخیره نشود)
4. فقط پس از تأیید صریح، داده نوشته می‌شود.

**اطلاعاتی که نیاز به تأیید دارند:**
- نام کاربر، روابط خانوادگی، محل زندگی
- ترجیحات مهم (غذا، زبان، سبک پاسخ)
- اهداف بلندمدت، برنامه‌های آینده
- هر یادداشت شخصی که کاربر صریحاً گفته «یادت باشه»

**اطلاعاتی که بدون تأیید ذخیره می‌شوند (موقت):**
- تاریخچه چت جلسه جاری (تا ۶۰ پیام)
- وضعیت شبکه، وضعیت UI

---

## ۴. اجزای سیستم

### ۴.۱ MemoryStore (ارتقا یافته)
- کلیدها: `user_name`, `preferences`, `notes`, `goals`, `facts`
- همه نوشته‌ها از طریق `ConfirmationGate`
- پشتیبانی از حذف کامل و export

### ۴.۲ FeedbackStore
- ذخیره rating (1–5 یا thumbs)
- correction text (کاربر گفته «نه، درستش اینه...»)
- implicit signals (زمان ماندن روی پاسخ، تکرار سؤال)

### ۴.۳ KnowledgeStore
- استخراج دانش از مکالمه با prompt مخصوص
- ذخیره به صورت key-value یا chunkهای متنی
- در آینده: embedding + جستجوی معنایی ساده (on-device یا با API)

### ۴.۴ OfflineLearningQueue
- وقتی آفلاین هستیم یا کاربر بعداً تأیید می‌کند، job در صف قرار می‌گیرد.
- با WorkManager (اندروید) یا localStorage + retry (وب) پردازش می‌شود.

### ۴.۵ TrainingPipeline (اسکلت)
- جمع‌آوری داده‌های تأییدشده
- آماده‌سازی dataset برای fine-tuning
- در حال حاضر فقط logging و export — اجرای واقعی fine-tune در نسخه بعدی

---

## ۵. جریان آنلاین vs آفلاین

| حالت | رفتار |
|------|--------|
| **آنلاین** | Confirmation فوری در UI → ذخیره فوری در Memory/Feedback/Knowledge |
| **آفلاین** | پیشنهاد ذخیره در صف offline → وقتی آنلاین شد + کاربر تأیید کرد، اعمال می‌شود |
| **Batch** | هر شب یا دستی: consolidation حافظه + آماده‌سازی داده training |

---

## ۶. نقاط یکپارچه‌سازی در کد فعلی

- `AuraViewModel.sendMessage()` → بعد از پاسخ مدل، `LearningManager.analyzeAndPropose(...)` فراخوانی می‌شود.
- UI دارای AlertDialog برای Confirmation است.
- `clearConversation()` حافظه بلندمدت را پاک نمی‌کند (فقط تاریخچه جلسه).

---

## ۷. فایل‌های پیاده‌سازی‌شده

```
app/src/main/java/com/aura/agent/learning/
├── ConfirmationGate.kt
├── LearningManager.kt
├── FeedbackStore.kt
├── KnowledgeStore.kt
├── OfflineLearningQueue.kt
└── TrainingPipeline.kt

app/src/main/java/com/aura/agent/data/
└── MemoryStore.kt          ← ارتقا یافته (با confirmation)

app/src/main/java/com/aura/agent/ai/
└── AuraViewModel.kt        ← یکپارچه‌سازی کامل

app/src/main/java/com/aura/agent/
└── MainActivity.kt         ← دیالوگ تأیید
```

---

## ۸. نقشه راه

| فاز | کار | وضعیت |
|-----|-----|--------|
| ۱ | طراحی + اسکلت کلاس‌ها | ✅ |
| ۲ | ConfirmationGate + UI dialog | ✅ |
| ۳ | اتصال کامل به ViewModel + ذخیره واقعی | ✅ |
| ۴ | Feedback UI (thumbs up/down) | بعدی |
| ۵ | Knowledge extraction با LLM | بعدی |
| ۶ | Offline queue با WorkManager | بعدی |
| ۷ | Training export + fine-tune | آینده |

---

*"At your service." — AURA Learning System*
