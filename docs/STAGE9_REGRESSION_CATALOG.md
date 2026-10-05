# QALQON — Stage 9: Regression Catalog

> Bug → Root cause → Regression test → Protection. Every critical historical fix has a
> permanent automated test, and each was re-verified against the current tree.

| ID | Bug (symptom) | Root cause | Fix commit | Regression test (file) | Status |
|---|---|---|---|---|---|
| R-1 | Recognition silently stopped enforcing after the first scan window | ScanScheduler re-armed only on protected-app *entry*, not while it stayed foreground | `2404d4d` | `protection/ProtectionEngineReliabilityTest` | ✅ covered |
| R-2 | Overlay/WindowManager failure crashed the process | `addView`/`removeView` not isolated; stale reference disabled the block | `2404d4d` | `ProtectionEngineReliabilityTest` (throwing executor / throwing clear) | ✅ covered |
| R-3 | Revoking Usage Access crashed the monitor | unguarded `queryEvents`/`queryUsageStats` | `2404d4d` | `ProtectionEngineReliabilityTest` + `ForegroundAppMonitor` fallback | ✅ covered |
| R-4 | Recognition exception killed the evaluation loop | `recognizer.evaluate` unguarded | `2404d4d` | `ProtectionEngineReliabilityTest` (NoFace fallback) | ✅ covered |
| R-5 | Protected app allowed when no face visible | no-face policy failed *open* by default | `f8d2c24` | `DefaultPolicyEvaluatorTest`, `ProtectionEngineReliabilityTest` | ✅ covered |
| R-6 | A malformed PIN salt was zero-filled | lenient hex decoding | `3a2a23e` | `security/PinSecurityTest`, `PinVerificationTest` | ✅ covered |
| R-7 | Parent UI stayed unlocked after backgrounding | lock only on logout/reset | `5edf794` | `security/AppLockBackgroundRelockTest` | ✅ covered |
| R-8 | Legacy overlay could not render / could crash | Service-hosted `ComposeView` had no ViewTree owners | `ecb208d` | `protection/LegacyOverlayHostTest` | ✅ covered |
| R-9 | "Full reset" left 5 tables on disk | reset written before those tables existed | `3c64a3e` | `security/Stage4DeletionAndBackupTest` | ✅ covered |
| R-10 | Raw exception / class name shown to the parent | Toast used `Throwable.message` | `d0adc4b` | `polish/Stage6UxClarityTest` | ✅ covered |
| R-11 | Dashboard showed a motion heuristic as "Real face" | label not gated on `LivenessSource` | `c778800` | `security/Stage5LivenessHardeningTest` | ✅ covered |
| R-12 | **Cross-account entitlement leak** | `applyVerified` published even after account switch | **Stage 9** | `billing/SubscriptionConcurrencyTest` | ✅ covered (fix + test) |

**Method:** for each fix commit, the regression test was located and confirmed present and
passing in the current tree. No fix lacks a test. For R-12 the test was validated by
temporarily reverting the fix (test fails) and restoring it (test passes).

**No fabricated bugs.** Only real fixes from the repository's history are listed; no
artificial bug was introduced.
