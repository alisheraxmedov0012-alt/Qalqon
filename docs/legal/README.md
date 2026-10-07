# QALQON — Legal & Support pages (deploy-ready)

> **Status: PREPARED — NOT PUBLISHED. Public hosting is the remaining Release Block 2 blocker.**
> These pages are complete and code-accurate, but **no official QALQON domain or hosting
> exists yet**, so they are not live. Do not invent a domain or claim they are published.

This directory holds the app's public legal and support pages as self-contained, mobile-friendly
static HTML (no JavaScript, no tracking, no login). They are ready to drop onto any static host
or CMS.

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

## Target URLs (once hosted)

Play Console requires public HTTPS pages. The app links to them through the single source of
truth `uz.faceguard.app.core.legal.LegalLinks`, which currently returns `null` (nothing is hosted),
so the app shows an honest "not available yet" state.

| Purpose | App path (`LegalLinks`) | Target URL |
|---|---|---|
| Privacy Policy | `/privacy` | `https://<official-qalqon-domain>/privacy` |
| Terms of Service | `/terms` | `https://<official-qalqon-domain>/terms` |
| Account deletion | `/delete-account` | `https://<official-qalqon-domain>/delete-account` |
| Support | `/support` | `https://<official-qalqon-domain>/support` |

Suggested host mapping: serve `en/privacy-policy.html` at `/privacy`, `en/terms-of-service.html`
at `/terms`, `en/delete-account.html` at `/delete-account`, `en/support.html` at `/support`, and
the `uz/` and `ru/` trees under `/uz/` and `/ru/`.

## Requirements (Play policy)

1. HTTPS with a valid certificate; no mixed content.
2. Public: reachable by anyone, no login, no geo-blocking.
3. Stable URL that does not change between releases.
4. Mobile-friendly and readable.
5. Contact information on every page.

## Two substitutions required before publishing

These pages contain **no fabricated domain and no fabricated email**. Before publishing, replace:

1. `[[SUPPORT_EMAIL]]` — the real, monitored support mailbox (every page + in-app
   `LegalLinks.SUPPORT_EMAIL`).
2. The official QALQON domain — set `LegalLinks.BASE_URL` in the app once the site is live.

## Deployment (developer action) — currently PENDING

No hosting credentials, DNS, or domain exist in this repository, and no deployment was performed.
Enabling a host (reusing an existing website/CMS, a static host such as GitHub Pages / Netlify /
Cloudflare Pages / S3+CloudFront, etc.) is a project-owner decision that this task does not take
unilaterally, and it does **not** produce the required `<official-qalqon-domain>` URL on its own.

**Exact remaining external action:** choose and configure a host for the official QALQON domain,
publish these pages at the paths above, then set `LegalLinks.BASE_URL` (and `SUPPORT_EMAIL`) and
enter the URLs in Play Console (App content → Privacy policy and Account deletion).

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

