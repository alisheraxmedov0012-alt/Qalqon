# YuzNazorat — repo memory for agents

Phase 2 (auth) is complete: full local registration + login (phone+PIN, hashed,
session persisted, duplicate-phone blocked). No face
recognition yet (roadmap in README). Phase 1 foundation was auth-scaffold; Phase 2 made auth real..

## Hard rules
- No INTERNET permission (offline/privacy-first).
- All user-facing strings in res/values*/strings.xml; default = Uzbek Latin;
  mirrors: values-en, values-ru. Never hardcode UI text in composables.
- Phase 1 packages: core, data, domain, feature/{auth,home,parent,child,settings},
  navigation. Keep new code in this layout.

## Structure notes
- Register->CreatePin handoff uses feature/auth/RegisterDraft.kt object
  (PLACEHOLDER, marked in code + README).
- Room entities: user_accounts, parent_profiles, child_profiles. DataStore:
  session (account flag) + settings.
- PIN: salted SHA-256 in AccountRepositoryImpl; digits-only phone; UNIQUE index.
  Keystore migration later. Login uses generic errors (no enumeration).
- core/ui has UiState (Idle/Loading/Success/Error) + reusable field components.
- Session lives in SessionManager (current_account_id). Splash routes on it.
- Phase 3: parent/child profile CRUD. one parent per account via createIfMissing.
  children: add/edit(name+level)/delete+confirm. HomeScreen aggregates summary.
  UiState everywhere; RestrictionLevel LOW/MEDIUM/HIGH with Uzbek labels.

## Env notes
- The dev container is ephemeral: `/workspace/project` survives, but the JDK and
  Android SDK on the image do NOT. After a reset, reinstall them before building:
  - `sudo apt-get install -y openjdk-21-jdk-headless unzip`
  - `curl -sSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip`
    then unpack into `$HOME/Android/Sdk/cmdline-tools/latest`
  - `sdkmanager --licenses`, then install `platform-tools platforms;android-35 build-tools;35.0.0`
  - write gitignored `local.properties` with `sdk.dir=$HOME/Android/Sdk`
- Build env for every command: `HOME=/home/openhands`, `ANDROID_HOME=$HOME/Android/Sdk`,
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`, `PATH=$JAVA_HOME/bin:$PATH`.
  AGP 8.7.2 / Kotlin 2.0.21 / SDK 35. JDK 21 works even though CI pins 17.
- `gradlew` has no execute bit at baseline: run `bash ./gradlew <task>` instead of
  `chmod +x` (which would leave a spurious mode change in `git status`).
- `:app:assembleDebug` is wired to also run `testDebugUnitTest` and
  `assembleDebugAndroidTest`, so it verifies the JVM suite and compiles the
  instrumented tests in one go.
- Gradle runs exceed the 10s terminal soft-timeout: run in background with
  `nohup ... > /tmp/log 2>&1 &` and poll the log.
- No `/dev/kvm` and no CPU virtualization here, so an emulator cannot run:
  instrumented tests compile but cannot be executed. Never claim otherwise.
- terminal tool: ONE heredoc per command call; big heredocs silently fail.
  Validate XML via python ET after each strings write.


- Phase 4: settings/rules. AppSettings extended (scanMode BALANCED/BATTERY_SAVER/
  STRICT; unknown/noFace via BlockPolicy ALLOW/SOFT_BLOCK/HARD_BLOCK;
  lowBatteryBehaviorEnabled). SettingsStore DataStore handles legacy value
  fallbacks. ProtectedApp placeholder behind interface, seeded impl.
  Settings screen has two tabs (Rules/Apps); Home shows summary.


- Phase 5 (enrollment scaffold): CAMERA in manifest; routes for parent/child enrollment;
  FaceEnrollmentViewModel with permission/steps/success/canceled/failure;
  guided Uzbek steps; placeholder preview. Accompanist-permissions dep.
  Enrollment metadata columns on parent_profiles + child_profiles:
  faceTemplateRef, enrollmentStatus, enrollmentVersion, lastEnrollmentAt.
  Both repos map the new columns. Nav uses child_face_enrollment/{childId}.


- Phase 6 (enrollment implementation): CameraX front camera + ML Kit detection.
  FaceCaptureController binds Preview/ImageAnalysis; passes face-detected
  FrameEvent. FaceEmbeddable seam (today: timestamps app-private). EnrollmentSteps
  moved to core/pipeline. DB setFaceEnrolled(now bumps enrollmentStatus / version
  / lastEnrollmentAt). Retry/Cancel buttons. Added camerax + mlkit-face to
  versions catalog and build gradle.


- Phase 7: recognition debug. Recognizer in core/recognition with sealed
  RecognitionResult, Thresholds config, template decode placeholder. Debug
  screen: live preview + status/confidence. Debug button on Home; route
  recognition_debug. 10 new keys in 3 locales (134 each).


- Phase 8: protected apps. Room `protected_apps` (version 2), PackageManager
  refresh via Settings Apps tab. ForegroundAppMonitor via UsageStats + usage-
  access guidance card on Home. Manifest declares PACKAGE_USAGE_STATS.


- Phase 9: protection engine. ProtectionEngine (state machine, debounce,
  recovery), OverlayControllerImpl (WindowManager overlay), PinHasher.
  ProtectionDebugScreen with PIN unlock + decision log. Recognizer.frames
  SharedFlow wired to FaceCaptureController. 12 new strings (153 keys).


- Phase 10: battery-conscious scan scheduler. ScanScheduler (event-driven,
  3 modes, low-battery override). ProtectionEngine gates on scheduler.scanning.
  ProtectionDebugScreen shows mode/camera/cooldown/trigger. 11 new strings.


- Phase 11: resilience. RecognitionResult gains CameraPossiblyObstructed /
  UnstableRecognition; multi-frame confirmation (3 frames) + hysteresis
  (0.25 band) in ProtectionEngine; obstruction heuristic (6-frame no-face
  streak) follows unknown policy; recovery delay enforced from settings.
  Debug screen shows result/confidence/policy/state/reason. 5 new strings.


- Phase 12: MVP polish. Models/SettingsStore reconciled (ScanMode, BlockPolicy,
  noFacePolicy, lowBatteryBehaviorEnabled); ProtectedAppEntity added to
  @Database (v3) — was missing; ActivityEventEntity/DAO + ActivityLogRepository
  with engine.onEvent hook; PrivacyScreen/HelpScreen/ActivityLogScreen;
  Home setup checklist (7 steps); Settings Data tab with face-delete + full
  reset (ResetRepositoryImpl wipes Room + DataStore + session); emergency PIN
  now via AccountRepository.verifyPin (salted); PinHasher removed as dead code.
  43 new strings (221 keys per locale).

- Phase 13: stabilization. Fixed over-escaped strings (all locales), removed
  21 dead keys/locale (200 each), registered missing debug routes in NavGraph
  (was a crash), added core/debug/DebugFlags gating, MainScope leak fixed,
  ScanScheduler import + policy key names fixed in ProtectionDebugScreen,
  TODO(real-embedding) markers, README limitations section.

- Phase 14: sync scaffolding. uz.faceguard.app.sync package with gateway
  interfaces (account/children/settings), NoOp defaults bound in Hilt, and
  SyncCoordinator fan-out. No INTERNET permission, no networking; core
  behavior unchanged. Sync-eligible fields documented on repository
  interfaces; README table of what syncs vs stays local.
- Phase 15 (audit/stabilization/Qalqon rebrand): fixed Gradle catalog TOML
  error + added CameraX/ML Kit/accompanist deps; FrameEvent.faceCount;
  @ExperimentalGetImage; engine dollar interpolation bugs; RecognitionDebug
  VM (missing body, callback mismatch, non-exhaustive when); enrollment
  parent accountId bug + CAPTURING gating; SYSTEM_ALERT_WINDOW declared;
  protection screen starts headless camera with permission flow; dead
  FaceEmbeddingPipeline removed; app renamed to Qalqon (package stays
  uz.faceguard.app); README restructured with product doc + dev summary.

- 2026-09-12 analysis snapshot: repository is currently single-module (`:app`) with layered package architecture (`core/data/domain/feature/navigation/sync`) inside the app module. Gradle stack: AGP 8.7.2, Kotlin 2.0.21, KSP, Hilt, Compose BOM 2024.12.01, Room 2.6.1, CameraX 1.4.1, ML Kit face-detection 16.1.7, Accompanist permissions 0.36.0; compile/target SDK 35, min SDK 26, Java/Kotlin target 17.

- 2026-09-12 UX cleanup: Home screen is parent-first by default (4 primary actions: parent profile, child profiles, protected apps shortcut, settings). Activity/privacy/help moved under collapsible additional section; debug tools isolated under collapsible developer section. Settings screen now accepts `initialTab` so Home can deep-link directly to Protected Apps tab.

- 2026-09-12 Protected apps reliability: `ProtectedAppsRepositoryImpl.refreshFromDevice()` now merges discovered launchable apps with existing Room rows (preserving `isProtected`) instead of rewriting them, removes only stale uninstalled rows, runs the PackageManager query on `Dispatchers.IO`, filters out disabled/system/internal packages and the Qalqon package itself. Settings apps tab auto-refreshes on open and shows loading/refreshing + selected count in Uzbek; Home shows the real protected count.

- 2026-09-12 Biometric enrollment->recognition wiring: enrollment now persists a real on-device geometry template (ML Kit landmarks + pose, 19-dim vector, base64) into `ParentProfile.faceTemplateRef` / `ChildProfile.faceTemplateRef` via `saveFaceEnrollment`, replacing the old flag-only `setFaceEnrolled`. `Recognizer` now decodes stored templates and does cosine-similarity matching against live frame features (parent evaluated before children). `FaceCaptureController` extracts features per frame; `FrameEvent` carries `features`. Removed synthetic hash/timestamp matching. Still geometry-based (not biometric-grade / not spoof-resistant) — documented in README.

- 2026-09-12 TFLite MobileFaceNet integration: added `org.tensorflow:tensorflow-lite` and `androidResources.noCompress += "tflite"`. New `FaceEmbeddingModel` seam + `TfLiteMobileFaceNet` (loads `assets/models/mobile_face_net.tflite`, reads output tensor size) + `FaceImageUtils` (rotate/crop). `FaceCaptureController` now converts frames to bitmap (RGBA), rotates upright, crops the face, and runs TFLite embedding with a `FaceFeatureExtractor` geometry fallback when the model is absent. `Recognizer` and `MeanFaceEmbeddingCollector` are dimension-agnostic now (no 19-dim hardcode). Model weights are intentionally NOT committed; see `app/src/main/assets/models/README.md`.

- 2026-09-12 Bundled MobileFaceNet weights: downloaded `MobileFaceNet.tflite` (5,233,396 bytes, sha256 d8ba40c0...) from MIT-licensed syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing into `app/src/main/assets/models/mobile_face_net.tflite`. Verified contract: input float32 [2,112,112,3] NHWC normalized (pixel-127.5)/128, output float32 [2,192]; batch slots are independent. Updated `TfLiteMobileFaceNet` to read shapes from the model, replicate the face across the fixed batch, and L2-normalize the embedding; `FaceEmbeddingConfig` normalization now (pixel-127.5)/128; Recognizer default thresholds set to 0.68/0.62 (cosine) anchored to the model reference.

- 2026-09-12 Protection runtime coherence: replaced the debug-only engine flow with an app-scoped `ProtectionRuntime` (singleton) started from `FaceGuardApp`, activated purely by `protectionEnabled` + a signed-in account. It owns `ForegroundAppMonitor` + `ScanScheduler` + `ProtectionEngine`, applies `ProtectionSettings` (unknown/no-face policies, recovery delay, scan mode, low-battery) live, and shares frames via the `Recognizer` flow. `ProtectionEngine` now ticks every 500ms, honors no-face policy, uses recovery delay, and treats frames older than 1.5s as no-face. Added parent-facing `ProtectionScreen` (route `protection`) with master toggle, setup checklist, live status, emergency PIN unlock and collapsible technical details; removed `ProtectionDebugScreen`.

- 2026-09-12 Parent-facing UI polish: added shared `SectionCard` in `core/ui/Components.kt` and replaced the duplicated private `SettingSection` in Settings. Home setup checklist now names the next step and collapses when complete. ParentProfile/ChildProfiles rebuilt on SectionCard with labeled phone, clear face-enroll primary actions, actionable empty state and radio-select restriction level. Settings sections gained plain-language subtitles; Protected apps tab is carded, count highlighted, whole-row toggle, and a search field appears past 15 apps. Uzbek copy de-jargoned (checking mode / unfamiliar face / no face visible / soft-hard close). Removed 3 now-unused parent string keys.

- 2026-09-23 Group 9 (liveness & anti-spoofing foundation) on branch
  `feature/policy-engine-foundation`, based on Group 8 HEAD 6554a4d. New pure
  `core/liveness` package: `LivenessState` (UNKNOWN/LIVE/SPOOF/NO_FACE/UNSTABLE,
  domain/policy), `LivenessResult`/`LivenessSource`/`LivenessFrame`/
  `LivenessWindow` (~2.5s temporal buffer, independent of engine debounce/TTL),
  `LivenessDetector` + `TemporalLivenessDetector` (model-score path first, else a
  passive pose-motion heuristic; the heuristic never emits SPOOF and never marks a
  motionless face LIVE), `LivenessEvaluator`, and an `AntiSpoofModel` seam.
  Pipeline: `FrameEvent` gained `liveProbability` + `FaceQuality.headEulerAngleX`;
  `FaceCaptureController.setAntiSpoofModel` populates it. `ProtectionEngine` gained
  a default-constructed `livenessEvaluator` + `liveness: StateFlow`, feeds the
  window in `evaluate()`, and passes `liveness.state` into `PolicyContext`.
  `ProtectionRuntimeState.liveness` mirrors it (account-scoped; cleared on account
  change / sign-out / deactivate). Policy: the `DefaultPolicyEvaluator` SPOOF gate
  runs BEFORE the parent override (a spoofed identity is never trusted);
  `PolicySettings.spoofAction` defaults to SOFT_BLOCK (not persisted yet).
  Accessibility/FGS untouched; no INTERNET; no UI change; no anti-spoofing model
  bundled (photo/screen/replay not provably defeated - documented in README).

- 2026-09-23 Phase 9 (recovery & state restoration), branch `feature/phase9-recovery`
  off Group 9 HEAD 18012c3. `ProtectionEngine` recovery is now generation-guarded:
  `recoveryJob` + `recoveryGeneration` token, so a stale timer can never release or
  shorten a newer cycle; `beginRecovery` cancels any previous timer (no duplicates);
  a (re)block invalidates the pending timer; `clearBlock()` invalidates it too.
  New `cancelRecovery()` drops a pending window without logging PROTECTION_RELEASED
  (idempotent, generation-bumping). `stop()` and `emergencyUnlock()` invalidate the
  timer. New `blockedApp: StateFlow<String?>` = the app the current cycle holds
  (best-effort restoration target; overlay removal returns the user to the
  still-foreground app - third-party internal state is never restored/claimed).
  `ProtectionRuntime` now cancels recovery on account change, when protection must
  not run (`syncActive`), and on `stop()`, and mirrors `blockedApp` into
  `ProtectionRuntimeState.blockedApp`. Events unchanged: CHILD_BLOCKED once per
  block transition, PROTECTION_RELEASED once per recovery cycle, no timer-tick events.
  Tests: `ProtectionEngineRecoveryLifecycleTest` + `ProtectionRuntimeRecoveryTest`.
  JVM regression 87/87 unchanged.

- Phase 5 Step 5 (schedule configuration UI): new `feature/schedule/` package.
  `ScheduleEditorState` is a UI state wrapper only - the persisted shape is still
  `ScheduleDraft`/`ScheduleDays`/`ScheduleWindow`/`ScheduleRule`. `ScheduleConfigController`
  holds the whole per-child configuration logic (list, editor, save, delete-with-confirmation,
  enabled toggle) and is Android-free, so it is JVM-tested against a fake `ScheduleRepository`;
  the Hilt ViewModels are thin wrappers. `ScheduleLabels.kt` maps days/modes/actions/times to
  string-resource ids, so no visible text is hardcoded, and only `SCHEDULE_ACTIONS` (derived from
  IMPLEMENTED_ACTIONS) is offered - DIM/BLUR/BLACK_SCREEN never are. Routes
  `child_schedules/{childId}` + `child_schedule_editor/{childId}?scheduleId={scheduleId}` are
  reached from the child-policy screen, so schedules are always per-child. App selection reads only
  `ProtectedAppsRepository.protectedApps` filtered to `isProtected`, so a schedule can never target
  an arbitrary app. 71 new strings in all three locales (441 -> 512, parity verified).
  JVM total 664 -> 720 (+56: ScheduleEditorStateTest, ScheduleConfigControllerTest), 0 failures.

- Phase 5 Step 6 (runtime activation + schedule activity events): schedules now surface in
  `ProtectionRuntimeState.scheduleResolution` (the domain `ScheduleResolution` itself, no second
  state type) and every *effective* state change is logged once as a new
  `ActivityEventType.SCHEDULE_CHANGED`, with child/schedule ids in the existing `detail` field -
  no new column, no migration. Transition detection lives in
  `domain/schedule/ScheduleTransition.kt`: `ScheduleStateIdentity` (None / Active(childId,
  scheduleId) / Conflict(childId, sorted ids)) is the stable id-based identity, `effectiveScheduleState`
  folds in the child + protected-app gates, and `ScheduleTransitionTracker` reports a change once
  and then treats it as the new baseline. `ProtectionEngine` owns the tracker (engine lifecycle, not
  UI), recomputes the effective schedule on the existing 500ms tick, publishes
  `effectiveSchedule` only when it really changed, and logs through its existing `onEvent` hook -
  no new timer, loop, service or scheduler. Parent/unknown/no-face carry no child id, so they
  collapse to None and can never be reported as under a child schedule. SCHEDULE_CHANGED does not
  produce a notification (`notificationEventFor` else-branch). JVM total 720 -> 766
  (+46 ScheduleTransitionTest), 0 failures.

- Phase 5 Step 7 (integration tests + closure audit): audited the whole Phase 5 stack and added
  `Phase5IntegrationTest` (46 tests) - a cross-step harness wiring the production
  `ScheduleConfigController` + `resolveScheduleForPackage`/`ScheduleResolver` +
  `DefaultPolicyEvaluator` + `ScheduleTransitionTracker`/`effectiveScheduleState` together, with
  only the two Room-backed adapters (`ScheduleRepository`, protected-app catalog) substituted.
  Covers scenarios A-Z (basic activation, non-targeting/unprotected app, parent precedence,
  priority, conflict, restriction combination incl. MUTE, cross-midnight, day/zone, disabled,
  child/account isolation, enable/disable, time boundary, persistence round trip, target
  replacement, all transition kinds, restart) plus permutation/order-independence and a
  whole-day no-spam sweep. Audit found NO production defects: single resolver/evaluator/executor/
  DAO/repository/event system, no duplicate timer or scheduler, no Phase-5-introduced
  WorkManager/AlarmManager/notification/service, no Phase 4 file touched, migration DDL still
  byte-identical to Room's generated schema, 513 keys in each locale (72 schedule-related).
  812 JVM tests / 53 classes, 0 failures. Instrumented tests compile (APK produced) but cannot
  execute - no device and no /dev/kvm in this container.

- Phase 6 Step 1 (eye-safety domain foundation): new `domain/eyesafety/` package - pure, no
  Android/Room/Compose/coroutines. `EyeSafetyFrame` (timestampMs/facePresent/faceWidthRatio, with
  `usableRatio` returning null for no-face, NaN/Infinity, <=0 and >1 so invalid input is neither
  "safe" nor "far"), `EyeSafetyConfig` (4 explicit thresholds + confirmFrames/maxWindowAgeMs/
  maxWindowFrames/minimumPresenceRatio, `require`-validated with NO silent clamping;
  `EyeSafetyConfig.DEFAULT` = 0.30/0.27/0.40/0.35), `EyeSafetyWindow` (age + frame eviction,
  strictly increasing timestamps), `EyeSafetyDetector`/`TemporalEyeSafetyDetector` (returns
  EyeSafetyState; UNKNOWN/enabled/presence/confirmFrames gates, then hysteresis + persistence over
  the last confirmFrames usable observations) and `EyeSafetyEvaluator` (observe/result/reset, owns
  the confirmed state). Reuses the existing `domain.policy.EyeSafetyState`; no new enum.
  No-face is UNKNOWN, never SAFE and never a distance. JVM total 883 (+71).

- Phase 6 Step 2 (eye-safety policy integration): `PolicySettings` gains
  `eyeSafetyWarningAction` / `eyeSafetyDangerAction` (both `ProtectionAction`, both default ALLOW =
  no-op). `DefaultPolicyEvaluator` gains `applyEyeSafety` and the child branch now reads
  `applySchedule(applyEyeSafety(evaluateChild(...)))` - eye safety inner, schedule outer. It is a
  restriction layer: `restrictionRank` maximum, so eye safety can only tighten (never unlock);
  UNKNOWN/SAFE return the decision untouched, and it is gated on `isProtectedApp` exactly like
  schedules. Parent / unknown / no-face / obstructed and the parent-device bypass never consult it.
  On an equal rank with a schedule the inner layer's trigger survives (the outer layer only
  overrides on a strictly greater rank) - same rule as the ordinary decision under a schedule, so
  no tie-breaker was invented. Phase 5 tests unchanged and green. JVM total 883 -> 927 (+44
  EyeSafetyPolicyTest). No Room/migration/UI/runtime change (DB stays v9).

- Phase 6 Step 3 (eye-safety persistence, DB v9 -> v10): new table `child_eye_safety` with
  composite PK `(accountId, childId)` and no extra index (the PK index covers the lookup, as with
  `schedule_app_targets`). Columns: accountId, childId, enabled, warningEnter/Exit and
  dangerEnter/Exit threshold **percentages** (Int, `30` = 30%), confirmFrames, warningAction,
  dangerAction (each `ProtectionAction.name`), updatedAt. Config only - no ratio, window,
  EyeSafetyState, frame or per-observation data. `MIGRATION_9_10` is a single additive
  `CREATE TABLE IF NOT EXISTS` whose DDL is byte-identical to Room's generated schema (verified
  against FaceGuardDatabase_Impl.java); the chain is now v3..v10 and no historical migration was
  edited. Domain `EyeSafetyRepository` + `ChildEyeSafetyConfig` compose the Step 1 `EyeSafetyConfig`
  (so its invariants re-run on load) and add only the scope, the two actions and updatedAt; the
  three temporal params are engine tuning and take domain defaults on load. Mapper converts whole
  percents <-> ratios and throws on an unknown action or a corrupt/invalid row instead of fixing
  it. **Absence is the answer**: a missing row means unconfigured, nothing is seeded, and no default
  row is ever written. Repo is bound in Hilt but has NO consumer yet (no runtime/UI). 7 new test
  files (1 JVM mapper + DAO/repo/migration instrumented + 6 existing migration tests updated for
  v10). JVM total 927 -> 944 (+17). DB version 10.

- Phase 6 Step 4 (eye-safety runtime integration): the child's eye-safety state now reaches the
  existing policy pipeline, with no second engine/evaluator/executor/scheduler/service/camera. New
  `core/eyesafety/`: `eyeSafetyFrameOf(quality, timestampMs)` (the boundary adapter - takes
  `FaceQuality`, not `FrameEvent`, so it stays Android-free and JVM-testable; no-face becomes
  `EyeSafetyFrame.noFace`, never a zero ratio) and `EyeSafetyObservationState` (owns only "which
  child's evaluator is active and when to reset"; delegates thresholds/hysteresis/confirmFrames to
  the Step 1 `EyeSafetyEvaluator`). `ProtectionEngine` gained `eyeSafetyConfigLookup` and calls
  `recordEyeSafety(frame, recognizedChildId, now)` at the point where the frame AND the confirmed
  identity are both available, passing the state into `PolicyContext.eyeSafetyState`; a
  `policySettings()` override supplies the child's persisted warning/danger actions through the
  existing Step 2 settings mechanism. `ProtectionRuntime` injects `EyeSafetyRepository`, caches the
  per-child config via `refreshChildEyeSafety()` (observeConfig per child, cancelled/re-created on
  account/child change - no Room on the tick) and installs the lookup; account change and
  `stop()`/`deactivate()` reset the session. Gate: no recognised child (parent/unknown/no-face/
  obstructed) or unconfigured/disabled config ⇒ UNKNOWN + a no-op, so one child's DANGER can never
  leak to another child or to a non-child. JVM total 944 -> 982 (+38 EyeSafetyRuntimeTest).
  DB/schema/migration/UI/recognition/ScanScheduler/liveness untouched; Step 3 repository consumed
  unchanged.

- Phase 6 Step 5 (eye-safety configuration UI + localization): new `feature/eyesafety/` package, mirroring the Phase 5 schedule config screen. `EyeSafetyEditorState` is a UI state wrapper only - it holds thresholds/confirmFrames as digit-only **text** (whole percents, so no float reaches the UI) and builds the real `ChildEyeSafetyConfig` via `toModel()`, so the Step 1 `EyeSafetyConfig` invariants stay the single authority (nothing is clamped, swapped or silently repaired). `EyeSafetyConfigController` is Android-free and JVM-tested against a fake `EyeSafetyRepository`: one-shot load (no live collector, so a refresh cannot clobber unsaved edits), a distinct unconfigured state, validation, save, and both failure paths; the Hilt ViewModel is a thin wrapper taking `childId` from the route. `EyeSafetyConfigController.create()` forces `EyeSafetyConfig.DEFAULT.copy(enabled = false)` - the domain default is enabled=true, and an unconfigured child must not open with eye safety apparently on. A missing row stays missing until an explicit save; disabling updates the row in place (never deletes). Entry card added to the per-child policy screen beside the schedules entry, new route `child_eye_safety/{childId}`. 42 new strings x 3 languages (513 -> 555, full key-set equality verified by `EyeSafetyStringsLocalizationTest`, which also checks no duplicates/placeholders/empty/declared-but-unused).   Literal `%` strings carry `formatted="false"`. JVM total 982 -> 1041 (+59).

- Phase 6 Step 6 (integration tests + closure audit): no production change - the audit found no
  defect. Added `Phase6IntegrationTest` (34 JVM tests), the end-to-end suite the other Phase 6
  tests could not provide: they drive one layer each (`EyeSafetyPolicyTest` sets `eyeSafetyState`
  on the context, `EyeSafetyRuntimeTest` feeds the observation state by hand), so nothing walked a
  **saved** configuration through to the decision. This wires the real `EyeSafetyConfigController`
  -> `EyeSafetyRepository` -> the runtime's `observeConfig` cache -> `eyeSafetyConfigLookup` ->
  `eyeSafetyFrameOf(FaceQuality)` -> `EyeSafetyObservationState`/`EyeSafetyEvaluator` ->
  `PolicyContext.eyeSafetyState` -> `DefaultPolicyEvaluator`, plus `resolveScheduleForPackage` for
  the schedule composition. Only two substitutions: the Room-backed repository (instrumented-tested
  in Step 3) and `ProtectionRuntime.refreshChildEyeSafety`'s one-line cache copy, which needs an
  Android `Context` to instantiate. Covers scenarios A-L end to end (SAFE/WARNING/DANGER + the
  configured actions and triggers reaching policy, DANGER hysteresis and recovery, no-face never
  SAFE, parent and unknown after a child, child A->B isolation, protected vs unprotected, and the
  schedule and screen-time compositions), the configuration->runtime propagation chain (new save,
  updated threshold, disable, delete, sibling isolation, account switch) and the stale-state audit.
  Audit verified: 555/555/555 localization parity with identical key sets, migration chain
  v3..v10 all registered with no destructive fallback, `MIGRATION_9_10` DDL still byte-identical to
  Room's generated schema, exactly one of every singleton, no parallel Eye Safety
  engine/scheduler/service, `faces.firstOrNull()` unchanged, no eye-safety notification or liveness
  work, and no Room/DAO/suspend on the 500ms evaluation path. JVM total 1041 -> 1075 (+34).
