# QALQON — Permission & Capability Disclosures

> **Status: PREPARED — NOT SUBMITTED.** In-app disclosures are implemented in code; the
> Play Console declarations are **NOT SUBMITTED / NOT VERIFIED** (no Play Console access).

## Permission / capability matrix
| Permission/Capability | Why needed | In-app disclosure | Play declaration | Required? |
|---|---|---|---|---|
| Camera | Recognise a face | Permission rationale + system dialog | Standard runtime permission | Core |
| Accessibility service | Touch-blocking protection overlay + foreground transitions | Separate disclosure + affirmative consent before enabling (`ProtectionScreen` → `accessibility_disclosure_*`) | **Accessibility declaration required** (manual) | Core |
| Usage Access (Usage Stats) | Detect which app is in the foreground | Rationale in the requirements card | Sensitive-permission declaration (manual, if prompted) | Core |
| Display over other apps (Overlay) | Visual protection fallback over a protected app | Rationale in the requirements card | Declaration if prompted | Conditional |
| Notifications | Ongoing protection status + parent alerts | System dialog (Android 13+) | Normal | Recommended |
| Foreground Service (`camera`, `specialUse`) | Background protection runtime | Persistent notification | **FGS declaration** (manual) | Core runtime |
| Battery optimization ignore | Reliability (recommended) | Device-reliability card | Policy-dependent | Recommended |

## Accessibility — declaration pack

**Official requirement (verbatim, verified 2026-10-07):**
> "Must be within the app itself, cannot only be in the app description or on a website;
> … Must require affirmative user action for consent (for example, tap to accept, or tick
> a check box); … This should be a separate disclosure indicating why the app requires the
> AccessibilityService API and any potential use cases."
> — https://support.google.com/googleplay/android-developer/answer/10964491

**For apps that are NOT accessibility tools** (QALQON does not set `isAccessibilityTool`):
> "you will be required to complete an accessibility declaration in Play Console."
> — same source

**QALQON's actual AccessibilityService behavior (must match the declaration):**
- Purpose: **app functionality** — it detects protected-app window transitions and draws
  the **touchable blocking overlay** so the child cannot interact with a protected app.
- Event types: `typeWindowStateChanged|typeWindowsChanged` only.
- Data read: **only the foreground package name**. `canRetrieveWindowContent=false`, so no
  screen text, passwords or notifications are read; nothing is stored or transmitted.
- Enablement: Android Settings → Accessibility; the app shows a disclosure and requires
  affirmative consent before sending the user there.
- Declaration answers: purpose = *App functionality*; "do you collect/share personal or
  sensitive data using accessibility capabilities?" = **No**.

**PLAY ACCESSIBILITY DECLARATION = REQUIRES PLAY CONSOLE MANUAL SUBMISSION.**

## Usage Access — disclosure
QALQON uses **Usage Access / UsageStats** to learn which app is in the foreground so it can
apply protection when a protected app is opened. It reads only the foreground package name;
usage data is **not** sent off the device. (API: `AppOpsManager` `OPSTR_GET_USAGE_STATS`;
Settings route: `ACTION_USAGE_ACCESS_SETTINGS`.) Declaration, if prompted, is manual.

## Overlay — disclosure
QALQON uses **draw-over-other-apps** (`SYSTEM_ALERT_WINDOW`) to show the protection screen
over a protected app as a **visual fallback**; the primary block is the accessibility
overlay. This does **not** mean QALQON reads or transmits screen content — it only draws a
window.

## Foreground Service — declaration pack
| Field | Value |
|---|---|
| Type | `camera` + `specialUse` |
| Subtype property | `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE = parental_control_protection_state` |
| Purpose | Keep the protection runtime (foreground-app monitoring + on-device recognition) alive while QALQON is not on screen |
| User-visible | Persistent, silent notification ("protection is running") |
| Camera type timing | Claimed only in a legal while-in-use foreground moment (never from boot) |
