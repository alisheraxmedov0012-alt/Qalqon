# QALQON — Child / Family Policy Compliance

> Stage 8 remediation. QALQON is a **parental-control app**, not a children's app.

## 1. Classification
- QALQON is used **by a parent/guardian**, not by a child. There are **no child
  accounts**; the parent enrolls the child's face and configures the rules.
- It is **not directed to children** and must **not** be listed as a children's /
  "Designed for Families" app.
- It is **not** a child-directed entertainment app.

## 2. Families policy applicability
Google's Families policy applies to apps whose **target audience includes children**.
QALQON's intended audience is **adults (parents/guardians)**, so the Families policy is
**not applicable by target audience**. The Play Console **Target audience** declaration
must therefore select the **adult age groups only** and **not** "Designed for Families".

> Marketing/listing language must not appeal to children and must describe the app as a
> **parental-control tool for parents**. (See the store listing draft.)

## 3. Child-related data
- A child profile (name, restriction level) and a child **face template** are stored
  **on-device, encrypted**, entered/controlled by the parent.
- Data is **not collected** (no off-device transmission; no INTERNET permission).
- The child's face template is deletable by the parent; account deletion removes it and
  its encryption key.

## 4. Parent-controlled usage
All child-related configuration and deletion are behind the **parent PIN-gated UI**
(`AppLockState`). The child cannot alter settings or delete data.

## 5. Ads & third-party SDKs
- **No ads.**
- No advertising/analytics SDKs. SDKs present (Billing, CameraX, ML Kit, TFLite, Hilt,
  Room, Compose, DataStore, Accompanist) do not transmit user data off-device.

## 6. Privacy policy & Data safety
Both must describe the on-device-only handling of child data and the parent-controlled
deletion. See `STAGE8_PRIVACY_POLICY_HOSTING.md` and
`STAGE8_DATA_SAFETY_ANSWER_SHEET.md`.

## 7. Play Console answers (draft)
| Field | Answer |
|---|---|
| Target audience (age groups) | **Adults (18+)** only — not designed for children |
| Designed for Families | **No** |
| Ads | **No** |
| Content rating | Complete the questionnaire truthfully (no violence/sex/gambling/…); expect a general/utility rating |
| Stores with families programs (e.g. Google Play "Teacher Approved") | **Do not opt in** |

> UI paths/labels may change — **verify current Play Console UI** when completing the form.

**Status: CODE/DOC PASS. Play Console declaration: `MANUAL ACTION REQUIRED`.**
