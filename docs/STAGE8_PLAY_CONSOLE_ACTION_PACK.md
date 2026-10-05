# QALQON — Play Console Action Pack (step-by-step)

> Stage 8 remediation. Practical "go here → choose this → enter that" checklist for a
> developer with Play Console access. **UI paths/labels may change — verify current Play
> Console UI.** No status here is verified by this task.

## 1. Create / configure the app
- Play Console → **All apps → Create app**. Name "Qalqon", default language, app/Game.
- **App → Package name** `uz.faceguard.app` (permanent).

## 2. Upload the first build
- **Release → Testing → Internal testing → Create new release**; upload the AAB.
- Complete **Play App Signing**. Add testers.
- **Do the first upload only after** the declarations below are done; Play will surface
  warnings for missing items.

## 3. App content (App content section — each is a sub-task)
- **Privacy policy** → paste the HTTPS URL (see `STAGE8_PRIVACY_POLICY_HOSTING.md`).
- **Account deletion** → select "My app allows users to create an account"; provide the
  in-app path AND the external web URL (see `STAGE8_ACCOUNT_DELETION.md`).
- **Data safety** → answer using `STAGE8_DATA_SAFETY_ANSWER_SHEET.md`
  (no data collected/shared; deletion available).
- **Ads** → "No, my app does not contain ads".
- **App access** → "All or some functionality is restricted" → provide the test
  account + PIN + instructions (see `STAGE8_REVIEWER_ACCESS.md`).
- **Target audience** → adults only; **not** designed for children
  (`STAGE8_CHILD_FAMILY_COMPLIANCE.md`).
- **Content rating** → complete the questionnaire truthfully.
- **Sensitive permissions / declarations** → FGS (`camera`, `specialUse`), Accessibility,
  and any prompted permission forms (packs prepared).
- **Government/financial/health/news** → all "No".

## 4. Accessibility declaration (prompted by the AccessibilityService)
- Play Console → the **Accessibility API** declaration. Answer using
  `STAGE8_ACCESSIBILITY_DECLARATION.md`. Attach the **demo video** when recorded
  (`NOT CREATED — MANUAL ACTION REQUIRED`).

## 5. Foreground service declaration
- Provide type justifications (`camera`, `specialUse`) per `STAGE8_FGS_DECLARATION.md`.

## 6. Monetization → Subscriptions
- Create `qalqon_premium` → base plan `monthly` → offer `trial-3-day` → set pricing →
  activate → License testing. See `STAGE8_SUBSCRIPTION_PLAY_CONSOLE.md`.

## 7. Store listing
- **Main store listing**: app name, short/full description (uz/ru/en), screenshots,
  feature graphic, icon; **document the Accessibility API use** in the description.
- Use `STAGE8_STORE_LISTING_DRAFT.md` and `STAGE8_STORE_ASSETS_PLAN.md`.
- **Store settings**: category, tags, contact email, website, privacy-policy URL.
- **Countries/regions**: choose distribution.

## 8. Pre-submit review
- Walk `STAGE8_PLAY_CONSOLE_MASTER_CHECKLIST.md`; confirm every `MANUAL ACTION REQUIRED`
  item is done; then submit for review.

## 9. After submission
- Monitor **Policy status**; respond to any policy warning.
- Verify the real purchase/trial/refund/restore with a license tester.

> **REMINDER:** this task could not access Play Console, hosting, or Google Play billing
> test environment. Nothing above is claimed as done.
