# QALQON — Stage 5: Background & Process Reliability

> Stage 5 deliverable (roadmap 5/12). Question this stage answers with code and tests:
>
> **"Is the architecture enough that QALQON is not an app that only protects while its
> own UI is open?"**
>
> **Honesty contract.** No physical device and no ADB are available in this
> environment (`/dev/kvm` absent; the emulator cannot run). Every device-dependent
> behaviour below is marked **NOT TESTED** or **ANDROID-LIMITED**; nothing is claimed
> as device-verified. The JVM suite runs here (2059 tests, 0 failures); the instrumented
> suite compiles in CI but is not executed here.

---

## 1. Current architecture (audit, before this change)

```
Application.onCreate
  └─ ProtectionRuntime.start()            (app-scoped @Singleton; observes settings/account)
       └─ syncActive(): settings.protectionEnabled && account != null
            ├─ ForegroundAppMonitor.start()      (usage-stats poll; accessibility authoritative)
            ├─ ProtectionEngine.start()          (frame collector + 500ms tick → PolicyEvaluator)
            ├─ ScreenTimeUsageCollectionRunner
            └─ ProtectionServiceLauncher.start() → ProtectionForegroundService (FGS)

ProtectionForegroundService  (foregroundServiceType="camera|specialUse")
  ├─ startForeground(base specialUse) at onCreate; camera type claimed only in a
  │  legal while-in-use moment (observeCameraSession + promoteToCameraForeground)
  ├─ owns ProtectionCameraSession (process-scoped camera, latched once started)
  │     └─ CameraXSessionBinding → FaceCaptureController → ML Kit → Recognizer(SharedFlow)
  └─ onDestroy → cameraSession.stop(); runtime.stop()

ProtectionAccessibilityService  (separate lifecycle)
  └─ window transitions → runtime.onAccessibilityForegroundApp()
     + TYPE_ACCESSIBILITY_OVERLAY blocking window (touch-consuming)

ProtectionBootReceiver → ProtectionBootRestorer → ProtectionServiceLauncher
  (BOOT_COMPLETED only; reads persisted protectionEnabled + account; specialUse type only)
```

### Answers to the audit questions

| Question | Before Stage 5 |
|---|---|
| When does the service start? | `runtime.activate()` (persisted ON + signed in) or boot restore. |
| When does it stop? | Protection off / sign out (`deactivate()` → `stopService`), or process/OS destroy. |
| What if the process dies? | **Nothing restarted it** (`START_NOT_STICKY`). Protection was silently lost until the user reopened the app. |
| When is the camera bound? | In the legal while-in-use moment; latched afterwards, so closing the UI does **not** stop it. |
| Activity closes? | `onStop → onUiBackground()`; the service + latched camera session keep running. |
| Screen lock? | **Untracked.** CameraX/platform behaviour decided; stale recognition could persist for up to the frame TTL. |
| Screen unlock? | **No explicit resume path.** |
| Reboot? | Boot receiver restores the persisted intent; camera is limited until the next UI foreground (`cameraLimitedAfterBoot`). |
| Service crash (same process)? | `onDestroy → runtime.stop()` deactivated the runtime, and a recreate could **not** re-activate it (runtime `started` was already true). |
| Camera error? | Logged once, **never retried** — recognition silently stopped until a session restart. |

### Gaps this stage closes

1. **Process-death recovery** — `START_NOT_STICKY` meant an OS low-memory kill was a
   permanent, silent protection loss.
2. **Same-process service recreate** — a recreated service did not re-derive the active
   session, leaving protection inactive.
3. **Camera interruption** — a failed bind was never retried; one bad camera moment
   disabled recognition.
4. **Screen lock/unlock** — no screen-off handling (stale recognition, wasted camera) and
   no explicit resume.
5. **Recents removal** — semantics were implicit; now explicit and guarded.

---

## 2. What changed

### 2.1 Restart strategy (`ProtectionServiceLifecyclePolicy`)

The service now returns **`START_STICKY`** so the OS *may* recreate it — and, with it, the
app-scoped runtime — after an OS-initiated kill. This is deliberately **not** a force-stop
bypass: a force-stopped package is never restarted by Android, and disabling protection
stops the service (a stopped service is not sticky-restarted). The decision is a pure,
unit-tested value ([`ProtectionServiceLifecyclePolicy`]).

* A `null` intent (system restart) is handled explicitly. Once the persisted settings are
  observed (`ProtectionRuntimeState.settingsLoaded`), a restart whose intent is no longer
  "protection ON + signed in" calls `stopSelf()` rather than showing a false "protected".
* `runtime.onServiceStarted()` re-derives the active session on every create/start, fixing
  the same-process recreate gap. Idempotent.

### 2.2 Recents removal

`onTaskRemoved()` is intentionally a no-op (guarded by
`ProtectionServiceLifecyclePolicy.shouldStopOnTaskRemoved() == false`) and the manifest
declares `android:stopWithTask="false"`. Android keeps a started service running and only
notifies it, so a child swiping QALQON away cannot disable protection. (OEM task-clear
killers are Stage 6.)

### 2.3 Camera interruption recovery (`CameraRecoveryBackoff`, `ProtectionCameraSession`)

* `FaceCaptureController` now reports the *asynchronous* bind outcome
  (`setBindStateListener`); `CameraXSessionBinding` forwards it.
* A failed bind makes the session retry with a **bounded** backoff (1s, 2s, 5s, 15s, 30s;
  five attempts), one timer at a time, reset on success. When the bound is reached the
  session reports `recovering = true` (recognition camera-limited) until a later resume —
  never an unbounded tight loop.
* `ProtectionRuntimeState.cameraRecovering` mirrors it. It is **reporting only**: it never
  changes `ready` or `degradedCapabilities` (asserted by `Stage5RuntimeStateContractTest`).

### 2.4 Screen lock / unlock

The service registers a runtime (not manifest) receiver for `SCREEN_OFF`, `SCREEN_ON` and
`USER_PRESENT`, unregistered with the service so it cannot leak or fire while protection is
off. It never bypasses a platform restriction — it only touches an already-latched session:

* **Screen off** → `ProtectionEngine.onCameraInterrupted()` drops the last frame, the
  in-progress confirmation and the identity/liveness signals; the session
  `suspendBinding()` releases the camera while the session and the FGS camera type stay
  latched. The protection **state is untouched** (releasing a block would be fail-open).
* **Screen on / user present** → the binding is re-established; an interrupted recovery
  resumes with a fresh budget. Because the foreground service (and its camera type) stayed
  alive, this is a *rebind*, not a new background camera-FGS start.

### 2.5 Stale recognition after an interruption

`ProtectionEngine.onCameraInterrupted()` guarantees a pre-interruption recognition is never
treated as current: pending confirmations, the cached frame, identity and liveness are all
dropped, while the existing fail-closed no-face policy continues to govern from the next
tick. The existing 1500 ms frame TTL is unchanged.

---

## 3. Process-kill recovery matrix

| Event | Detectable? | Auto recovery? | Android limitation? |
|---|---|---|---|
| Activity destroy (Home/Recents) | Yes (`onStop`) | Service + latched session keep running | No |
| Service destroy (same process) | Yes (`onDestroy`) | Re-created on next start; `onServiceStarted()` re-activates | No |
| Process death (OS kill) | No | **Yes, best-effort** via `START_STICKY` (new process rebuilds from persisted settings) | Yes — restart is not guaranteed |
| Low-memory kill | No | Same as process death | Yes |
| Force stop | No | **No** | **Android-limited** — never restarted; user must relaunch |
| Reboot | Yes (`BOOT_COMPLETED`) | Yes (persisted intent) | Camera limited until first UI foreground (Android 15 FGS rules) |

---

## 4. Android limitation matrix

| Scenario | Before | After Stage 5 | Android limitation? |
|---|---|---|---|
| Activity closed | PASS | PASS | No |
| Home | PASS | PASS | No |
| App switching | PASS | PASS | No |
| Recents removal | PASS (implicit) | PASS (explicit, guarded) | OEM killers = Stage 6 |
| Screen lock | PASS WITH LIMITATION | PASS WITH LIMITATION (stale cleared, camera released) | Camera behaviour on screen-off is platform-defined |
| Screen unlock | PASS WITH LIMITATION | PASS WITH LIMITATION (explicit rebind) | Rebind is legal only while the FGS holds the camera type |
| Camera interruption | FAIL (no retry) | PASS WITH LIMITATION (bounded retry; exhausted → reported limited) | Camera may stay unavailable (phone call, other app, screen off) |
| Service restart | FAIL (no re-activation) | PASS | No |
| Process death | FAIL (NOT_STICKY) | PASS WITH LIMITATION (best-effort sticky restart) | Restart not guaranteed |
| Low memory | FAIL | PASS WITH LIMITATION | Same as process death |
| Force stop | FAIL | FAIL (ANDROID-LIMITED, documented) | **Yes — not bypassable** |
| Reboot | PASS WITH LIMITATION | PASS WITH LIMITATION | Camera needs one UI foreground |
| Accessibility disconnect | PASS (self-heal via reassert) | PASS (unchanged) + independent of FGS restart | No |

---

## 5. Tests

* `ProtectionServiceLifecyclePolicyTest` (6) — sticky strategy, no stop on task removal,
  self-stop rule is exactly the inverse of `ProtectionServicePolicy.shouldRun`.
* `CameraRecoveryBackoffTest` (8) — bounds, ordering, exhaustion, reset, positive delays.
* `ProtectionCameraSessionRecoveryTest` (17) — bind-failure retry, single timer, exhaustion,
  reset-on-success, stop cancels, screen suspend/resume, no duplicate live binding.
* `ProtectionEngineCameraInterruptionTest` (6) — interruption never releases a block (both
  soft and hard), resets confirmation, clears identity/liveness, keeps the blocked-app
  context, safe when idle.
* `Stage5RuntimeStateContractTest` (4) — the new report fields are not capabilities.
* `SecurityThreatModelTest` updated — the force-stop section now describes the sticky
  restart honestly and still admits force stop is not bypassed.

JVM total: **2018 → 2059** (0 failures, 0 errors, 0 skipped).

Instrumented additions (compile in CI; **not executed here** — no emulator):
`ProtectionServiceRestartPolicyInstrumentedTest` asserts the manifest `stopWithTask=false`
and the camera/specialUse declarations.

---

## 6. Real device test

**REAL DEVICE TEST = NO.** No physical device and no ADB in this environment. Emulator/CI
compilation is **not** presented as device validation. The manual checklist for a real
device is in `tools/device-qa/` and §36 of the stage brief.

---

## 7. Stage 5 decision

**PASS WITH LIMITATIONS.** The generic background/process runtime is hardened and
regression-tested; force stop and post-reboot camera availability remain documented Android
limitations, not bugs. Next: **6/12 — Permission & OEM Compatibility**.
