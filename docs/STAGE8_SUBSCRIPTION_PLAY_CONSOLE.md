# QALQON — Subscription Play Console Setup & Test Plan

> Stage 8 remediation. The billing **code** was delivered in Stage 7 (client-only
> Google Play Billing 9.1.0). This document is the **Play Console setup + test plan**.
> Billing is **NOT VERIFIED** by any real purchase — no Play Console or license tester
> is available here.

## Product model (from code — `domain/billing/Subscription.kt`)
| Item | Value |
|---|---|
| Product ID | `qalqon_premium` |
| Base plan ID | `monthly` |
| Trial offer ID | `trial-3-day` |
| Trial length | 3 days |
| After trial | monthly auto-renewing subscription |
| Price | **dynamic from Google Play** (`ProductDetails`), never hardcoded |

## 1. Play Console setup steps
1. Play Console → your app → **Monetize → Products → Subscriptions → Create subscription**.
2. Product ID: `qalqon_premium`; name/description per store listing.
3. Add a **base plan**: ID `monthly`, auto-renewing, monthly billing period.
4. Set the **base plan price** per country (Play-managed, or a price template).
5. Add an **Offer** to the base plan: ID `trial-3-day`, a **3-day free trial**, then the
   base-plan price; eligibility (new customers).
6. Activate the subscription; make it available in the target countries.
7. **License testing:** Play Console → Setup → **License testing** — add the test
   Google accounts (use "Test" license-response options).
8. Publish to at least **Internal testing** (billing cannot be tested from a local debug APK).

> UI paths can change — **verify current Play Console UI**.

## 2. Disclosures the app already shows (code) — for review
The subscription screen shows, on-screen: "3 days free"; the Play-provided price "Then
<price> per month"; "Monthly auto-renewing subscription"; the auto-renew and cancel
notices; the first-charge point; benefits; and Manage / Restore / Refresh actions.
Prices are dynamic (never hardcoded — pinned by test).

## 3. Test cases (run in Internal testing with a license tester)
| # | Case | Expected |
|---|---|---|
| 1 | No subscription | status "No subscription"; premium features locked; CTA "Start 3-day free trial" |
| 2 | Start trial | Play sheet → purchase → status "Free trial active"; premium on |
| 3 | Trial → paid renewal | after 3 days the status becomes "Active" |
| 4 | Cancel (in Play) | status "Canceled; active until period end"; premium stays on until expiry |
| 5 | Restore purchase | a real Play query restores the entitlement (not a toast) |
| 6 | Reinstall | fresh install + sign-in + restore rebuilds the entitlement |
| 7 | Payment failure | Play grace/account-hold state (server-side) reflects; premium per policy |
| 8 | Refund/revocation | after Play revokes, entitlement refreshes to expired/revoked |
| 9 | Offline (active) | premium retained from cache within the 72h staleness window |
| 10 | Offline (stale) | beyond 72h with no verification, premium is not extended |

## 4. Known client-only limitations (do not claim verified)
- No server-side verification (client trusts the Play query).
- Client cannot read absolute expiry / grace / account hold / revocation (need Play
  Developer API + RTDN on a server).
- Play entitlements are per **Google** account, not per QALQON account.
- Trial detection is a heuristic (purchase time within the catalog trial window).

**Status: CODE PASS. Play Console config: `NOT VERIFIED — MANUAL ACTION REQUIRED`.
Real purchase/renewal/refund/restore: `NOT TESTED`.**
