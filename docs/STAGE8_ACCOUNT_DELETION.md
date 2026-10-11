# QALQON — Account Deletion (In-App + External Web Resource)

> **SUPERSEDED (Release Block 2 finalization): the external resource is now live** at
> `https://qalqon.win/en/delete-account`. The canonical content is
> `docs/legal/en/delete-account.html`. This Stage 8 record is retained for history.

> Stage 8 remediation. Google Play's **Account Deletion Requirement** (User Data
> policy) applies because QALQON allows account creation.

**Official requirement (verbatim):**
> "This option must be available both from within your app and externally through a
> designated web resource. When a user requests account deletion, you are required to
> delete all associated user data; merely freezing the account is not sufficient."
> — support.google.com/googleplay/android-developer/answer/10144311

> "A link to this web resource must be entered in the designated URL form field within
> Play Console." — same source

---

## A. In-app account deletion — CODE STATUS: PASS

**Where:** Settings → **Privacy** (`PrivacySettingsScreen`) → "Delete all data"
(`data_reset_all`) with a confirmation dialog (`data_reset_confirm_*`), or the same
reset surfaced after wiping. Reachable without any hidden pattern.

**What it deletes** — `ResetRepositoryImpl.resetAll()` (Stage 4 hardened):

| Data | Cleared | Where |
|---|---|---|
| Account (`user_accounts`) | ✅ | Room |
| Parent profile + face template | ✅ | Room `parent_profiles` |
| Child profiles + face templates | ✅ | Room `child_profiles` |
| Protected apps, child policies, schedules + targets, eye-safety | ✅ | Room |
| Activity log, parent requests, notification records | ✅ | Room |
| Screen-time usage, limits, snapshot checkpoints | ✅ | Room |
| Settings | ✅ | DataStore `settings` |
| Session (account id) | ✅ | DataStore `session` |
| PIN attempt / lockout state | ✅ | DataStore `security` |
| **Biometric encryption key** | ✅ | Android Keystore (`deleteKey()`) |

Because the Keystore key is deleted, any residual ciphertext is undecryptable
(deletion is complete and fail-safe). Verified by `SecurityPersistenceTest`
(instrumented) and `Stage4DeletionAndBackupTest` (JVM).

**Authentication required?** The Privacy/delete action lives behind the PIN-gated
parent UI (`AppLockState` + `lockRedirectFor`), so a child cannot trigger it.

**Confirmation?** Yes — an explicit confirm dialog states the action is irreversible.

**Note (P3, not a blocker):** the label is "Delete all data" rather than "Delete
account". Functionally this **is** account deletion (single local account). An
explicit "Delete account" wording would be clearer; left as a polish item to avoid
Stage 6 copy churn. Not a Play blocker: the requirement is that the option exists and
deletes all data, which it does.

**Child deletion residue (P3):** deleting a *single child profile* leaves that child's
per-child config rows (policies/schedules/eye-safety/usage). Not a Play compliance
issue (Play requires *account* deletion, which is complete) and no biometric residue
(the child row and its encrypted template are removed). Documented, not fixed.

---

## B. External web account-deletion resource — STATUS: `MANUAL ACTION REQUIRED`

**Current: no web resource exists.** QALQON is **backendless / client-only** (no
server, no INTERNET permission), so there is no server-side account to delete. The
web resource is therefore a **request/instructions page**, which is what Google's
policy requires ("a readily discoverable option to initiate app account deletion …
outside of your app (for example, by visiting your website)").

**Do not claim a server-side deletion** — there is none. The page must be honest:
data lives on the user's device, and the in-app deletion removes it; the web page
lets a user request deletion and explains the on-device step.

### Required URL
- Suggested: `https://<your-real-domain>/qalqon/delete-account` (placeholder; **no fake
  domain**). Must be HTTPS, public, no login.

### Required page content
1. App name + package (`uz.faceguard.app`).
2. The data categories held (account, profiles, face templates, settings, activity).
3. That the data is stored **on the user's device** and there is no server copy.
4. A deletion request form/email: ask for the account **phone number** (the only
   account identifier) so the request can be matched.
5. What will be deleted and the expected response time.
6. The on-device step (in-app delete) and/or app-uninstall instructions.
7. Retention statement: no data retained after deletion.
8. Contact email.

### Identity verification
The account is identified by the **phone number**. The page asks the requester to
supply it and confirm they are the account holder.

### Play Console field
App content → **Account deletion** → paste the web URL, and describe the in-app path.

---

## C. Web-resource semantics (honest)
Because there is no backend, we **cannot** delete data remotely. The honest flow:
1. User requests deletion on the web page (records the request).
2. We confirm the on-device deletion path (in-app delete / uninstall).
3. We state clearly that no server data exists to delete.

This satisfies the policy's intent (a discoverable external deletion option + clear
instructions) without a false "server deletion" claim. If a backend is added later
(separate scope), the page should perform authoritative server deletion.
