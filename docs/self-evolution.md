# Self-Evolution Engine — طراحی کامل

**نسخه طراحی:** 1.0  
**هدف:** حلقه خودتکامل‌دهندهٔ AURA که بدون وابستگی اجباری به API خارجی، قابلیت‌های جدید را تشخیص دهد، کد تولید/اصلاح کند، Build و تست کند، نسخه بسازد، عملکرد را مقایسه کند، ارتقا را فعال یا Rollback کند، و دانش/حافظه/pipeline را بهبود دهد.

> *"At your service." — AURA*

---

## ۱. اصول طراحی (هم‌راستا با Core 3.0)

| اصل | الزام |
|-----|--------|
| 🔒 بدون وابستگی اجباری | حلقهٔ تکامل در حالت LOCAL_ONLY هم باید بتواند (با محدودیت) کار کند |
| 📡 آنلاین + آفلاین | کشف قابلیت و یادگیری آنلاین؛ Build/Test/Rollback روی دستگاه |
| 🛡️ ایمنی اول | هیچ تغییر حساس بدون تأیید کاربر؛ snapshot اجباری قبل از هر ارتقا |
| 📦 ایزوله‌سازی | تمام تغییرات فقط در workspace سندباکس پروژه؛ نه روی دادهٔ کاربر |
| 📉 حداقل API خارجی | تولید کد ترجیحاً با OnDevice یا Local؛ Remote فقط وقتی سیاست اجازه دهد |
| 🔁 قابل‌بازگشت | هر نسخهٔ موفق یا ناموفق قابل Rollback تا N نسخهٔ قبلی |

---

## ۲. معماری کلی

```
┌──────────────────────────────────────────────────────────────────┐
│                         UI / دستورات صوتی-متنی                     │
│  «تکامل کن» · «وضعیت تکامل» · «Rollback» · «قابلیت‌های جدید»      │
└────────────────────────────┬─────────────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────────────┐
│                    SelfEvolutionEngine (Orchestrator)             │
│  state machine: IDLE → DETECT → PLAN → CODE → BUILD → TEST →     │
│                 COMPARE → DEPLOY | ROLLBACK → LEARN → IDLE       │
└───┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬─────────────┘
    │      │      │      │      │      │      │      │
    ▼      ▼      ▼      ▼      ▼      ▼      ▼      ▼
 Capability  Code   Build  Test  Version  Deploy  Knowledge  Safety
 Detector   Gen    Runner Runner Comparator Gate   Updater   Gate
```

### لایه‌بندی نسبت به هسته موجود

```
UI
 └─ AuraViewModel / دستورات
     └─ SelfEvolutionEngine          ← جدید
         ├─ از AuraCore استفاده می‌کند (برای استدلال و تولید کد)
         ├─ از LearningManager / KnowledgeStore (برای ذخیره تجربه)
         ├─ از ModelConfig / ModelAutoUpgrade (برای بهبود مدل/prompt)
         └─ از SecurityGuards + ConfirmationGate (ایمنی)
```

---

## ۳. ساختار پکیج و فایل‌ها

```
app/src/main/java/com/aura/agent/evolution/
├── SelfEvolutionEngine.kt          # Orchestrator + state machine
├── EvolutionModels.kt              # data classes, enums, events
├── EvolutionConfig.kt              # DataStore تنظیمات (auto, limits, N rollback)
├── CapabilityDetector.kt           # تشخیص نیاز/قابلیت جدید
├── EvolutionPlanner.kt             # تبدیل قابلیت → طرح تغییر (diff plan)
├── CodeGenerator.kt                # تولید/پچ Kotlin بر اساس plan
├── ProjectSandbox.kt               # کپی/ایزوله کردن workspace
├── BuildRunner.kt                  # gradle assembleDebug / unitTest
├── TestRunner.kt                   # unit + smoke + regression metrics
├── VersionComparator.kt            # مقایسه متریک‌ها با baseline
├── DeployGate.kt                   # فعال‌سازی نسخه یا Rollback
├── SnapshotManager.kt              # snapshot قبل از تغییر + بازیابی
├── KnowledgeUpdater.kt             # ثبت تجربه در KnowledgeStore
├── PromptEvolver.kt                # بهبود system prompt / pipeline
└── SafetyPolicy.kt                 # قوانین قرمز (فایل‌های ممنوع، عملیات ممنوع)
```

مستندات: همین فایل `docs/self-evolution.md`

---

## ۴. مدل داده (EvolutionModels.kt)

```kotlin
enum class EvolutionPhase {
    IDLE, DETECT, PLAN, CODE, BUILD, TEST, COMPARE, DEPLOY, ROLLBACK, LEARN, FAILED
}

enum class CapabilitySource {
    USER_REQUEST,          // دستور صریح کاربر
    FEEDBACK_PATTERN,      // تکرار thumbs-down / اصلاح
    KNOWLEDGE_GAP,         // سوالاتی که Local با confidence پایین جواب داد
    RUNTIME_ERROR,         // crash / exception گزارش‌شده
    MODEL_SIGNAL,          // مدل پیشنهاد قابلیت داد
    SCHEDULED_SCAN         // اسکن دوره‌ای
}

data class Capability(
    val id: String,
    val title: String,
    val description: String,
    val source: CapabilitySource,
    val priority: Int,                 // 1–10
    val estimatedRisk: RiskLevel,      // LOW / MEDIUM / HIGH / CRITICAL
    val suggestedModules: List<String>, // مثلاً ["learning", "voice"]
    val createdAt: Long = System.currentTimeMillis()
)

enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

data class EvolutionPlan(
    val capabilityId: String,
    val steps: List<PlanStep>,
    val targetFiles: List<String>,     // مسیرهای نسبی در sandbox
    val newFiles: List<String>,
    val testHints: List<String>,
    val requiresUserConfirm: Boolean
)

data class PlanStep(
    val order: Int,
    val action: PlanAction,            // CREATE | PATCH | DELETE | RENAME
    val path: String,
    val rationale: String
)

enum class PlanAction { CREATE, PATCH, DELETE, RENAME }

data class CodePatch(
    val path: String,
    val action: PlanAction,
    val content: String?,              // محتوای کامل برای CREATE/PATCH
    val oldContentHash: String?        // برای اطمینان از عدم تداخل
)

data class BuildResult(
    val success: Boolean,
    val durationMs: Long,
    val apkPath: String? = null,
    val logExcerpt: String,
    val errors: List<String> = emptyList()
)

data class TestResult(
    val success: Boolean,
    val durationMs: Long,
    val passed: Int,
    val failed: Int,
    val metrics: Map<String, Double>,  // latency_ms, memory_mb, ...
    val logExcerpt: String
)

data class CompareReport(
    val baselineVersion: String,
    val candidateVersion: String,
    val improved: Boolean,
    val regressions: List<String>,
    val gains: List<String>,
    val scoreDelta: Double             // مثبت = بهتر
)

data class EvolutionRun(
    val id: String,
    val capability: Capability,
    val phase: EvolutionPhase,
    val plan: EvolutionPlan? = null,
    val patches: List<CodePatch> = emptyList(),
    val build: BuildResult? = null,
    val test: TestResult? = null,
    val compare: CompareReport? = null,
    val snapshotId: String? = null,
    val versionTag: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val message: String = ""
)

data class VersionRecord(
    val tag: String,                   // e.g. "3.0.1-evo-20261008"
    val snapshotId: String,
    val capabilityIds: List<String>,
    val metrics: Map<String, Double>,
    val createdAt: Long,
    val active: Boolean
)
```

---

## ۵. ماشین حالت (SelfEvolutionEngine)

```
                    ┌─────────┐
         ┌─────────►│  IDLE   │◄────────────────────────────┐
         │          └────┬────┘                             │
         │               │ trigger (دستور / auto / error)   │
         │               ▼                                  │
         │          ┌─────────┐                             │
         │          │ DETECT  │─── هیچ قابلیتی ─────────────┤
         │          └────┬────┘                             │
         │               │ Capability(ها)                   │
         │               ▼                                  │
         │          ┌─────────┐                             │
         │          │  PLAN   │─── رد Safety / کاربر ───────┤
         │          └────┬────┘                             │
         │               │ EvolutionPlan                    │
         │               ▼                                  │
         │          ┌─────────┐                             │
         │          │  CODE   │─── تولید ناموفق ────────────┤
         │          └────┬────┘                             │
         │               │ patches در sandbox               │
         │               ▼                                  │
         │          ┌─────────┐                             │
         │          │ BUILD   │─── fail ──► ROLLBACK ───────┤
         │          └────┬────┘                             │
         │               │                                  │
         │               ▼                                  │
         │          ┌─────────┐                             │
         │          │  TEST   │─── fail ──► ROLLBACK ───────┤
         │          └────┬────┘                             │
         │               │                                  │
         │               ▼                                  │
         │          ┌─────────┐                             │
         │          │ COMPARE │─── regression ─► ROLLBACK ──┤
         │          └────┬────┘                             │
         │               │ improved                         │
         │               ▼                                  │
         │          ┌─────────┐                             │
         │          │ DEPLOY  │                             │
         │          └────┬────┘                             │
         │               │                                  │
         │               ▼                                  │
         │          ┌─────────┐                             │
         └──────────│  LEARN  │─────────────────────────────┘
                    └─────────┘
```

### API اصلی Orchestrator

```kotlin
class SelfEvolutionEngine(
    private val core: AuraCore,
    private val learning: LearningManager,
    private val knowledge: KnowledgeStore,
    private val config: EvolutionConfig,
    private val detector: CapabilityDetector,
    private val planner: EvolutionPlanner,
    private val coder: CodeGenerator,
    private val sandbox: ProjectSandbox,
    private val buildRunner: BuildRunner,
    private val testRunner: TestRunner,
    private val comparator: VersionComparator,
    private val deployGate: DeployGate,
    private val snapshots: SnapshotManager,
    private val knowledgeUpdater: KnowledgeUpdater,
    private val promptEvolver: PromptEvolver,
    private val safety: SafetyPolicy,
    private val scope: CoroutineScope
) {
    val phase: StateFlow<EvolutionPhase>
    val currentRun: StateFlow<EvolutionRun?>
    val lastReport: StateFlow<String?>

    /** شروع یک چرخه کامل (یا ادامه از فاز مشخص) */
    suspend fun runCycle(trigger: CapabilitySource = CapabilitySource.USER_REQUEST): EvolutionRun

    /** فقط تشخیص بدون اعمال */
    suspend fun detectOnly(): List<Capability>

    /** Rollback دستی به tag مشخص یا آخرین نسخهٔ پایدار */
    suspend fun rollback(toTag: String? = null): Result<VersionRecord>

    fun cancel()
}
```

---

## ۶. جزئیات هر ماژول

### ۶.۱ CapabilityDetector

**ورودی‌ها:**
- دستور کاربر («قابلیت ترجمه آفلاین اضافه کن»)
- الگوی FeedbackStore (تکرار اصلاح روی یک موضوع)
- InferenceResponse با confidence < آستانه از Local
- لاگ‌های runtime (Crashlytics-like محلی)
- اسکن دوره‌ای KnowledgeStore برای gapها

**خروجی:** لیست `Capability` مرتب‌شده بر اساس priority و risk

**قوانین:**
- CRITICAL فقط از USER_REQUEST پذیرفته می‌شود
- حداکثر N قابلیت در هر چرخه (پیش‌فرض ۱ برای ایمنی)

### ۶.۲ EvolutionPlanner

با استفاده از `AuraCore.infer` (ترجیح OnDevice/Local، Remote فقط در HYBRID/REMOTE و آنلاین):

1. خواندن ساختار فعلی sandbox (فهرست فایل‌های مرتبط)
2. تولید `EvolutionPlan` شامل فایل‌های هدف، گام‌ها، تست‌های پیشنهادی
3. اجرای `SafetyPolicy.validate(plan)`
4. اگر `requiresUserConfirm` یا risk ≥ HIGH → ConfirmationGate

### ۶.۳ CodeGenerator

- برای هر `PlanStep` محتوای Kotlin تولید می‌کند
- ترجیح: مدل OnDevice یا Local با templateهای ثابت پروژه
- Remote فقط وقتی سیاست و شبکه اجازه دهد
- خروجی: لیست `CodePatch`
- اعتبارسنجی سطحی: package name، importهای مجاز، عدم لمس فایل‌های ممنوع

### ۶.۴ ProjectSandbox

```
filesDir/evolution/
├── workspace/          # کپی کاری از سورس مجاز
├── snapshots/          # tar/zip قبل از هر تغییر
├── builds/             # خروجی APK و لاگ
└── versions.json       # VersionRecordها
```

- فقط مسیرهای سفید‌لیست (مثلاً `app/src/main/java/com/aura/agent/**` به جز `security/` و `BuildConfig`)
- هرگز به `local.properties`، کلیدها، یا دادهٔ کاربر دست نمی‌زند

### ۶.۵ BuildRunner

**فاز ۱ (فعلی — بدون JDK کامل روی دستگاه):**
- اعتبارسنجی ساختار فایل + بررسی سینتکس سطحی Kotlin (regex/parser سبک)
- در صورت اتصال به محیط dev (ADB/CI): فراخوانی `./gradlew assembleDebug test`

**فاز ۲ (آینده):**
- Gradle wrapper داخل sandbox یا Termux/embedded build

خروجی همیشه `BuildResult` با لاگ خلاصه.

### ۶.۶ TestRunner

| نوع تست | هدف |
|---------|-----|
| Unit (موجود) | عدم شکست تست‌های قبلی |
| Smoke | راه‌اندازی AuraCore + یک infer محلی |
| Regression metrics | latency، memory، confidence میانگین روی مجموعه ثابت سوالات |

متریک‌های پایه در `VersionRecord.metrics` ذخیره می‌شوند.

### ۶.۷ VersionComparator

```text
score = w1 * (-latency) + w2 * (-memory) + w3 * confidence + w4 * tests_passed_ratio
```

- اگر `scoreDelta < 0` یا regression در تست‌های حیاتی → ROLLBACK
- آستانه‌ها در `EvolutionConfig` قابل تنظیم

### ۶.۸ DeployGate + SnapshotManager

1. قبل از CODE: `snapshotId = snapshots.create("pre-evo-...")`
2. بعد از COMPARE موفق:
   - ثبت `VersionRecord` با tag جدید
   - علامت‌گذاری نسخهٔ قبلی به عنوان inactive
   - در صورت نیاز، کپی فایل‌های تأییدشده از sandbox به درخت اصلی (با تأیید مجدد اگر risk بالا)
3. Rollback:
   - بازیابی snapshot
   - فعال‌سازی مجدد VersionRecord قبلی
   - ثبت رویداد در KnowledgeStore

### ۶.۹ KnowledgeUpdater + PromptEvolver

پس از هر چرخه (موفق یا ناموفق):

- ذخیره خلاصه: چه قابلیتی، چه نتیجه‌ای، چه خطایی
- در صورت موفقیت مکرر روی یک الگوی prompt → پیشنهاد به‌روزرسانی system prompt از طریق `PromptEvolver`
- اتصال به `TrainingPipeline` برای export نمونه‌های جدید

### ۶.۱۰ SafetyPolicy (قرمزها)

**ممنوع بدون تأیید صریح کاربر:**
- هر فایل زیر `security/`
- تغییر `AndroidManifest` permissions
- حذف دادهٔ کاربر / MemoryStore / SecureStore
- فعال‌سازی LIVE trading
- شبکه به دامنهٔ غیر از لیست سفید در کد تولیدشده
- اجرای دستور shell خارج از gradle مجاز

**سقف‌ها:**
- حداکثر ۱ قابلیت CRITICAL در روز
- حداکثر اندازهٔ پچ (مثلاً ۵۰ KB مجموع)
- timeout برای هر فاز

---

## ۷. تنظیمات (EvolutionConfig)

DataStore مشابه `ModelConfig`:

| کلید | پیش‌فرض | توضیح |
|------|---------|--------|
| auto_evolution | false | چرخهٔ خودکار (فقط LOW risk) |
| max_capabilities_per_cycle | 1 | |
| keep_versions | 5 | تعداد نسخه برای Rollback |
| min_score_delta | 0.0 | حداقل بهبود برای Deploy |
| allow_remote_codegen | true | فقط اگر سیاست Core اجازه دهد |
| sandbox_only | true | تا تأیید نهایی فقط در sandbox |

دستورات پیشنهادی:
- `تکامل خودکار روشن/خاموش`
- `وضعیت تکامل`
- `شروع تکامل`
- `Rollback`
- `لیست نسخه‌ها`

---

## ۸. جریان نمونه (Happy Path)

1. کاربر: «قابلیت خلاصه‌سازی صوت آفلاین اضافه کن»
2. DETECT → Capability(title=Offline voice summary, risk=MEDIUM)
3. PLAN → فایل‌های `voice/` + تست جدید؛ ConfirmationGate تأیید می‌کند
4. Snapshot گرفته می‌شود
5. CODE → پچ‌ها در sandbox
6. BUILD → موفق
7. TEST → unit + smoke OK؛ latency +3%
8. COMPARE → scoreDelta مثبت کوچک، بدون regression حیاتی
9. DEPLOY → VersionRecord جدید؛ در صورت `sandbox_only=false` اعمال روی درخت اصلی
10. LEARN → دانش «خلاصه صوت آفلاین پیاده شد» ذخیره می‌شود

---

## ۹. محدودیت‌های صادقانه (فاز ۱)

| موضوع | واقعیت فاز ۱ |
|--------|----------------|
| Build کامل روی دستگاه | بدون JDK/Gradle کامل؛ اعتبارسنجی ساختاری + CI اختیاری |
| تولید کد پیچیده | وابسته به قدرت مدل (OnDevice stub فعلاً ضعیف است) |
| اعمال خودکار روی سورس اصلی | پیش‌فرض فقط sandbox؛ merge دستی یا با تأیید |
| تست UI کامل | فقط unit/smoke؛ UI instrumentation در فاز بعد |
| iOS/Desktop | خارج از scope فعلی Android |

فاز ۲: OnDevice LLM واقعی + Gradle embedded یا اتصال dev machine.  
فاز ۳: اعمال خودکار کنترل‌شده + تست UI.

---

## ۱۰. یکپارچه‌سازی با اجزای موجود

| جزء موجود | نقش در تکامل |
|-----------|----------------|
| AuraCore | استدلال، تولید plan/code، سیاست runtime |
| LearningManager | منبع Capability از feedback؛ ذخیره تجربه |
| KnowledgeStore | gap detection + ثبت نتایج |
| ModelAutoUpgrade | ارتقای مدل Remote (جدا از ارتقای کد) |
| TrainingPipeline | export پس از LEARN |
| ConfirmationGate | تأیید planهای پرریسک |
| SecurityGuards | HTTPS و محدودیت‌های شبکه در کد تولیدشده |
| HardwareProfiler | تصمیم برای سنگین بودن Build/Test روی دستگاه |

---

## ۱۱. نقشهٔ پیاده‌سازی پیشنهادی

| فاز | تحویل | معیار خروج |
|-----|--------|------------|
| **E0** | مدل‌ها + Config + SafetyPolicy + SnapshotManager خالی | کامپایل |
| **E1** | CapabilityDetector + Planner + سندباکس خواندنی | detectOnly کار می‌کند |
| **E2** | CodeGenerator + نوشتن در sandbox | پچ روی فایل آزمایشی |
| **E3** | BuildRunner (ساختاری) + TestRunner smoke | BuildResult/TestResult |
| **E4** | Comparator + DeployGate + Rollback | یک چرخه کامل روی قابلیت LOW |
| **E5** | KnowledgeUpdater + PromptEvolver + دستورات UI | گزارش در چت |
| **E6** | auto_evolution برای LOW + اتصال CI واقعی | پایدار در استفاده روزانه |

---

## ۱۲. معیارهای موفقیت کلی

- در حالت بدون API key، حداقل DETECT + PLAN + LEARN کار کند.
- هیچ ارتقایی بدون snapshot قابل Rollback نباشد.
- هیچ فایل امنیتی یا کلید بدون تأیید لمس نشود.
- پس از ۱۰ چرخهٔ آزمایشی، نرخ Rollback ناشی از regression < ۳۰٪.
- دانش هر چرخه در KnowledgeStore قابل بازیابی باشد.

---

## ۱۳. دستورات کاربر (پیشنهادی)

| دستور | عمل |
|--------|------|
| `وضعیت تکامل` | phase + آخرین run |
| `شروع تکامل` / `تکامل کن` | runCycle |
| `قابلیت‌های جدید` | detectOnly |
| `تکامل خودکار روشن/خاموش` | config |
| `Rollback` | آخرین نسخهٔ پایدار |
| `Rollback به ۳.۰.۱` | tag مشخص |
| `لیست نسخه‌ها` | VersionRecordها |

---

*طراحی نسخه ۱.۰ — هم‌راستا با AURA Core 3.0*  
*At your service.*
