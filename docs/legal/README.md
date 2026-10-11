# QALQON — Legal & Support pages (published)

> **Status: PUBLISHED on the official QALQON domain `https://qalqon.win`** (Cloudflare Pages).
> The four resources are live over HTTPS in all three languages, with the real support mailbox
> `alisheraxmedov0012@gmail.com` set on every page. Legal-counsel review remains outstanding
> (see "Legal review" below).

This directory holds the app's public legal and support pages as self-contained, mobile-friendly
static HTML (no JavaScript, no tracking, no login). Cloudflare Pages serves them from this folder.

## Files

```
docs/legal/
  index.html                         language hub
  en/privacy-policy.html             Privacy Policy   (authoritative)
  en/terms-of-service.html           Terms of Service (authoritative)
  en/delete-account.html             external account-deletion page
  en/support.html                    support page
  uz/…  ru/…                         non-authoritative translations (AI-assisted)
```

English is the **authoritative** version. The `uz` and `ru` pages are translations provided for
the app's three supported languages; they are **not certified legal translations**.

## Live URLs (verified over HTTPS)

| Purpose | Live URL |
|---|---|
| Privacy Policy | `https://qalqon.win/en/privacy-policy` (`/uz/…`, `/ru/…`) |
| Terms of Service | `https://qalqon.win/en/terms-of-service` (`/uz/…`, `/ru/…`) |
| Account deletion | `https://qalqon.win/en/delete-account` (`/uz/…`, `/ru/…`) |
| Support | `https://qalqon.win/en/support` (`/uz/…`, `/ru/…`) |

Cloudflare Pages normalises the `*.html` URLs to the extensionless form (308 → 200), so the
canonical URLs above are the ones the app and Play Console use. The app links to them through the
single source of truth `uz.faceguard.app.core.legal.LegalLinks` (`BASE_URL = "https://qalqon.win"`).
The app links to the **English (authoritative)** pages.

Support email (published on every page and in `LegalLinks.SUPPORT_EMAIL`):
**alisheraxmedov0012@gmail.com**.

## Requirements (Play policy)

1. HTTPS with a valid certificate; no mixed content.
2. Public: reachable by anyone, no login, no geo-blocking.
3. Stable URL that does not change between releases.
4. Mobile-friendly and readable.
5. Contact information on every page.

## Deployment

Hosted on **Cloudflare Pages** (project owner's infrastructure), building this `docs/legal`
folder. The production Git branch is `feature/phase4-screen-time-complete`; the build output
directory is `docs/legal`. No repository or Cloudflare settings were changed by this task.

To update content: edit the HTML here and push; Cloudflare Pages rebuilds. Keep `LegalLinks.BASE_URL`
and the paths in the app in sync with the paths above.


## Account deletion semantics (honest)

QALQON is backendless: your data lives only on the device, and the in-app deletion
(Settings → Privacy → delete all data) performs a **real** deletion of all local data plus the
Keystore key. The external page explains this and provides a request path; it does **not** claim a
server-side deletion, because there is no server. See `docs/playstore/ACCOUNT_DELETION.md`.

## Legal review

These documents are professional drafts but have **not** been reviewed by legal counsel.
**Legal review is REQUIRED before production publication.**

## Google Play account-deletion conformance (analysis)

Google Play's Account Deletion requirement applies when an app lets users create an account.
QALQON's account model and the honest conformance position:

| Question | Answer (from the code) |
|---|---|
| Does QALQON create a user account? | **Yes** — a local account (name + phone number + PIN). |
| Local-only or server-backed? | **Local-only.** No QALQON server exists (no INTERNET permission). |
| What user data exists? | Account, PIN (salted hash), parent/child profiles, encrypted face templates, protected apps/policies/schedules/screen-time/eye-safety, activity log, settings, local subscription cache. |
| Is there any server-side user data? | **No.** Nothing is transmitted; there is no server copy. |
| What can the external URL actually delete? | **Nothing remotely** — there is no remote data. It can only explain the on-device deletion path and record a support request. |
| What does the in-app deletion delete? | All local data **and** the Android Keystore biometric key (`ResetRepositoryImpl.resetAll()`), returning the app to a fresh-install state. |
| Is subscription cancellation separate? | **Yes** — a Google Play subscription is not cancelled by deleting the QALQON account. |
| Is the public deletion resource sufficient for this architecture? | The requirement asks for a discoverable external **option to initiate** deletion. Since the data is device-local, the external page can only initiate/guide; the actual deletion is completed on-device. This is documented honestly and is **not** presented as authenticated server-side deletion. |

**Limitation (precise):** Google Play's external-deletion mechanism implicitly assumes
server-held account data. QALQON is backendless, so the external resource is an
initiate/instructions page, not an authenticated remote-deletion endpoint. This is a
**policy-interpretation nuance** to confirm with Google Play review (or with counsel); it is
**not** a code defect and is **not** worked around by adding a backend (out of scope — §52/§15
of Block 2 forbid adding a backend just to appear compliant). If Google Play requires
authenticated remote deletion for this app, that is a product/architecture decision for the
project owner, not a Block 2 change.

