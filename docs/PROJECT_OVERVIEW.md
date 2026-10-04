# Qalqon — Technical Repository Audit & Architectural Analysis

> **Audit date:** 2026-10-03
> **Branch:** `feature/phase4-screen-time-complete`
> **Audited commit:** `076fae91190f1331e731e6be47b46cb299e31050`
> **Method:** static inspection of the tree, Gradle build files, Kotlin sources, resource files, tests and CI, plus a live `./gradlew :app:testDebugUnitTest` run.
> **Product:** Qalqon (YuzNazorat) — an offline-first, face-recognition parental-control Android app.

---

## 1. Project Baseline & Tech Stack

### 1.1 Repository baseline

| Item | Value |
|---|---|
| Active branch | `feature/phase4-screen-time-complete` |
| HEAD commit | `076fae9` — `style(home): update icons and shield depth to match mockup exactly` |
| Local vs remote | In sync (`origin/feature/phase4-screen-time-complete`) |
| Working tree | Clean at audit start |
| Gradle | 9.7.1 (wrapper) |
| Repo layout | **Single Gradle module** (`:app`) with layered packages |
| Main Kotlin files | 232 (≈34,111 LOC) |
| JVM test files | 138 files / 136 test classes |
| Instrumented test files | 60 files / 57 test classes |

Recent commit trail (Home-screen visual work on top of the Phase 4 screen-time branch):

```
076fae9 style(home): update icons and shield depth to match mockup exactly
f0ea277 style(home): achieve pixel-perfect visual match with target mockup
0c1ce80 style(home): overhaul hero illustration and prominent metric tiles to match mockup
8e60208 style(ui): refine home screen layout with hero graphic and dynamic tile badges
3ce3cf1 style(ui): align home with premium reference design
5ea6d04 style(ui): redesign home dashboard experience
a3925fb style(ui): finalize premium qalqon experience
```

### 1.2 Build & language stack

| Concern | Value | Source |
|---|---|---|
| Android Gradle Plugin | 8.7.2 | `gradle/libs.versions.toml` |
| Gradle | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin | **2.3.21** | version catalog |
| KSP | 2.3.6 | version catalog |
| Hilt | 2.58 | version catalog |
| Compose BOM | 2024.12.01 | version catalog |
| Room | **2.7.2** | version catalog |
| CameraX | 1.4.1 | version catalog |
| ML Kit face-detection | 16.1.7 | version catalog |
| ML Kit GenAI Prompt | 1.0.0-beta4 | version catalog |
| TensorFlow Lite | 2.16.1 | version catalog |
| Accompanist permissions | 0.36.0 | version catalog |
| DataStore Preferences | 1.1.1 | version catalog |
| AndroidX Biometric | 1.1.0 | version catalog |
| Navigation Compose | 2.8.5 | version catalog |
| Lifecycle | 2.8.7 | version catalog |
| JUnit | 4.13.2 | version catalog |

### 1.3 SDK / JVM targets (`app/build.gradle.kts`)

| Setting | Value |
|---|---|
| `namespace` / `applicationId` | `uz.faceguard.app` |
| `compileSdk` / `targetSdk` | 35 |
| `minSdk` | 26 |
| Java / Kotlin target | 17 |
| `versionCode` / `versionName` | 1 / 0.1.0 (overridable via `-PqalqonVersionCode` / `-PqalqonVersionName`) |
| Build features | `compose = true`, `buildConfig = true` |
| Release | `isMinifyEnabled = false`; signed only when the full keystore env/local.properties set exists |
| Native assets | `androidResources.noCompress += "tflite"` |
| Test runner | `androidx.test.runner.AndroidJUnitRunner` |

Notable build-system detail: **release signing is not stored in the repo**. It is resolved, in order, from `QALQON_RELEASE_STORE_FILE` / `..._PASSWORD` / `..._KEY_ALIAS` / `..._KEY_PASSWORD` environment variables, then from machine-local `local.properties` (`qalqon.storeFile` etc.). `assembleRelease` deliberately fails loudly when the identity is absent, so an unsigned APK cannot be presented as production.

### 1.4 Dependency set (`app/build.gradle.kts`)

* **UI:** Compose UI / graphics / Material3 / tooling-preview, Activity-Compose
* **Architecture:** Lifecycle runtime-ktx, viewmodel-compose, runtime-compose, Navigation-Compose, Hilt-Navigation-Compose
* **DI:** Hilt (`hilt-android` + `ksp hilt-compiler`)
* **Persistence:** Room runtime/ktx + `ksp room-compiler`, DataStore Preferences
* **Vision:** CameraX (core/camera2/lifecycle/view), ML Kit face-detection, TensorFlow Lite
* **Assist/GenAI:** ML Kit GenAI Prompt (Gemini Nano, capability detection + grounded generation)
* **Security:** AndroidX Biometric
* **Permissions UX:** Accompanist permissions
* **Test:** JUnit4; androidTest: `androidx.test.ext.junit`, `runner`, `core-ktx`, `room-testing`

**Deliberately absent:** `androidx.work` (WorkManager) — scheduling is event-driven by design; `material-icons-extended` — the icon set is limited to ~50 core glyphs plus hand-built vectors.

---

## 2. Architecture & Module Structure

### 2.1 Pattern

**Single-module, layered Clean-ish architecture with MVVM + unidirectional data flow.**

* **Presentation:** Jetpack Compose screens + `@HiltViewModel` ViewModels exposing `StateFlow<UiState>`.
* **Domain:** pure Kotlin (no Android framework) — models, policy/schedule/eye-safety/enrollment logic, repository *interfaces*, similarity maths, diagnostics.
* **Data:** Room (`data/db`), DataStore (`data/prefs`), repository implementations (`data/repository`) with mappers separating entities from domain models.
* **Core (platform):** Android-coupled engines and services (protection, accessibility, camera, recognition, notifications, security, usage).
* **Sync:** scaffolding only — gateway interfaces with NoOp bindings.

Because all logic layers are pure and Android-free wherever possible, the bulk of behaviour is testable in the JVM suite (1,748 tests).

### 2.2 Package map

```
uz.faceguard.app
├── FaceGuardApp.kt            # @HiltAndroidApp; starts app-scoped ProtectionRuntime
├── MainActivity.kt            # single activity; FaceGuardTheme + FaceGuardNavHost
├── core/                      # Android/platform engines (no UI policy decisions)
│   ├── accessibility/         # ProtectionAccessibilityService, overlay host/window, event filter
│   ├── debug/                 # DebugFlags (gates debug screens out of production)
│   ├── diagnostics/           # RuntimeHealthMapper, SystemHealthSnapshotSource
│   ├── embed/                 # FaceEmbeddingModel seam, TfLiteMobileFaceNet, image utils
│   ├── enrollment/            # EnrollmentFrameMapper (sanitising boundary)
│   ├── eyesafety/             # eyeSafetyFrameOf adapter, EyeSafetyObservationState
│   ├── i18n/                  # AppLanguage, LocalizedApp (locale wrapping)
│   ├── liveness/              # LivenessDetector, TemporalLivenessDetector, AntiSpoofModel seam
│   ├── monitor/               # ForegroundAppMonitor (UsageStats)
│   ├── notification/          # AndroidNotifications
│   ├── pipeline/              # CameraSessionLifecycleOwner, FaceCaptureController, FaceDetectorConfig, FrameEvent
│   ├── policy/                # ActivationDelayGate, DefaultPolicyEvaluator
│   ├── protection/            # ProtectionEngine, ProtectionRuntime, FGS, boot receiver/restorer, overlay controller
│   ├── recognition/           # Recognizer (cosine matching)
│   ├── scan/                  # ScanScheduler (event-driven, battery modes)
│   ├── screentime/            # CollectionInterval, ScreenTimeUsageCollectionRunner
│   ├── security/              # AppLockState, PIN hashing, Keystore cipher, biometric prompt
│   ├── theme/                 # Theme.kt, QalqonTokens, QalqonSemanticColors, QalqonTypography
│   ├── time/ · usage/ · util/ # elapsed-time source, usage access, validation
│   └── ui/                    # UiState, Components.kt (legacy), qalqon/ (design-system components)
├── data/
│   ├── db/                    # FaceGuardDatabase (v10), Entities.kt (14 entities), Daos.kt (13 DAOs), migrations/
│   ├── prefs/                 # DataStore: session + settings
│   └── repository/            # 21 repository impls + mappers (+ reset, biometric migration service)
├── di/AppModule.kt            # single Hilt @Module wiring the graph
├── domain/
│   ├── model/ · repository/Repositories.kt   # pure models + repo interfaces
│   ├── policy/ schedule/ screentime/ eyesafety/ enrollment/ request/ notification/
│   ├── diagnostics/ · security/ · similarity/ · model/
├── feature/                   # one package per screen area (see §4)
├── navigation/                # NavGraph.kt (Routes + graph), QalqonAppShell, BottomNavigationPolicy, QalqonTopLevelDestination
└── sync/                      # SyncInterfaces, NoOpSyncGateways, SyncCoordinator (no networking)
```

### 2.3 Data flow

```
Room (SSOT)  ── DAO Flow ──► RepositoryImpl ──► Domain model ──► ViewModel (StateFlow)
DataStore    ── Flow ─────►                 ──►                 ──► Compose (collectAsStateWithLifecycle)
ProtectionRuntime (app-scoped StateFlow) ──► HomeViewModel/ProtectionViewModel
```

* **Persistence:** Room DB **version 10**, 14 entities, migration chain **v3…v10** (expressly additive; no historical migration edited, no destructive fallback). DataStore holds session identity and app settings (with legacy-value fallbacks and account-namespaced keys).
* **Engines:** `ProtectionRuntime` is an app-scoped singleton started from `FaceGuardApp`. It owns `ForegroundAppMonitor` + `ScanScheduler` + `ProtectionEngine`, applies `ProtectionSettings` live, and shares camera frames from `Recognizer`. Activation is driven purely by `protectionEnabled` + a signed-in account.
* **Background work:** `ProtectionForegroundService` (camera/special-use FGS), `ProtectionAccessibilityService` (foreground-app tracking + blocking overlay), `ProtectionBootReceiver` (boot restore). **No WorkManager** — `ScanScheduler` is event-driven with BALANCED / BATTERY_SAVER / STRICT modes and a low-battery override.
* **Vision pipeline:** CameraX front camera → ML Kit face detection → `FaceCaptureController` (quality metrics + TFLite embedding with a geometry fallback) → `Recognizer` (cosine similarity, parent evaluated before children) → `ProtectionEngine` (debounce, liveness window, eye-safety, schedule, policy) → `ProtectionActionExecutor`/overlay.

### 2.4 Manifest surface (security-relevant)

| Component | Purpose |
|---|---|
| `MainActivity` | Single activity host |
| `FaceGuardApp` | Hilt app + runtime bootstrap |
| `ProtectionForegroundService` | Foreground camera/guard service |
| `ProtectionAccessibilityService` | Foreground-app signal + blocking overlay |
| `ProtectionBootReceiver` | Restore protection after boot |

Permissions declared: `CAMERA`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`.
`INTERNET` and `ACCESS_NETWORK_STATE` are present **only** as manifest-merger `tools:node="remove"` removals; `usesCleartextTraffic="false"`. **The app is genuinely offline.**

---

## 3. Design System & UI Tokens

### 3.1 Token files (`core/theme`)

| File | Contents |
|---|---|
| `QalqonTokens.kt` | `QalqonSpacing`, `QalqonShapes`, `QalqonElevation`, `QalqonIconSize`, `QalqonSizes` |
| `QalqonSemanticColors.kt` | `QalqonSemanticColors` data class + `LightQalqonSemanticColors` / `DarkQalqonSemanticColors` + `LocalQalqonSemanticColors` |
| `QalqonTypography.kt` | 13-style `Typography` scale |
| `Theme.kt` | `FaceGuardTheme`, light/dark `ColorScheme`, `QalqonTheme` accessor, `QalqonDimens` grouping |

### 3.2 Dimension tokens

| Scale | Values (dp) |
|---|---|
| **Spacing** | `none 0 · xs 4 · sm 8 · md 12 · lg 16 · xl 24 · xxl 32` |
| **Shapes (radius)** | `small 8 · medium 12 · large 16 · xLarge 20 · pill 999` |
| **Elevation** | `flat 0 · raised 1 · modal 6` |
| **Icon size** | `xs 16 · sm 20 · md 24 · lg 32` |
| **Sizes** | `touchTarget 48 · buttonCompact 36 · buttonDefault 48 · buttonLarge 56 · indicator 8 · avatar 40 · illustration 104 · border 1 · strokeThin 2` |

`QalqonDimens` re-exposes the whole scale (`screenPadding = 24`, `cardPadding = 16`, `rowPadding = 12`, `cardCorner = medium`).

### 3.3 Semantic colour tokens (26, light + dark)

Transport/state: `success`, `onSuccess`, `successContainer`, `warning`, `onWarning`, `warningContainer`, `info`, `onInfo`, `infoContainer`.
Protection: `protectionActive`, `protectionInactive`, `protectionBlocking`, `protectionWarning`.
Capability: `capabilityGranted`, `capabilityMissing`.
Child setup: `childConfigured`, `childNeedsSetup`.
Requests: `requestPending`, `requestApproved`, `requestDenied`.
Home hero/CTA: `illustrationShield` (`0xDCE8FF`), `illustrationStroke` (`0x2563EB`), `cta` (`0x1D61E0`), `onCta`, `dangerContainer` (`0xFEE2E2`), `onDangerContainer` (`0xDC2626`).

Dark theme provides a distinct mapping for every token (lightened/desaturated), so status meaning is readable on dark surfaces.

### 3.4 Typography contract

`displayLarge…labelSmall` (13 styles). Semantic usage is documented and enforced socially: `headline*` → major figures, `title*` → screen/section/card titles, `body*` → explanatory text, `label*` → metadata/status. Nothing sets `maxLines` globally, so Uzbek/Russian strings wrap rather than truncate.

### 3.5 Component library (`core/ui/qalqon`, 22 public composables)

`QalqonCard`, `QalqonSectionCard`, `QalqonStatusCard`, `QalqonMetricCard`, `QalqonListCard`, `QalqonListRow`, `QalqonDivider`, `QalqonSectionHeader`, `QalqonChildCard`, `QalqonStatusBadge`, `QalqonStatusBanner`, `QalqonCapabilityRow`, `QalqonAlertRow`, `QalqonBadge`, `QalqonCountBadge`, `QalqonConfirmDialog`, `QalqonEmptyState`, `QalqonErrorState`, `QalqonLoadingState`, `QalqonSettingRow`, `QalqonSettingSwitchRow`, `QalqonSettingSliderRow`. Plus `QalqonComponentPreviews.kt` (light+dark previews).

### 3.6 Rules enforced by tests (not just convention)

* **No raw colours** (`Color(0x…`) and **no raw dp literals** (`N.dp`) in the redesigned screens — `FinalUiConsistencyTest`, `HomeDashboardLocalizationTest`, `HomeVisualRefinementTest`.
* **No hardcoded UI text**; string-resource keys must have **byte-identical key sets** across `values` (Uzbek), `values-en`, `values-ru`, and every declared key must be referenced from Kotlin — `LocalizationCompletenessTest`, `FinalUiConsistencyTest`, `QalqonComponentStringsTest`.
* **Touch-target floor** 48 dp for every interactive token — `QalqonDesignTokensTest`.
* **Design-system purity:** components may not import `data.`, `domain.repository`, `core.protection`, `core.recognition` — `QalqonFoundationCompatibilityTest`.
* Semantic palette completeness/uniqueness and light≠dark mapping — `QalqonSemanticColorsTest`.

---

## 4. Screen & Feature Inventory

### 4.1 Top-level destinations (bottom navigation)

`QalqonTopLevelDestination` — 5 tabs, each a stable route + localized label + icon:

| Tab | Route | Label res |
|---|---|---|
| HOME | `home` | `nav_home` |
| CHILDREN | `child_profiles` | `nav_children` |
| ACTIVITY | `activity_log` | `nav_activity` |
| HELP | `help` | `nav_help` |
| SETTINGS | `settings` | `nav_settings` |

`BottomNavigationPolicy.resolve(currentRoute, destination)` is a pure, JVM-tested decision: a **different** tab → `Switch(route)` (save/restore state); the **current** tab re-tapped → `ResetToRoot(route)`. This fixed the earlier silent no-op re-tap. `QalqonAppShell` wraps the single `NavHostController` in a `Scaffold` with a floating rounded `Surface` bottom bar; the shell owns **no** navigation state and the bar hides on every non-tab route.

### 4.2 Route table (`navigation/NavGraph.kt` → `Routes`)

Onboarding/auth: `splash`, `language`, `pin_unlock`, `welcome`, `register`, `login`, `create_pin`.
Primary: `home`, `parent_profile`, `child_profiles`, `settings`, `activity_log`, `requests`, `help`, `help_assistant`, `privacy`, `protection`, `recognition_debug`.
Settings sub-routes: `settings_apps`, `settings_protection`, `settings_family`, `settings_security`, `settings_appearance`, `settings_notifications`, `settings_privacy`, `settings_support`, `settings_developer`.
Parameterised: `parent_face_enrollment`, `child_detail/{childId}`, `child_face_enrollment/{childId}`, `child_policy/{childId}" ?section=`, `child_schedules/{childId}`, `child_schedule_editor/{childId}?scheduleId=`, `child_eye_safety/{childId}`.

**Gate:** `isProtectedRoute`, `lockRedirectFor`, `notificationRouteFor` remain the single authority; every tab route is protected and redirects to `PIN_UNLOCK` while locked.

### 4.3 Screen inventory (25 screen files / ~26 screens)

| Area | File(s) | Responsibility |
|---|---|---|
| Home | `feature/home/HomeScreen.kt` (+ `DashboardAggregator`, `DashboardState`, `HomeDashboardPresentation`, `ScreenTimeSummary*`, `ScreenTimeTargetState`) | Parent dashboard: hero protection status, attention, today metrics, children, quick actions. `HomeViewModel` aggregates real state only; presentation mapping is pure and JVM-tested |
| Children | `feature/child/ChildProfilesScreen.kt`, `ChildDetailScreen.kt` | Child CRUD + per-child hub |
| Child policy | `feature/policy/ChildPolicyScreen.kt` | Per-app policy + screen-time section entry |
| Schedules | `feature/schedule/ScheduleScreens.kt` | Schedule list + editor |
| Eye safety | `feature/eyesafety/EyeSafetyScreens.kt` | Per-child eye-safety config editor |
| Enrollment | `feature/enrollment/FaceEnrollmentScreen.kt` | Frontal multi-frame capture state machine |
| Recognition | `feature/recognition/RecognitionDebugScreen.kt` | Debug-only live recognition |
| Protection | `feature/protection/ProtectionScreen.kt` | Master toggle, setup checklist, live status, emergency PIN unlock |
| Requests | `feature/requests/RequestsScreen.kt` | Child extra-time requests |
| Activity | `feature/activity/ActivityLogScreen.kt` | Protection event log |
| Settings | `feature/settings/SettingsScreen.kt`, `SettingsCategoryScreens.kt`, `ProtectedAppsScreen.kt` (+ `SettingsStatusViewModel`) | Tabbed settings incl. app protection picker + health audit |
| Auth | `feature/auth/` — `SplashScreen`, `LanguageScreen`, `WelcomeScreen`, `RegisterScreen`, `LoginScreen`, `CreatePinScreen`, `PinUnlockScreen` | Local auth + PIN gate |
| Parent | `feature/parent/ParentProfileScreen.kt` | Parent profile + face enrollment |
| Privacy / Help | `feature/privacy/PrivacyScreen.kt`, `feature/help/HelpScreen.kt`, `HelpAssistantScreen.kt` | Privacy doc, help centre, grounded assistant (`feature/help/ai/` — Gemini Nano capability + grounded generation) |

**ViewModels:** ~22, almost all `@HiltViewModel` colocated with their screen (Home, Protection, ChildPolicy, settings/status, schedule, eye-safety, enrollment, help, activity, requests, auth flows, etc.). `HomeViewModel` is the largest aggregation point (dashboard + screen-time summary + target + parent profile).

### 4.4 Domain engines (business logic)

* `domain/policy` — `DefaultPolicyEvaluator` (identity → liveness spoof gate → schedule → eye safety → per-app rules), `PolicyContext`, `PolicySettings`.
* `domain/schedule` — resolution + conflict detection, precedence.
* `domain/screentime` — limit evaluation, usage accounting/intervals.
* `domain/eyesafety` — `EyeSafetyEvaluator`, config invariants (whole-percent ↔ ratio).
* `domain/enrollment` — quality gate, embedding validator, frontal collector.
* `domain/diagnostics` — `SystemHealthEvaluator` (only blames a device that actually requested protection).
* `domain/similarity` — `CosineSimilarity`.
* `domain/request` — extra-time request domain.

---

## 5. Test Suite & CI Pipeline

### 5.1 Suite shape

| Suite | Files | Classes | Notes |
|---|---|---|---|
| JVM unit (`app/src/test`) | 138 | 136 | Pure JVM; source-level assertions + pure logic |
| Instrumented (`app/src/androidTest`) | 60 | 57 | Room migrations, DAOs, repositories, engine integration; **compile-only locally** (no `/dev/kvm`) |

Test packages: `home`, `design`, `navigation`, `i18n`, `polish`, `settings`, `child`, `activity`, `schedule`, `screentime`, `eyesafety`, `enrollment`, `policy`, `protection`, `requests`, `security`, `diagnostics`, `help(+/ai)`, `data(+/prefs)`, `dashboard`, `tests`.

**Distinctive patterns worth preserving:**

* **Source-level composition tests.** `HomeCompositionTest` / `HomeVisualRefinementTest` read `HomeScreen.kt` as text and assert structural invariants (section order, that the hero is a `Surface` on `surfaceContainerLow`, that the CTA meets the 48 dp token, that tiles are bordered white surfaces). Layout intent is pinned without a device.
* **Localization parity tests.** Key sets must be identical across three locales and every key referenced — catching "Uzbek UI + English button" drift.
* **Migration tests** for every step v3→v10 plus DAO/repository instrumented tests.
* No mocks of the code under test where avoidable — fakes (`FakeAppUsageSource`, `FakeProtectedAppsRepository`, `PassthroughTemplateCipher`) are used for external boundaries.

### 5.2 Live result (this audit)

```
./gradlew :app:testDebugUnitTest --no-daemon
BUILD SUCCESSFUL
tests=1748  failures=0  errors=0  skipped=0
```

### 5.3 CI (`.github/workflows/build.yml`)

| Job | Trigger | What it does |
|---|---|---|
| `build` | push to `main` / `feature/phase4-screen-time-complete`, every PR, manual | JDK 17 (temurin), `:app:testDebugUnitTest`, `assembleDebug`, upload debug APK |
| `instrumented-tests` | needs `build` | KVM enable, **API 35** emulator with `-no-snapshot` (plus `-gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none`), `connectedDebugAndroidTest`, upload reports |
| `release` | tag push / `workflow_dispatch` only | Secrets-gated signed release APK; warns and skips when secrets are absent |

Job permissions are least-privilege (`contents: read`). The deliberate **`-no-snapshot`** AVD stability fix is intact and must not be removed.

---

## 6. Current Status & Future Roadmap

### 6.1 Where the project stands

Qalqon is a **functionally broad, offline-first MVP** whose core loop (enroll faces → protect chosen apps → detect identity → enforce policy) runs end-to-end, with a mature pure-domain test suite and a coherent design system.

Completed capability phases (per repo memory + code): auth/PIN gate (2), profiles (3), rules/settings (4), enrollment scaffold + implementation (5–6), recognition debug (7), protected apps + foreground monitor (8), protection engine (9), scan scheduling (10), resilience/obstruction (11), MVP polish — privacy/help/activity/reset (12), stabilization (13), sync scaffolding (14), audit + Qalqon rebrand (15), liveness/anti-spoof foundation, recovery & state restoration, eye-safety (config → runtime → UI → integration), settings/system health auditing, enrollment hardening, release signing + CI.

**Active work:** `feature/phase4-screen-time-complete` — screen-time target selection, usage accounting, limit evaluation, and a Home dashboard that surfaces them; the last six commits are Home visual redesign.

### 6.2 Identified technical debt & risks

| # | Item | Impact | Evidence |
|---|---|---|---|
| 1 | **Stale repo memory** — `AGENTS.md` states Kotlin 2.0.21 / Room 2.6.1 / JDK 21 | Misleads future contributors & agents | Catalog says Kotlin 2.3.21 / Room 2.7.2; JVM target 17 |
| 2 | **`gradlew` has no execute bit** (mode `100644`) | `./gradlew` fails; everyone must remember `bash ./gradlew` | `git ls-files -s gradlew` |
| 3 | **`exportSchema = false`** on Room | No exported schema history; migrations verified by hand against generated DDL | `FaceGuardDatabase.kt` |
| 4 | **No R8/minify on release** | Larger APK, slower startup | `isMinifyEnabled = false` |
| 5 | **Instrumented tests never run locally** (no `/dev/kvm`) | Room/DAO/engine integration only exercised in CI | AGENTS.md + `/dev/kvm` absent |
| 6 | **Ephemeral toolchain** — `/opt` JDK+SDK wiped between sessions | Repeated reinstall cost; a documented setup step, not automated | Reproduced twice during this audit |
| 7 | **Composition-contract churn** — Home visual iterations required editing `HomeCompositionTest` twice | Source-text tests are brittle to intentional redesigns | `HomeCompositionTest` edits in `5ea6d04`, `0c1ce80`, `f0ea277` |
| 8 | **Compiler warnings on the Home path** — 2× `ExperimentalCoroutinesApi` opt-in, 1 unnecessary `!!`, 2 deprecated `quadraticBezierTo` | Minor; noise that can mask real warnings | `:app:compileDebugKotlin` output |
| 9 | **Biometric assertions are honest but limited** — geometry/TFLite embeddings, no bundled anti-spoofing model | Photo/screen/replay not provably defeated | README + `core/liveness` |
| 10 | **Sync is a façade** — NoOp gateways, no networking | Intentional; must not be mistaken for implemented sync | `sync/NoOpSyncGateways.kt` |
| 11 | **Key-set parity is all-or-nothing** — adding one string requires 3 edits + usage | Friction, but a genuine safeguard | localization tests |
| 12 | **`QalqonCard` accumulated optional params** (`elevation`, `shape`) | API growth; watch for bloat | `QalqonCard.kt` |

### 6.3 Recommended next steps

**Immediate (low-risk hygiene)**
1. Update `AGENTS.md` to the real toolchain (Kotlin 2.3.21, Room 2.7.2, JVM 17) and record the `/opt` reinstall recipe as a script.
2. Fix the `gradlew` mode (`git update-index --chmod=+x gradlew`) so `./gradlew` works and CI's `chmod +x` becomes a no-op.
3. Enable `exportSchema = true` and commit the schema JSON; keep the migration tests as the regression net.
4. Clear the four Home-path compiler warnings.

**Near-term (product)**
5. Finish and verify Phase 4 screen-time end-to-end on an emulator in CI (usage accounting → limit → policy → Home surface).
6. Consolidate the two `HomeCompositionTest`-style contract suites behind a small helper to reduce redesign churn.
7. Evaluate R8 with a conservative keep-rule set on a release candidate.

**Strategic**
8. Keep offline-first guarantees intact: any future sync must remain opt-in and must not reintroduce `INTERNET` into the shipping manifest.
9. Treat anti-spoofing and biometric-grade matching as explicit non-goals until a real model + evaluation harness exists; keep the README's honesty section authoritative.
10. If screen-count keeps growing, consider splitting `:app` into `:core:ui`, `:domain`, `:data` Gradle modules — the package boundaries already match the seams.

---

*Report generated from a static + live audit of commit `076fae9` on `feature/phase4-screen-time-complete`. All numeric results are measured, not estimated.*
