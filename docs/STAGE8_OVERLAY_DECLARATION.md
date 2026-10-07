# QALQON — Overlay (SYSTEM_ALERT_WINDOW) Declaration Pack

> Stage 8 remediation. `SYSTEM_ALERT_WINDOW` is a special access. QALQON uses it only
> as a **fallback** blocking screen; the primary input block is the accessibility
> overlay.

## 1. Why it is core functionality
When the accessibility service is unavailable, QALQON still needs to show the parent's
blocking message over a protected app. A `TYPE_APPLICATION_OVERLAY` window provides
that visual fallback.

## 2. When it is shown
- Only while protection is active **and** a protected app is in the foreground, **and**
  the accessibility overlay host is absent.
- It is a full-screen, ~92% black scrim with the message
  (`protection_overlay_message`: "This app is protected. Ask your parent for help.")
  and an optional "Request extra time" button.
- Marked `FLAG_SECURE` (kept out of screenshots/Recents).

## 3. Child-protection use case
The child must see that the app is blocked. The window is `FLAG_NOT_TOUCHABLE`
(visual only) — the real input block is the accessibility overlay, so this window
cannot itself trap the user.

## 4. No deceptive / phishing behavior
- No credential capture of any kind; the overlay contains only a fixed message and a
  button.
- No ads, no unrelated content, no misleading UI, no imitation of another app's login.
- Not used to change device settings or trap input.

## 5. User education / permission flow
- Not a runtime permission; the user grants "Display over other apps" in system settings.
- QALQON deep-links there (`ACTION_MANAGE_OVERLAY_PERMISSION`) and shows the capability
  in the Protection requirements card (`protection_req_overlay`) with Open/Ready.
- Revocation is detected and surfaced as a degraded capability.

## 6. Privacy
The overlay reads no data; it renders a fixed message. On-device only; no transmission.

## 7. Play Console action
No dedicated overlay declaration form; reviewed via Data safety + listing. The listing
must explain that the overlay shows the blocking screen. **Status: CODE PASS.**
