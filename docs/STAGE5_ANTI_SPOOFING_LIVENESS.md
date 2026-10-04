# QALQON — Stage 5: Anti-Spoofing / Liveness Strategy

> Commercial Master Plan — Stage 5 deliverable.
> Baseline: `feature/phase4-screen-time-complete` @
> `6727e183d760e7758bb2923f87c4755096663ffb` (Stage 4, PASS WITH LIMITATIONS).
>
> **Honesty contract.** QALQON does **not** have a production-grade liveness model.
> What exists is a *passive heuristic anti-spoof signal* plus an unwired
> `AntiSpoofModel` seam. This document states exactly what that defends against,
> what it does not, and what a real solution would require. No FAR/FRR/APCER/BPCER
> number is claimed because none was measured. No photo-proof/video-proof claim is
> made.

---

## 1. Production-readiness level

**LEVEL 1 — Heuristic anti-spoof signals.** And, more precisely, the signal is
**decision-inert**: without a model it never changes a protection decision.

Evidence:

* The only liveness decision that affects policy is `LivenessState.SPOOF` →
  `PolicySettings.spoofAction` (`DefaultPolicyEvaluator`, gate #2).
* `SPOOF` is emitted **only** by the model path of `TemporalLivenessDetector`
  (`mean model score ≤ 0.35`). The passive heuristic emits `LIVE`/`UNKNOWN`/`UNSTABLE`
  only — **never `SPOOF`**.
* No `AntiSpoofModel` implementation is bundled and `FaceCaptureController.setAntiSpoofModel`
  is **never called** in production, so `FrameEvent.liveProbability` is always `null`
  and the model path never activates.
* Therefore, in the current build, liveness does not alter any block/allow decision;
  enforcement is entirely identity-based.

**PRODUCTION-GRADE LIVENESS NOT VERIFIED.**

---

## 2. Current implementation (audited)

### Files
`core/liveness/`: `AntiSpoofModel.kt`, `LivenessDetector.kt`, `LivenessEvaluator.kt`,
`LivenessFrame.kt`, `LivenessResult.kt`, `LivenessWindow.kt`, `TemporalLivenessDetector.kt`.

### `AntiSpoofModel`
An **interface** (`isReady()`, `livenessScore(bitmap): Float?`). **No implementation
exists**; it is a seam so a model can be added later without touching engine/runtime/
policy. It returns `null` on absence or inference failure — never a fabricated score.

### Bundled model
`assets/models/mobile_face_net.tflite` is the **recognition** embedding model
(MobileFaceNet, `[2,112,112,3] → [2,192]`, MIT, SHA-256 documented in
`assets/models/README.md`). It is **not** an anti-spoof model. The upstream project
name contains "FaceAntiSpoofing" but only the recognition `.tflite` is shipped.

### Heuristic (`TemporalLivenessDetector`)
Inputs are only `LivenessFrame` fields. The heuristic uses **head-pose range** only:

```
motion = max(range(yaw), range(pitch))   over the window
motion >= 3.0°  -> LIVE
motion <= 0.8°  -> UNKNOWN
else            -> UNSTABLE
```

`roll`, `faceWidthRatio` and the `FaceQuality` fields (brightness, sharpness,
landmarkVisibility) are **carried but not used** by liveness. There is no blink,
eye, mouth, optical-flow, depth, texture or frequency analysis.

### Temporal window (`LivenessWindow`)
≤24 frames, ≤2500 ms age, strictly increasing timestamps; out-of-order/duplicate
frames rejected; idle windows decay to empty.

### Integration (`ProtectionEngine`)
Per tick: `livenessEvaluator.observe(LivenessFrame.from(frame))` (only when a frame
exists) → `result(now)` → published to `ProtectionRuntimeState.liveness` and passed
into `PolicyContext.liveness`. Frames older than the engine's 1500 ms TTL count as
`NoFace`.

### Thresholds
* Recognition (cosine, L2-normalized embeddings): **parent 0.68, child 0.62**
  (`Recognizer.Thresholds`). Model-path liveness: spoof ≤ 0.35, live ≥ 0.65
  (unused in production). Heuristic motion: live ≥ 3.0°, static ≤ 0.8°.
* None of these was tuned against genuine/impostor/spoof samples.
  **THRESHOLD NOT VALIDATED.**

### Failure behaviour
| Input | Result | Effect on decision |
|---|---|---|
| No frames / empty window | `UNKNOWN` (source NONE) | none (identity decides) |
| No face / mostly faceless | `NO_FACE` | none (identity `NO_FACE` path) |
| Static face (≤0.8°) | `UNKNOWN` | none |
| Moving face (≥3°) | `LIVE` | none |
| Mid-band motion | `UNSTABLE` | none |
| Model score ≤0.35 | `SPOOF` | `spoofAction` (default SOFT_BLOCK) — **unreachable without a model** |
| Model exception | `null` score → heuristic | none |
| NaN / malformed model score | `UNSTABLE` | none (never trusted as live) |

`SPOOF` is checked **before** the identity switch, so a recognised-but-spoofed
parent/child is never trusted; a liveness failure never marks a parent as a child.

---

## 3. Spoof attack taxonomy — current result

Because `SPOOF` is unreachable, no attack is blocked by liveness today; the attack
"result" below describes the *decision* outcome, which is identity-driven.

| # | Attack | Expected current result | Why | Risk |
|---|---|---|---|---|
| A | Printed photo (still) | Not detected; identity decides | motion ≤0.8° → UNKNOWN → falls through | High |
| B | High-res printed photo | Not detected | same | High |
| C | Phone screen photo (still) | Not detected | same | High |
| D | Phone screen recorded video | Not detected | motion ≥3° → LIVE → falls through | High |
| E | Tablet screen replay | Not detected | same | High |
| F | Video replay w/ head movement | Not detected | `LIVE` (motion) | High |
| G | Video replay w/ blinking | Not detected | motion present | High |
| H | Video replay, different pose | Not detected | motion present | High |
| I | Cropped-face video | Not detected | same | High |
| J | Low-light replay | Not detected | noisy motion → LIVE/UNSTABLE | High |
| K | Glare/reflection replay | Not detected | detection may degrade; no spoof signal | High |
| L | Partial face / mask | Not detected as spoof | ML Kit may detect; embedding decides | Medium |
| M | Multiple faces | Not resolved | `faces.firstOrNull()` used; order not guaranteed | Medium |
| N | Static real face, poor quality | UNKNOWN → identity | correct for a genuine parent; child still enforced | Low |
| O | Genuine face, fast movement | LIVE → identity | motion is live; blur may hurt recognition | Low |
| P | Genuine face, poor light | quality degrades | may fail detection/recognition | Low |
| Q | Genuine face at angle | UNKNOWN/LIVE → identity | recognition may fail | Low |
| R | Genuine face, occluded | may be NO_FACE/UNKNOWN | identity decides | Low |

**Photo attack (A/B/C):** ML Kit detects a photo as a face; MobileFaceNet can
produce an embedding close to the genuine one, so a printed/screen photo of the
parent can be recognised as the parent → ALLOW. Liveness does not stop it. **P1
finding.**

**Video replay (D–K):** movement satisfies the heuristic → `LIVE`; the replay can
be recognised as parent → ALLOW. "Movement detected" is **not** "live". **P1
finding.**

---

## 4. Genuine-user false-rejection assessment

| Condition | Effect | Assessment |
|---|---|---|
| Lighting (dark/backlit) | detection/embedding degrade; heuristic may be UNSTABLE | no liveness-caused reject (liveness is inert); recognition may fail |
| Angle (large yaw/pitch) | heuristic LIVE; recognition weaker | identity may be UNKNOWN |
| Motion | heuristic LIVE | none |
| Blur | `sharpness` recorded but unused by liveness | none from liveness |
| Occlusion | may be NO_FACE | identity path |
| Multiple faces | primary face chosen by ML Kit order | ambiguous, see §6 |

Because liveness never *rejects*, it adds **no** false-rejection risk today. The
false-rejection risk would appear only once a model/active challenge is added and
must then be measured.

---

## 5. Metrics

| Metric | Status |
|---|---|
| FAR (False Acceptance Rate) | **NOT MEASURED** |
| FRR (False Rejection Rate) | **NOT MEASURED** |
| APCER (Attack Presentation Classification Error Rate) | **NOT MEASURED** |
| BPCER (Bona Fide Presentation Classification Error Rate) | **NOT MEASURED** |

No dataset exists (no genuine/impostor/spoof samples), and no device testing was
possible, so no metric is fabricated. `0% FAR` / `100% spoof protection` are **not**
claimed.

### Methodology (for when data exists)
* **FAR/FRR** on the recognition thresholds (0.68/0.62) using genuine pairs
  (parent/child enrollments vs live captures) and impostor pairs (different people,
  including parent-vs-child) across pose/lighting/distance.
* **APCER/BPCER** per ISO/IEC 30107-3, with an Attack Presentation set (the
  taxonomy A–M) and a Bona Fide set (N–R). Report at a fixed operating point, and
  as a DET/ROC curve rather than a single number.
* Report device, lighting, display size and distance per sample; never average away
  the worst case.

---

## 6. Multi-face

ML Kit returns faces in an unspecified order; QALQON uses `faces.firstOrNull()` for
both embedding and quality. If a parent and child (or child + unknown) are both in
frame, the chosen face is non-deterministic. Liveness is unaffected (any face →
facePresent). Per Stage 5 scope this is **reported, not changed** (changing selection
would alter the recognition/decision pipeline). Residual risk: Medium — a deliberate
"parent photo beside the child" arrangement could select the parent. Recommended fix
(deferred): explicit primary-face policy (largest/centred, or require a single face
for a trusted parent).

---

## 7. Strategy comparison

| Option | Security | Privacy | Offline | CPU/Battery | UX friction | Effort | Spoof resistance | Readiness |
|---|---|---|---|---|---|---|---|---|
| A. Current heuristic only | None (inert) | Best | Yes | Negligible | None | Done | None vs photo/replay | Shipped foundation |
| B. Passive heuristic (same) | None | Best | Yes | Negligible | None | Done | None | Level 1 |
| C. Active challenge only | Medium | Best | Yes | Low | High (action required) | Medium | Beaten by a targeted pre-recorded video if predictable | Not built |
| D. Passive + active | Medium-High | Best | Yes | Low-Med | High | High | Better; predictable challenges still weak | Not built |
| E. Dedicated offline anti-spoof model | High (if validated) | Best (on-device) | Yes | Model-dependent | Low (passive) | High | Highest | Needs a vetted model + data |
| F. Model + active fallback | Highest | Best | Yes | High | Medium | Very High | Highest | Long-term target |

---

## 8. Recommended strategy

**Immediate (Stage 5):** keep the honest `Level 1` foundation, make **no**
security claim, and remove the one place the app presented a heuristic as a liveness
verdict (see §9, code change). Do **not** ship a model without evidence; do **not**
raise thresholds without data.

**Next (a later, dedicated stage):** move toward **Option E — a dedicated, fully
offline anti-spoof model behind the existing `AntiSpoofModel` seam**, evaluated
against APCER/BPCER, ideally with **Option F** (a random active challenge) as a
fallback when the model is uncertain. This is the only path to a real spoof-resistance
claim, and it must be evidence-driven.

---

## 9. Code change in Stage 5 (minimal hardening)

One confirmed issue was fixed: `feature/home/DashboardState.livenessLabelRes` mapped
`LivenessState.LIVE` to the string "Real face" **regardless of source**. Since the
only producer in this build is the passive motion heuristic, the dashboard would have
shown "Real face" for anything that moved — including a video replay. The label is now
**source-gated**: a `LIVE` verdict is surfaced only when `LivenessSource.MODEL`
produced it; a heuristic `LIVE` is not surfaced as a liveness verdict. `SPOOF` (model-
only) stays surfaceable. `DashboardUiState` now carries `livenessSource`.

Regression tests: `security/Stage5LivenessHardeningTest` (heuristic LIVE never shown
as a real face; model LIVE is; NaN model scores fail safe; out-of-range scores clamp
deterministically; heuristic never emits SPOOF; unknown identity + LIVE is not
trusted; a spoofed parent never unlocks even when repeated; an empty window is UNKNOWN,
never a latched LIVE).

No recognition, policy, threshold, model or camera behaviour was changed. No model was
integrated. No cloud/network component was added.

---

## 10. Model requirements (if/when a model is integrated)

Offline only. Before any `.tflite` is added, document: license + provenance; input
shape/normalization/output contract; size; latency and RAM; CPU/GPU/NNAPI support;
Android/TFLite compatibility; quantization; and — mandatorily — **accuracy evidence
(APCER/BPCER on a documented dataset)** with the known attack types it was validated
against. A `.tflite` file's mere presence proves nothing. Runtime download and any
cloud liveness API are **forbidden**.

Proposed architecture (no double inference): `Camera → face detection → crop/align →
liveness model → liveness score → recognition → identity → ProtectionEngine`, with the
liveness model reusing the face crop the recognition path already computes.

---

## 11. Test dataset protocol (no real biometrics in git)

Genuine set: parent + child, multiple angles, lighting, distances, glasses, normal
and fast movement. Spoof set: printed photo, screen photo, screen video, replay video,
varied display sizes/brightness/glare/quality. **Real user biometric samples must
never be committed**; the protocol lives here, data is collected and evaluated
out-of-band, and only synthetic/non-biometric fixtures (deterministic frame streams,
as the existing tests use) are committed.

---

## 12. Privacy constraints (preserved)

No cloud liveness, no remote biometric service, no network face data. Any future
model stays on-device behind `AntiSpoofModel`. Verified consistent with the Stage 4
verdict: no face-data egress path.

---

## 13. Threat model

| Threat | Likelihood | Impact | Current mitigation | Current detection | Residual risk | Recommended mitigation |
|---|---|---|---|---|---|---|
| Printed photo | High | High (parent unlock) | identity only | none | High | offline anti-spoof model |
| Phone-screen photo | High | High | identity only | none | High | offline model |
| Phone-screen video | High | High | identity only | none | High | offline model |
| High-quality replay | High | High | identity only | none | High | offline model (+ active fallback) |
| Moving replay | High | High | identity only | none (motion = LIVE) | High | offline model |
| Low-light spoof | Medium | High | identity only | none | High | offline model w/ low-light data |
| Multi-face ambiguity | Medium | Medium | first-face used | none | Medium | explicit primary-face policy |
| Genuine false rejection | Low today | Medium | liveness inert | n/a | Low now; rises with a model/challenge | measure FRR before enabling |

Root / compromised OS remains **NOT FULLY PROTECTABLE** (Stage 4 boundary).

---

## 14. Real-device limitation

No physical device or local emulator was available. **REAL-WORLD SPOOF TESTING =
NOT TESTED.** Front camera, sensors, screen brightness, refresh rate and exposure
effects are unmeasured. This limitation carries into Stage 10 (Real Device QA).

---

## 15. Acceptance summary

* Current liveness fully audited; `AntiSpoofModel.kt` audited (no implementation).
* Thresholds documented; unvalidated (no data).
* Photo / printed / screen / video / moving replay assessed as **not stopped**.
* Genuine failure modes assessed; liveness adds no false reject today.
* Passive/active/model options compared; recommendation given.
* Offline-only and no-biometric-egress constraints preserved.
* FAR/FRR/APCER/BPCER methodology defined; **none measured**.
* Multi-face / low-light / motion blur assessed and reported, not silently changed.
* Failure behaviour and state-machine integration documented.
* Regression tests added; documentation captured; level assigned; residual risk stated.
