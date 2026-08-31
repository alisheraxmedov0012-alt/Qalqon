# Qalqon — smart face-based parental control (Android MVP)

Qalqon is an offline-first, privacy-first parental control app. A parent
creates a local account, enrolls their own face and their children's faces,
picks protected apps, and the device recognizes who is holding it: a parent
sees no restrictions, a child gets blocked, unknown users follow the chosen
fallback policy. All face processing happens on-device.

- **Package:** `uz.faceguard.app` (internal name predates the "Qalqon" brand;
  user-visible naming is Qalqon everywhere)
- **Stack:** Kotlin, Jetpack Compose, Material 3, MVVM, Hilt, Room, DataStore,
  CameraX + ML Kit face detection
- **Default locale:** Uzbek (Latin). English and Russian mirrors ship in
  `values-en` / `values-ru`; no user-facing string is hardcoded.

## What currently works

- Splash → welcome → register (full name, phone, PIN) / login with
  salted+hashed PIN verification, session persistence in DataStore
- Parent profile (auto-created) with face enrollment status
- Child profiles: add / edit / delete with restriction level + enrollment status
- Face enrollment flow (4 guided steps, camera preview, progress) for both
  parent and children; status chips update everywhere
- Protected apps catalog: real installed apps (PackageManager), toggle
  protection, count on home
- Settings: block policy (allow/soft/hard), scan mode, camera threshold,
  recovery delay, low-battery behavior, PIN change, emergency reset, reset tools
- Privacy screen (local storage, backups, face data, no network), help/about
- Activity log: recognition/block/unlock events, newest 100, clear action
- Recognition debug + protection debug screens (live camera, decision
  pipeline, emergency PIN unlock) gated behind `DebugFlags`
- Usage-access + camera + overlay permission flows with Uzbek guidance

## Partially implemented

- **Protection engine** runs only while the protection debug screen is open
  (headless camera analyzer + foreground monitor + overlay/audio effects).
  There is no background service yet, so protection does not apply once the
  user leaves the screen.
- **Overlay permission** (`SYSTEM_ALERT_WINDOW`) is declared and checked, but
  blocking other apps system-wide needs an AccessibilityService — see
  limitations.
- **Unknown-user / no-face fallback policy** is implemented in the engine but
  only exercised in the debug flow.

## Placeholder only (clearly marked in code)

- **Face embedding/matching is synthetic** (`TODO(real-embedding)`): the
  recognizer scores a deterministic timestamp-seeded pseudo-embedding. The
  full pipeline works end-to-end, but matching is not biometrically accurate.
  Swap points: `core/embed/FaceEmbeddable`, `core/recognition/Recognizer`.
- `sync/` gateways are no-ops (see Phase 14 notes below).

## Required Android permissions

| Permission | Why |
|---|---|
| `CAMERA` | Face enrollment + recognition frames |
| `PACKAGE_USAGE_STATS` | Detect which app is in the foreground |
| `SYSTEM_ALERT_WINDOW` | Draw the blocking overlay |

No `INTERNET` permission. The app cannot talk to a network.

## Known Android limitations

- System-wide app blocking on modern Android requires an AccessibilityService
  (or Device Admin); a WindowManager overlay only works while our process is
  alive and permission is granted.
- UsageStats foreground detection can lag or be throttled on some OEM builds.
- Room uses `fallbackToDestructiveMigration` — no migration history yet.
- No automated tests: validation is via Python source checks (no Android
  toolchain in this environment).

## Recommended next development order

1. AccessibilityService for system-wide protection (biggest product gap)
2. Real on-device embedding model behind `FaceEmbeddable`/`Recognizer`
3. Foreground service so protection survives leaving the debug screen
4. Encrypted template storage + Room migrations
5. Unit/UI tests once a build toolchain is available

## Privacy notes

Face frames never leave the device and are never persisted — only a
template reference (currently synthetic) and enrollment flags are stored.
PINs are salted and hashed; the raw PIN is never stored. `allowBackup=false`.

## Offline-first notes

All data lives in Room + DataStore. The `sync` package defines optional
future backend sync (account metadata, child profiles, settings backup) via
gateway interfaces bound to no-ops; PIN material, face templates, the
activity log, and the protected-apps catalog are local-only forever.

## Battery strategy notes

Scanning is event-driven (`core/scan/ScanScheduler`): the camera only runs
while a scan window is open, triggered by a protected app opening, screen-on,
or interaction, followed by a cooldown. Scan modes (Battery Saver / Balanced /
Strict) trade window length vs cooldown; low battery can force Battery Saver.

## Developer summary (audit pass)

**Fixed:** broken Gradle catalog (TOML syntax error, missing CameraX/ML Kit/
accompanist dependencies), `FrameEvent.faceCount` mismatch, missing
`@ExperimentalGetImage` opt-in, literal `\$` string escapes in the engine,
`RecognitionDebugViewModel` (missing function body, callback type mismatch,
non-exhaustive `when`, `accountRepositoryRef` hack), parent face enrollment
silently writing to a nonexistent account id, enrollment frame gating
(IDLE phase deadlock), missing `SYSTEM_ALERT_WINDOW` permission, dead
`FaceEmbeddingPipeline`, branding renamed to Qalqon.

**Still risky:** synthetic matching (not biometric), overlay blocking is
demo-scoped, UsageStats lag on OEM devices, no tests.

**Needs manual testing:** full enrollment flow on a real device, camera
permission denial paths, overlay display with/without permission, protection
debug screen with a real protected app in the foreground.

**Improve next:** AccessibilityService, real embeddings, foreground service.

---

## Phase 15 (audit, stabilization, Qalqon branding) — what changed since Phase 14

- Fixed the version catalog: TOML syntax error (`,,`), added missing
  `mlkit-face` version, CameraX (core/lifecycle/view) and
  accompanist-permissions dependencies the code already imported.
- `FrameEvent` gained the `faceCount` field the controller passed.
- `FaceCaptureController`: `@ExperimentalGetImage` opt-in, shared
  `buildAnalysis`, new headless `startAnalyzerOnly()` for the
  protection screen, ImageProxy leak on null image fixed.
- `ProtectionEngine`: literal `\$` escapes now interpolate.
- `Recognizer.evaluate` accepts a nullable parent (engine reality).
- Recognition debug: VM body/callback/exhaustive-when fixes.
- Protection debug: camera permission flow + headless camera start, so
  the engine actually receives frames.
- Enrollment: parent subject now resolves the real account id (was
  writing to id -1), frames gated to the CAPTURING phase, callback
  wired through the `Callback` interface.
- Manifest: `SYSTEM_ALERT_WINDOW` declared (overlay permission flow was
  broken without it).
- Removed dead `FaceEmbeddingPipeline`; branded everything Qalqon;
  README restructured around the product.

## Phase 14 (future sync scaffolding) — what changed since Phase 13

New `uz.faceguard.app.sync` package prepares the architecture for optional
backend sync without adding any network code:

- `AccountSyncGateway`, `ChildProfileSyncGateway`, `SettingsSyncGateway`
  interfaces with `SyncResult` semantics.
- `NoOp*SyncGateway` implementations bound in Hilt by default — sync is
  permanently disabled until real gateways are provided.
- `SyncCoordinator` as the single fire-and-forget fan-out point; swapping
  DI bindings later enables sync without touching call sites.
- Repository interfaces now document which fields are sync-eligible.
- No INTERNET permission, no networking dependencies, no behavior change.

## Phase 13 (stabilization & cleanup) — what changed since Phase 12

- Fixed a localization defect: apostrophes in `strings.xml` had been
  over-escaped across phases (`\\\'`), which would have rendered literal
  backslashes in the UI. All three locales are normalized.
- Removed 21 dead string keys per locale (200 keys each, parity verified).
- Fixed navigation: `recognition_debug` and `protection_debug` routes were
  referenced from Home but never registered in the NavHost — registered now.
- Fixed the emergency PIN path and removed dead `PinHasher` leftovers.
- `ProtectionDebugViewModel`: proper `private val` constructor injection
  (removed the `accountRepositoryRef` workaround); added the missing
  `ScanScheduler` import and aligned block-policy string keys.
- Home: foreground monitor now runs on `rememberCoroutineScope()` instead of
  a leaked `MainScope()`; debug entries are gated behind
  `core/debug/DebugFlags.DEBUG_SCREENS_ENABLED`.
- Placeholder comments converted to explicit `TODO(real-embedding)` markers.

## Phase 12 (MVP polish: onboarding, privacy, reset, activity log) — what changed since Phase 11

- Welcome onboarding now explains the four core promises in Uzbek: separate
  parent/child recognition, fully offline operation, on-device processing,
  and battery-friendly scanning.
- Home shows a seven-step setup checklist (account, parent profile, parent
  face, child added, child face, protected apps, protection enabled).
- Local activity log (`activity_events` table, DB v3): child/parent
  recognized, unknown user, protected app opened, child blocked, parent
  unlocked, emergency PIN unlock. `ActivityLogScreen` lists the newest 100.
- `PrivacyScreen` and `HelpScreen` (static Uzbek copy, string resources).
- Settings gains a "Ma'lumotlar" tab: delete parent face data, delete each
  child's face data, and a full local reset (Room + DataStore + session)
  behind a confirmation dialog.
- Model reconciliation: `ScanMode` (BATTERY_SAVER/BALANCED/STRICT),
  `BlockPolicy`, `AppSettings` (incl. noFacePolicy + lowBatteryBehaviorEnabled),
  `ProtectedAppEntity` added to the Room schema (was missing from
  `@Database.entities`), and the emergency PIN check now goes through the
  salted `AccountRepository.verifyPin` instead of comparing hashes in the UI.

## Phase 11 (resilience: unknown / no-face / obstruction / instability) — what changed since Phase 10

- `RecognitionResult` gains `CameraPossiblyObstructed` and
  `UnstableRecognition`; the engine classifies raw results before acting.
- Multi-frame confirmation (3 consecutive same-class frames) plus a rolling
  confidence band (spread > 0.25 → hold state) keep transitions stable.
- No-face streak ≥ 6 while a protected app is active → obstruction heuristic;
  obstruction follows the unknown-user policy (fail-safe toward blocking).
- Recovery delay is read from settings and enforced on every `NoFace`
  transition; parent recognition still unlocks immediately.
- `ProtectionDebugScreen` now shows recognition result, confidence, fallback
  policy, state, and the last transition reason — all in Uzbek.

## Phase 10 (battery-conscious scan scheduling) — what changed since Phase 9

- `core/scan/ScanScheduler` makes recognition event-driven: the camera only
  runs while a scan window is open. Windows open on protected-app-foreground,
  screen-on, or interaction triggers; they close on cooldown expiry.
- Scan modes (`BATTERY_SAVER` / `BALANCED` / `STRICT`) map to window/cooldown
  pairs (3s/30s, 5s/15s, 8s/5s). Low battery (if enabled in settings) forces
  Battery Saver regardless of the selected mode.
- `ProtectionEngine.evaluate` now gates recognition on `scheduler.scanning`;
  the camera is never left on between windows.
- `ProtectionDebugScreen` shows scan mode, camera on/off, cooldown seconds,
  and the last trigger reason in Uzbek.

## Phase 9 (protection engine) — what changed since Phase 8

- `core/protection` with `ProtectionEngine` (state machine: UNPROTECTED /
  SOFT_BLOCKED / HARD_BLOCKED / RECOVERING), `OverlayControllerImpl` (black
  overlay via WindowManager, SYSTEM_ALERT_WINDOW), and `PinHasher`.
- `ProtectionDebugScreen` (route `protection_debug`) shows state, last decision,
  and emergency PIN unlock. Home has a new debug button.
- `Recognizer` now exposes a `frames` SharedFlow; `FaceCaptureController`
  publishes each detected frame so the engine can evaluate without changing
  the enrollment path.
- Debounce (1.2 s) + recovery delay (3 s) gate state transitions; parent
  recognition unlocks immediately; child recognition hard-blocks; unknown
  follows the settings policy.
- Volume mute is best-effort via `AudioManager.ADJUST_MUTE`; interaction
  blocking uses `FLAG_NOT_TOUCHABLE` on the overlay view. Both are documented
  MVP limitations — accessibility-based interaction blocking lands later.

## Phase 8 (protected apps + foreground monitor) — what changed since Phase 7

- ProtectedAppsRepository now reads the actual installed launchable apps
  (PackageManager) and persists each selection in Room; toggles per-package
  flip `isProtected`.
- Settings Apps tab has a refresh button; selection list comes from Room.
- Home shows the count of protected apps and a debug card for the current
  foreground app via UsageStats. When usage access isn't granted, the card
  shows the usage-access permission guidance with a button to the settings.
- Manifest declares `PACKAGE_USAGE_STATS` (special access, granted through
  system settings — not a runtime dialog).
- Documented limitation: UsageStats events can lag on some OEMs; the protection
  phase may still switch to accessibility-based monitoring.

## Phase 7 (matching debug screen) — what changed since Phase 6

- `core/recognition` module with interfaces `FaceDetector` / `FaceEmbedder`
  and a cosine-similarity `Recognizer` that scores against enrolled
  `faceTemplateRef`s on parent + children.
- Sealed `RecognitionResult` (ParentRecognized / ChildRecognized / Unknown /
  NoFace) with a configurable `Thresholds` (parent=0.82, child=0.78 default).
- Home has a new debug button that opens `RecognitionDebugScreen`:
  live camera preview + status in Uzbek + confidence display.
- Today the pipeline uses a deterministic placeholder (seeded random vectors)
  until the embedding implementation lands; the match seam is behind
  `Recognizer.evaluate(frame, parent, children)`.
- No global app blocking integrated yet — this phase stays non-destructive.

## Recognition pipeline (debug-level)

```
Camera frames (FaceCaptureController)
  → ML Kit detection (InputImage)
  → Recognizer.evaluate(frame, parentProfile, children)
      → template decoding (placeholder decode: hash-seeded)
      → cosine-similarity vs each member
      → threshold decision
          • ParentRecognized / ChildRecognized (above thresholds)
          • Unknown (below)
          • NoFace (no templates)
  → Result rendered in Uzbek on RecognitionDebugScreen
```

Limits & next steps (kept deliberately at code level too):
- `templateEmbeddingDecode` is a placeholder hash-based conversion; will be
  replaced when the real embedding pipeline lands. Current threshold
  defaults are developer-tuned; make them user-configurable if UX demands it.
- No persistence of recognition events for privacy (no timestamps / raw scores
  outside of debug UI, matching architecture privacy note).

## Phase 6 (enrollment implementation) — what changed since Phase 5

- CameraX (front camera) binding in `FaceCaptureController`: a `Preview` +
  `ImageAnalysis` use case; analysis runs on ML Kit on-device face detection.
- Only frames that contain at least one detected face are handed to the
  callback (`FrameEvent` — the InputImage seam).
- `FaceEmbeddable` interface behind the controller; today the placeholder
  `PrivateStorageEmbeddable` logs timestamps in-app-private and returns a
  template reference. The future face embedding replaces only this.
- `EnrollmentSteps` held in `core/pipeline` (keeps viewmodel/screen small).
- On success: DAO `setFaceEnrolled(...)` now bumps `enrollmentStatus`,
  `enrollmentVersion`, `lastEnrollmentAt` alongside the boolean.
- Existing limitations (documented in code / README): frames stored as
  timestamps rather than actual template bytes; thresholds use a fixed
  count (placeholder); camera errors silently absorbed in the catch-all —
  next phase replaces with proper `Exception` handling.

## Storage chaining for enrollment

Change is staged behind a single abstraction seam:
```
FaceCaptureController (CameraX ← Preview + ImageAnalysis)
  ├─ emits FrameEvent (MLKit-detected face)
  └─ FaceEmbeddable (today: placeholder impl, later: template pipeline)

EnrollmentSteps (step resources anchor)
DAO setFaceEnrolled (increments enrollmentStatus / version / lastAt)
```

## Phase 5 (enrollment scaffold) — what changed since Phase 4

- CAMERA permission (manifest + guided Accompanist-based request) before any
  camera work.
- Routes: `parent_face_enrollment`, `child_face_enrollment/{childId}` reachable
  from ParentProfile and from each child row.
- Enrollment metadata on both profiles: `faceTemplateRef`,
  `enrollmentStatus` (NONE/IN_PROGRESS/ENROLLED/FAILED/CANCELED),
  `enrollmentVersion`, `lastEnrollmentAt` — mapped entity → domain and
  back.
- Scaffolded `FaceEnrollmentScreen` with privacy-first explanation, placeholder
  preview, guided steps (to'g'riga / chapga / o'ngga / biroz yuqoriga
  qarang) in Uzbek, and a state machine covering permission / stepIndex /
  progress / success / canceled / failure — CameraX + recognition just
  plug in later.
- Accompanist-permissions dependency keyed through the catalog for the
  permission flow.

## Enrollment architecture plan (phase 6 → face integration)

```
Screen (FaceEnrollmentScreen)
  ├─ permission gate (Accompanist) → CAMERA granted or exit
  ├─ placeholder preview Card ← swapped for CameraX PreviewView
  └─ guided steps list in Uzbek         ← same order, no digit hardcode

ViewModel (FaceEnrollmentViewModel)
  ├─ startEnrollment(subjectId)  → Flow<EnrollmentEvent> from RecognitionEngine
  ├─ onCaptureDone() → stopCamera → pick EmbeddingEngine.collect(frames)
  └─ onCameraError() → failures surface via Ui(success / canceled / failure)

RecognitionEngine (feature/enrollment/RecognitionEngine.kt)
  ├─ interface FaceEmbedder (vision seam — ML Kit Vision later)
  └─ interface FaceRecognitionEngine<Boolean> (identity match)

Repository access
  ├─ ParentProfileRepository.setFaceEnrolled(true)
  └─ ChildProfileRepository (same) — stay at the data layer
```

Camera integration next: swap the placeholder preview for a CameraX preview,
collect frames at the four steps, embed via a bundled ML Kit model, and
persist only the `faceTemplateRef` — never the raw frames.

## Phase 4 (rules) scope — what changed since Phase 3

- `AppSettings` extended: protection toggle, scan mode (`BALANCED` /
  `BATTERY_SAVER` / `STRICT`), recovery delay, unknown-user policy, no-face
  policy (both mapped to `BlockPolicy` enum: `ALLOW` / `SOFT_BLOCK` /
  `HARD_BLOCK`), and `lowBatteryBehaviorEnabled`.
- All values persist to DataStore via the cleaned `SettingsStore`; the UI reads
  them live from `SettingsRepository`.
- New `ProtectedApp(packageName, appDisplayName, isProtected)` placeholder
  option behind a `ProtectedAppsRepository` interface, today seeded by a
  `ProtectedAppsRepositoryImpl` with well-known packages; the real installed-
  apps query replaces the impl without touching consumers.
- Settings screen gets two tabs (Rules / Apps) with the scaffolded protected
  apps list + rules. Home now shows protection status, scan mode, and the
  current unknown-user policy in a summary card.
- Legacy `RESTRICT/WARN/IGNORE` stored values map safely to `SOFT_BLOCK` /
  `SOFT_BLOCK` / `ALLOW` while loading older data.

## Settings / rule design

```
AppSettings (DataStore-backed)
  protectionEnabled Boolean (master switch)
  scanMode          Enum    (BALANCED / BATTERY_SAVER / STRICT)
  recoveryDelayMs   Long    (cool-down before re-apply)
  unknownUserPolicy BlockPolicy
  noFacePolicy      BlockPolicy
  lowBatteryBehaviorEnabled Boolean

BlockPolicy (ALLOW / SOFT_BLOCK / HARD_BLOCK)
  → used for both unknown-user and no-face rules, mapped to Uzbek labels:
    Ruxsat / Yumshoq blok / Qattiq blok

ProtectedApp (packageName, appDisplayName, isProtected)
  → placeholder entity; toggles kept in-memory in the scaffolded repository,
    to be moved behind Room once real app-list retrieval ships.
```

The protection phase (next) consumes these values from `SettingsRepository`
via the face scanner + restricted-applications controller.

## Phase 3 (profiles) scope — what changed since Phase 2

- Parent profile: one per account (enforced in `ParentProfileRepository.createIfMissing`),
  editable display name, persisted face-enrolled flag (`setFaceEnrolled` ready for the
  face phase), timestamps maintained on create/update.
- Child profiles: N per account; add, edit (name + restriction level), delete with
  confirmation; per-profile face-enrolled flag; timestamps on all mutations.
- Restriction level: `RestrictionLevel` enum (LOW / MEDIUM / HIGH) shown to parents
  via Uzbek labels (Yumshoq / O'rtacha / Qattiq).
- Home screen now aggregates: account header, parent-profile existence + face flag,
  children with face flags, plus protection toggle. `UiState` (loading / success /
  empty / error) everywhere.
- Local-only as before: Room + DataStore, no network, still no INTERNET permission.

## Profile model

```
UserAccount               (1 per session, from Phase 2)
 ├── ParentProfile        1 per account for MVP
 │     id, accountId, displayName, isFaceEnrolled,
 │     createdAt, updatedAt (Room + timestamps on save)
 └── ChildProfile         N per account
       id, accountId, childName, isFaceEnrolled,
       restrictionLevel (LOW / MEDIUM / HIGH),
       createdAt, updatedAt (Room)
```

`RestrictionLevel` maps to user-visible Uzbek labels, kept in
`values*/strings.xml`; translations mirror the same keys. `isFaceEnrolled` is a
plain flag — the actual recognition will arrive in a later (face) phase; it
only "marks seats" until then and is set by `createIfMissing` /
`setFaceEnrolled`.

## Phase 2 (auth) scope — what changed since Phase 1

- Accounts persist in Room (`user_accounts`, unique normalized phone).
- Registration = full name + phone + PIN (4 or 6 digits) with duplicate-phone
  rejection.
- Login = phone + PIN; generic error on failure (no account enumeration).
- Session = current account id in DataStore; survives restarts; Splash routes
  Home-vs-Welcome accordingly; Settings hosts Logout (clears session).
- Reusable Compose kit in `core/ui/`: `AppTextField`, `AppPhoneField`
  (digits-only normalization), `AppPinField` (4/6-digit, masked),
  `AppLoadingButton`, and a `UiState` sealed type (Idle / Loading / Success /
  Error) shared by auth screens.
- `Validation` in `core/util/`: name/phone/PIN checks + `normalizePhone`
  (digits-only; strips '+', spaces, dashes, parentheses).
- All visible text remains Uzbek via string resources; `values-en` and
  `values-ru` mirror the same keys.

## Module structure

```
app/src/main/java/uz/faceguard/app/
├── FaceGuardApp.kt / MainActivity.kt
├── core/
│   ├── theme/Theme.kt
│   ├── ui/{UiState.kt, Components.kt}   reusable fields + loading button + state
│   └── util/Validation.kt               name / phone / PIN rules + normalization
├── data/
│   ├── db/                              Room entities, DAOs, FaceGuardDatabase
│   ├── prefs/                           SessionManager (session), SettingsStore
│   └── repository/                      local repository implementations
├── domain/
│   ├── model/                           UserAccount, profiles, AppSettings, AuthResult
│   └── repository/                      interfaces (framework-free)
├── feature/
│   ├── auth/{Splash,Welcome,Register,Login,CreatePin,RegisterDraft}
│   ├── home/ · parent/ · child/ · settings/  (all UiState-backed)
└── navigation/NavGraph.kt
```

## Auth flow

```
Splash
  ├─ session in DataStore? ─ yes ──► Home
  └─ no session ──► Welcome ──► Register (name+phone) ──► CreatePin
        │                                               (account created,
        ▼                                                session started)
      Login (phone+PIN) ──► Home                         ──► Home

Settings ──► Logout (clear session) ──► Welcome
```

## Local security notes (deliberate MVP choices)

1. **PINs are never stored raw.** We persist `salt + SHA-256(salt+pin)` per
   account (see `SessionManager` + `AccountRepositoryImpl`); only the hash
   flows to Room. Production upgrade path: Android Keystore / PBKDF2/Argon2 +
   rate limiting — SHA-256 is the placeholder noted in code.
2. **Normalization before comparison.** Phones are stored digits-only via
   `Validation.normalizePhone`; both registration and login normalize before
   lookup, so `+998 90 123-45-67` and `998901234567` collide as one identity.
3. **Duplicate phones blocked twice**: pre-check in the repository for a typed
   error, plus a UNIQUE Room index as the durable guard.
4. **No account-enumeration**: wrong phone vs. wrong PIN produce the same
   localized error string.
5. **Session = id only**: DataStore holds the current account id (no PIN, no
   phone); logout removes it, nothing else is rewritten.
6. **Inputs are validated** before reaching the repository (see `core/ui` +
   ViewModels); display errors come from `UiState.Error(resId)` only.

## Known placeholders (from Phase 1, still INTENTIONAL)

- `RegisterDraft` object couples Register → CreatePin (marked in code; will be
  replaced by nav-args or a flow-scoped shared VM in a clean-up pass).
- Parent screen's enroll-face button is a documented placeholder for Phase 3.
- Settings values persist already, but only become behavior once the
  protection phase lands.

## Roadmap

1. Phase 3 — Face: CameraX + ML Kit (bundled, offline) behind a `FaceEmbedder`
   seam; enrollment UX; recognition engine.
2. Phase 4 — Monitoring: accessibility service, bounded scans, fail-closed.
3. Phase 5 — Restrictions: overlay, mute, touch blocking per app.
4. Phase 6 — Hardening: liveness, FAR/FRR tuning, migrations, tests.

## Build

Android Studio (Ladybug+) or JDK 17 + Android SDK 35:

```bash
gradle wrapper --gradle-version 8.9   # if wrapper missing
./gradlew :app:assembleDebug
```

First run: Splash → Welcome → Register → CreatePin → Home (child can be added).
Restart routes straight to Home; profile/summary state is rehydrated from Room.
Logout is under Settings. No network needed.

## Guidelines for contributors/agents

- Do not add INTERNET without explicit product decision.
- Keep all user-facing text in resources (default Uzbek Latin; mirror en/ru).
- Raw PIN must never reach the data layer in any future change.

## Future backend sync (planned, not implemented)

The `sync` package defines what would leave the device **if** an optional
account-sync feature ships later:

| Syncs in a future version | Stays local-only forever |
|---|---|
| Account metadata (name, phone) | PIN (raw, hash, salt) |
| Child profiles (name, level) | Face templates / embeddings |
| Settings backup | Activity log entries |
| | Protected-apps catalog (device-specific) |

Core flows (auth, enrollment, recognition, protection) never depend on
sync; the app remains fully functional with sync disabled or absent.

## Known limitations (MVP)

- **Synthetic face matching.** The recognizer compares a frame-derived
  pseudo-embedding against a hash-seeded template; it demonstrates the full
  pipeline (capture → recognize → protect) but is not biometrically accurate.
  Marked with `TODO(real-embedding)`.
- **Accessibility-service blocking is not implemented.** Restrictions apply
  only while the debug protection screen hosts the engine; a production app
  needs an AccessibilityService (or Device Admin) to overlay other apps
  reliably. The overlay permission path exists but is demo-scoped.
- **No real migrations.** Room uses `fallbackToDestructiveMigration`; a
  release build needs explicit migrations.
- **No automated tests.** Validation is via Python source checks only (XML
  parse, brace balance, string parity); a JVM/CI toolchain was unavailable.
- **Activity log retention** is capped at the newest 100 events with no
  automatic pruning job.

## Future improvements

- Real on-device embedding model (e.g. FaceNet-style TFLite) behind the
  existing `FaceEmbeddable` / `Recognizer` abstractions.
- Foreground service + AccessibilityService for system-wide protection.
- Encrypted template storage (SQLCipher / EncryptedFile).
- Proper Room migrations and unit/UI tests once a build toolchain exists.
