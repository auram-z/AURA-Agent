# Self-Evolution Engine — طراحی و وضعیت پیاده‌سازی

**نسخه طراحی:** 1.0  
**وضعیت کد:** ✅ E0–E6 پیاده‌سازی‌شده (اکتبر ۲۰۲۶)

> *"At your service." — AURA*

---

## وضعیت فازها

| فاز | تحویل | وضعیت |
|-----|--------|--------|
| **E0** | Models + Config + SafetyPolicy + SnapshotManager | ✅ |
| **E1** | CapabilityDetector + Planner + Sandbox | ✅ |
| **E2** | CodeGenerator | ✅ |
| **E3** | BuildRunner + TestRunner | ✅ |
| **E4** | VersionComparator + DeployGate + Rollback | ✅ |
| **E5** | KnowledgeUpdater + PromptEvolver + دستورات UI | ✅ |
| **E6** | auto_evolution برای LOW + maybeAutoEvolve | ✅ |

---

## اصول طراحی

| اصل | الزام |
|-----|--------|
| 🔒 بدون وابستگی اجباری | حلقه در LOCAL_ONLY هم کار می‌کند |
| 📡 آنلاین + آفلاین | کشف/یادگیری آنلاین؛ Build/Test/Rollback روی دستگاه |
| 🛡️ ایمنی اول | Snapshot اجباری؛ مسیرهای security ممنوع |
| 📦 ایزوله | تغییرات در `filesDir/evolution/workspace` |
| 🔁 قابل‌بازگشت | Rollback تا N نسخه |

---

## ماشین حالت

```
IDLE → DETECT → PLAN → CODE → BUILD → TEST → COMPARE → DEPLOY|ROLLBACK → LEARN → IDLE
```

---

## پکیج `com.aura.agent.evolution`

| فایل | نقش |
|------|------|
| `EvolutionModels.kt` | enum / data class |
| `EvolutionConfig.kt` | DataStore تنظیمات |
| `SafetyPolicy.kt` | قوانین قرمز |
| `SnapshotManager.kt` | zip snapshot |
| `ProjectSandbox.kt` | workspace ایزوله |
| `CapabilityDetector.kt` | تشخیص قابلیت |
| `EvolutionPlanner.kt` | طرح تغییر |
| `CodeGenerator.kt` | اسکلت Kotlin |
| `BuildRunner.kt` | اعتبارسنجی ساختاری |
| `TestRunner.kt` | smoke / structural |
| `VersionComparator.kt` | مقایسه متریک |
| `DeployGate.kt` | deploy + rollback |
| `KnowledgeUpdater.kt` | ثبت در KnowledgeStore |
| `PromptEvolver.kt` | بهبود notes پرامپت |
| `SelfEvolutionEngine.kt` | Orchestrator |

---

## دستورات چت (AuraViewModel)

| دستور | عمل |
|--------|------|
| `وضعیت تکامل` | فاز + config |
| `قابلیت‌های جدید` | detectOnly |
| `شروع تکامل` / `تکامل کن` | runCycle کامل |
| `تکامل خودکار روشن/خاموش` | E6 auto |
| `Rollback` / `Rollback به TAG` | بازگشت نسخه |
| `لیست نسخه‌ها` | VersionRecordها |

---

## محدودیت صادقانه

- Build روی دستگاه = اعتبارسنجی ساختاری (نه Gradle کامل)؛ CI در نقشه راه
- CodeGenerator templateمحور است (نه LLM کامل)
- `sandbox_only=true` پیش‌فرض: فایل‌ها در workspace دستگاه می‌مانند
- اعمال خودکار روی سورس APK نیازمند rebuild دستی / CI است

---

*At your service.*
