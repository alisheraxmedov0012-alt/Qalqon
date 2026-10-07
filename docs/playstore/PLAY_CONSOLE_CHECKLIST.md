# QALQON — Play Console Checklist

> Every item is tagged **READY** (prepared in the repo) · **MISSING** (needs to exist) ·
> **NOT VERIFIED** (requires Play Console access / a device). Nothing here is
> **SUBMITTED** or **APPROVED**.

## App setup
| # | Item | Status |
|---|---|---|
| 1 | App created in Play Console | **NOT VERIFIED** |
| 2 | Package name `uz.faceguard.app` | **READY** (in code) / NOT VERIFIED (Console) |
| 3 | App name | **READY** (`STORE_LISTING.md`) |
| 4 | Default language (uz; en/ru supported) | **READY** |

## Store listing
| # | Item | Status |
|---|---|---|
| 5 | Icon 512×512 | **MISSING** (plan ready) |
| 6 | Feature graphic 1024×500 | **MISSING** |
| 7 | Screenshots | **MISSING** (shot list ready) |
| 8 | Short description | **READY** |
| 9 | Full description | **READY** |
| 10 | Contact email | **MISSING** |
| 11 | Category / tags | **READY** (Tools; privacy/parental control) |

## App content
| # | Item | Status |
|---|---|---|
| 12 | Privacy policy URL | **MISSING** (text ready, not hosted) |
| 13 | Target audience (adults only; not Designed for Families) | **READY** (declaration) |
| 14 | Content rating questionnaire | **NOT VERIFIED** |
| 15 | Ads declaration (none) | **READY** (no ads) |
| 16 | App access (reviewer instructions) | **READY** (`STAGE8_REVIEWER_ACCESS.md` draft) |
| 17 | Data safety form | **READY** (`DATA_SAFETY.md`) / NOT SUBMITTED |
| 18 | Accessibility declaration | **READY** (`PERMISSION_DISCLOSURES.md`) / NOT SUBMITTED |
| 19 | FGS declaration (`camera`, `specialUse`) | **READY** / NOT SUBMITTED |
| 20 | Sensitive permission declarations (Usage Access, Overlay) | **READY** / NOT SUBMITTED |
| 21 | Account deletion URL | **MISSING** (content ready) |

## Monetization
| # | Item | Status |
|---|---|---|
| 22 | Subscription `qalqon_premium` | **READY** (`SUBSCRIPTION_DISCLOSURE.md`) / NOT VERIFIED |
| 23 | Base plan `monthly` | **READY** / NOT VERIFIED |
| 24 | Trial offer `trial-3-day` | **READY** / **TRIAL CONFIGURATION = NOT VERIFIED** |
| 25 | Price | **NOT VERIFIED** (Play is the source of truth; not in repo) |
| 26 | Countries / regions | **NOT VERIFIED** |
| 27 | Subscription disclosure | **READY** |
| 28 | License testers | **NOT VERIFIED** (none available) |

## Release
| # | Item | Status |
|---|---|---|
| 29 | Release AAB | **READY** (`:app:bundleRelease` builds — see §Build) |
| 30 | Release signing (upload key) | **NOT VERIFIED** (no signing secrets here) |
| 31 | Play App Signing | **NOT VERIFIED** |
| 32 | Release notes | **MISSING** |
| 33 | Testing track (internal → closed → open) | **NOT VERIFIED** |
| 34 | Pre-launch report | **NOT VERIFIED** |
| 35 | Target API level 36 | **READY** (code) |
| 36 | Version code/name policy | **READY** (`-PqalqonVersionCode`/`-PqalqonVersionName`) |

## Legal / support
| # | Item | Status |
|---|---|---|
| 37 | Privacy policy content | **READY** |
| 38 | Terms of service | **MISSING** (not yet drafted) |
| 39 | Support contact | **MISSING** |
| 40 | Third-party license attribution | **PARTIAL** (`THIRD_PARTY_LICENSES.md`) — LEGAL REVIEW REQUIRED |

## Bottom line
- **Technically prepared to submit:** YES (AAB builds; declarations/prepared docs ready).
- **Submitted / approved:** NO. **Play Console access is required** to complete every
  `NOT VERIFIED`/`MISSING` item.
