# QALQON — Stage 8: Play Console & Submission Action Items

> Developer actions required in **Google Play Console** and on the **web** before
> QALQON can be submitted. None of these can be done from the repository; no URL or
> credential is invented here. Marked `TODO` until the developer completes them.

Legend: **Required** = Play submission blocker · **Recommended** = best practice.

---

## A. Blockers (complete before submission)

### A1. External account-deletion web resource — REQUIRED
- **Why:** Play User Data policy — apps with account creation must allow deletion
  **in-app AND via an external web resource**
  (`support.google.com/googleplay/android-developer/answer/13463537`).
- **Do:** publish an HTTPS page (or a simple form) where a user can request
  deletion of their QALQON account and all associated data. A support email/form
  that clearly explains the process is acceptable.
- **Then:** in Play Console → **App content → Account deletion** → provide the URL.
- **Note:** the in-app path already exists (`ResetRepository.resetAll()` deletes the
  account + all data + the Keystore key). Only the web resource is missing.
- **URL:** `TODO` (developer to host).

### A2. Privacy policy URL — REQUIRED
- **Why:** Play requires a privacy policy in **Play Console and within the app**
  for apps that handle personal/sensitive data (`.../answer/10144311`).
- **Do:** host a full privacy policy on a public HTTPS URL covering: data
  categories (name, phone, PIN, face template, foreground app, activity log,
  purchase), on-device-only processing, no off-device transmission, encryption,
  retention, deletion, camera, face/biometric data, children's data, Accessibility
  service, Usage Access, overlay, notifications, billing, third-party SDKs, contact.
- **Then:** Play Console → **App content → Privacy policy** → paste the URL; also
  add a link to it inside the app's Privacy screen.
- **URL:** `TODO` (developer to host).

### A3. Target API level 36 — CODE DONE, verify in Console
- The code now targets API 36 (AGP 8.13.2 + Gradle 9.5.1). Upload a build and
  confirm Play Console → **Policy status** shows no target-API issue.
- If a warning appears, the extension form (to 2026-11-01) is available on the
  policy warning's details page — but a new app should simply target 36.

---

## B. Declarations & forms — REQUIRED

### B1. Data safety form
- **Do:** Play Console → **App content → Data safety**. Declare **no data
  collected / no data shared** (the app transmits nothing off-device; no INTERNET).
  Confirm every SDK matches (Billing, CameraX, ML Kit, TFLite, Hilt, Room, Compose,
  DataStore — none transmit user data off-device in this app).
- **Must match** the privacy policy and the listing.

### B2. Accessibility declaration
- **Do:** Play Console → the **accessibility declaration** for the
  `ProtectionAccessibilityService`: declare that it is **not** an accessibility tool
  (`isAccessibilityTool` remains unset) and describe the **parental-control** use
  and the narrow data access (foreground package name only, no window content).
- **Demo video:** have a screen recording ready if Google requests it.

### B3. Target audience & content rating
- **Do:** declare the app as **parental control / not designed for children**
  (the child has no account; the parent enrolls the child's face). Complete the
  content-rating questionnaire truthfully.

### B4. App access (reviewer instructions)
- **Do:** provide test-account credentials and step-by-step instructions for the
  reviewer (create account → PIN → grant Camera / Usage Access / Overlay /
  Accessibility (with the in-app disclosure) → enroll parent + child face → choose a
  protected app → enable protection). **Do not commit real credentials to the repo.**

### B5. Sensitive permissions declarations
- **Do:** complete the Play Console declarations/justifications for
  `PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, and the foreground-service types
  (`camera`, `specialUse`). Justify each by its parental-control function.

### B6. Ads declaration
- **Do:** declare **no ads**.

### B7. Subscription configuration
- **Do:** Play Console → create subscription `qalqon_premium`, base plan `monthly`,
  and the 3-day trial offer `trial-3-day` (matching `ProductCatalog`); add license
  testers; publish to at least Internal testing. Verify real purchase, trial,
  cancel, renewal, restore.

---

## C. Store listing & assets — REQUIRED

### C1. Listing text (uz / ru / en)
- Provide **app name, short description, full description** in all three languages.
- **Do NOT** claim: "100% secure", "unbypassable", "anti-spoofing", "photo-proof",
  "works on every device", "AI guarantees detection". QALQON has a heuristic
  liveness signal only (Stage 5) and OEM/system limitations (Stage 3).
- Factual claims allowed: on-device face recognition; local-only data; blocks
  selected apps; parent/child recognition; subscription with 3-day trial.
- **Document the Accessibility API use in the listing** (policy requirement).

### C2. Visual assets
- App icon, feature graphic, phone screenshots. Screenshots must show **real**
  functionality (no fake prices, no fake reviews, no fake notifications, no fake
  security stats).

### C3. Categorisation & contact
- Category (parental control / tools), tags, support email and website.

---

## D. After submission (not verifiable here)

- Google's **real review** result and any **policy warnings** — monitor Play
  Console → Policy status.
- Real **license-tester purchase / trial / refund / restore** verification.
- Real-device behaviour (Stage 10).

---

## E. Explicitly out of Stage 8 scope

Automated QA fortress (Stage 9), real-device 72-hour soak (Stage 10), release
engineering / launch (Stage 11), commercial launch (Stage 12).
