# QALQON — Subscription Disclosure

> **Status: PREPARED — NOT SUBMITTED.** Describes QALQON Premium exactly as the Stage 9
> billing code implements it. The Play Console product/base-plan/trial configuration is
> **NOT VERIFIED** (no Play Console access here).

## What is sold
| Item | Value | Source |
|---|---|---|
| Product | QALQON Premium (subscription) | `domain/billing/Subscription.kt` |
| Product ID | `qalqon_premium` | same |
| Billing period | Monthly (auto-renewing) | base plan `monthly` |
| Free trial | 3 days (Play offer `trial-3-day`) | same |
| Price | **Dynamic from Google Play** — never hardcoded in the app | `ProductDetails` |

## What the user must understand before buying (user-facing disclosure)
- What they buy: a **monthly auto-renewing subscription** ("QALQON Premium").
- Price: shown by **Google Play** at purchase time (localized, per country).
- Free trial: **3 days**, then the subscription price, **unless cancelled** at least 24 h
  before the trial ends.
- Charges: billed to the **Google Play account**; renews automatically each month until
  cancelled.
- Cancellation: any time in **Google Play → Subscriptions**; access continues until the end
  of the paid period.
- What Premium unlocks: additional child profiles, schedules, screen-time limits,
  eye-safety, extra-time requests, activity history. **Core protection stays free.**
- Managing/restoring: the in-app Premium screen offers **Restore purchase** (a real Play
  query).

These are presented in the subscription screen and are consistent with
`docs/playstore/STORE_LISTING.md`.

## Play Console configuration (manual, not verified here)
1. Monetize → Products → Subscriptions → create **`qalqon_premium`**.
2. Add base plan **`monthly`** (auto-renewing, monthly).
3. Add offer **`trial-3-day`** (3-day free trial).
4. Set price per country.
5. Add **license testers**; test purchase → acknowledgement → entitlement → restore →
   cancellation → expiry on a real device.

## Not verified (honest)
- **TRIAL CONFIGURATION = NOT VERIFIED** — the 3-day trial must actually exist in Play
  Console; the code references it but cannot confirm the Console state.
- **PRICE = NOT VERIFIED** — not set in the repo (by design; Play is the source of truth).
- **REAL PLAY PURCHASE/RESTORE/CANCEL TEST = NOT VERIFIED** (no license tester/device).

## Consistency
The app never hardcodes a price (`PlayComplianceContractTest.theSubscriptionScreenDoesNotHardcodeAPrice`),
never grants Premium from a local flag alone, and only QALQON's product grants Premium
(Stage 9).
