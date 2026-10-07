# QALQON — Reviewer Access & Test Instructions

> Stage 8 remediation. Google Play's review requires **app access** instructions when an
> app has a login. QALQON has a local account + PIN, permissions, and a subscription.
> This is the draft for Play Console → **App content → App access**.

**Do NOT commit real credentials to the repository.** Use placeholders here; enter the
real test account in Play Console only.

## 1. What the reviewer needs
| Item | Value |
|---|---|
| Test account phone | `[TEST PHONE — enter in Play Console only]` |
| Test PIN | `[TEST PIN — enter in Play Console only]` |
| Login mode | Local account (no backend, no OTP/SMS) |

## 2. Step-by-step review flow
1. Launch Qalqon → **Welcome** → **Register**.
2. Enter a name, the test phone number, and the test PIN (4- or 6-digit). **Register**.
3. (If the language picker shows) choose Uzbek / Russian / English.
4. **Settings → Family → Parent profile** → enroll the parent face (grant **Camera**).
5. **Children → Add** → create a child → **Enroll face** (point the camera at a face).
6. **Settings → Protection → Protected apps** → tick one app (e.g. the browser).
7. **Protection screen** → grant each required access:
   - **Camera** (runtime dialog)
   - **Usage access** (system settings)
   - **Display over other apps** (system settings)
   - **Background app monitoring service** — tap → the **in-app disclosure** appears →
     **"I agree — open settings"** → enable in system settings
   - **Notifications** (if prompted)
8. Turn **Protection ON** → the ongoing notification appears.
9. Open the protected app (or have a child face in front of the camera) → QALQON blocks
   it with the blocking overlay.
10. **Subscription (optional, requires a license tester):** Settings → Subscription →
    "Start 3-day free trial" → complete the Play test purchase → status becomes
    "Free trial active".

## 3. Notes for the reviewer
- QALQON is **fully offline**; no network is needed.
- Face recognition is **on-device**; no data leaves the device.
- The accessibility service reads **only the foreground package name** (no screen
  content); the in-app disclosure + consent is shown before enabling.
- Protection works best while Qalqon has been opened at least once (camera session is
  latched from a foreground moment).

## 4. Special permissions to expect
Camera (runtime), Usage access (special), Display over other apps (special),
Accessibility service (user-enabled, with disclosure/consent), Notifications (runtime).

## 5. Subscription testing
Requires a **license tester** account configured in Play Console → License testing; use
Internal testing track.

**Status: DRAFT — real credentials go into Play Console only, `MANUAL ACTION REQUIRED`.**
