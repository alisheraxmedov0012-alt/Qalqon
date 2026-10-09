# QALQON — Store Listing (Play-safe copy)

> **Status: PREPARED — NOT SUBMITTED.** Draft listing copy for **English (primary),
> Uzbek, Russian**. No unsupported claims. Nothing is submitted to Play Console.

## Forbidden claims (must NOT appear)
"100% secure", "impossible to bypass", "unbreakable", "perfect face recognition",
"guaranteed child detection", "anti-spoofing", "photo/video-proof",
"works on every device", "never fails", "guaranteed privacy", "Google-approved",
"GDPR certified", "COPPA compliant", medical/therapeutic or "cures phone addiction".

## Factual claims we may make (all verified in code)
On-device face recognition for parent/child · fully offline · data stays on the phone ·
blocks selected apps for the child · parent/child recognition · parental controls ·
monthly premium subscription with a 3-day free trial · broad Android compatibility
(API 26–36) — careful wording because per-OEM physical validation is not complete.

---

## English (primary)
- **App name (≤30):** `QALQON — Parental Control`
- **Short description (≤80):** `On-device face recognition that protects your child's phone time. Offline and private.`
- **Full description:**

QALQON is a parental-control app that recognises when your child is using the phone and
applies the protection rules you set.

**How it works**
- You enrol your own face and your child's face on the device.
- When the front camera recognises the child's face, QALQON applies your rules to the apps
  you selected (for example limits or blocking). When you (the parent) look at the phone,
  protection is lifted so the phone stays usable for you.

**Privacy first**
- Face recognition runs **entirely on your phone**. QALQON has **no Internet permission**
  and does not upload your data.
- Raw camera frames are **not saved**. Face templates are stored **encrypted** on the
  device and never leave it.
- No ads, no analytics, no trackers.

**Main features**
- Parent and child face recognition on-device
- Choose which apps to protect, per child
- Protection status at a glance; parent-only settings behind a PIN
- Works offline; designed for privacy

**Permissions QALQON asks for, and why**
- Camera — recognises a face.
- Usage Access — knows which app is open, to apply protection.
- Accessibility — draws the touch-blocking protection screen and reads only the
  foreground app's package name (never screen text, passwords or notifications).
- Display over other apps — shows the protection screen over a protected app.
- Notifications — shows the ongoing protection status.

**Premium**
- QALQON Premium is a monthly subscription (auto-renewing) with a 3-day free trial.
- Price is shown in Google Play before you confirm. Cancel any time in Google Play.
- Core protection stays free.

**Good to know / limitations**
- Face matching is a convenience signal, not a security guarantee; photos or screens may
  not be reliably detected.
- Protection depends on Android permissions you grant; you can revoke them at any time,
  which reduces protection.
- Behaviour can vary by device manufacturer; broad Android compatibility, individual
  results may differ.

**Support:** `alisheraxmedov0012@gmail.com`

---

## Uzbek (values)
- **App name (≤30):** `QALQON — ota-ona nazorati`
- **Short description (≤80):** `Bolani tanlangan ilovalardan himoya qiluvchi ota-ona nazorati ilovasi`
- **Full description:** (mirror the English structure; see `docs/STAGE8_STORE_LISTING_DRAFT.md` for the ready Uzbek/Russian body.)

## Russian (values)
- **App name (≤30):** `QALQON — родительский контроль`
- **Short description (≤80):** `Родительский контроль с распознаванием лица на устройстве`
- **Full description:** (mirror the English structure; see `docs/STAGE8_STORE_LISTING_DRAFT.md`.)

## Category & tags
- Category: **Tools** (secondary: Parenting). Tags: parental control, privacy, security.
- Contains ads: **No**. In-app purchases: **Yes** (subscription).

## Claim ↔ capability cross-check
| Claim | Backed by |
|---|---|
| On-device recognition | ML Kit + TFLite bundled models; no INTERNET (`PlayComplianceContractTest`) |
| Data stays on device | No INTERNET permission; backup/D2D excluded (Stage 10) |
| Templates encrypted | AES-256-GCM Keystore envelope (Stage 10) |
| Blocks selected apps | Accessibility touch-blocking overlay; visual fallback when overlay only (Stage 4) |
| Works offline | Protection/recognition offline-first; only billing needs Play IPC (Stage 5/9) |
| Premium monthly + 3-day trial | `qalqon_premium` / `monthly` / `trial-3-day` — **TRIAL CONFIGURATION IN PLAY CONSOLE = NOT VERIFIED** |
