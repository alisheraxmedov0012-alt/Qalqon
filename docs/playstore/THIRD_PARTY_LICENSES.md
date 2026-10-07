# QALQON — Third-Party Licenses

> **Status: INVENTORY PREPARED — NOT LEGALLY REVIEWED.** This is a dependency inventory and
> license-family assessment for attribution planning. It is **not legal advice**; a lawyer
> should confirm attribution/NOTICE obligations before publication.

## Dependency inventory (from `gradle/libs.versions.toml`)
| Dependency | Family | Typical license |
|---|---|---|
| AndroidX Core / Activity / Lifecycle / Navigation / Compose (BOM) / DataStore / Room / Biometric / Test | AndroidX (Google) | Apache-2.0 |
| Material3 (Compose) | Google | Apache-2.0 |
| Hilt / Dagger | Google | Apache-2.0 |
| ML Kit face-detection, ML Kit GenAI prompt | Google | ML Kit Terms (not OSS) |
| TensorFlow Lite (`org.tensorflow:tensorflow-lite`) | Google/TF | Apache-2.0 |
| CameraX (`androidx.camera:*`) | AndroidX | Apache-2.0 |
| Google Play Billing (`com.android.billingclient:billing`) | Google | Apache-2.0 / Play SDK terms |
| Accompanist Permissions | Google | Apache-2.0 |
| Kotlin stdlib / kotlinx-coroutines / KSP | JetBrains | Apache-2.0 |
| JUnit 4 | JUnit | EPL-1.0 |
| Bundled model assets (`mobile_face_net.tflite`, `models_bundled/*`) | MobileFaceNet / liveness models | **License must be confirmed** — see below |

## Attribution / NOTICE status
- **OSS attribution screen:** not present in the app. Apache-2.0 and most permissive
  licenses require preserving copyright/license notices; confirm whether an in-app or
  in-README attribution notice is required.
- **ML Kit** is governed by Google's ML Kit Terms, not an OSS license; its use is described
  in the privacy policy (on-device, no transmission).
- **Google Play Billing** use is disclosed in the privacy policy and subscription
  disclosure.

## Action items (manual)
- [ ] Confirm the license/provenance of every bundled model asset under
      `app/src/main/assets/` (especially `mobile_face_net.tflite` and `models_bundled/*`).
- [ ] Decide on an attribution/NOTICE surface (in-app "Open source licenses" screen or a
      NOTICE file) per the licenses' requirements.
- [ ] **LEGAL REVIEW REQUIRED** before publication.

No license text has been modified in this repository.
