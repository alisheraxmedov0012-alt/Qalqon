# QALQON — Usage Access Declaration Pack

> Stage 8 remediation. `PACKAGE_USAGE_STATS` is a special access used for foreground-app
> detection.

## 1. Why it is required
To know **which app is currently in the foreground**, so QALQON can detect when a
**protected app** is opened while protection is active. Without it, QALQON cannot
observe foreground app changes from Usage Stats (the accessibility service is a second,
independent source).

## 2. Exact use
- Read the most recent `MOVE_TO_FOREGROUND` event / last-used app via
  `UsageStatsManager` (polling).
- Only the **foreground package name** is used, transiently, to drive protection.
- Historical aggregate usage is read **only** for the parent's screen-time feature
  (local accounting).

## 3. No unnecessary app inventory
QALQON does **not** build or upload an installed-app inventory. The protected-app
picker enumerates **launcher apps locally** via a targeted `<queries>` (MAIN/LAUNCHER)
declaration — not the broad `QUERY_ALL_PACKAGES`.

## 4. No external transmission
There is no INTERNET permission; nothing read here leaves the device.

## 5. User permission flow
- Not a runtime permission: the user grants it in **Settings → Special access → Usage
  access**.
- QALQON deep-links there (`ACTION_USAGE_ACCESS_SETTINGS`) and shows the capability in
  the Protection requirements card (`protection_req_usage`) with an Open/Ready state.
- Revocation is detected and surfaced as a degraded capability (protection continues,
  reported honestly).

## 6. Privacy disclosure
Covered by the in-app Privacy screen and the Privacy Policy (§5 permissions). Data is
on-device only.

## 7. Play Console action
There is no dedicated Play Console "usage access" declaration form; the requirement is
reviewed via the **Data safety** form and the store listing. Ensure:
- Data safety reflects **no data collected** (nothing transmitted).
- The listing explains the usage-access purpose (foreground app detection for blocking).

**Status: CODE PASS. Play Console: nothing to submit beyond Data safety (see the Data
Safety Answer Sheet).**
