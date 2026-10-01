# 🌌 AURA-Agent

**دستیار هوشمند شخصی همه‌کاره — الهام‌گرفته از جارویس (Jarvis)**

> *"At your service."*

AURA یک ایجنت هوشمند کامل روی اندروید است.

---

## ✨ ویژگی‌ها (همه فازها)

### فاز ۱ — هسته
- ✅ رابط چت مدرن هولوگرافیک
- ✅ هسته AI چندمدله (Grok / OpenAI / Gemini)
- ✅ شخصیت جارویس
- ✅ حافظه مکالمه

### فاز ۲ — صدا
- ✅ Speech-to-Text (تشخیص گفتار)
- ✅ Text-to-Speech (پاسخ صوتی)
- ✅ دکمه صدا در رابط

### فاز ۳ — ابزارها و حافظه
- ✅ سیستم Tool Calling
- ✅ ابزارهای دستگاه (باتری، زمان، اپ‌ها)
- ✅ حافظه بلندمدت (آماده)
- ✅ معماری قابل توسعه برای MCP

---

## 🚀 راه‌اندازی

```bash
git clone https://github.com/auram-z/AURA-Agent.git
cd AURA-Agent
```

فایل `local.properties`:

```properties
AURA_API_KEY=your_key
AURA_BASE_URL=https://api.x.ai/v1
AURA_MODEL=grok-beta
```

مجوزهای مورد نیاز: `RECORD_AUDIO`

---

## 📁 ساختار

```
com.aura.agent/
├── MainActivity.kt
├── ai/
│   ├── AuraAiService.kt
│   ├── AuraViewModel.kt
│   └── tools/          # سیستم ابزارها
├── voice/             # STT + TTS
├── data/
└── ui/theme/
```

---

*"At your service." — AURA*
