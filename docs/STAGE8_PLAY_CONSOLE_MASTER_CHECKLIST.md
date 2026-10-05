# QALQON — Play Console Master Checklist

> Stage 8 remediation. Every item is tagged with its **owner type**:
> **CODE** (done in the repo) · **PLAY CONSOLE** · **HOSTING** · **REAL DEVICE** ·
> **MANUAL**. Status is honest: `DONE (code)`, `NOT VERIFIED`, or
> `MANUAL ACTION REQUIRED`.

| # | Item | Type | Status |
|---|---|---|---|
| 1 | App created in Play Console | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 2 | Package name verified (`uz.faceguard.app`) | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 3 | Target API 36 | CODE | `DONE` (AGP 8.13.2 / Gradle 9.5.1 / compileSdk+targetSdk 36) |
| 4 | Privacy Policy URL | HOSTING | `MANUAL ACTION REQUIRED` (text ready; not hosted) |
| 5 | Account deletion URL | HOSTING | `MANUAL ACTION REQUIRED` (page content ready; not hosted) |
| 6 | Data Safety form | PLAY CONSOLE | `MANUAL ACTION REQUIRED` (answer sheet ready) |
| 7 | App access (reviewer instructions) | PLAY CONSOLE | `MANUAL ACTION REQUIRED` (draft ready) |
| 8 | Target audience (adults only) | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 9 | Content rating questionnaire | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 10 | Ads declaration (none) | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 11 | Accessibility declaration | PLAY CONSOLE | `MANUAL ACTION REQUIRED` (pack ready; disclosure code done) |
| 12 | FGS declaration (`camera`, `specialUse`) | PLAY CONSOLE | `MANUAL ACTION REQUIRED` (pack ready) |
| 13 | Sensitive permission declarations (Usage access, Overlay) | PLAY CONSOLE | `MANUAL ACTION REQUIRED` (packs ready) |
| 14 | Subscription product `qalqon_premium` | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 15 | Base plan `monthly` | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 16 | Trial offer `trial-3-day` | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 17 | Pricing | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 18 | License testers | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 19 | Store listing (uz/ru/en) | PLAY CONSOLE | `MANUAL ACTION REQUIRED` (draft ready) |
| 20 | Screenshots | MANUAL | `MANUAL ACTION REQUIRED` (plan ready) |
| 21 | Feature graphic | MANUAL | `MANUAL ACTION REQUIRED` (plan ready) |
| 22 | App icon (512×512) | MANUAL | `MANUAL ACTION REQUIRED` (vector exists in-app) |
| 23 | Reviewer instructions | PLAY CONSOLE | `MANUAL ACTION REQUIRED` (draft ready) |
| 24 | Contact email / developer identity | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 25 | Countries/regions | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 26 | Release track (internal→closed→open) | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 27 | App signing (Play App Signing) | PLAY CONSOLE | `MANUAL ACTION REQUIRED` |
| 28 | Final pre-submit review | MANUAL | `MANUAL ACTION REQUIRED` |
| 29 | Real purchase/trial/refund/restore test | REAL DEVICE | `NOT VERIFIED` (license tester needed) |
| 30 | Real Google review | REAL DEVICE | `NOT VERIFIED` (post-submission) |

## Code-side items already satisfied (with evidence)
- Target API 36 — `app/build.gradle.kts`, merged debug+release manifests.
- No INTERNET / no location — source + merged manifest (`tools:node="remove"`).
- Accessibility disclosure + affirmative consent — `ProtectionScreen.kt` + strings.
- `isAccessibilityTool` unset, `canRetrieveWindowData=false` — service config.
- Account reset deletes all data + Keystore key — `ResetRepositoryImpl`.
- Privacy screen present — `feature/privacy/PrivacyScreen.kt`.
- Subscription disclosures dynamic (no hardcoded price) — subscription screen.
- PlayComplianceContractTest pins these.

> **Note:** Play Console UI paths and labels change over time — **verify current Play
> Console UI** while completing each item.
