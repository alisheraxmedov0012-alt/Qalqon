# QALQON — Foreground Service Declaration Pack

> Stage 8 remediation. `ProtectionForegroundService` keeps the app-scoped protection
> runtime (and the process-scoped camera session) alive while Qalqon is not on screen.

## 1. Manifest declaration
```xml
<service
    android:name=".core.protection.ProtectionForegroundService"
    android:exported="false"
    android:foregroundServiceType="camera|specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="parental_control_protection_state" />
</service>
```
Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
`FOREGROUND_SERVICE_CAMERA`.

## 2. Why a foreground service
Protection must keep running (foreground-app monitoring + recognition fed by the
process-scoped camera session) while the parent's own app is not visible. A foreground
service with an ongoing, user-visible notification is the honest mechanism for a
long-running user-visible task.

## 3. Service types
- **`specialUse`** (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`): the base type, with the
  subtype property `parental_control_protection_state`. It is the correct type because
  the service is a parental-control state holder that is not any of the standard
  categories (media/dataSync/location/etc.). Claimed from both foreground and boot
  contexts.
- **`camera`** (`FOREGROUND_SERVICE_TYPE_CAMERA`): claimed **only in a legal
  while-in-use moment** when the camera session actually starts (Android 14+ requires
  the while-in-use camera capability to be held then). **Never** claimed from
  `BOOT_COMPLETED` (Android 15 forbids camera/dataSync/media* FGS starts from boot),
  so the boot path uses `specialUse` only and reports recognition as limited until the
  app is next opened.

Why `specialUse` and not a standard type: the service is not playing media, syncing
data, using location, or doing any standard categorized work — it holds parental-control
protection state. `specialUse` with the declared subtype is the accurate mapping.

## 4. Notification
- Dedicated low-importance channel (`protection_service`), localized name/description.
- Ongoing, silent notification (`setOngoing(true)`, `setSilent(true)`,
  `CATEGORY_SERVICE`) with a tap-to-open intent (immutable `PendingIntent`).
- The notification is honest and user-visible; losing the `POST_NOTIFICATIONS`
  permission suppresses display only, not the service.

## 5. Start restrictions / behavior
- Started/stopped guarded (`ContextCompat.startForegroundService` wrapped) — a rejected
  background start degrades gracefully, never crashes.
- `START_NOT_STICKY` (Stage 2 decision; no blind resurrection) — the app is not
  resurrected after a kill until reopened or a reboot restore path triggers.
- Android 14/15/16 FGS rules respected (types declared; camera only while-in-use;
  boot path avoids camera type).

## 6. Play Console action — `MANUAL ACTION REQUIRED`
Complete the Play Console **foreground service declaration**: declare the `camera` and
`specialUse` types and provide justification (parental control; ongoing protection
state; while-in-use camera for on-device face recognition). **Review may request a
demo/video.** UI paths can change — **verify current Play Console UI**.

**Status: CODE PASS. Play Console declaration: NOT VERIFIED — manual action required.**
