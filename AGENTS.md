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
- No JDK/Android SDK/Gradle here - source-complete authoring only; build in
  Android Studio (AGP 8.7.2, Kotlin 2.0.21, SDK 35).
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
