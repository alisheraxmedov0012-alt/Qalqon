# QALQON — Accessibility API Declaration Pack

> Stage 8 remediation. QALQON includes `ProtectionAccessibilityService`. Per Google
> Play it is **not** an accessibility tool, so it must complete an accessibility
> declaration, show a clear in-app disclosure, and obtain affirmative consent.

**Official requirement (verbatim):**
> "only services designed to help people with disabilities … are eligible to declare
> themselves as accessibility tools by setting isAccessibilityTool=true … For all other
> uses … you will be required to complete an accessibility declaration in Play Console
> and must implement a clear in-app disclosure explaining data access and use, and
> obtain affirmative user consent." — support.google.com/googleplay/android-developer/answer/16558241

> "Provide a clear Play Console declaration and demo video if using AccessibilityService
> API." — same source

> The Accessibility API may be used to "prevent the ability for users to disable or
> uninstall any app or service unless authorised by a parent or guardian through a
> **parental control app**". — support.google.com/googleplay/android-developer/answer/18258653

---

## 1. Intended use
Parental control: detect when the **child opens a protected app** and apply the
parent's rules (including drawing a blocking overlay the child cannot dismiss).

## 2. Why the Accessibility API is necessary
Android provides no other supported way for a parental-control app to (a) receive
foreground window/app transitions system-wide and (b) draw a **touch-consuming**
overlay over another app. The `SYSTEM_ALERT_WINDOW` overlay is `FLAG_NOT_TOUCHABLE`
(visual only) and cannot block input; only an `AccessibilityService` can own a
`TYPE_ACCESSIBILITY_OVERLAY`. Usage Stats alone cannot block.

## 3. Parental-control use case (policy-allowed)
QALQON uses the service as a **parental-control app authorised by the parent/guardian**
— an explicitly permitted use ("unless authorised by a parent or guardian through a
parental control app").

## 4. `isAccessibilityTool`
**Not set** (absent from `accessibility_service_config.xml` and the manifest). QALQON
is not a disability-support tool, so it must not set the flag. (Pinned by
`PlayComplianceContractTest`.)

## 5. Exact events / actions
- **Events consumed:** `typeWindowStateChanged`, `typeWindowsChanged` only.
- **Config:** `canRetrieveWindowContent="false"`, `accessibilityFeedbackType="feedbackGeneric"`,
  `accessibilityFlags="flagDefault"`.
- **Action:** reads the foreground **package name**; drives the runtime; can show/hide
  a `TYPE_ACCESSIBILITY_OVERLAY` blocking window.
- **Does NOT:** retrieve window content; read screen text, passwords, notifications or
  chat; perform gestures/clicks on other apps; autonomously initiate, plan or execute
  actions.

## 6. Data accessed vs not accessed
| Accessed | Not accessed |
|---|---|
| Foreground package name | Window/screen content |
| Window transition events | Text, passwords, notifications |
| — | Touch input content |

Data is processed **on-device only**, used solely for protection, and not transmitted
(there is no INTERNET permission).

## 7. User consent — CODE STATUS: PASS
Tapping "enable" shows an in-app **disclosure dialog** (what the service does; only the
foreground package name is read; no window content; on-device only) with an explicit
**"I agree — open settings"** confirm; the system settings intent launches **only after**
that affirmative action. Strings exist in uz/en/ru. Pinned by `PlayComplianceContractTest`.

## 8. Screenshots needed
The declaration may ask for screenshots showing the disclosure. Provide:
1. The Protection screen with the "Background app monitoring service" requirement row.
2. The disclosure dialog with "I agree — open settings".

## 9. Demo video — STATUS: `NOT CREATED — MANUAL ACTION REQUIRED`
Google may request a short demo video showing the disclosure + consent flow. **No video
exists**; it must be recorded manually (script below). Do not fabricate a video file.

**Demo video script (record on a device):**
1. Open Qalqon → Protection.
2. Tap the accessibility requirement's "Grant/Open".
3. Show the disclosure dialog; read the key points (foreground package only; no content).
4. Tap "I agree — open settings".
5. Show the Android Accessibility settings page for Qalqon.
6. (Optional) Enable, then show protection blocking a protected app.

## 10. Play Console declaration draft (answer the prompts honestly)
- **Does your app use the AccessibilityService API?** Yes.
- **Is your app an accessibility tool (`isAccessibilityTool=true`)?** No.
- **Purpose:** parental control (parent-authorised app blocking).
- **Events used:** window state / windows changed (foreground package only).
- **Data retrieved:** foreground package name; no window content.
- **Data transmitted off-device?** No.
- **Prominent disclosure shown + affirmative consent?** Yes (in-app dialog).
- **Demo video:** [attach when recorded].

> UI paths may change — **verify current Play Console UI** when completing the form.
