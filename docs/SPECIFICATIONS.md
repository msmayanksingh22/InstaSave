# InstaSave Technical Specifications

---

## 1. Control Center & Download Mode Specifications

The Control Center on `MainActivity` serves as the centralized interface for configuring core operational behaviors.

### 1.1 Download Mode Specification
InstaSave supports two mutually exclusive persistent download modes, plus an explicit manual action:

```kotlin
enum class DownloadMode {
    AUTOMATIC,
    CLIPBOARD
}
```

| Mode | Trigger Mechanism | Overlay Visibility | Background Behavior |
| :--- | :--- | :--- | :--- |
| **`AUTOMATIC`** | Edge-docked floating button over Instagram | Visible (`sizePx x sizePx`) | `FloatingTriggerService` active; automated post detection and copy-link execution. |
| **`CLIPBOARD`** | Focus-based clipboard detection | Hidden (`floating_button_enabled = false`) | Detects Instagram links on clipboard upon returning to InstaSave; automatically initiates download pipeline. |
| **`+ Paste Instagram Link`** | User-tapped manual button | N/A | Opens manual URL entry dialog; executes download without altering persistent mode settings. |

#### State Transition Rules:
1. Selecting **`Automatic`**:
   - Persists `download_mode = "AUTOMATIC"` in `instasave_settings.xml`.
   - Sets `floating_button_enabled = true`.
   - If the service is actively running and prerequisites are satisfied, shows the floating overlay.
   - **Critical Rule**: If InstaSave is **Stopped**, switching to `Automatic` must **NOT** start `FloatingTriggerService`.
2. Selecting **`Clipboard`**:
   - Persists `download_mode = "CLIPBOARD"` in `instasave_settings.xml`.
   - Sets `floating_button_enabled = false`.
   - If `FloatingTriggerService` is active, hides the floating view while maintaining background readiness.
   - **Critical Rule**: If InstaSave is **Stopped**, switching to `Clipboard` must **NOT** start `FloatingTriggerService`.
3. **Stopped State Invariant**:
   - Mode changes while stopped must strictly preserve the stopped state.
   - `FloatingTriggerService.updatePreferences()` and `resetPosition()` check `if (!isRunning) return`.
   - `MainActivity` validates `FloatingTriggerService.isRunning` before dispatching intent updates to the service.

### 1.2 Recents Dismissal Resilience
- Configured with `android:stopWithTask="false"` in `AndroidManifest.xml`.
- The user's explicit intent to run the service is persisted in `InstaSavePreferences` via `isServiceRunningDesired: Boolean`.
- When the user swipes InstaSave away from Recents:
  - `FloatingTriggerService.onTaskRemoved()` checks `isServiceRunningDesired`.
  - If `true`, the foreground service, notification, and overlay window remain alive, anchored, and responsive over other apps.
- Calling explicit `Stop InstaSave` (via UI or notification action) sets `isServiceRunningDesired = false`, dismisses the overlay, removes the foreground notification via `stopForeground(STOP_FOREGROUND_REMOVE)`, and invokes `stopSelf()`.

---

## 2. Carousel Download Behavior Specifications

Multi-image and video carousel posts are governed by `CarouselDownloadBehavior`:

```kotlin
enum class CarouselDownloadBehavior {
    ENTIRE_CAROUSEL,
    CURRENT_ITEM,
    ASK
}
```

### 2.1 Specification: `ENTIRE_CAROUSEL`
- **Action**: All resolved items in the carousel are queued and downloaded in order.
- **Naming**: Sequenced with slide indices (e.g. `InstaSave_20260905_120000_1.jpg`, `InstaSave_20260905_120000_2.jpg`).

### 2.2 Specification: `CURRENT_ITEM` & Safe Refusal
- **Goal**: Download strictly the single item visible on screen when the user initiated the save.
- **Corroboration Preconditions**:
  1. Post container boundary: `width > 600px` and `height > 400px`.
  2. Slide indicator regex: `"(\\d{1,2})\\s*(?:of|/)\\s*(\\d{1,2})"`.
  3. Total slide count match: `indicator.totalSlides == resolvedMedia.size`.
  4. Freshness window: `currentTimeMs - indicator.timestamp <= 30000L` (30s).
- **Safe Refusal Guarantee**:
  - If any precondition fails (missing indicator, mismatched count, or stale observation), InstaSave must abort with **Safe Refusal**.
  - **Zero Files Saved**: Downloads are cancelled (`selectedIndices = emptySet()`, `completedCount = 0`).
  - **User Feedback**: Displays Toast and Banner: `"Safe Refusal: Couldn't identify current slide (0 saved)"`.
  - **Invariant Enforcement**: Maintains `WRONG_MEDIA_DOWNLOADS = 0`.

### 2.3 Specification: `ASK` (Non-Intrusive Prompt Notification Flow)
To prevent disruptive full-screen takeovers and misleading success checkmarks, carousel handling in `ASK` mode differs by trigger source:

#### A. Automatic Overlay Trigger Flow:
1. When the floating button is tapped over a carousel post:
   - Automation engine copies the link and Composite Resolver extracts items.
   - The floating button immediately and smoothly returns to `VisualState.IDLE`.
   - **Zero False Checkmarks**: `transactionId = "ASK_PROMPT_POSTED"` and `success = false` ensure the overlay never displays a misleading green checkmark for uncompleted downloads.
2. A high-priority heads-up prompt notification is posted via `DownloadNotificationManager.showCarouselPromptNotification(...)` on channel `instasave_prompts_channel`:
   - Title: `"Carousel detected"`
   - Text: `"N photos available"`
   - **Action: `SAVE ALL`**: Dispatches broadcast to `CarouselActionReceiver.ACTION_SAVE_ALL`, downloading all slides directly in the background and posting `"✓ N photos saved"`.
   - **Action: `CHOOSE`**: Launches `MediaSelectionActivity` with thumbnail grid and selection checkboxes.
   - **Action: `CURRENT`**: (Only present if high-confidence slide index is detected) downloads strictly the active slide.

#### B. Manual Download & `CHOOSE` Flow (`MediaSelectionActivity`):
- High-resolution thumbnail cards with slide index badges (`#1`, `#2`, ...).
- Dynamic indicator text: `"X of Y items selected"`.
- Action button: `SELECT ALL` toggles to `CLEAR ALL`.
- Bottom CTA button: `DOWNLOAD SELECTED (N)`.
- Upon tapping `DOWNLOAD SELECTED`, passes `selectedIndices: Set<Int>` to `InstagramDownloadCoordinator.executeDirectDownload()`. Only selected slides are written to Scoped Storage.

---

## 3. Concurrency & Reentrancy Specifications (`ClipboardDownloadCoordinator`)

To prevent race conditions, repeated downloads of identical URLs, and unhandled crashes during rapid multitasking, `ClipboardDownloadCoordinator` enforces strict method contracts:

### 3.1 State Monitor Lock
All state transitions are synchronized via an internal lock:
```kotlin
private val stateLock = Any()
```

### 3.2 Method Contracts

#### `evaluateProcessDecision(cleanUrl, clipTimestamp, prefs)`
- **Thread Safety**: Fully thread-safe.
- **Parameters**:
  - `cleanUrl`: Sanitized Instagram URL (canonical without tracking parameters).
  - `clipTimestamp`: Timestamp of the clipboard clip event.
  - `prefs`: Instance of `InstaSavePreferences`.
- **Returns**:
  - `ProcessDecision.ALLOW`: Safe to proceed with resolution and download.
  - `ProcessDecision.REJECT_ALREADY_PROCESSING`: URL is currently active in another thread; ignore duplicate trigger.
  - `ProcessDecision.REJECT_ALREADY_COMPLETED`: URL was previously downloaded and clipboard timestamp has not changed; ignore stale clipboard.
  - `ProcessDecision.DEFER_QUEUED`: Another URL is actively processing; safely queue `cleanUrl` in `pendingClipboardUrl`.

#### `startProcessing(cleanUrl)`
- Sets `currentlyProcessingClipboardUrl = cleanUrl`.
- Logs event to Logcat under tag `InstaDownloaderPOC`.

#### `onProcessingSuccess(cleanUrl, clipTimestamp, prefs)`
- Atomically clears `currentlyProcessingClipboardUrl`.
- Writes `last_processed_clip_url = cleanUrl` and `last_processed_clip_timestamp = clipTimestamp` to `SharedPreferences`.

#### `onProcessingFailed(cleanUrl)`
- Atomically clears `currentlyProcessingClipboardUrl`.
- **Critical Requirement**: Must **NOT** persist `cleanUrl` into `last_processed_clip_url`. Enables immediate retry without requiring clipboard changes.

#### `pollPending()`
- Atomically retrieves and clears `(pendingClipboardUrl, pendingClipboardTimestamp)`.

---

## 4. Unified Storage & Scoped Storage Specifications

### 4.1 Save Location Policy
InstaSave standardizes media output to user-selectable Scoped Storage directories:

```kotlin
const val DEFAULT_RELATIVE_PATH = "Pictures/InstaSave/"
```

Supported standard directory options:
- `Pictures/InstaSave/` (Default)
- `Pictures/Instagram/`
- `DCIM/InstaSave/`
- `Download/InstaSave/`

### 4.2 MediaStore Output Writer (`MediaStoreWriter.kt`)
- **Images**: Written to `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = path`.
- **Videos**: Written to `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = path`.
- **Pending Flag Handling (Android 10+)**:
  - Creates entry with `IS_PENDING = 1`.
  - Streams OkHttp response chunks to `ContentResolver.openOutputStream(uri)`.
  - Upon flush and close, updates entry with `IS_PENDING = 0` to make media visible to system gallery apps.
  - On streaming failure, deletes the incomplete pending URI immediately.

---

## 5. Foreground Service Specification (`specialUse`)

To guarantee persistent overlay availability on Android 14+ and Android 16 while adhering strictly to Google Play and AOSP foreground service policies, InstaSave implements the `specialUse` foreground service type.

### 5.1 Manifest Permissions & Declarations
In `app/src/main/AndroidManifest.xml`:
```xml
<!-- Foreground Service Permissions -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- Floating Overlay Permission -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- Service Declaration -->
<service
    android:name=".service.FloatingTriggerService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Persistent floating overlay control for user-initiated Instagram media saving" />
</service>
```

### 5.2 Mandatory Subtype Property Rule
- On Android 14 (API 34) and higher, declaring `android:foregroundServiceType="specialUse"` without providing the matching `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" ... />` causes immediate runtime rejection or crashes.
- The subtype value must be a clear description of why the foreground service is indispensable:
  `"Persistent floating overlay control for user-initiated Instagram media saving"`

### 5.3 Startup Invocation
In `FloatingTriggerService.kt`:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    )
} else {
    startForeground(NOTIFICATION_ID, notification)
}
```

---

## 6. Floating Button Stability & Geometry Specification

### 6.1 Fixed Geometry (0px Anchor Drift)
- The overlay root layout `WindowManager.LayoutParams` strictly enforces fixed dimensions:
  ```kotlin
  params.width = sizePx
  params.height = sizePx
  ```
- **`WRAP_CONTENT` is strictly prohibited**. Expanding text pills (e.g. `"Resolving..."` or `"✓ Saved"`) are eliminated.
- All state transitions occur inside the stationary circular container:

| State | Visual Icon | Animation / Duration | Anchor Shift |
| :--- | :--- | :--- | :---: |
| **IDLE** | `ic_download_arrow` (White arrow, Pink BG) | Static icon | **0 px** |
| **WORKING** | `ic_download_arrow` + Progress Ring | Indeterminate circular rotation | **0 px** |
| **SUCCESS** | `ic_check` (White checkmark, Green tint) | Alpha fade-in, auto-revert after 1.5s | **0 px** |
| **DUPLICATE** | `ic_check` (Double check badge) | Alpha fade-in, auto-revert after 1.5s | **0 px** |
| **ERROR** | `ic_close` (White X icon, Red tint) | Alpha fade-in, auto-revert after 1.5s | **0 px** |

### 6.2 Normalized Coordinate Persistence (`FloatingPositionManager`)
- Coordinates are stored as normalized floats to withstand display rotation or resolution changes:
  $$\text{norm\_x} = \frac{x}{\text{max\_x}}, \quad \text{norm\_y} = \frac{y}{\text{max\_y}}$$
- **Drag Write Throttle**: During dragging (`ACTION_MOVE`), coordinates are updated strictly in memory via `windowManager.updateViewLayout()`. Disk commits to `SharedPreferences` occur strictly on `ACTION_UP` (drag release).
- **Default Edge Position**: Normalized coordinates `(0.92, 0.40)` place the button comfortably on the right edge within thumb reach.

---

## 7. Operational Lifecycle & "Stop InstaSave" Specification

### 7.1 Decoupled Lifecycle
InstaSave enforces a strict separation between the OS-level `AccessibilityService` and the application's overlay service:
- **"Running"**: `FloatingTriggerService` is active, the overlay is attached to `WindowManager`, and the service is listening for user interaction.
- **"Stopped"**: `FloatingTriggerService` is terminated, the foreground notification is removed, and the overlay view is dismissed.
- **System Accessibility**: The OS-level accessibility service remains enabled and ready for future sessions.

### 7.2 Stop Action Sequence
When "Stop InstaSave" is triggered (via Dashboard or Notification Drawer):
1. Calls `stopForeground(STOP_FOREGROUND_REMOVE)` to immediately dismiss the notification.
2. Removes the floating overlay view from `WindowManager`.
3. Sets `isRunning = false` and notifies active state observers.
4. Calls `stopSelf()` to cleanly terminate `FloatingTriggerService`.
5. Cancels pending timers or background coroutines.

---

## 8. Notification Permissions & Dual Channel Specification

### 8.1 Channels
1. `instasave_service_channel`:
   - Name: `"InstaSave Service"`
   - Importance: `NotificationManager.IMPORTANCE_LOW` (No sound, no vibration)
   - Ongoing: `true`
   - Purpose: Persistent foreground drawer notification with explicit "Stop" action.
2. `instasave_downloads_channel`:
   - Name: `"InstaSave Downloads"`
   - Importance: `NotificationManager.IMPORTANCE_DEFAULT`
   - Purpose: Download completion alerts.
3. `instasave_prompts_channel`:
   - Name: `"Carousel Prompts"`
   - Importance: `NotificationManager.IMPORTANCE_HIGH` (Heads-up banner, vibration)
   - Category: `NotificationCompat.CATEGORY_EVENT`
   - Purpose: Non-intrusive interactive prompt notification for carousel downloads in `ASK` mode (`SAVE ALL`, `CHOOSE`, `CURRENT`).

### 8.2 Behavior Under Denied `POST_NOTIFICATIONS`
- On Android 13+ (API 33–36), notification permission is optional.
- If `POST_NOTIFICATIONS` is denied:
  - The OS suppresses drawer notifications automatically.
  - The foreground service continues running normally without exceptions.
  - The floating overlay button appears and operates with full fidelity.
  - The app suppresses completion notifications silently without crashing.

---

## 9. Privacy & Local-First Guarantees

The About card in `SettingsActivity` guarantees:
> *"Downloads are resolved and saved directly on your device. No account or app-owned server is required."*

Technical verification:
- Zero external analytics dependencies in `build.gradle.kts`.
- Zero tracking or telemetry endpoints.
- No network requests other than direct media downloads from Instagram CDN servers.

---

## 10. Headless Web Bridge & Pre-Warmed Engine Specifications

### 10.1 WebKit Environment Parameters
- **Engine**: Headless `android.webkit.WebView` pre-warmed via `HeadlessWebBridgeResolver.preWarm(context)`.
- **Target URL**: `https://fastdl.app/en`
- **User-Agent String**: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36`
- **Resource Constraints**:
  - `loadsImagesAutomatically = false` (Prohibits bitmap and video assets during extraction).
  - `domStorageEnabled = true` (Retains token/session state required by converter scripts).
- **Execution Target**:
  - Resolution latency target: **< 1,200 ms** under active pre-warming.
  - Fail-safe timeout: **14,000 ms**.

### 10.2 Vue 3 State & Carousel Extraction Criteria
- **Deep Proxy Inspection Target**: Traverses `__vueParentComponent` ancestors inspecting `props.data`, `proxy.data`, and `setupState.data`.
- **Carousel Load-More Criteria**: Automates clicks on `.load-more-trigger`, `button.load-more`, and sets `visibleDataCount = 999` to ensure all 10–15+ items extract without slicing.

---

## 11. Universal Persistent Thumbnail Specifications

### 11.1 File Storage Schema
- **Path Format**: `context.filesDir/thumbnails/${shortcode}.jpg`
- **File Format**: JPEG, downsampled to 240x240 px, quality 85.
- **Disk Footprint**: ~15 KB – 30 KB per thumbnail.
- **Database Column**: `downloads.thumbnail_path` stores the absolute internal file path.

### 11.2 Lifecycle & Cleanup Guarantees
- Stored exclusively in internal private app storage (`context.filesDir`).
- **Immunity**: Immune to external gallery deletions, Google Photos cleanups, and file manager purges.
- **Backfill Concurrency**: Bounded at max 3 concurrent network requests (`Semaphore(3)`).

---

## 12. In-App Software Update (OTA) Specifications

### 12.1 Update Discovery Contract
- **Endpoint**: `https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/release-manifest.json`
- **Method**: HTTP `GET` with header `User-Agent: InstaSave-Updater/1.0`.
- **Evaluation**: `remoteVersionCode > localVersionCode`.

### 12.2 Installation Intent Contract
- **Storage Target**: Internal cache directory `context.cacheDir/updates/InstaSave_v${remoteVersionName}.apk`.
- **FileProvider Authority**: `com.instadown.assist.fileprovider`.
- **Intent**: `Intent.ACTION_VIEW` with MIME `application/vnd.android.package-archive` and `FLAG_GRANT_READ_URI_PERMISSION`.

---

## 13. Production Signing & Security Hardening Specifications

### 13.1 Keystore Specification
- **Keystore File**: `app/instasave-release.jks`
- **Key Algorithm**: RSA 2048-bit
- **Alias**: `instasave`
- **Validity**: 10,000 days
- **Certificate DN**: `CN=InstaSave Software, OU=Mobile Security, O=InstaSave Global, L=San Francisco, ST=California, C=US`

### 13.2 Signature Schemes
- **v1 (JAR Signature)**: Enabled
- **v2 (APK Signature Scheme v2)**: Enabled
- **v3 (APK Signature Scheme v3)**: Enabled
- **v4**: Disabled (not required for sideloading)

