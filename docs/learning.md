# AURA Learning System — v2.4

## وضعیت فازها

| فاز | کار | وضعیت |
|-----|-----|--------|
| ۱ | طراحی + اسکلت | ✅ |
| ۲ | ConfirmationGate + UI | ✅ |
| ۳ | ViewModel + ذخیره واقعی | ✅ |
| ۴ | Feedback UI (👍👎) | ✅ |
| ۵ | Knowledge extraction با LLM | ✅ |
| ۶ | Offline queue با WorkManager | ✅ |
| ۷ | Training export JSONL | ✅ |

## نحوه استفاده

### حافظه
- «یادت باشه …» یا «اسم من …» → دیالوگ تأیید

### بازخورد (فاز ۴)
- روی هر پاسخ AURA دکمه‌های 👍 و 👎
- بدون دیالوگ؛ مستقیم در FeedbackStore

### دانش (فاز ۵)
- برای پیام‌های بلندتر، LLM نکات پایدار را استخراج می‌کند
- ذخیره فقط با تأیید شما

### صف آفلاین (فاز ۶)
- هر ۶ ساعت WorkManager صف را پردازش می‌کند
- دستور: «پردازش صف آفلاین»

### آموزش مدل (فاز ۷)
- دستور: «آماده‌سازی آموزش» یا «export training»
- خروجی: `filesDir/training/aura_finetune_*.jsonl`

## فایل‌های کلیدی

```
learning/
├── ConfirmationGate.kt
├── LearningManager.kt
├── FeedbackStore.kt
├── KnowledgeStore.kt
├── KnowledgeExtractor.kt   ← فاز ۵
├── OfflineLearningQueue.kt
├── OfflineLearningWorker.kt ← فاز ۶
└── TrainingPipeline.kt      ← فاز ۷ JSONL
```

*At your service.*
