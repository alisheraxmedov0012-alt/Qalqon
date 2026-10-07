# QALQON — Account Deletion (in-app + external resource)

> **Status: In-app deletion IMPLEMENTED; external web resource NOT PUBLISHED.**
> Play's Account Deletion Requirement needs **both**.

**Official requirement (verbatim, verified 2026-10-07):**
> "This option must be available both from within your app and externally through a
> designated web resource. When a user requests account deletion, you are required to
> delete all associated user data; merely freezing the account is not sufficient."
> — https://support.google.com/googleplay/android-developer/answer/10144311

## A. In-app deletion — IMPLEMENTED (code)
**Where:** Settings → **Privacy** → delete all data (confirmation dialog). The Privacy
action lives behind the PIN-gated parent UI, so a child cannot trigger it.

**What it deletes** (`ResetRepositoryImpl.resetAll()`, hardened in Stage 4/10):
| Data | Cleared |
|---|---|
| Account (`user_accounts`) | ✅ |
| Parent profile + **face template** | ✅ |
| Child profiles + **face templates** | ✅ |
| Protected apps, child policies, schedules + targets, eye-safety | ✅ |
| Activity log, parent requests, notification records | ✅ |
| Screen-time usage, limits, snapshot checkpoints | ✅ |
| Settings DataStore, session DataStore, PIN-attempt/lockout DataStore | ✅ |
| **Biometric encryption key (Android Keystore)** | ✅ (`deleteKey()`) |

Because the Keystore key is deleted, any residual ciphertext is undecryptable — deletion
is complete and fail-safe. Verified by `Stage4DeletionAndBackupTest` and
`PlayComplianceContractTest`. **Logout ≠ Delete** in the code (logout only ends the
session).

**Biometric residue:** deleting a *single child profile* removes that child's row and its
encrypted template; per-child config rows are cleared on full account reset. (Documented.)

## B. External web resource — NOT PUBLISHED (manual)
Because QALQON is **backendless / client-only** (no server, no Internet), the external
resource is a **request/instructions page** that explains the on-device deletion path and
how to request help. **Do not claim server-side deletion — there is none.**

Required page: `[ACCOUNT DELETION URL]` (public HTTPS). Suggested content:
- What QALQON stores and that it is on the user's device only.
- Steps to delete in-app (Settings → Privacy → delete all data).
- A contact path (`[SUPPORT EMAIL]`) to request assistance.
- Scope: account, parent/child profiles, face templates, settings, activity, logs.

**Play Console:** enter the URL in App content → **Account deletion** once hosted.
**Status: NOT VERIFIED** (no URL, no Play Console access).
