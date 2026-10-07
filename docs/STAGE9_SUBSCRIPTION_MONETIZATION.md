# QALQON — Stage 9: Subscription & Monetization

> Stage 9 deliverable (roadmap 9/12). Goal: production-quality Google Play monthly
> subscription — purchase → acknowledgement → entitlement → restore → renewal →
> cancellation → expiration → reinstall/account-state synchronisation.
>
> **Honesty contract.** No physical device, no Play Console access, no license tester and
> no ADB in this environment. Real Google Play purchase/renewal/refund/revocation are
> **NOT VERIFIED**. The entitlement logic is verified against a fake billing gateway
> (**MOCK VERIFIED**). Client-only verification is **not** server verification.

---

## 1. Audit (before this change)

The billing stack already existed and was strong: a `BillingClient` behind a
`BillingGateway` seam, a pure `PurchaseProcessor`, an account-scoped
`EntitlementStore` cache, a `SubscriptionManager` (the single entitle­ment brain),
`PremiumAccessEvaluator` (one central gate), a 72-hour bounded offline staleness policy,
acknowledgement with in-session idempotency, and ~43 billing tests.

**One real defect was found:**

| Defect | Detail |
|---|---|
| **Wrong product can grant Premium** | `queryPurchasesAsync(SUBS)` returns every subscription purchase for the app, but neither `PurchaseProcessor.toEntitlement` nor `SubscriptionManager` checked the purchase's `productId`. Any other subscription product in the app's catalog (e.g. a legacy/deprecated plan) would be entitled — and would even be acknowledged. This is the Stage 9 failure criterion "**wrong product grants Premium**". |

Nothing else was found wrong; per the brief, the correct existing implementation was
**not rewritten**.

---

## 2. What changed

1. `ProductCatalog.knownProductIds` + `ProductCatalog.isQalqonProduct(productId)` — the
   declared set of QALQON subscription products (currently `qalqon_premium`).
2. `PurchaseProcessor.entitleablePurchases(purchases)` — the single pure choke point that
   keeps only QALQON's products; used by both the entitlement mapping and the
   acknowledgement path so they can never disagree.
3. `PurchaseProcessor.toEntitlement` now derives the entitlement only from QALQON
   products (a foreign purchase neither grants Premium nor marks the account
   ever-entitled).
4. `SubscriptionManager.applyVerified` acknowledges only QALQON products.

This also covers the purchase-update listener path (`onPurchaseUpdate` → `applyVerified`).

---

## 3. Purchase Flow

```
Premium screen → SubscriptionViewModel.subscribe()
  → SubscriptionManager.loadProduct()          (dynamic ProductDetails from Play)
  → SubscriptionManager.launchPurchase(activity, details)   (BillingFlowParams + offerToken)
  → GooglePlayBillingGateway.launchPurchase → BillingClient.launchBillingFlow
  → Play sheet → PurchasesUpdatedListener → gateway.purchaseUpdates
  → SubscriptionManager.onPurchaseUpdate → applyVerified:
        1. filter to QALQON products                 (Stage 9)
        2. PurchaseProcessor.toEntitlement           (state)
        3. acknowledge purchased-but-unacked, QALQON only, deduped per token
        4. persist to the account-scoped cache
        5. publish entitlement + premiumActive
  → UI shows the entitlement state (never "activated" before Play confirms)
```

---

## 4. Premium Entitlement

`Google Play subscription state` → `BillingGateway` → `PurchaseProcessor` →
`PremiumEntitlement(state, …)` → `PremiumAccessEvaluator` → features/UI.

- The local cache (`EntitlementStore`) is a **cache of a verified state**, never the
  authority. `refresh()` re-queries Play.
- State (`EntitlementState`) is stored, not a bare boolean flag.
- Offline staleness is bounded to 72 h (`OfflineEntitlementPolicy`).
- `EntitlementState` (billing/subscription state) is deliberately distinct from
  "premium access" (`grantsPremium` / `isPremiumActive`).

---

## 5. Restore / Reinstall

- **Restore** is `refresh()`: a real `queryPurchasesAsync(SUBS)` + entitlement
  recomputation, not a re-read of a local flag.
- **Reinstall / app-data-clear**: local cache is empty, but the first `refresh()` queries
  Play and restores an existing subscription. (Verified against the fake gateway;
  real reinstall **NOT VERIFIED**.)
- **Account switch / sign-out**: the cache is account-scoped and never leaks; sign-out
  clears the in-memory entitlement. Note: the Play purchase itself is per **Google**
  account, so two QALQON accounts sharing one Google account both see it (documented
  limitation).

---

## 6. Subscription Lifecycle

| State | Support | Verified | Notes |
|---|---|---|---|
| active | SUPPORTED | MOCK VERIFIED | `ACTIVE` |
| trial | SUPPORTED | MOCK VERIFIED | derived from catalog trial window (client heuristic) |
| pending | SUPPORTED | MOCK VERIFIED | `PENDING`, never grants Premium |
| cancelled (still in period) | SUPPORTED | MOCK VERIFIED | `CANCELED_ACTIVE`, still grants access |
| renewal | SUPPORTED | NOT VERIFIED | client sees the new purchase on the next query |
| grace period | PARTIAL | NOT VERIFIED | client library does not expose it; modelled, needs server/RTDN |
| account hold | PARTIAL | NOT VERIFIED | client library does not expose it; modelled |
| expired | SUPPORTED | MOCK VERIFIED | `EXPIRED`, loses Premium |
| revoked/refunded | PARTIAL | NOT VERIFIED | not separable client-side; modelled |
| billing unavailable | SUPPORTED | MOCK VERIFIED | keeps last-known state, never false-expires |

---

## 7. Offline / Billing Failure

- A billing/network failure is **not** an expiration: the last verified state is kept
  (bounded by the 72 h staleness window).
- **Cancellation ≠ immediate expiration**, **pending ≠ purchased**, **billing failure ≠
  expiry**, **local cache ≠ authority** — all held by the architecture and tests.
- Stage 1–8 (face recognition, protection, camera, background) remain fully offline; only
  subscription synchronisation needs Play connectivity (over IPC — no `INTERNET`).

---

## 8. Security / Trust Boundary

- The purchase token is used only for acknowledge/re-query; it is **never logged, never
  persisted, never in UI state** (asserted by `Stage9RegressionGuardTest`).
- The entitlement cache is account-scoped; the local value can only ever be a *cache of a
  Play-verified state*, and cannot grant Premium indefinitely.
- No debug/fake purchase path exists in production; no server/backend was added; the
  merged manifest still removes `INTERNET`/`ACCESS_NETWORK_STATE` (billing uses
  `com.android.vending.BILLING` over IPC).

---

## 9. Play Console configuration

- Product `qalqon_premium`, base plan `monthly`, trial offer `trial-3-day` (3 days) are
  declared once in `ProductCatalog` and referenced from code only through it.
- **PLAY CONSOLE LIVE CONFIGURATION = NOT VERIFIED** (no Play Console access here).

---

## 10. Tests

- JVM: **2241 / 0 failures** (was 2221 → **+20**): `Stage9ProductIdentityTest` (12),
  `Stage9RegressionGuardTest` (8).
- Existing billing tests remain green.

## 11. Real Google Play test

- **REAL PLAY PURCHASE TEST = NO**
- **LICENSE TESTER = NO**
- **LIVE PLAY CONSOLE CONFIGURATION VERIFIED = NO**

Everything billing-related here is **MOCK VERIFIED** against a fake gateway or
**NOT VERIFIED** on real Play.

---

## 12. Known limitations

- Client-only billing: no server-side purchase verification (**NO SERVER-SIDE PURCHASE
  VALIDATION**), so replay/tamper resistance is weaker than a server-verified model.
- Grace period, account hold and revocation are not exposed by the client library.
- Play purchases are per Google account, not per QALQON account.
- Real purchase/renewal/restore/reinstall flows are not exercised on device.
