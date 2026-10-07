# QALQON — Stage 7: Subscription & 3-Day Trial

> Commercial Master Plan — Stage 7 deliverable.
> Baseline: `feature/phase4-screen-time-complete` @
> `4824a1ef3175b81e71366886f10664661e58fe71` (Stage 6, PASS WITH LIMITATIONS).
>
> **Honesty contract.** Real Google Play purchase, renewal, refund, revocation, account
> hold, grace period and cross-device restore are **NOT TESTED** here (no device, no
> Play Console, no license tester). The entitlement/state logic is **MOCK VERIFIED**
> against a fake billing gateway. Client-only verification is **not** server
> verification.

---

## 1. Product model

| Item | Value |
|---|---|
| Subscription product ID | `qalqon_premium` |
| Base plan ID | `monthly` |
| Trial offer ID | `trial-3-day` |
| Trial length | **3 days** (Google Play offer, not a local timer) |
| After trial | monthly auto-renewing subscription |
| Pricing source | **Google Play `ProductDetails`** (never hardcoded) |

Free vs premium boundary (product decision, centralized in `PremiumFeature`):

| Feature | Tier |
|---|---|
| Core protection (app blocking, parent/child face, PIN, emergency unlock) | **Free** |
| Multiple child profiles, schedules, screen-time limits, eye safety, extra-time requests, activity history | **Premium** |

Core protection stays free on purpose: a paying user must never lose protection because
of a billing state. Premium gating is one central evaluator (`PremiumAccessEvaluator`),
never `if (subscribed)` scattered across screens.

---

## 2. Architecture decision

**RECOMMENDED: Client-only Google Play Billing (`BillingClient`) + account-scoped local
entitlement cache.** (Option C: `queryPurchasesAsync` / `queryProductDetailsAsync`
based, with a bounded offline cache.)

**WHY**
- Billing talks to the Play Store over **IPC**, so no `INTERNET` permission is needed —
  the offline-first, no-INTERNET, no-biometric-egress architecture is preserved
  (verified: the merged manifest still removes `INTERNET`/`ACCESS_NETWORK_STATE`, adds
  only `com.android.vending.BILLING`, and injects **no** location permission).
- No backend exists; building one is a **separate scope** (see §14). A client-only
  implementation is the smallest correct change.
- The entitlement layer sits behind `BillingGateway` + `EntitlementStore` seams, so a
  future server/RDTN verification layer can replace the gateway without touching the
  domain or UI.

**TRADE-OFF**
- No server-side purchase verification (client trusts the Play `queryPurchasesAsync`
  result). Replay/tamper resistance is weaker than a server-verified model.
- Client-only cannot read the absolute **expiry date, grace period, account hold or
  revocation** (the library does not expose them); those need the Play Developer API /
  RTDN on a server. The domain model already has the states so a server can map into it
  later.

**KNOWN LIMITATION**
- Google Play purchases are per **Google** account, not per QALQON account. Two QALQON
  accounts on one device sharing one Google account will both see the purchase. The
  local cache is account-scoped (no leak), but the Play entitlement itself is
  device/Google-account wide. Mitigation (deferred): `obfuscatedAccountId` +
  server verification.

---

## 3. Google Play Billing

| | |
|---|---|
| Library | `com.android.billingclient:billing:9.1.0` |
| Why 9.1.0 | v7's new-app/update deadline (2026-08-31) has passed; v9 is current and supported for new apps until **2028-08-31**. v9 requires target SDK 35 + AndroidX Core ≥ 1.15, both matched. |
| API notes | `onProductDetailsResponse` returns a `QueryProductDetailsResult`; `Purchase` no longer exposes per-purchase offer details, so trial is derived from the catalog trial window (documented heuristic). |

Manifest impact (evidence: merged debug manifest): no `INTERNET`, no location
permission; adds `com.android.vending.BILLING`, `ProxyBillingActivity(V2)`, and the Play
billing binding `<queries>`.

---

## 4. Entitlement model

`PremiumEntitlement(state, productId, basePlanId, offerId, isTrial, autoRenewing,
acknowledged, expiryTimeMillis, lastVerifiedAtMillis, source)`. The **purchase token is
never** part of this model.

States (`EntitlementState`): `NONE`, `PENDING`, `TRIAL`, `ACTIVE`, `CANCELED_ACTIVE`,
`GRACE_PERIOD`, `ACCOUNT_HOLD`, `EXPIRED`, `REVOKED`, `BILLING_UNAVAILABLE`, `UNKNOWN`.
`grantsPremium` is true only for `TRIAL`, `ACTIVE`, `CANCELED_ACTIVE`, `GRACE_PERIOD`.

Client-derivable today: `NONE`, `PENDING`, `TRIAL`, `ACTIVE`, `CANCELED_ACTIVE`,
`EXPIRED`, `BILLING_UNAVAILABLE`, `UNKNOWN`. Server-only (model present, not derivable
client-side): `GRACE_PERIOD`, `ACCOUNT_HOLD`, `REVOKED`.

---

## 5. Purchase flow

`SubscriptionScreen → SubscriptionViewModel → SubscriptionManager → BillingGateway`.

1. Screen loads → `loadProduct()` (dynamic price/trial) + `refresh()`.
2. User taps "Start 3-day free trial" → `launchPurchase(activity, details)`.
3. Play sheet → purchase → `PurchasesUpdatedListener` → `purchaseUpdates` flow.
4. `SubscriptionManager.onPurchaseUpdate` → `PurchaseProcessor` → entitlement.
5. Unacknowledged purchased items are acknowledged (idempotent per token).
6. Cache saved, entitlement published, premium gate flips.
7. UI refreshes from the single `entitlement` StateFlow.

Premium is **never** granted on an invalid/incomplete purchase: `PENDING` and
`UNSPECIFIED` do not grant access.

## 6. Acknowledgement

New subscriptions must be acknowledged within 3 days or Play refunds/revokes them. The
manager acknowledges every purchased-but-unacknowledged item; a failure is **logged and
retried** on the next refresh/reconnect (never silently ignored), and the entitlement is
not treated as final until acknowledged. A token acknowledged in a session is not
re-acknowledged (idempotent).

---

## 7. Verification

Client-only. The app trusts the Play `queryPurchasesAsync(SUBS)` result and does not
verify the purchase token against the Play Developer API (no backend). **This is a
limitation, not server verification.** Planned server scope in §14.

## 8. Renewal

On account start, on the subscription screen open, and on every explicit `refresh()`,
the manager re-queries Play. Renewal is **not** inferred from a local countdown: the
authoritative signal is Play's active-purchase result. A successful query that no longer
contains the purchase → `EXPIRED`.

## 9. Cancellation

Google Play cancellation takes effect at the end of the paid period. A canceled-but-still
-active subscription is `CANCELED_ACTIVE` and **keeps premium** until it disappears from
Play. The screen shows the state and offers "Manage subscription", which opens Play's
subscription management page (the policy-required easy cancellation path).

## 10. Grace period

`GRACE_PERIOD` grants premium and maps to a clear message ("Payment issue; premium still
active"). It is a **server-side** Play state; the client library does not surface it, so
it is not derivable client-only (documented limitation).

## 11. Account hold

`ACCOUNT_HOLD` does **not** grant premium. Server-side Play state; not client-derivable.
The screen has a distinct "Payment issue; premium paused" label.

## 12. Refund / revocation

Refund/revocation → the purchase disappears from Play → the next authoritative query
yields `EXPIRED` (or `REVOKED` when a server/RTDN layer supplies it). Revoked is never
kept as active. Purchase tokens are never logged.

## 13. Restore

The "Restore purchase" button calls `manager.restore()`, which performs a **real Play
query** (not a UI toast) and re-applies the entitlement. This is the reinstall path.

## 14. Reinstall / device change

Entitlement is never trusted from a local flag alone: on a fresh install the cache is
empty, and the first query/restore rebuilds it from Play. Account isolation is enforced;
biometric data and entitlement are separate concepts and never cross devices (biometric
templates stay device-bound/encrypted; the subscription is per Google account).

---

## 15. Offline behavior

`OFFLINE_ENTITLEMENT_POLICY` (centralized in `OfflineEntitlementPolicy`):

- A cached entitling entitlement is honored while `now < expiryTimeMillis` (when known),
  otherwise for up to **72 hours** since the last successful verification.
- A failed/unavailable query **never downgrades** a paying user (no ACTIVE→EXPIRED
  regression); it keeps the last verified state.
- The cache is **not** the source of truth; it cannot extend entitlement indefinitely
  (72 h bound) — verified by `SubscriptionManagerTest`.
- The cache is account-scoped.

## 16. Payment failure

`BillingError` covers `USER_CANCELED`, `BILLING_UNAVAILABLE`, `ITEM_UNAVAILABLE`,
`ITEM_ALREADY_OWNED`, `NETWORK_ERROR`, `PENDING`, `UNEXPECTED`, each with a localized,
actionable message. The raw Play debug message is never shown to the user and never
logged with a purchase token.

## 17. Premium access boundary

One central gate: `PremiumAccessEvaluator.isAvailable(feature, entitlement, now)`.
`EntitlementRepository.premiumActive` is the observable boolean screens consume. No
screen reads a subscription flag directly.

---

## 18. UX / localization

The subscription screen shows, on the screen itself: "3 days free", the Play price
"Then <price> per month", "Monthly auto-renewing subscription", the auto-renew and
cancel notices, the benefit list, the free-tier note, and Manage/Restore/Refresh
actions. Prices come from Play. Uzbek / Russian / English are complete and key-parity
is enforced by tests.

## 19. Security / privacy

- **INTERNET:** still absent (merged manifest verified); billing uses Play IPC.
- **Purchase token:** never logged, never persisted in the entitlement model.
- **Biometric:** untouched; no interaction with the face pipeline.
- No Google-account personal data stored; only non-secret entitlement fields are cached.

---

## 20. Play Console setup requirements

1. Create the subscription product `qalqon_premium` with base plan `monthly`.
2. Add a **3-day free trial** offer (`trial-3-day`) on the base plan.
3. Complete the Payments profile and the subscription's benefit/terms metadata.
4. Add license testers for QA.
5. Publish to at least Internal testing (billing cannot be tested from a local debug APK).
6. Keep the product/base-plan/offer IDs matching `ProductCatalog`.

## 21. Known limitations

- **No server-side verification** (client-only trust).
- Client-only cannot derive **expiry date, grace period, account hold, revocation**.
- Play purchases are per **Google** account, not per QALQON account.
- Trial detection is a heuristic (purchase time within the catalog trial window).
- **Real Google Play purchase/renewal/refund/restore = NOT TESTED** (no device/Play).
- 72-hour offline staleness window is a policy trade-off (revenue vs. paying-user UX).

## 22. Future backend sub-scope (NOT this stage)

A minimal verification service (Play Developer API `purchases.subscriptionsv2.get` +
Real-time Developer Notifications) would supply authoritative expiry/grace/hold/revoke
and eliminate the client-only limitation. It is **out of Stage 7 scope** and is not
implemented here.
