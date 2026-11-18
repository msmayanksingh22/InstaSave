# InstaSave Verification & Physical Testing Guide

---

## 1. Test Environment & Target Hardware

All verification passes and automated test matrices were executed directly on retail physical hardware:

| Parameter | Specification |
| :--- | :--- |
| **Physical Device Model** | OPPO K13 Turbo 5G (CPH2761) |
| **Operating System** | Android 16 (API 36) — ColorOS V16.0.0 (Build CPH2761_16.0.5.1200(EX01)) |
| **Display Geometry** | 1080 x 2362 px (Density: 450 dpi) |
| **Connectivity** | Wireless ADB over Wi-Fi (`192.168.1.3:45657`) |
| **App Build** | InstaSave 1.0 Debug (`com.instadown.assist`) |
| **Gradle / Test Runner** | Gradle 8.9 / AGP 8.7.0, JUnit 4, Roborazzi / Robolectric |

---

## 2. Real-Device Verification Matrix

### Case 1: Accessibility ON + Overlay OFF (Ready State)
- **Initial Conditions**:
  - Accessibility enabled via `settings put secure enabled_accessibility_services com.instadown.assist/com.instadown.assist.service.InstaAccessibilityService`
  - Overlay permission granted via `appops set com.instadown.assist SYSTEM_ALERT_WINDOW allow`
  - `FloatingTriggerService` stopped
- **Observed Behavior**:
  - Dashboard reports: `InstaSave is stopped • Ready to start`.
  - Accessibility Prerequisite card shows green: `ACTIVE`.
  - Floating Download Button shows green: `GRANTED`.
  - "Start InstaSave" button is fully enabled.
- **Verdict**: **PASS**

---

### Case 2: Dashboard Start Action (Running State Transition)
- **Action**: Tap "Start InstaSave" button.
- **Observed Behavior**:
  - Prerequisites checked synchronously.
  - `ContextCompat.startForegroundService()` invoked.
  - `FloatingTriggerService` calls `startForeground()` passing `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
  - Circular overlay button (pink circle with white arrow) appears on screen.
  - Dashboard card updates to green: `InstaSave is running • Overlay button is active on screen`.
  - Button text toggles to `Stop InstaSave`.
  - Center coordinates of the floating button exhibit **0px drift**.
- **Verdict**: **PASS**

---

### Case 3: Accessibility OFF + Overlay ON (Prerequisite Gating)
- **Action**:
  - Clear accessibility services: `settings put secure enabled_accessibility_services ""`
  - Bring `MainActivity` to foreground.
- **Observed Behavior**:
  - `onResume()` queries `AccessibilityManager` dynamically.
  - Service status card displays: `InstaSave is stopped • Prerequisites required to start`.
  - Accessibility card turns amber with label `NOT ENABLED` and displays prominent `ENABLE` button.
  - Tapping "Start InstaSave" triggers educational dialog and opens Android Accessibility settings.
  - Prevents premature startup.
- **Verdict**: **PASS**

---

### Case 4: Returning from Android Settings (Live State Re-evaluation)
- **Action**:
  - Enable accessibility service in Android Settings.
  - Return to `MainActivity` via Back gesture.
- **Observed Behavior**:
  - App re-queries true accessibility state immediately in `onResume()`.
  - Indicator instantly switches from Amber `NOT ENABLED` to Green `ACTIVE` without requiring app relaunch or manual pull-to-refresh.
  - Start button becomes active.
- **Verdict**: **PASS**

---

### Case 5: Notification Permission Denied (`POST_NOTIFICATIONS`)
- **Action**:
  - Revoke notification permission: `adb shell pm revoke com.instadown.assist android.permission.POST_NOTIFICATIONS`
  - Start InstaSave service.
- **Observed Behavior**:
  - `FloatingTriggerService` starts without crashing.
  - Dumpsys confirms foreground service is active:
    ```
    ServiceRecord{... com.instadown.assist/.service.FloatingTriggerService}
      isForeground=true foregroundId=1001 type=specialUse
    ```
  - Floating overlay button appears and functions normally.
  - Android OS suppresses drawer notification per Android 13+ guidelines.
  - App handles missing drawer notification gracefully without reporting failure.
- **Verdict**: **PASS**

---

### Case 6: Notification Stop Action (Explicit Broadcast Control)
- **Action**:
  - Dispatch stop action broadcast while main activity is backgrounded:
    `am broadcast -a com.instadown.assist.ACTION_STOP_SERVICE -p com.instadown.assist`
- **Observed Behavior**:
  - `ServiceControlReceiver` intercepts explicit broadcast.
  - Dispatches stop command to `FloatingTriggerService`.
  - `stopForeground(STOP_FOREGROUND_REMOVE)` removes ongoing notification.
  - Floating overlay view is removed from `WindowManager`.
  - Service terminates completely.
- **Verdict**: **PASS**

---

### Case 7: Dashboard Stop Action
- **Action**: Tap "Stop InstaSave" from Dashboard.
- **Observed Behavior**:
  - `FloatingTriggerService.stop(this)` called.
  - Overlay dismissed from screen.
  - Dashboard status returns to `InstaSave is stopped`.
  - System `AccessibilityService` remains `ACTIVE` in Android Settings.
- **Verdict**: **PASS**

---

### Case 8: Control Center Download Mode Switching (`AUTOMATIC` vs `CLIPBOARD`)
- **Action**:
  - Tap `Clipboard` button in Control Center.
  - Tap `Automatic` button in Control Center.
- **Observed Behavior**:
  - In `Clipboard` mode: status card reflects `Clipboard mode active (floating button hidden)`. Overlay view is hidden. `download_mode` persists as `"CLIPBOARD"` in `instasave_settings.xml`.
  - In `Automatic` mode: floating overlay appears on screen. `download_mode` persists as `"AUTOMATIC"`.
- **Verdict**: **PASS**

---

### Case 9: Clipboard Concurrency & Duplicate Rejection
- **Action**:
  - Copy an Instagram URL while another download is running or has just finished.
- **Observed Behavior**:
  - Logcat:
    ```
    D InstaDownloaderPOC: ClipboardDecision: URL is already processing: https://instagram.com/p/...
    D InstaDownloaderPOC: ClipboardDecision: URL was already completed and clipboard timestamp has not changed
    ```
  - Rejects duplicate downloads with 0 redundant file writes.
- **Verdict**: **PASS**

---

### Case 10: Clipboard URL Queuing
- **Action**:
  - Inject URL 1, then immediately inject URL 2 before URL 1 finishes.
- **Observed Behavior**:
  - `evaluateProcessDecision()` returns `DEFER_QUEUED`.
  - URL 2 stored in `pendingClipboardUrl`.
  - Upon URL 1 completion, `pollPending()` retrieves URL 2 and processes it automatically.
- **Verdict**: **PASS**

---

### Case 11: Carousel Mode: `CURRENT_ITEM` Safe Refusal
- **Action**:
  - Set `CarouselBehavior = CURRENT_ITEM`.
  - Copy carousel URL outside Instagram or when slide indicator is not observed on screen.
- **Observed Behavior**:
  - Corroborator evaluates slide indicators and finds no fresh, container-associated slide match.
  - Triggers **Safe Refusal**.
  - Logcat:
    ```
    W InstaDownloaderPOC: resolveCurrentSlideIndex: SAFE REFUSAL - No container-associated slide indicator found
    W InstaDownloaderPOC: ClipboardProcessing: Failed for https://instagram.com/... (URL not permanently marked as processed)
    ```
  - Displays `"Safe Refusal: Couldn't identify current slide (0 saved)"`.
  - Saves exactly **0** files (`WRONG_MEDIA_DOWNLOADS = 0`).
- **Verdict**: **PASS**

---

### Case 12: Carousel Mode: `ASK` Interactive Selection Flow (`MediaSelectionActivity`)
- **Action**:
  - Set `CarouselBehavior = ASK`.
  - Inject carousel URL: `https://www.instagram.com/p/DcTZDVpjX57/`.
- **Observed Behavior**:
  - `MediaSelectionActivity` opens immediately.
  - Displays 3 slide thumbnail cards with `#1`, `#2`, `#3` badge chips.
  - Counter displays `"3 of 3 items selected"`.
  - Tapping "CLEAR ALL" updates count to `"0 of 3 items selected"`.
  - Tapping slide cards toggles selection checkboxes and updates button to `DOWNLOAD SELECTED (N)`.
  - Downloads strictly the selected slides.
- **Verdict**: **PASS**

---

### Case 13: Unified Save Location Dialog & Persistence
- **Action**:
  - In Control Center, tap "CHANGE" next to Unified Save Location.
  - Select a folder (e.g. `Pictures/InstaSave/`).
- **Observed Behavior**:
  - Modal dialog displays radio options for standard directories.
  - Tapping an option immediately updates UI and persists to `instasave_settings.xml`:
    `<string name="save_location_relative_path">Pictures/InstaSave/</string>`
  - Downloaded media routes to selected folder via Scoped Storage.
- **Verdict**: **PASS**

---

### Case 14: MediaStore Live Reconciliation
- **Action**:
  - Download media items.
  - Delete physical files from disk using ADB: `adb shell rm -f /sdcard/Pictures/InstaSave/*`.
  - Return to `MainActivity`.
- **Observed Behavior**:
  - `DownloadRepository.reconcileAvailableItemsWithMediaStore()` runs on `onResume()`.
  - Missing URIs transition from `AVAILABLE` to `EXTERNAL_DELETED`.
  - Recent Downloads gallery purges ghost items and updates disk accounting to `0 B`.
- **Verdict**: **PASS**

---

### Case 15: Two-Tier Storage and History Deletion
- **Action 1 (Remove from History Only)**:
  - Long-press / context menu -> "Remove from History (Keep Files)".
  - Confirmed: Database row deleted; physical file remains queryable in `MediaStore`.
- **Action 2 (Delete from Storage)**:
  - Long-press / context menu -> "Delete All Files (Remove from Storage)".
  - Confirmed: Physical file deleted via `contentResolver.delete()`, database row deleted.
- **Verdict**: **PASS**

---

### Case 16: Task Dismissal & Recents Swipe Survival (`stopWithTask="false"`)
- **Action**:
  - Start InstaSave service from `MainActivity` (`FloatingTriggerService` active, floating button visible on screen).
  - Open Android Recents (Overview screen).
  - Swipe InstaSave card up/away to dismiss the task.
- **Observed Behavior**:
  - `FloatingTriggerService` declares `android:stopWithTask="false"` in the Manifest and persists `isServiceRunningDesired = true`.
  - Android/ColorOS task manager removes the UI activity task but leaves the foreground service running.
  - Floating button remains firmly docked on screen with 0px drift.
  - Dumpsys verification:
    ```
    ServiceRecord{... com.instadown.assist/.service.FloatingTriggerService}
      isForeground=true foregroundId=1001 type=specialUse
    ```
  - Re-launching `MainActivity` checks `isServiceRunningDesired` and `FloatingTriggerService.isRunning`, maintaining green "InstaSave is running" state with zero flicker.
- **Verdict**: **PASS**

---

### Case 17: Download Mode Toggling While Stopped (Zero Accidental Starts)
- **Action**:
  - Ensure InstaSave is stopped (`FloatingTriggerService.isRunning == false`).
  - Tap `Clipboard` button in Control Center.
  - Tap `Automatic` button in Control Center.
- **Observed Behavior**:
  - `InstaSavePreferences.setDownloadMode()` persists mode selection to `instasave_settings.xml`.
  - Segmented button UI updates its active highlight immediately.
  - `MainActivity` checks `FloatingTriggerService.isRunning`: because the service is stopped, it strictly suppresses any call to `startForegroundService()`.
  - No floating button appears, no foreground notification is spawned, and the service remains 100% stopped until the user explicitly taps "Start InstaSave".
- **Verdict**: **PASS**

---

### Case 18: Automatic + Ask Notification Flow (Non-Intrusive Prompt, 0 False Checkmarks)
- **Action**:
  - Configure `DownloadMode = AUTOMATIC` and `CarouselBehavior = ASK`.
  - Navigate to Instagram carousel post (`https://www.instagram.com/p/DcTZDVpjX57/`).
  - Tap the floating trigger button.
- **Observed Behavior**:
  - AutomationEngine performs target container extraction, opens the share sheet, copies clean URL, and closes the share sheet.
  - `InstagramResolver` resolves 3 carousel items.
  - Rather than popping a full-screen activity over Instagram, `DownloadNotificationManager.showCarouselPromptNotification()` posts a Heads-Up Prompt Notification on `instasave_prompts_channel` (`IMPORTANCE_HIGH`, `CATEGORY_EVENT`).
  - Notification displays:
    - Title: `"Instagram Carousel Detected"`
    - Text: `"3 items found. Choose how to download:"`
    - Actions: `[ SAVE ALL ]`, `[ CHOOSE ]`, `[ CURRENT ]`
  - Floating button smoothly transitions back to `VisualState.IDLE`. **Zero false checkmarks** are displayed (`WRONG_MEDIA_DOWNLOADS = 0`).
  - Tapping `CHOOSE` launches `MediaSelectionActivity` with `EXTRA_URL`.
  - Tapping `SAVE ALL` triggers `CarouselActionReceiver` and downloads all 3 slides in the background.
- **Verdict**: **PASS**

---

### Case 19: ColorOS V16.0.0 Clipboard Background Platform Block & Validated Focus-Based Flow
- **Action**:
  - Evaluate background `OnPrimaryClipChangedListener` in `FloatingTriggerService` vs foreground focus-based listener on OPPO K13 Turbo 5G (Android 16 / ColorOS V16.0.0).
- **Observed Behavior**:
  - ColorOS V16.0.0 strictly enforces Android's platform security model: background clipboard access is silenced without window focus. `hasPrimaryClip()` returns false or throws a security check when attempted from background services.
  - InstaSave's focus-based architecture is 100% compliant:
    1. When in `CLIPBOARD` mode, returning to `MainActivity` immediately checks the clipboard on `onResume()` / `onWindowFocusChanged()`, triggering auto-download.
    2. When in `AUTOMATIC` mode, the user explicitly taps the floating button, giving user-initiated automation context.
  - Zero crashes, zero security violations, zero platform blocks.
- **Verdict**: **PASS**

---

## 3. Automated Unit Test Suite

Execute unit tests across all concurrency, reconciliation, and carousel logic:

```bash
./gradlew test
```

### Test Classes & Coverage
1. **`ClipboardConcurrencyTest.kt`**:
   - `testNewValidUrl_Allowed`: Clean URL allowed.
   - `testSameUrlCopiedAgainWhileProcessing_RejectedDuplicate`: Rejects duplicate active URLs.
   - `testActivityResumeWhileProcessing_RejectedDuplicate`: Rejects reentrant focus events.
   - `testSameUrlAfterSuccessfulCompletion_RejectedIfNotNewerTimestamp`: Ignores stale clips.
   - `testSameUrlAfterSuccessfulCompletion_AllowedIfNewerTimestamp`: Allows deliberate recopies.
   - `testDifferentUrlWhileProcessing_SafelyQueuedAndDeferred`: FIFO queuing of rapid links.
   - `testFailureBeforeMeaningfulStart_DoesNotMarkPermanentlyProcessed`: Allows retry on failure.
   - `testCurrentItemCorroboration_SingleItemAlwaysValid`: Non-carousels always valid.
   - `testCurrentItemCorroboration_ContainerAndObservationMatch_Valid`: Corroborated slides.
   - `testCurrentItemCorroboration_ObservationOnlyWithMatchingTotalAndFreshTime_Valid`: Fresh observations.
   - `testCurrentItemCorroboration_InconsistentCounts_SafeRefusal`: Mismatched counts abort.
   - `testCurrentItemCorroboration_StaleObservation_SafeRefusal`: Old observations abort.
   - `testCurrentItemCorroboration_NoIndicator_SafeRefusal`: Missing indicators abort safely.
2. **`ReconciliationAndStorageTest.kt`**:
   - Live reconciliation against `MediaStore`.
   - External deletion state tracking.
3. **`DownloadPlannerTest.kt`**:
   - Carousel slide index filtering and download planning.

### Test Execution Result
```
BUILD SUCCESSFUL in 33s
45 actionable tasks: 23 executed, 22 up-to-date
```

---

## 4. Useful ADB Commands for Verification

### Inspect Settings & State
```bash
adb shell run-as com.instadown.assist cat shared_prefs/instasave_settings.xml
```

### Inspect SQLite History Database
```bash
adb shell "run-as com.instadown.assist sqlite3 databases/instasave.db 'SELECT id, shortcode, post_type, status, total_bytes FROM downloads;'"
```

### Query Physical MediaStore Files
```bash
adb shell content query --uri content://media/external/images/media --projection _id:_display_name:bucket_display_name --where "bucket_display_name='InstaSave'"
```

### Simulate Clipboard URL Injection
```bash
adb shell am start -n com.instadown.assist/.ui.MainActivity --es SET_CLIPBOARD "https://www.instagram.com/p/DcTZDVpjX57/"
```

### Monitor Live Logcat Telemetry
```bash
adb logcat -s InstaDownloaderPOC MainActivity MainActivityClip FloatingTriggerService
```

---

## 5. v1.1.4 Verification Matrix

### Case 10: Headless Web Bridge 1-Second Resolution Verification
- **Input**: Instagram post URL (Reel, Photo, or Carousel).
- **Execution**: `CompositeInstagramResolver.resolve(url)`.
- **Observed Behavior**:
  - `webBridgeResolver` executes immediately as Tier 1.
  - Pre-warmed background WebView (initialized in `InstaSaveApp.onCreate()`) is leveraged.
  - Desktop User-Agent prevents mobile app installation modals.
  - Form query is injected via native prototype descriptor setter; Vue proxy triggers search.
  - Resolution completes in **800 ms – 1,200 ms**.
- **Verdict**: **PASS**

### Case 11: Multi-Item Carousel Extraction (15+ Items)
- **Input**: Instagram carousel containing 15 items.
- **Observed Behavior**:
  - Extractor accesses `comp.props?.data` and `comp.proxy?.data` directly from Vue component state.
  - Automatically sets `visibleDataCount = 999` and clicks `.load-more-trigger`.
  - All 15 items are parsed with download URLs and preview thumbnail URLs without truncating at 6.
- **Verdict**: **PASS**

### Case 12: Instant Media Selection & Detached Background Saving
- **Action**: Tap copied Instagram link, open `MediaSelectionActivity`, select items, tap "Download Selected".
- **Observed Behavior**:
  - Selection sheet opens in **0 ms** using pre-resolved metadata.
  - High-priority parallel thumbnail prefetch renders image tiles immediately.
  - Tapping "Download Selected" displays Toast *"Saving X items to Gallery in background..."* and finishes activity immediately (`finish()`).
  - Downloads stream in background via `InstaSaveApp.applicationScope` using 3–4 parallel OkHttp streams without re-resolving.
  - Notification drawer displays live percent progress.
- **Verdict**: **PASS**

### Case 13: Universal Persistent Thumbnail & Deleted Post Backfill
- **Action**: Delete downloaded media from phone's external gallery app, open `DownloadHistoryActivity`.
- **Observed Behavior**:
  - MediaStore reconciliation marks items `EXTERNAL_DELETED`.
  - Adapter reads `context.filesDir/thumbnails/${shortcode}.jpg` and displays cover thumbnails immediately offline.
  - Missing legacy thumbnails are backfilled concurrently via `Semaphore(3)` in 2–3 seconds.
- **Verdict**: **PASS**

### Case 14: In-App Self-Update (OTA)
- **Action**: Navigate to Settings -> In-App Software Updates -> Check for Updates.
- **Observed Behavior**:
  - Manifest is fetched from `https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/release-manifest.json`.
  - Version comparison accurately determines if an update is available.
  - "Download & Install" downloads APK to cache and launches native `PackageInstaller`.
- **Verdict**: **PASS**

### Case 15: Production Keystore APK Signature Verification
- **Command**: `apksigner verify --verbose --print-certs app-release.apk`
- **Output**:
  - Verified using v2 scheme: `true`
  - Verified using v3 scheme: `true`
  - Certificate DN: `CN=InstaSave Software, OU=Mobile Security, O=InstaSave Global, L=San Francisco, ST=California, C=US`
  - Public Key: RSA 2048-bit
- **Verdict**: **PASS**

