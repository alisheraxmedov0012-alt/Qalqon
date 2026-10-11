# QALQON — Release Block 5: Recognition Final Hardening & Anti-Spoof Audit

> **Status: PREPARED — NOT device-validated.** This document records a technical audit of the
> on-device recognition and liveness pipeline. It contains **no** real-world accuracy, liveness
> efficacy or FAR/FRR claim: those require a physical device and a labelled dataset and are
> **NOT MEASURED** here. Nothing below is a "spoof-proof" or "100% accurate" guarantee.
>
> Scope: Release Completion **Block 5** — Anti-Spoof + Recognition Final Hardening.
> Branch: `feature/phase4-screen-time-complete`. Package `uz.faceguard.app`.
> Companion docs: `docs/STAGE7_SUBSCRIPTION_BILLING.md`, `README.md` ("Known limitations").

---

## 1. Recognition architecture (CameraX → ML Kit → embedding → identity → liveness → engine)

| Stage | What happens | Code |
|---|---|---|
| Frame capture | Front camera; `ImageAnalysis` `STRATEGY_KEEP_ONLY_LATEST`, RGBA_8888 output; `ImageProxy` → `Bitmap` (rotation applied) | `core/pipeline/FaceCaptureController.kt` |
| Detection | ML Kit `FaceDetection` (`FAST`, landmarks ALL, classification/contours NONE, tracking off) | `core/pipeline/FaceDetectorConfig.kt`, `FaceCaptureController` |
| Embedding | Per detected face (`MAX_ANALYZED_FACES = 5`): crop (15% margin) → TFLite **MobileFaceNet** (192-d, L2-normalized, NHWC 112×112, `(px-127.5)/128`); on failure → 19-d geometry fallback (pose/size/landmarks) tagged `GEOMETRY` | `core/embed/TfLiteMobileFaceNet.kt`, `FaceImageUtils.kt`, `FaceFeatureExtractor.kt`, `FaceEmbeddingModel.kt` |
| Template store | Mean of accepted per-frame vectors → Base64 (`FaceEmbeddingCodec`) in `ParentProfile.faceTemplateRef` / `ChildProfile.faceTemplateRef` | `core/embed/FaceEmbeddable.kt`, `FaceEmbeddingCodec.kt` |
| Similarity | Cosine similarity (min-dimension compare, degenerate→0.0, clamp [-1,1]) | `domain/similarity/CosineSimilarity.kt` |
| Identity decision | Pure, order-independent per-face decision; single best identity across classes, accepted only if it clears **its own** threshold | `core/recognition/IdentityDecision.kt` (`IdentityDecisionEngine`) |
| Multi-face aggregation | Deterministic priority `CHILD > PARENT > UNKNOWN > NO_FACE`; most-restrictive child wins | `core/recognition/MultiFacePolicyEngine.kt` |
| Liveness | Temporal window → model path (if a model exists) else passive motion heuristic | `core/liveness/*`, `domain/policy/LivenessPolicy.kt` |
| Engine | Recogniser → `MultiFaceDecision.result` → `classify()` (debounce/confirmation) → `PolicyContext` (identity + liveness) → `PolicyEvaluator` | `core/protection/ProtectionEngine.kt`, `core/policy/DefaultPolicyEvaluator.kt` |
| Identity → policy | `RecognitionResult` → `IdentityContext`; NoFace ≠ UNKNOWN | `core/protection/IdentitySignal.kt` |

**Thresholds** (`core/recognition/Recognizer.kt`): `Thresholds(parent = 0.68, child = 0.62)`;
constructor rejects values `< 0.1`. Thresholds are **asymmetric** (parent stricter) and applied
to each class's **own** score; the winner is the strictly-closest identity that clears its own
threshold (tie → UNKNOWN). Comment states these are heuristic, anchored to the bundled
MobileFaceNet reference (L2 distance 0.8 ≈ cosine 0.68) and "tune on real devices".

**Geometry fallback is not identity-grade:** `EmbeddingSource.GEOMETRY` frames can never
authorise PARENT/CHILD; dimension mismatch is filtered before comparison.

---

## 2. Recognition audit findings

Legend: Critical / High / Medium / Low. "Existing tests" = coverage present before this block.

| Area | Finding | Severity | Existing tests | Minimal safe action |
|---|---|---|---|---|
| Parent | Correct match, sub-threshold, inclusive boundary, tie, margin | — (no defect) | `RecognitionDecisionTest` | none |
| Child | Correct match + id/name, sub-threshold, boundary, multi-child most-restrictive | — | `RecognitionDecisionTest`, `MultiFacePolicyTest` | none |
| Unknown | Low similarity → UNKNOWN with best score; sub-threshold nearest-class stays UNKNOWN | — | `RecognitionDecisionTest` | none |
| Parent↔Child flip | Stage-1 fix: sub-threshold parent is never forced into CHILD; tie → UNKNOWN | — (fixed earlier) | `RecognitionDecisionTest` | none |
| **Non-finite input** | NaN/±Inf in the live vector or a **stored template** could enter the maths. Verified fail-safe: the winner is never PARENT/CHILD → UNKNOWN. **No test existed.** | **Medium** (coverage) | **added** `RecognitionRobustnessTest` | pin behaviour (done) |
| Corrupted child template | A NaN child template also prevents a valid PARENT match (comparison not order-independent w.r.t. NaN) → UNKNOWN. Fail-safe (deny) | Low | added `RecognitionRobustnessTest` | document |
| Wrong dimension | Live vector only compared to same-length templates | — | `RecognitionDecisionTest`, `MultiFacePolicyTest` | none |
| Multi-face | Every face evaluated; order-independent; geometry/dimension faces can't authorise | — | `MultiFacePolicyTest` (incl. permutations, random shuffles) | none |
| Thresholds | Asymmetric, per-class, boundary-inclusive; NaN threshold → no accept (fail-safe) | Low | `RecognitionDecisionTest` | document (no calibration data) |
| Lighting / angle / distance | Quality metrics computed (brightness, sharpness, pose, size, centre, occlusion) and **gated at enrollment** (`EnrollmentQualityGate`); at **recognition** they are guidance only, not identity gates | Medium (recognition robustness) | `EnrollmentQualityGateTest`, `EnrollmentFrameMappingTest` | document; real-device NOT RUN |
| **Multi-face + liveness decoupling** | Liveness is computed from the **primary** face (`faces.firstOrNull()`) while identity is aggregated over **all** faces. A recognised PARENT on one face + a live (motion) unknown face on another could combine to PARENT+LIVE | **Medium** | none | **documented only** — see §2.1 |
| False positive (wrong person accepted) | Reduced by strict-closest + own-threshold + UNKNOWN fail-safe; not eliminated (no anti-spoof) | Medium | `RecognitionDecisionTest` | document; FAR NOT MEASURED |
| False negative (real user denied) | Possible under poor lighting/angle; fail-safe direction (deny → no-face/unknown policy) | Low | see lighting row | document; FRR NOT MEASURED |

### 2.1 Multi-face + liveness decoupling (documented risk, no ad-hoc fix)

`FaceCaptureController` computes `quality`/liveness from the **primary** (first) face, while
`Recognizer.evaluateAll` aggregates identity over **every** face. Consequences:

- Safety-positive: any recognised CHILD wins over a parent, so a child present in any face
  drives child policy.
- Risk: a frame containing a **recognised PARENT face + a separate live/unknown face** resolves
  identity to PARENT (Parent > Unknown) while liveness may be measured on the motion of the
  other face — i.e. liveness and the identity-bearing face are not bound.

Binding liveness to the identity-bearing face is a **product-policy decision** (Block-5 rule 6:
do not introduce an ad-hoc new multi-face policy). It is recorded here as a Medium risk and
deferred pending a product decision and a validated liveness model.

---

## 3. Liveness / anti-spoof audit

**How it works today** (`core/liveness/TemporalLivenessDetector.kt`):

1. **Model path** (only if a model supplies per-frame scores): mean of scored frames ≥ `0.65` →
   `LIVE`; ≤ `0.35` → `SPOOF`; else `UNSTABLE`. Non-finite mean → `UNSTABLE` (never LIVE/SPOOF).
2. **Passive heuristic path** (default; **no model bundled**): head-pose *range* (yaw/pitch) over
   the window. `≥ 3.0°` → `LIVE`; `≤ 0.8°` → `UNKNOWN` (a still photo **must not** be trusted, but
   is not positively labelled SPOOF); else `UNSTABLE`. `source = HEURISTIC`, `confidence = null`.

**It is a motion heuristic, not a trained anti-spoof classifier.** It uses no texture, depth,
reflectance or frequency-domain signal.

Threat coverage (`docs/README` known-limitations reaffirmed):

| Attack | Current heuristic | Evidence |
|---|---|---|
| Printed photo | Weak/absent — a hand-shaken photo still shows motion | NOT TESTED |
| Phone/tablet screen photo | Weak/absent | NOT TESTED |
| Replayed video | Weak/absent (video has motion) | NOT TESTED |
| Pre-recorded motion | Weak/absent | NOT TESTED |
| Partial occlusion / crop | Not addressed (enrollment occlusion proxy only) | NOT TESTED |
| Deliberate lighting/angle change | Not addressed | NOT TESTED |

**Honest limitations (must not be marketed otherwise):** the build makes **no real-world
spoofing-efficacy claim**; the heuristic never emits `SPOOF`; `LivenessPolicy` treats
`UNKNOWN/UNSTABLE/NO_FACE` as `UNDECIDED` and **does not** hard-block on them (a still real face
reads UNKNOWN). Only a positive `SPOOF` (model-only) overrides a recognised identity (fail-closed).

**Status: real spoof tests = NOT RUN (no device).** No attack above is claimed defeated.

---

## 4. Anti-spoof model candidates (evidence only)

No model is bundled. Candidates were assessed from public sources; **provenance and license were
not fully verifiable**, and nothing was downloaded or integrated.

### Candidate 1 — MiniFASNetV1 / MiniFASNetV2 (Silent-Face-Anti-Spoofing)

| Field | Value |
|---|---|
| Official name / version | MiniFASNetV1, MiniFASNetV2 |
| Official source | `github.com/minivision-ai/Silent-Face-Anti-Spoofing` (+ `…-APK`) |
| Provenance | minivision-ai (小视科技) repo, master branch |
| License (repo/code) | **Apache-2.0** (stated on the repository page) |
| Weights license | Repo states Apache-2.0; **the training-data provenance of publicly redistributed weights is not documented in the sources checked** → commercial-use rights for the *weights* are **UNVERIFIED** |
| Format | PyTorch `.pth` (repo); community TFLite/LiteRT conversions exist |
| Size | ~0.435M params (V2), ~0.081 GFLOPs; HF LiteRT build 1.85 MB |
| Latency (from source, not QALQON) | HF litert-community: ~5 ms GPU / 7.9 ms CPU (Pixel 8a) — **not measured in QALQON** |
| Benchmark (from source) | Repo reports APK model **FPR 1e-5 / TPR 97.8%** (their own test) — **not QALQON's accuracy** |
| Offline | Yes (on-device inference) |
| Missing evidence | Exact artifact SHA-256; per-file weights license; dataset license (e.g. any CelebA-Spoof-derived weights); QALQON-pipeline latency/accuracy |

### Candidate 2 — litert-community/Silent-Face-Anti-Spoofing-LiteRT (Hugging Face)

| Field | Value |
|---|---|
| Official name / version | Silent-Face-Anti-Spoofing (MiniFASNetV2), LiteRT conversion |
| Official source | `huggingface.co/litert-community/Silent-Face-Anti-Spoofing-LiteRT` |
| License | Page states "Apache-2.0 (Silent-Face-Anti-Spoofing / minivision-ai)" — a **community** redistribution |
| Format / size | TFLite, **1.85 MB**; input `[1,3,80,80]` **NCHW, BGR, x/255**; output `[1,3]` softmax, `argmax==1` = live |
| Latency (from source) | ~5 ms (LiteRT GPU) / 7.9 ms (CPU XNNPACK) on Pixel 8a — not measured in QALQON |
| Offline | Yes |
| Integration note | QALQON's `TfLiteMobileFaceNet` is NHWC with a single `[192]` output and **cannot host this**; a new wrapper is required |
| Missing evidence | Weights/dataset license; SHA-256; validity in QALQON's exact crop/preprocess path |

**No model was downloaded, vetted, integrated, or claimed as production-ready.**

---

## 5. Decision and rationale

# **Path B — keep the passive heuristic; document the limitation.**

Rationale (Block-5 §12 conditions for Path A are **not all met**):

1. **Offline & license:** offline is satisfied, but the **weights' provenance and commercial-use
   rights are unverifiable** from the sources checked (only the *repo* license is Apache-2.0).
2. **Device validation unavailable:** no device → APCER/BPCER, latency and behaviour in QALQON's
   crop/preprocess path **cannot** be validated. A model cannot be called production-ready without it.
3. **Integration risk:** a new NCHW/BGR 3-class model needs a separate interpreter and preprocessing;
   it cannot be verified here and would risk the existing recognition path.
4. The task states that **not adding a model is a correct technical outcome**, and the existing
   robust heuristic + model seam is the safe default.

**No model or dependency added.** The `AntiSpoofModel` seam (`isReady()`/`livenessScore`) already
exists, so a vetted model can be added later without touching the engine/policy.

### Future acceptance criteria (before a model is integrated)

- [ ] Provenance documented: exact URL, version, file SHA-256.
- [ ] License reviewed for **commercial** use (weights **and** any dataset).
- [ ] Offline confirmed; no network/telemetry.
- [ ] QALQON-specific latency budget measured on target devices.
- [ ] APCER/BPCER (or equivalent) reproduced and documented, incl. the dataset used.
- [ ] Isolated prototype + rollback; existing recognition tests stay green.
- [ ] Real-device spoof tests (printed photo, screen, video, replay) recorded.

---

## 6. Offline / privacy check

| Check | Result | Evidence |
|---|---|---|
| Face images/embeddings leave the device? | No | No `INTERNET` permission (merged manifest); templates stored locally (`FaceEmbeddingCodec` in profile) |
| New model requiring cloud/remote inference? | None added | — |
| New internet permission? | No | `AndroidManifest.xml` `INTERNET tools:node="remove"` |
| New telemetry/network dependency? | None added | `gradle/libs.versions.toml` unchanged |
| Biometric vectors in logs? | No | `RecognitionPipelineContractTest.noBiometricVectorIsEverLogged`; purchase-token rule analogous |
| Retrieval/**deletion** flows intact? | Yes | `ResetRepositoryImpl` (delete all + Keystore key) unchanged |

---

## 7. Tests, build, and status

Added: `app/src/test/java/uz/faceguard/app/recognition/RecognitionRobustnessTest.kt`
(8 tests) — non-finite live/template → UNKNOWN (never PARENT/CHILD), all-zero live → UNKNOWN,
brute-force non-finite combinations, liveness decoupling + heuristic-never-SPOOF.

Real device / spoof / FAR-FRR: **NOT RUN (no device)**.

Status: **TECHNICAL AUDIT PASS — DEVICE VALIDATION PENDING.**

> Automated/JVM results prove numeric robustness and policy contracts only. They do **not** prove
> real-world biometric accuracy, liveness efficacy, or FAR/FRR. Those remain open.

---

## 8. Revision of record

| Field | Value |
|---|---|
| Branch | `feature/phase4-screen-time-complete` |
| Prepared against commit | `87972e0622b74a2a7e7668c35c5f68897e8b5835` |
| Date | 2026-10-11 |
| Status | PREPARED — NOT device-validated. Block 5 not closed. |
