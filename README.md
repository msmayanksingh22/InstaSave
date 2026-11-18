# InstaSave

> **"Save What Inspires You"**  
> An ultra-reliable, private, on-device Android utility engineered to save Instagram media directly to your device storage with zero remote dependencies.

---

## 📖 Table of Contents
- [Overview](#-overview)
- [Core Philosophy & Guarantees](#-core-philosophy--guarantees)
- [Safety Invariants](#-safety-invariants)
- [System Architecture](#-system-architecture)
- [Key Subsystems & Features](#-key-subsystems--features)
  - [1. Control Center & Download Modes](#1-control-center--download-modes)
  - [2. Carousel Download Behaviors](#2-carousel-download-behaviors)
  - [3. Concurrency & Reentrancy Management](#3-concurrency--reentrancy-management)
  - [4. MediaStore Authority & Storage Reconciliation](#4-mediastore-authority--storage-reconciliation)
  - [5. Persistent Floating Trigger Service (`specialUse`)](#5-persistent-floating-trigger-service-specialuse)
  - [6. Adaptive Accessibility Automation Engine](#6-adaptive-accessibility-automation-engine)
  - [7. Interactive Media Selection Activity](#7-interactive-media-selection-activity)
  - [8. Two-Tier History & Carousel Inspector](#8-two-tier-history--carousel-inspector)
- [Android Compatibility & Permissions](#-android-compatibility--permissions)
- [Project Directory Structure](#-project-directory-structure)
- [Building & Installation](#-building--installation)
- [Verification & Documentation Suite](#-verification--documentation-suite)

---

## 🌟 Overview

**InstaSave** is an advanced Android utility built for modern Android operating systems (Android 14 through Android 16 / API 34–36). It bridges Instagram UI interaction with a headless extraction and media download engine, saving high-resolution photos, multi-image/video carousels, and reels directly into user-accessible `MediaStore` storage (`Pictures/InstaSave/` by default).

InstaSave operates entirely locally on your physical hardware, eliminating third-party downloader web APIs, third-party backend servers, analytics tracking, and account logins.

---

## 🛡️ Core Philosophy & Guarantees

1. **100% Local & On-Device Execution**:
   - URL resolution, HTML parsing, media extraction, and file streaming occur directly between your device and Instagram's official CDN.
   - Zero telemetry, analytics SDKs, advertising frameworks, or remote command servers.
2. **MediaStore as the Authority of Truth**:
   - SQLite stores logical history records, but Android's system `MediaStore` is the definitive physical authority.
   - If a file is deleted outside InstaSave (via Google Photos, OPPO Photos / Gallery, or file managers), InstaSave detects the missing URI during background reconciliation, transitions the record to `EXTERNAL_DELETED`, and prevents desynchronization.
3. **Decoupled Architecture**:
   - The extraction and download pipeline operates independently from UI activities, overlay services, and database layers via an asynchronous transaction event bus.
4. **Resilient System Lifecycle**:
   - Uses Android's official `specialUse` foreground service type for persistent overlay controls, maintaining strict decoupling from the system-level `AccessibilityService`.

---

## 🔒 Safety Invariants

During automated testing and real-device validation, InstaSave adheres to three non-negotiable safety invariants:

| Safety Invariant | Target Value | Real-Device Status | Description |
| :--- | :---: | :---: | :--- |
| `INCORRECT_CONTROL_CLICKS` | **0** | **VERIFIED (0)** | Zero misclicks on incorrect UI elements or adjacent posts. |
| `WRONG_POST_SELECTIONS` | **0** | **VERIFIED (0)** | Strict container boundary verification ensures only the target post is parsed. |
| `WRONG_MEDIA_DOWNLOADS` | **0** | **VERIFIED (0)** | Corroborated slide indicators and strict **Safe Refusal** prevent saving unintended slides. |

---

## 🏗️ System Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                       USER INTERACTION LAYER                                     │
│                                                                                                  │
│   ┌──────────────────────────────┐                         ┌─────────────────────────────────┐   │
│   │ FloatingTriggerService       │                         │ MainActivity (Control Center)   │   │
│   │ • Stationary Circular Overlay│                         │ • Mode: AUTOMATIC / CLIPBOARD   │   │
│   │ • specialUse Foreground FGS  │                         │ • Carousel: ENTIRE/CURRENT/ASK  │   │
│   │ • 0px Anchor Drift Geometry  │                         │ • Unified Save Location Picker  │   │
│   │ • Edge Docking Persistence   │                         │ • + Paste Instagram Link Action │   │
│   └──────────────┬───────────────┘                         └────────────────┬────────────────┘   │
└──────────────────┼──────────────────────────────────────────────────────────┼────────────────────┘
                   │ Triggers On-Screen Automation                            │ User-Initiated Input
                   ▼                                                          ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    DOWNLOAD RESOLUTION PIPELINE                                  │
│                                                                                                  │
│   ┌──────────────────────────────────────────┐             ┌─────────────────────────────────┐   │
│   │ AutomationEngine (InstaAccessibility)    │             │ ClipboardDownloadCoordinator    │   │
│   │ • Post Boundary Detection                │             │ • Single-Flight Concurrency Lock│   │
│   │ • Share / Overflow Button Locator        │             │ • Reentrancy & Queuing Engine   │   │
│   │ • "Copy link" Click Automation           │             │ • Multi-Signal Slide Evaluator  │   │
│   └────────────────────┬─────────────────────┘             └────────────────┬────────────────┘   │
│                        │ Acquired URL                                       │ Validated Intent   │
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

## 🚀 Key Subsystems & Features

### 1. Control Center & Download Modes
Located directly on the Home dashboard (`MainActivity`), the Control Center provides instant access to core operation modes:

- **`AUTOMATIC` Mode**:
  - Launches `FloatingTriggerService` with an edge-docked circular overlay button over Instagram.
  - Automatically identifies target post boundaries on screen and simulates the "Share" -> "Copy link" gesture flow.
  - **Recents Swipe Resilience**: Configured with `android:stopWithTask="false"` and `isServiceRunningDesired` persistence, ensuring the floating button survives when InstaSave is swiped away from Android Recents / App Overview.
- **`CLIPBOARD` Mode**:
  - Automatically detects and downloads valid Instagram URLs copied to the clipboard when you open or switch back to InstaSave.
  - Keeps the floating button completely hidden (`floating_button_enabled = false`) for users who prefer zero on-screen overlays.
  - **Platform Security Compliance**: Fully adheres to ColorOS V16.0.0 / Android 16 platform restrictions by evaluating the clipboard on window focus rather than relying on silenced background listeners.
- **Stopped State Isolation Invariant**:
  - Switching modes between `Automatic` and `Clipboard` while InstaSave is stopped persists user settings without inadvertently starting the background service. The service is strictly started only when the user explicitly taps "Start InstaSave".
- **`+ Paste Instagram Link` (Manual Action)**:
  - An explicit dialog action permanently accessible on the Home screen.
  - Allows direct manual pasting of any Instagram URL without changing persistent mode settings.
  - Feeds into the identical resolution and download pipeline.

### 2. Carousel Download Behaviors
InstaSave offers three granular download policies for multi-image/video carousel posts:

1. **`Entire` (`CarouselDownloadBehavior.ENTIRE_CAROUSEL`)**:
   - Downloads all slides and videos in the carousel automatically in the background.
2. **`Current` (`CarouselDownloadBehavior.CURRENT_ITEM`)**:
   - Downloads strictly the currently visible slide.
   - **Multi-Signal Corroboration Engine**: Requires corroborated evidence from:
     - Post container dimensions (`width > 600px`, `height > 400px`).
     - Accessibility slide indicator pattern (`"(\\d{1,2})\\s*(?:of|/)\\s*(\\d{1,2})"`).
     - Indicator total matching resolved media count (`totalSlides == totalMediaItems`).
     - Timestamp freshness (< 30 seconds old).
   - **Safe Refusal Guarantee**: If slide indicator evidence is missing or ambiguous, InstaSave strictly refuses to guess. Exactly **0 files are downloaded**, and safe user feedback is displayed (`"Safe Refusal: Couldn't identify current slide (0 saved)"`), preserving `WRONG_MEDIA_DOWNLOADS = 0`.
3. **`Ask` (`CarouselDownloadBehavior.ASK`)**:
   - Provides interactive slide selection tailored to the trigger context:
     - **In-App / Manual**: Directly opens `MediaSelectionActivity` with high-resolution visual thumbnail cards.
     - **Overlay / Automatic**: Posts a non-intrusive Heads-Up Prompt Notification (`instasave_prompts_channel`, `IMPORTANCE_HIGH`) offering instant actions:
       - `[ SAVE ALL ]`: Downloads all slides in the background via `CarouselActionReceiver`.
       - `[ CHOOSE ]`: Launches `MediaSelectionActivity` for selective slide filtering.
       - `[ CURRENT ]`: Saves only the currently visible slide.
     - **Zero False Checkmarks**: The floating button returns cleanly to `VisualState.IDLE` upon posting the prompt, never showing false success checkmarks before media is actually downloaded.

### 3. Concurrency & Reentrancy Management
Powered by `ClipboardDownloadCoordinator`, the download pipeline enforces strict thread-safety and duplicate prevention:

- **Single Flight Guarantee**: Synchronized state locks (`stateLock`) and volatile pointers (`currentlyProcessingClipboardUrl`) guarantee only one active resolution/download executes at a time.
- **Reentrancy Protection**: Duplicate triggers during activity `onResume` or window focus transitions are immediately rejected (`REJECT_ALREADY_PROCESSING`).
- **Timestamp Discrimination**: An already-completed URL is ignored on subsequent app opens unless a newer clipboard timestamp is detected (`REJECT_ALREADY_COMPLETED`).
- **Safe FIFO Queuing**: If a new URL arrives while another download is running, it is safely queued (`pendingClipboardUrl`) and processed automatically once the active download completes.
- **Retry Resilience**: If a download fails before a meaningful write, `onProcessingFailed()` leaves the URL uncommitted, allowing immediate retry.

### 4. MediaStore Authority & Storage Reconciliation
- **Unified Save Location**: Saves media into standard Scoped Storage locations (`Pictures/InstaSave/` and `Movies/InstaSave/`), configurable via the Control Center "Change Save Location" dialog.
- **Live Background Reconciliation**:
  - On app launch and resume, `DownloadRepository` validates every `AVAILABLE` record against the Android `ContentResolver`.
  - Missing files are updated to `EXTERNAL_DELETED`.
- **Accurate Storage Accounting**:
  - `StorageAccountant` calculates physical disk footprint directly from `MediaStore.MediaColumns.SIZE` across the target directory, preventing desynchronized cache calculations.

### 5. Persistent Floating Trigger Service (`specialUse`)
- **Android 14–16 Compliance**: Declares `foregroundServiceType="specialUse"` with the mandatory manifest property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
- **0px Anchor Drift**: The overlay container enforces fixed dimensions (`sizePx x sizePx`). Text pill expansion is completely eliminated; all state transitions (`IDLE`, `WORKING`, `SUCCESS`, `DUPLICATE`, `ERROR`) occur within the circular perimeter.
- **Normalized Position Persistence**: Coordinates are stored as normalized ratios `(norm_x, norm_y)`. Disk writes are throttled to `ACTION_UP` gestures.
- **Independent Lifecycle**: Stopping InstaSave removes the floating view and foreground notification via `stopForeground(STOP_FOREGROUND_REMOVE)` without disabling the user's system `AccessibilityService`.

### 6. Adaptive Accessibility Automation Engine
- **Target Boundary Isolation**: Traverses the node hierarchy upwards from touch/screen coordinates to locate the enclosing post container (`width > 600px`, `height > 400px`).
- **Adaptive Button Locators**: Matches share and copy link controls by content descriptions, text IDs, resource IDs, and geometric position.
- **Deterministic Action Back Dispatch**: Automatically closes the Instagram share sheet after copying the link, returning the user to their feed seamlessly.

### 7. Interactive Media Selection Activity
- **High-Resolution Card Grid**: Renders thumbnail cards with position badge chips (`#1`, `#2`, ...).
- **Dynamic Selection Status**: Live counter displaying `"X of Y items selected"`.
- **Bulk Toggle**: One-tap toggle between `SELECT ALL` and `CLEAR ALL`.
- **Dynamic CTA**: Primary action button updates dynamically to `DOWNLOAD SELECTED (N)`.
- **Direct Parameter Passing**: Passes validated `selectedIndices: IntArray` directly to `InstagramDownloadCoordinator.executeDirectDownload()`.

### 8. Two-Tier History & Carousel Inspector
- **Responsive 2-Column Grid**: Displays history cards with media badges (`PHOTO`, `CAROUSEL`, `REEL`), slide counts, and localized timestamps.
- **Carousel Inspector Dialog**: Tapping any carousel card opens an itemized modal listing every individual slide with thumbnails, dimensions, file sizes, and instant gallery launch.
- **Two-Tier Deletion**:
  1. *Remove from History (Keep Files)*: Deletes database metadata while preserving physical files on storage.
  2. *Delete All Files (Remove from Storage)*: Physically deletes files via `ContentResolver.delete()` and purges database rows.

---

## 📱 Android Compatibility & Permissions

InstaSave is engineered for modern Android security standards (Android 14+ / Android 16):

| Permission | Purpose | Failure Handling |
| :--- | :--- | :--- |
| `android.permission.FOREGROUND_SERVICE` | Mandatory for persistent overlay execution | Service startup prevented if missing |
| `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` | Complies with Android 14+ foreground service policies | Declared in Manifest with subtype property |
| `android.permission.SYSTEM_ALERT_WINDOW` | Renders floating trigger button over other apps | Gated at startup; guides user to settings |
| `android.permission.BIND_ACCESSIBILITY_SERVICE` | Automates target post interaction and slide inspection | Monitored dynamically; graceful prompts |
| `android.permission.POST_NOTIFICATIONS` | Ongoing service and download completion notifications | **Optional**; app functions fully if denied |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Optional automatic startup on device reboot | User-configurable in Settings |

### Required Manifest Properties (Android 14+)
```xml
<service
    android:name=".service.FloatingTriggerService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Persistent floating overlay control for user-initiated Instagram media saving" />
</service>
```

---

## 📁 Project Directory Structure

```
d:\Project\InstaDown\app\src\main\java\com\instadown\assist\
├── data/
│   ├── db/
│   │   ├── InstaSaveDatabaseHelper.kt   # SQLite open helper (downloads, download_items)
│   │   └── InstaSaveDbContract.kt       # Schema DDL, table definitions & column names
│   ├── model/
│   │   ├── DownloadRecord.kt            # Transaction and item data models
│   │   ├── ItemStatus.kt                # AVAILABLE, EXTERNAL_DELETED, FAILED, etc.
│   │   └── TransactionStatus.kt         # SUCCESS, PARTIAL_SUCCESS, FAILED, etc.
│   └── repository/
│       └── DownloadRepository.kt        # MediaStore reconciliation & two-tier deletion
├── download/
│   ├── ClipboardDownloadCoordinator.kt  # Concurrency, reentrancy & slide corroboration
│   ├── DownloadPlanner.kt               # Download task planner and index filter
│   ├── InstagramDownloadCoordinator.kt  # Execution coordinator & direct download engine
│   ├── MediaDownloader.kt               # OkHttp chunked streaming engine
│   └── MediaStoreWriter.kt              # Scoped storage content resolver writer
├── engine/
│   ├── AutomationEngine.kt              # UI automation state machine & gesture dispatcher
│   ├── InstagramTargetSelector.kt       # Post boundary & control detection
│   └── ShareSheetLocator.kt             # Copy Link accessibility locator
├── overlay/
│   ├── FloatingOverlayView.kt           # 0px drift circular overlay widget
│   └── InstaSavePreferences.kt          # DownloadMode, CarouselBehavior & settings
├── receiver/
│   ├── BootReceiver.kt                  # Device boot completion receiver
│   └── ServiceControlReceiver.kt        # Notification drawer "Stop" broadcast receiver
├── resolver/
│   ├── HeadlessEngine.kt                # HTML / JSON HTTP resolver
│   ├── InstagramResolver.kt             # Shortcode extractor & media payload parser
│   └── ResolvedMediaItem.kt             # Model for resolved URLs and dimensions
├── service/
│   ├── FloatingTriggerService.kt        # Foreground overlay service (specialUse)
│   ├── InstaAccessibilityService.kt     # Accessibility automation & slide extraction
│   └── InstagramAssistSession.kt        # Per-download session coordinator
├── ui/
│   ├── adapter/
│   │   ├── CarouselItemViewerAdapter.kt # Slide inspector adapter
│   │   ├── DownloadHistoryAdapter.kt    # 2-column history grid adapter
│   │   └── MediaSelectionAdapter.kt     # Interactive carousel selector adapter
│   ├── DiagnosticsActivity.kt           # Telemetry & automation logs
│   ├── DownloadHistoryActivity.kt       # Full download history grid
│   ├── MainActivity.kt                  # Control Center, status indicators & recent gallery
│   ├── MediaSelectionActivity.kt        # Interactive slide selection UI (ASK mode)
│   └── SettingsActivity.kt              # Preferences, button customization & about info
└── util/
    ├── DownloadNotificationManager.kt   # Dual-channel notification builder
    ├── FloatingPositionManager.kt       # Normalized coordinate persistence
    └── StorageAccountant.kt             # Direct MediaStore disk usage auditor
```

---

## 🛠️ Building & Installation

### Prerequisites
- Android Studio Ladybug / Koala or Android SDK Platform-Tools
- JDK 17+
- Android Device running Android 14+ (tested on Android 16 / API 36)

### Build APK
```powershell
# Build Debug APK
./gradlew assembleDebug

# Run Full Unit Test Suite
./gradlew test
```

### Install via ADB
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📚 Verification & Documentation Suite

For exhaustive technical blueprints, formal state machine definitions, and physical hardware verification test matrices, refer to the documentation suite in `docs/`:

- 📐 **[Architecture Blueprint](docs/ARCHITECTURE.md)**: Deep dive into the transaction event bus, concurrency models, and storage reconciliation.
- 📋 **[Technical Specifications](docs/SPECIFICATIONS.md)**: Formal specifications for foreground services, floating geometry, and lifecycle management.
- 🔬 **[Verification & Physical Testing Guide](docs/VERIFICATION_AND_TESTING.md)**: Comprehensive test matrix executed directly on OPPO K13 Turbo 5G (CPH2761) (Android 16).
