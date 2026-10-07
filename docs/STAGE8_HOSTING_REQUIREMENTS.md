# QALQON — Hosting Requirements (Privacy Policy + Account Deletion)

> Stage 8 remediation. QALQON needs **two public HTTPS pages** before Play submission.
> Neither exists yet. **Do not invent a domain.** Placeholders use `<your-real-domain>`.

## Pages to host
| Page | Suggested path | Purpose |
|---|---|---|
| Privacy Policy | `/qalqon/privacy` | Required by Play (App content → Privacy policy) |
| Account deletion | `/qalqon/delete-account` | Required by Play (Account deletion) |

Example (placeholder): `https://<your-real-domain>/qalqon/privacy`

## Requirements
1. **HTTPS** (valid TLS certificate; no mixed content).
2. **Public** — reachable by anyone, no login, no geo-blocking.
3. **Stable** — the URL does not change between releases.
4. **Mobile-friendly** — readable on a phone.
5. **No authentication** for the policy page.
6. **Contact information** on both pages.
7. Privacy page content = `STAGE8_PRIVACY_POLICY_HOSTING.md` §1.
8. Deletion page content + instructions = `STAGE8_ACCOUNT_DELETION.md` §B/C.
9. State **data categories**, **on-device storage** (no server copy), and
   **retention/deletion** clearly.
10. The app should also link to the privacy policy URL (Privacy screen) — code change
    possible once the URL exists.

## Suggested hosting approach (developer decides)
- Reuse an existing website/CMS (simplest), or
- **GitHub Pages** for a repository the developer owns. The project remote is
  `alisheraxmedov0012-alt/Qalqon`; enabling Pages is a developer decision — this task
  does **not** create repositories, accounts, or Pages sites unilaterally.
- Any static host (S3+CloudFront, Netlify, Cloudflare Pages, etc.).

## Play Console fields
- **App content → Privacy policy** → privacy URL.
- **App content → Account deletion** → external deletion URL + in-app instructions.

## Verification checklist (after hosting)
- [ ] Both URLs open over HTTPS on phone + desktop, without login.
- [ ] Content matches the prepared text.
- [ ] Privacy URL linked from the app's Privacy screen.
- [ ] Both URLs entered in Play Console.

**Status: HOSTING — `MANUAL ACTION REQUIRED`. No live URL exists.**
