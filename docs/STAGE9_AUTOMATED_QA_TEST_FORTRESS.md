# QALQON — Stage 9: Automated QA & Test Fortress

> Commercial Master Plan — Stage 9 deliverable.
> Baseline HEAD: `db07553bc220367792162f41ba8203caa2bbbf61`.
>
> **Scope:** automated tests only. Real-device QA, OEM matrix, 72-hour soak, Play Console,
> submission, production signing and launch are **out of scope** (Stages 10–12).

---

## 1. Baseline & method

Verified from the repository, not assumed: 159 JVM test files / 60 instrumented files;
`./gradlew :app:testDebugUnitTest` = **1905** tests (baseline). The audit below classifies
every layer **CATEGORY | EXISTING | MISSING | RISK | ACTION**.

## 2. Test inventory

| Layer / category | Where | Existing | Assessment |
|---|---|---|---|
| Unit (JVM) | `app/src/test` | 1905 tests / 159 files | Broad, deterministic |
| Instrumentation | `app/src/androidTest` | 560 tests / 60 files | Room, protection runtime, DAOs, migrations |
| Room migrations | `androidTest/data/db` | 3→4 (baseline) + 4→10 | Complete chain, additive only |
| DAO/relations | `androidTest/data/db` | policy/schedule/screentime/eye-safety/usage/checkpoint | Good |
| ViewModels | unit + androidTest | policy/schedule/eye-safety/settings/screentime | Good |
| ProtectionEngine | unit `ProtectionEngineReliabilityTest` + androidTest | state machine, recovery, idempotency, failure isolation | Strong |
| ProtectionRuntime | androidTest | identity/liveness/recovery | Good |
| Services | androidTest | foreground service, accessibility, boot receiver | Present |
| Permissions | unit `CameraPermissionTrackingTest`, `ProtectionDegradationTest` | camera/usage/overlay/accessibility states | Good |
| State restoration | unit + androidTest | settings parsing, session, account scope | Partial |
| Subscription | unit `billing/*` | states, transitions, offline, ack, restore, account isolation | Strong (+ this stage) |
| Regression | unit + androidTest | one per historical fix (see catalog) | Strong |
| Critical journeys | unit integration + androidTest | covered across suites (see §5) | Present |
| Localization | unit `i18n/*`, `*LocalizationTest` | uz/en/ru parity enforced | Strong |
| Accessibility (contract) | unit | semantics/contentDescription source contracts | Present |
| Concurrency/race | unit | **gap** → added this stage | Filled |

## 3. Gap analysis (this stage's focus)

| AREA | TESTED | PARTIAL | MISSING | RISK | ACTION |
|---|---|---|---|---|---|
| ProtectionEngine/runtime | ✅ | | | Low | none |
| Policy / screen-time / schedule / eye-safety | ✅ | | | Low | none |
| Room + migrations + deletion | ✅ | | | Low | none |
| Account reset / biometric deletion | ✅ | | | Low | none |
| Subscription state model | ✅ | | | Low | none |
| **Subscription cross-account race (in-flight refresh)** | | | ❌ | **High** | **fix + test** |
| **Exhaustive entitlement × offline matrix** | | ⚠️ | | Medium | **test** |
| **Suite determinism guard (self-enforcing)** | | | ❌ | Medium | **test** |
| Service lifecycle semantics | | ⚠️ device-bound | | Medium | Stage 10 |
| TalkBack / real a11y | | | ❌ | — | Stage 10 |

## 4. The bug the fortress caught

**Root cause** (`SubscriptionManager.applyVerified`): the account id was captured at the
start of `refresh()`, but the verified result was **published unconditionally**. If the
user switched QALQON account while a Play verification was still in flight, the late
result was published into the *new* session — a **cross-account entitlement leak**
(would grant premium for account B from account A's purchase, and violate INVARIANT 6).

**Fix:** persist the result to the account's own cache, but publish only when the account
is still current:
```kotlin
store.save(id, ent)
if (id == accountId) publish(ent)
```

**Regression test:** `SubscriptionConcurrencyTest
.accountSwitchDuringInFlightRefresh_doesNotLeakThePreviousAccountsEntitlement` — a
deterministic gated-gateway test. **Proven**: temporarily reverting the fix makes the
test fail; restoring it makes it pass. The test is now permanent regression protection.

## 5. Added tests (this stage)

| Class | Tests | Risk closed |
|---|---|---|
| `billing/SubscriptionConcurrencyTest` | 3 | in-flight refresh vs account switch; concurrent refreshes; duplicate purchase updates |
| `billing/EntitlementMatrixTest` | 7 | exhaustive `grantsPremium` + offline staleness boundary across all 11 states |
| `quality/TestSuiteHygieneTest` | 2 | self-enforcing: no `Thread.sleep`/`@Ignore`/`@Disabled`/`Random(`/`System.exit(`/`.awaitTermination(` in the unit suite; no `@Test`-less test class |

Total added: **12**.

## 6. Regression catalog

| ID | Bug | Root cause | Fix commit | Regression test | Status |
|---|---|---|---|---|---|
| R-1 | ScanScheduler not re-armed after cooldown | re-arm only on entry | `2404d4d` | `ProtectionEngineReliabilityTest` | ✅ |
| R-2 | Overlay/WindowManager failure crashed process | unguarded add/remove | `2404d4d` | `ProtectionEngineReliabilityTest` (throwing executor/clear) | ✅ |
| R-3 | Usage Access revoked crashed monitor | unguarded poll | `2404d4d` | `ForegroundAppMonitor` framing + engine fallback | ✅ |
| R-4 | Recognition failure crashed loop | unguarded evaluate | `2404d4d` | `ProtectionEngineReliabilityTest` | ✅ |
| R-5 | No-face policy defaulted to ALLOW | fail-open default | `f8d2c24` | `DefaultPolicyEvaluatorTest` / `ProtectionEngineReliabilityTest` | ✅ |
| R-6 | PIN salt hex decoded leniently | zero-fill | `3a2a23e` | `PinSecurityTest` / `PinVerificationTest` | ✅ |
| R-7 | Parent UI never re-locked on background | lock only on logout | `5edf794` | `AppLockBackgroundRelockTest` | ✅ |
| R-8 | Legacy overlay could not render outside an Activity | no ViewTree owners | `ecb208d` | `LegacyOverlayHostTest` | ✅ |
| R-9 | Incomplete full reset (5 tables left) | reset written before tables existed | `3c64a3e` | `Stage4DeletionAndBackupTest` | ✅ |
| R-10 | Raw exception surfaced to users | Toast used `Throwable.message` | `d0adc4b` | `Stage6UxClarityTest` | ✅ |
| R-11 | Dashboard showed heuristic `LIVE` as "Real face" | label not source-gated | `c778800` | `Stage5LivenessHardeningTest` | ✅ |
| R-12 | **Cross-account entitlement leak on in-flight refresh** | publish without account check | **this stage** | `SubscriptionConcurrencyTest` | ✅ |

## 7. Flaky-test policy

- **Forbidden in the JVM suite** (enforced by `TestSuiteHygieneTest`): `Thread.sleep(`,
  `@Ignore`, `@Disabled`, `Random(`, `System.exit(`, `.awaitTermination(`.
- **Existing audit:** 0 `Thread.sleep`, 0 `Random(`, 0 `@Ignore`, 0 `runTest`
  (no `kotlinx-coroutines-test` dependency). 28 files use `runBlocking` for suspend code;
  2 files use bounded `System.currentTimeMillis()` deadlines (5 s) and 4 use short
  `delay()` — all **bounded and deterministic in effect**, not fixed sleeps.
- **Instrumented flake:** the API 35 emulator `awaitActive` main-looper stalls are a
  **device-bound** flake (documented; CI re-run verified). Not automatable in this stage;
  tracked for Stage 10.
- **Policy:** a flaky test is never skipped/deleted/timeout-inflated; it is diagnosed and
  fixed, or documented as device-only.

## 8. CI strategy

CI (`.github/workflows/build.yml`) runs `:app:testDebugUnitTest`, `assembleDebug` (which
also compiles the instrumented-test APK) and, in a second job, `connectedDebugAndroidTest`
on an API 35 emulator. Failures are non-zero-exit (no `continue-on-error`). The Stage 9
additions run inside the existing JVM job — no CI change was needed.

## 9. Coverage / gap analysis

No coverage tooling is configured (no JaCoCo) and no report is committed; adding it was
judged **not worth the build risk** in this stage. Coverage was assessed by **critical-area
inspection** (§3), which is the meaningful measure here: every critical area is TESTED
except the ones filled this stage, and the remaining gaps are device-only.

## 10. Known limitations

- Real-device behaviour (OEM, Doze, camera hardware, liveness performance, real Play
  billing, real reboot, 72-h soak, battery) is **NOT AUTOMATABLE IN THIS STAGE** — Stage 10.
- TalkBack/real-accessibility: **device-only** — Stage 10.
- Service lifecycle semantics (`START_NOT_STICKY`, boot) are exercised by instrumented
  tests under the emulator; physical-device confirmation is Stage 10.
- No code-coverage percentage is reported (tooling intentionally not added).

## 11. Files changed

- `app/src/main/java/uz/faceguard/app/core/billing/SubscriptionManager.kt` — publish guard.
- `app/src/test/java/uz/faceguard/app/billing/SubscriptionConcurrencyTest.kt` — new.
- `app/src/test/java/uz/faceguard/app/billing/EntitlementMatrixTest.kt` — new.
- `app/src/test/java/uz/faceguard/app/quality/TestSuiteHygieneTest.kt` — new.
- `docs/STAGE9_*.md` — this document + regression catalog.

## 12. Final validation

- Clean build ✅ · `:app:testDebugUnitTest` ✅ **1917 / 0 / 0 / 0** · `assembleDebug` ✅ ·
  `compileReleaseKotlin` ✅ · `:app:lintDebug` (see report) · CI (see final report).
