# InstaSave Architecture Blueprint

---

## 1. Architectural Philosophy & Core Invariants

InstaSave is architected around a strict unidirectional separation between the **Automation & Media Extraction Pipeline** and the **Utility & State Persistence Subsystem**. 

### 1.1 Non-Negotiable Safety Invariants
Every component, state machine, and background routine is governed by three strict invariants:

| Safety Invariant | Invariant Value | Operational Definition |
| :--- | :---: | :--- |
| `INCORRECT_CONTROL_CLICKS` | **0** | Zero misclicks on unintended controls or adjacent posts during automation. |
| `WRONG_POST_SELECTIONS` | **0** | Strict container boundary verification ensures only the target post is parsed. |
| `WRONG_MEDIA_DOWNLOADS` | **0** | Slide corroboration and strict **Safe Refusal** prevent downloading wrong carousel slides. |

### 1.2 Mandatory Architectural Boundaries
1. **Core Pipeline Decoupling**:
   - The extraction packages (`com.instadown.assist.engine`, `com.instadown.assist.resolver`, `com.instadown.assist.download`) must **NEVER** import SQLite classes, `SQLiteOpenHelper`, database DAOs, or Android UI activities.
   - The pipeline communicates its results outwards strictly via the decoupled `DownloadTransactionEvent` interface.
2. **MediaStore as the Physical Authority of Truth**:
   - The SQLite database represents the application's *logical transaction history*.
   - Android's system `MediaStore` is the *physical authority* for whether a downloaded file actually exists.
   - If a user deletes an image via Google Photos or a file manager, InstaSave detects the missing URI during background reconciliation, transitions the record to `EXTERNAL_DELETED`, and preserves history integrity without crashing.
3. **Strictly Non-Permanent CDN URLs**:
   - Signed Instagram CDN URLs expire within hours or days. The persistence layer never stores remote CDN URLs permanently; it stores only canonical metadata (`shortcode`, `postType`, `sourceUrl`, MediaStore content URIs).
4. **Conceptual Separation of Download Modes**:
   - `AUTOMATIC` (overlay trigger button) and `CLIPBOARD` (focus-based auto-download upon returning to InstaSave with overlay hidden) are persistent download modes.
   - Manual input (`+ Paste Instagram Link`) is an explicit home screen action, not a persistent mode, feeding the identical resolution and download pipeline.
5. **Stopped State Mode Isolation**:
   - Switching between `AUTOMATIC` and `CLIPBOARD` modes in `MainActivity` while the service is stopped must never boot or start `FloatingTriggerService`.
   - The toggle modifies persistent user preferences and UI state only; it explicitly checks `FloatingTriggerService.isRunning` before dispatching any service commands.
6. **Task Dismissal & Recents Swipe Resilience**:
   - Swiping `MainActivity` away from Android Recents / App Overview must not kill an active `FloatingTriggerService`.
   - `FloatingTriggerService` declares `android:stopWithTask="false"` in the Manifest and persists `isServiceRunningDesired = true` to guarantee uncompromised overlay availability on aggressive OEM task managers such as ColorOS V16.0.0.
7. **Platform Clipboard Security Compliance**:
   - On Android 10+ and strictly enforced on ColorOS V16.0.0 (API 36), passive background clipboard listening via `OnPrimaryClipChangedListener` is silenced by the OS without active window focus.
   - InstaSave uses the privacy-safe, compliant focus-based architecture: active window focus on resume or explicit user overlay trigger is required to read the clipboard.

---

## 2. High-Level Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                       USER INTERACTION LAYER                                     │
│                                                                                                  │
│   ┌──────────────────────────────┐                         ┌─────────────────────────────────┐   │
│   │ FloatingTriggerService       │                         │ MainActivity (Control Center)   │   │
│   │ • Stationary Circular Overlay│                         │ • Download Mode: AUTO/CLIPBOARD │   │
│   │ • specialUse Foreground FGS  │                         │ • Carousel: ENTIRE/CURRENT/ASK  │   │
│   │ • 0px Anchor Drift Geometry  │                         │ • Unified Save Location Picker  │   │
│   │ • Edge Docking Persistence   │                         │ • + Paste Instagram Link Action │   │
│   └──────────────┬───────────────┘                         └────────────────┬────────────────┘   │
└──────────────────┼──────────────────────────────────────────────────────────┼────────────────────┘
                   │ Triggers Automation Gesture                              │ User Action / Focus Flow
                   ▼                                                          ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    DOWNLOAD RESOLUTION PIPELINE                                  │
│                                                                                                  │
│   ┌──────────────────────────────────────────┐             ┌─────────────────────────────────┐   │
│   │ AutomationEngine (Accessibility)         │             │ ClipboardDownloadCoordinator    │   │
│   │ • Post Boundary Detection                │             │ • Single-Flight Concurrency Lock│   │
│   │ • Share / Overflow Button Locator        │             │ • Reentrancy & Queuing Engine   │   │
│   │ • "Copy link" Click Automation           │             │ • Multi-Signal Slide Evaluator  │   │
│   └────────────────────┬─────────────────────┘             └────────────────┬────────────────┘   │
│                        │ Acquired Clean URL                                 │ Validated Intent   │
│                        └─────────────────────────┬──────────────────────────┘                    │
│                                                  ▼                                               │
│                               ┌─────────────────────────────────────┐                            │
│                               │ InstagramResolver (Headless Engine) │                            │
│                               │ • Client-Side HTML / JSON Extraction│                            │
│                               │ • Shortcode & Post Type Parser      │                            │
│                               └──────────────────┬──────────────────┘                            │
│                                                  ▼                                               │
│                               ┌─────────────────────────────────────┐                            │
│                               │ MediaSelectionActivity (if ASK mode)│                            │
│                               │ • Visual Thumbnail Grid             │                            │
│                               │ • Selective Slide Filter            │                            │
│                               └──────────────────┬──────────────────┘                            │
│                                                  ▼                                               │
│                               ┌─────────────────────────────────────┐                            │
│                               │ InstagramDownloadCoordinator        │                            │
│                               │ • HTTP Chunked Streaming            │                            │
│                               │ • MediaStore Scoped Storage Writer  │                            │
│                               └──────────────────┬──────────────────┘                            │
└──────────────────────────────────────────────────┼───────────────────────────────────────────────┘
                                                   │ Dispatches Terminal Event
                                                   ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                   STORAGE & PERSISTENCE SUBSYSTEM                                │
│                                                                                                  │
│   ┌──────────────────────────────┐                         ┌─────────────────────────────────┐   │
│   │ InstaSaveDatabaseHelper      │                         │ DownloadRepository              │   │
│   │ • downloads (Transactions)   │ ◄────────────────────── │ • MediaStore Live Reconciliation│   │
│   │ • download_items (Media Rows)│                         │ • Two-Tier Storage Deletion     │   │
│   └──────────────────────────────┘                         └────────────────┬────────────────┘   │
│                                                                             │ Direct Queries     │
│                                                            ┌────────────────▼────────────────┐   │
│                                                            │ StorageAccountant               │   │
│                                                            │ • Scans Pictures/ & Movies/     │   │
│                                                            │ • Real-time Disk Usage Auditor  │   │
│                                                            └─────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Concurrency & Reentrancy Architecture (`ClipboardDownloadCoordinator`)

Handling clipboard-driven workflows on mobile devices requires defensive concurrency management. The application must handle rapid app-switching, window focus changes, duplicate clipboard events, and multi-threaded resolution calls without triggering duplicate downloads or losing user intent.

### 3.1 Synchronized State Machine
`ClipboardDownloadCoordinator.kt` maintains state using an internal monitor lock (`stateLock`) and volatile pointers:

```kotlin
class ClipboardDownloadCoordinator(private val context: Context? = null) {
    private val stateLock = Any()

    @Volatile
    var currentlyProcessingClipboardUrl: String? = null
        private set

    @Volatile
    var pendingClipboardUrl: String? = null
        private set

    @Volatile
    var pendingClipboardTimestamp: Long = 0L
        private set

    val isProcessing: Boolean
        get() = currentlyProcessingClipboardUrl != null
}
```

### 3.2 Concurrency Decision Matrix (`evaluateProcessDecision`)
Whenever a clipboard URL is evaluated (e.g. on window focus or clipboard listener event), it is evaluated against the decision matrix:

```kotlin
sealed class ProcessDecision {
    object ALLOW : ProcessDecision()
    object REJECT_ALREADY_PROCESSING : ProcessDecision()
    object REJECT_ALREADY_COMPLETED : ProcessDecision()
    object DEFER_QUEUED : ProcessDecision()
}
```

```mermaid
graph TD
    Start[Evaluate Clipboard URL] --> Lock[Acquire stateLock]
    Lock --> CheckActive{Is any URL actively processing?}
    
    CheckActive -- YES --> SameUrl{Is it the SAME URL?}
    SameUrl -- YES --> RejectActive[REJECT_ALREADY_PROCESSING<br/>Ignore Duplicate Event]
    SameUrl -- NO --> DeferQueue[DEFER_QUEUED<br/>Queue in pendingClipboardUrl]
    
    CheckActive -- NO --> CheckCompleted{Matches last completed URL?}
    CheckCompleted -- NO --> Allow[ALLOW<br/>Start Processing]
    CheckCompleted -- YES --> CheckTimestamp{Is clipTimestamp newer than<br/>lastProcessedTimestamp?}
    CheckTimestamp -- YES --> Allow
    CheckTimestamp -- NO --> RejectCompleted[REJECT_ALREADY_COMPLETED<br/>Ignore Stale Clipboard]
```

### 3.3 Transaction Lifecycle Transitions
1. **`startProcessing(url)`**:
   - Sets `currentlyProcessingClipboardUrl = cleanUrl`.
   - Logcat: `ClipboardProcessing: Started for https://instagram.com/...`
2. **`onProcessingSuccess(url, timestamp, prefs)`**:
   - Clears `currentlyProcessingClipboardUrl`.
   - Records `last_processed_clip_url = cleanUrl` and `last_processed_clip_timestamp = timestamp` in `instasave_settings.xml`.
   - Logcat: `ClipboardProcessing: Success for ... (recorded timestamp=...)`
3. **`onProcessingFailed(url)`**:
   - Clears `currentlyProcessingClipboardUrl`.
   - **Crucial Invariant**: Does **NOT** record the URL as completed in `SharedPreferences`. Retrying the download immediately is fully allowed.
   - Logcat: `ClipboardProcessing: Failed for ... (URL not permanently marked as processed)`
4. **`pollPending()`**:
   - Called upon completion or failure of the active transaction. If a secondary URL was queued in `pendingClipboardUrl`, it is atomically retrieved, cleared, and initiated.

---

## 4. Carousel Resolution & Multi-Signal Corroboration Engine

### 4.1 Granular Carousel Behaviors
The user configures `CarouselDownloadBehavior` via the Control Center:
- **`ENTIRE_CAROUSEL`**: Downloads all media files in the carousel automatically in the background.
- **`CURRENT_ITEM`**: Downloads only the single slide visible when the action was triggered.
- **`ASK`**: Context-aware slide selection depending on trigger environment:
  - **In-App / Clipboard Trigger**: Immediately launches `MediaSelectionActivity` with visual thumbnail cards.
  - **Overlay / Automatic Trigger**: Avoids disrupting the foreground app with an intrusive full-screen activity. Instead, InstaSave issues a high-priority Heads-Up Prompt Notification (`showCarouselPromptNotification`) on the dedicated `instasave_prompts_channel` (`IMPORTANCE_HIGH`, `CATEGORY_EVENT`).
    The notification presents three direct actions:
    1. **`SAVE ALL`**: Dispatches an explicit broadcast to `CarouselActionReceiver` (`ACTION_CAROUSEL_SAVE_ALL`) to download all items immediately in the background.
    2. **`CHOOSE`**: Direct intent launching `MediaSelectionActivity` with `EXTRA_URL` for full visual thumbnail filtering.
    3. **`CURRENT`**: Dispatches broadcast to `CarouselActionReceiver` (`ACTION_CAROUSEL_SAVE_CURRENT`) to save strictly the visible slide.
  - **Floating Overlay Visual Invariant**: Upon issuing the prompt notification, the floating overlay button immediately transitions cleanly back to `VisualState.IDLE`. It never displays a false green checkmark (`VisualState.SUCCESS` is reserved exclusively for completed file downloads), preventing false user confidence.

### 4.2 Multi-Signal Slide Indicator Corroboration
For `CarouselBehavior.CURRENT_ITEM`, a simple string match is insufficient to prevent downloading the wrong slide. InstaSave requires corroboration across multiple independent signals:

```kotlin
fun resolveCurrentSlideIndex(
    autoResult: AutomationResult?,
    totalMediaItems: Int,
    observedSlide: InstaAccessibilityService.ObservedSlideInfo?,
    currentTimeMs: Long = System.currentTimeMillis()
): SlideEvaluationResult
```

#### Evaluation Rules:
1. **Single Item Check**: If `totalMediaItems <= 1`, the post is not a carousel; index `0` is always valid.
2. **Post Container Walking**:
   - `InstaAccessibilityService` walks up the accessibility node hierarchy from the touch/focused node to find the enclosing post container (`width > 600px` and `height > 400px`).
   - Only indicators found within this target container are accepted, preventing contamination from adjacent feed posts.
3. **Slide Indicator Pattern Matching**:
   - Searches node `text` and `contentDescription` for patterns matching:
     $$\text{Pattern} = \text{"(\textbackslash d\{1,2\})\textbackslash s*(?:of|/)\textbackslash s*(\textbackslash d\{1,2\})"}$$
   - Parses `currentSlide` and `totalSlides`.
4. **Total Count Corroboration**:
   - The indicator's `totalSlides` must strictly equal the headless resolver's `totalMediaItems`. If the indicator says "2 of 4" but the post resolves to 6 items, the indicator is declared invalid.
5. **Freshness Window Check**:
   - Observed slides must have a timestamp age within `MAX_SLIDE_OBSERVATION_AGE_MS = 30000L` (30 seconds).

```mermaid
graph TD
    A[CURRENT_ITEM Triggered] --> B{totalMediaItems <= 1?}
    B -- YES --> C[Valid: Index 0]
    B -- NO --> D[Check Container-Bound Evidence]
    D --> E[Check Accessibility Event Stream]
    E --> F{Do Container & Observation Corroborate?}
    
    F -- Both Valid & Agree --> G[Valid: targetIndex = slideIndex]
    F -- Conflict, but Observation < 5s --> H[Valid: targetIndex = observedIndex<br/>User Swiped Recently]
    F -- Only One Valid & Matching Total --> I[Valid: Verified Slide Index]
    
    F -- Missing / Inconsistent / Stale --> J[SAFE REFUSAL<br/>WRONG_MEDIA_DOWNLOADS = 0]
```

### 4.3 Safe Refusal Contract
When slide evidence is missing, inconsistent, or stale:
- InstaSave **NEVER** guesses and **NEVER** silently downloads the entire carousel.
- Exactly **0 items are downloaded**.
- The transaction terminates with `errorCode = DownloadErrorCode.TARGET_SELECTION_FAILED`.
- The user receives clear, actionable feedback on screen:
  `"Safe Refusal: Couldn't identify current slide (0 saved)"`.
- The `WRONG_MEDIA_DOWNLOADS = 0` invariant is mathematically preserved.

---

## 5. Interactive Media Selection Architecture (`MediaSelectionActivity`)

When `CarouselDownloadBehavior.ASK` is active:
1. `InstagramResolver` resolves the post and identifies multiple items (`totalItems > 1`).
2. **Context Routing**:
   - **From In-App Clipboard / Manual Paste**: `MediaSelectionActivity` is launched directly with `EXTRA_URL` (`"extra_instagram_url"`).
   - **From Floating Overlay**: The user receives a heads-up prompt notification and taps `CHOOSE`, which launches `MediaSelectionActivity` with `EXTRA_URL`.
3. `MediaSelectionActivity` displays:
   - High-resolution thumbnail cards with slide index badges (`#1`, `#2`, ...).
   - Dynamic counter: `"X of Y items selected"`.
   - Quick bulk action: `SELECT ALL` toggles to `CLEAR ALL`.
   - Download CTA: `DOWNLOAD SELECTED (N)`.
4. User selections generate an array of 0-based indices (`selectedIndices: IntArray`).
5. `MediaSelectionActivity` passes `selectedIndices` into `InstagramDownloadCoordinator.executeDirectDownload()`:
   - `DownloadPlanner` filters the resolved items to include strictly the requested indices.
   - Non-selected items are completely skipped.

---

## 6. The Transaction Event Bus

Decoupling between the extraction pipeline and persistence is achieved through `DownloadTransactionEvent`:

```kotlin
data class DownloadTransactionEvent(
    val transactionId: String,
    val status: TransactionStatus,
    val shortcode: String,
    val postType: PostType,
    val sourceUrl: String,
    val carouselCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val totalBytes: Long,
    val timestamp: Long,
    val items: List<DownloadItemResult>,
    val errorMessage: String? = null
)

data class DownloadItemResult(
    val itemIndex: Int,
    val mediaType: MediaType,
    val mediaUri: Uri?,          // NULL if failed, queued, or skipped duplicate
    val fileName: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val status: ItemStatus
)
```

### Lifecycle Dispatch Guarantee
- The event is dispatched **on every terminal state**: `SUCCESS`, `PARTIAL_SUCCESS`, `FAILED`, `CANCELLED`, or `DUPLICATE`.
- For partial failures (e.g. 4 of 5 carousel slides succeed, 1 fails), the transaction status is marked `PARTIAL_SUCCESS`, successful items are marked `AVAILABLE` with valid `mediaUri`s, and failed items are marked `FAILED` with `mediaUri = null`.

---

## 7. MediaStore Authority & Live Reconciliation

`DownloadRepository` performs live reconciliation between SQLite records and Android `MediaStore`:

```
SQLite Query ──► DownloadRecord (items with media_uri)
                       │
                       ▼
         Verify URI via ContentResolver
                       │
            ┌──────────┴──────────┐
            ▼                     ▼
     File Exists             File Missing
            │                     │
            ▼                     ▼
     Keep AVAILABLE      Mark EXTERNAL_DELETED
```

### 7.1 Reconciliation Logic
1. When `MainActivity` resumes, it queries all records where `status == ItemStatus.AVAILABLE`.
2. Verifies each `media_uri` with `contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)`.
3. If query returns empty cursor, null, or throws `SecurityException` / `FileNotFoundException`, the item status in SQLite is updated to `EXTERNAL_DELETED`.
4. If an unexpected `IllegalStateException` or general provider failure occurs, item is marked `VERIFY_ERROR` to avoid falsely declaring a file missing during temporary OS glitches.

### 7.2 Two-Tier Deletion Flow
- **Remove from History (Keep Files)**:
  `repository.deleteDownloadRecord(downloadId)` executes `DELETE FROM downloads WHERE id = ?`. Child items are cascaded; files on disk remain completely untouched.
- **Delete All Files (Remove from Storage)**:
  `repository.deleteDownloadAndMedia(context, downloadId)` queries all associated `media_uri`s, calls `contentResolver.delete(uri, null, null)`, and then deletes the database row.

---

## 8. Storage Accounting Architecture

`StorageAccountant.kt` bypasses the database completely to determine disk usage, establishing `MediaStore` as the sole authority:

```kotlin
// MediaStore Query Scopes:
val photoCollection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
val videoCollection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

// Target Directories:
// - Pictures/InstaSave/ (or user-selected folder)
// - Movies/InstaSave/
```

- Executed asynchronously on `Dispatchers.IO`.
- Queries `MediaStore.MediaColumns.SIZE` directly for all files in the relative path.
- Invalidation triggers execute on download completion and deletion events.
- Accurately reflects true physical disk usage regardless of whether files were added via InstaSave or deleted via external gallery apps.

---

## 9. Threading & Concurrency Model

InstaSave utilizes Kotlin Coroutines and structured concurrency across all operational boundaries:

| Layer / Component | Execution Context | Dispatcher / Threading |
| :--- | :--- | :--- |
| **Accessibility Automation** | Main looper / Accessibility event thread | Fast, non-blocking node traversal (<150ms) |
| **Clipboard Concurrency Coordinator** | Synchronized State Machine | Thread-safe monitor locks (`stateLock`) |
| **Headless Resolver** | Coroutine Scope | `Dispatchers.IO` (Network & HTML parsing) |
| **Media Downloader** | Coroutine Scope | `Dispatchers.IO` (Chunked HTTP streaming) |
| **MediaStore Writes** | Coroutine Scope | `Dispatchers.IO` (Scoped Storage file output) |
| **Database Transactions** | Coroutine Scope | `Dispatchers.IO` (SQLite queries and updates) |
| **Storage Accounting** | Background Job | `Dispatchers.IO` (Cursor scanning) |
| **Floating Overlay UI** | Android Main Looper | `Dispatchers.Main` (WindowManager layout updates) |
| **Dashboard & Selection UI** | Activity Lifecycle Scope | `Dispatchers.Main` (View binding & state observation) |

---

## 10. Pre-Warmed Headless Web Bridge Architecture

To eliminate cold-start latencies and bypass unauthenticated Instagram SSR login redirects, InstaSave incorporates a persistent background WebKit runtime managed by `HeadlessWebBridgeResolver.kt` and initialized in `InstaSaveApp.onCreate()`.

```
Application Startup (InstaSaveApp)
           │
           ▼
Handler(MainLooper).post { HeadlessWebBridgeResolver.preWarm() }
           │
           ▼
Creates Background WebView
├── Settings: JS enabled, DOM Storage, Images Disabled (0 bandwidth waste)
├── Desktop UA: Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0.0.0
├── Injects AndroidBridge interface
└── Loads https://fastdl.app/en into memory
           │
           ▼
onPageFinished() ──► isPrewarmReady = true (Parked in memory awaiting input)
```

### 10.1 Desktop User-Agent Anti-Ad Spoofing
When WebKit runs with an Android mobile User-Agent, online converter engines serve interstitial modal dialogs prompting visitors to *"Install our Android app from Google Play"*, which overlays the input element and intercepts automated clicks. By explicitly spoofing a Desktop Chrome User-Agent (`Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36`), the server serves the clean desktop layout without mobile promotional modals. Proactive DOM purging (`dismissPopups()`) strips any residual overlays or modal elements before search injection.

### 10.2 Vue 3 Proxy & Prototype Setter Injection
The extractor form relies on Vue 3 reactive properties (`:disabled="disabled"`, `v-model="query"`). Assigning `input.value = url` directly in JavaScript fails to notify Vue's internal reactive proxy. The automation script invokes the native HTMLInputElement prototype setter:
```javascript
var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
setter.call(input, targetUrl);
input.dispatchEvent(new Event('input', { bubbles: true }));
input.dispatchEvent(new Event('change', { bubbles: true }));
```
It then traverses DOM nodes up the hierarchy to locate `__vueParentComponent.proxy` and directly triggers `proxy.handleSearchButtonClick()`, executing search immediately with zero polling stall.

### 10.3 Dual-Channel Fail-Safe IPC
To ensure callback delivery across varied OEM Android WebKit versions, the bridge establishes dual IPC channels:
1. Primary: `@JavascriptInterface window.AndroidBridge.onMediaFound(jsonStr)`.
2. Fallback: `WebChromeClient.onConsoleMessage` intercepting `INSTASAVE_FOUND:<json>` and `INSTASAVE_ERROR:<msg>`.

---

## 11. Multi-Item Carousel Extraction Subsystem

FastDL initializes carousels with a virtualized DOM slice (`visibleDataCount: 6`). Extracting raw DOM nodes prematurely truncates large carousels at 6 items. The extraction engine bypasses this limit using two coordinated mechanisms:
1. **Direct Vue State Extraction**: Evaluates `comp.props?.data`, `comp.proxy?.data`, and `comp.setupState?.data` directly from the parent Vue component, retrieving all 10–15+ raw media models before DOM virtualization.
2. **Dynamic DOM Expansion**: Simultaneously sets `visibleDataCount = 999` and issues automated synthetic clicks on `.load-more-trigger, button.load-more` selectors, while scrolling the document to guarantee full media availability.

---

## 12. Single-Flight Resolution Hand-off & Detached Background Downloading

### 12.1 Zero-Delay Activity Hand-off
Previously, `MainActivity` resolved a link from the clipboard, and then `MediaSelectionActivity` re-resolved the same URL upon opening, doubling latency. `MediaSelectionActivity.start(context, url, preloadedResult)` now accepts the existing `ResolveResult`. If `preloadedResult` is present, `displayResolvedMedia()` executes synchronously in 0 ms, skipping network resolution entirely.

### 12.2 Detached Application-Scope Background Saving
Tapping *"Download Selected"* in `MediaSelectionActivity` transfers execution to `InstaSaveApp.applicationScope` and immediately finishes the activity (`finish()`). The user returns to their previous application instantly, while:
1. `InstagramDownloadCoordinator.executeDirectDownload()` receives `preResolvedResult`, bypassing re-resolution.
2. `MediaDownloadManager` streams media via parallel OkHttp sockets (3–4 concurrent streams bounded by Semaphore).
3. `DownloadNotificationManager` posts ongoing progress to the system notification drawer and alerts the user upon completion.

---

## 13. Universal Persistent Thumbnail Subsystem

To ensure thumbnails remain accessible even after users delete high-resolution photos or videos from their phone gallery, InstaSave employs an isolated, persistent internal thumbnail cache.

```
Media Downloaded / Resolved
           │
           ▼
Extract lightweight preview image (~20 KB)
           │
           ▼
Save to internal private storage:
context.filesDir / "thumbnails" / "${shortcode}.jpg"
           │
           ▼
Store path in SQLite: downloads.thumbnail_path
```

### 13.1 Offline Persistence Guarantee
Because files in `context.filesDir` are private to InstaSave, external gallery cleaners, Google Photos deletions, and file managers cannot delete them. When `DownloadHistoryActivity` loads, it references `filesDir/thumbnails/${shortcode}.jpg` first, rendering the card immediately even when local media is marked `EXTERNAL_DELETED`.

### 13.2 Bounded Parallel Backfilling
For legacy records created before persistent thumbnail caching, `DownloadHistoryActivity.backfillThumbnailsInBackground`:
1. Executes an instant local pass checking `filesDir`, `cacheDir`, and local `MediaStore` in 0 ms.
2. For records with deleted local files, launches a bounded parallel coroutine pool (`Semaphore(3)`) resolving remote preview images via the fast pre-warmed bridge.
3. Updates SQLite and calls `updateRecordInMemory(shortcode, author, thumbPath, caption)` on the fly, rendering thumbnails within 2–3 seconds.

---

## 14. In-App Software Self-Update Subsystem (OTA)

InstaSave includes an autonomous software update mechanism that checks for new releases on GitHub without requiring USB debugging or third-party app stores.

```
Settings -> "Check for Updates"
           │
           ▼
HTTP GET release-manifest.json
           │
           ▼
Compare remote versionCode > current versionCode
           │
           ├── If newer version available:
           │   ├── Download app-release.apk to internal app cache
           │   ├── Emit real-time byte progress (0% - 100%)
           │   └── Launch PackageInstaller intent via FileProvider
           │
           └── If up to date:
               └── Notify "You are using the latest version."
```

### Manifest Schema (`release-manifest.json`):
```json
{
  "versionCode": 6,
  "versionName": "1.1.4",
  "releaseNotes": "Summary of updates and fixes",
  "apkUrl": "https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/app-release.apk",
  "fallbackApkUrl": "https://github.com/msmayanksingh22/InstaSave/releases/download/v1.1.4/app-release.apk",
  "fileSizeBytes": 3842477
}
```

---

## 15. In-App Diagnostics & Telemetry Subsystem

To facilitate debugging without tethered USB debugging or ADB shell sessions:
1. `AppLogger.kt`: Maintains an in-memory thread-safe circular buffer of up to 1,000 log events with millisecond timestamps and log levels.
2. In-App Log Viewer: Accessible from Settings -> In-App Software Updates & Diagnostics -> View Diagnostic Logs.
3. One-Tap Export:
   - **Copy to Clipboard**: Copies the full formatted session log for direct messaging.
   - **Share Logs via File**: Writes `instasave-debug-[timestamp].txt` and shares it via Android's native system share sheet.

---

## 16. Cryptographic Release Signing & Security Architecture

### 16.1 Official Production Keystore
Release APKs are signed using a dedicated 2048-bit RSA keystore (`app/instasave-release.jks`):
- **Alias**: `instasave`
- **Validity**: 10,000 days (~27 years)
- **Distinguished Name**: `CN=InstaSave Software, OU=Mobile Security, O=InstaSave Global, L=San Francisco, ST=California, C=US`
- **Signatures Enabled**: APK Signature Scheme V1, V2, and V3.

### 16.2 Play Protect Hardening
To prevent heuristic false-positives from Google Play Protect:
1. All private application activities (`MediaSelectionActivity`, `DownloadHistoryActivity`, `DiagnosticsActivity`, `MediaViewerActivity`, `SettingsActivity`) explicitly declare `android:exported="false"`.
2. Release signing uses official production signatures rather than Android SDK test/debug certificates.

