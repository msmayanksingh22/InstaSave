# AGENTS.md — Master AI Agent Dossier & Consolidated Project Blueprint

> **Notice for AI Assistants & Autonomous Agents**:  
> This file contains the complete, authoritative specification, architectural blueprint, consolidated user requirements, and technical handover documentation for **InstaSave**. When continuing work on this codebase, you must adhere strictly to all architectural boundaries, safety invariants, and user directives defined below.

---

## 📋 Consolidated Master User Prompt

If an AI agent is starting a session or tasked with continuing work on InstaSave, the following single consolidated prompt encapsulates all user requirements, bug fixes, and feature specifications:

```markdown
You are an expert principal Android systems engineer and architect working on InstaSave, an ultra-reliable, private, commercial-grade Android utility for downloading Instagram media (photos, 15+ item carousels, and reels).

### Core Goals & User Requirements:
1. **Ultra-Fast 1-Second Link Resolution**:
   - Link resolution must be lightning-fast (load in ~1 second).
   - Eliminate headless web bridge timeouts, cold-start stalls, and rate limits.
   - Do NOT rely on fragile mobile WebViews that trigger "Download our FastDL app" popups or ads.
   - Prioritize the working resolution engine as Tier 1, pre-warming the background WebView on application startup (`InstaSaveApp.onCreate()`).

2. **Instant Media Selection & Preview Grid**:
   - When a multi-item carousel post is resolved, opening the "Select Media" sheet must be instantaneous (0 ms delay).
   - Media thumbnails inside the selection grid must render immediately without freezing or waiting for sequential network downloads.
   - Single-flight resolution: Never re-resolve a link that has already been resolved by the Control Center / clipboard coordinator.

3. **Full 15+ Carousel Extraction**:
   - Carousels must never be truncated at 6 items.
   - Inspect Vue 3 reactive state and automate DOM expansion (.load-more-trigger) to reliably extract all 10 to 15+ images and videos.

4. **Detached Background Saving (Super Fast & Non-Blocking)**:
   - When the user taps "Download Selected", do NOT keep them trapped on the screen watching progress or re-resolving the post.
   - Pass pre-resolved media models directly to the download coordinator (0 ms re-resolution bypass).
   - Display a quick Toast ("Saving X items to Gallery in background..."), transfer execution to the application background scope, and close the selection activity immediately.
   - Stream real-time progress through Android's system notification drawer and save files via parallel OkHttp streams (3–4x concurrent downloads).

5. **Universal Persistent Thumbnails in History & Archive**:
   - In History & Archive, cover thumbnails must display for ALL posts, including posts whose original files have been deleted from device storage.
   - For thumbnails, store small, low-resolution images (~20 KB) in internal private storage (`context.filesDir/thumbnails/${shortcode}.jpg`). Private thumbnails must be immune to gallery deletions and device cleaners.
   - If older records are missing thumbnails, backfill them concurrently in the background within 2–3 seconds without blocking the UI.

6. **In-App Self-Updating (OTA without USB Debugging)**:
   - Provide in-app software updating via Settings -> "Check for Updates" querying release-manifest.json on GitHub.
   - When an update is available, download the release APK in the background and launch the native package installer without requiring a PC or USB debugging.

7. **In-App Diagnostic Logs (Debugging without ADB)**:
   - Maintain an in-memory circular log ring buffer (`AppLogger.kt`).
   - Provide an in-app viewer with level filters and one-tap "Copy to Clipboard" and "Share Logs via File" actions so issues can be diagnosed remotely without USB debugging.

8. **Google Play Protect Security Hardening & Authorization**:
   - Release APKs must be signed using our official dedicated production keystore (`instasave-release.jks`) using APK Signature Scheme V2 and V3.
   - Remove or avoid declaring invasive dropper/spyware permissions (e.g. `REQUEST_INSTALL_PACKAGES`, `BIND_ACCESSIBILITY_SERVICE`) that trigger Google Play Protect's automated malware blocking.
   - Keep all internal activities and receivers private (`android:exported="false"`).

9. **Strict Non-Negotiable Constraints**:
   - ONLY FREE solutions — Never suggest or introduce paid APIs, paid services, or external subscriptions.
   - Route all CLI commands through RTK (`rtk git ...`, `rtk ./gradlew ...`).
   - Use Markdown links with `[basename](file:///path)` format.
```

---

## 🔗 Official Repository & Artifact Links

| Resource | Official URL |
| :--- | :--- |
| **GitHub Git Repository** | `https://github.com/msmayanksingh22/InstaSave.git` |
| **GitHub Web UI** | `https://github.com/msmayanksingh22/InstaSave` |
| **Direct Raw APK** | `https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/app-release.apk` |
| **Release Manifest (OTA)** | `https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/release-manifest.json` |
| **GitHub Releases Page** | `https://github.com/msmayanksingh22/InstaSave/releases` |

---

## 🛠️ Technical Stack & Environment

- **Language**: Kotlin 1.9.x
- **Compile SDK**: 36 (Android 16 ready)
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 36
- **Build System**: Gradle 8.14.3 / Android Gradle Plugin (AGP) 8.7.x
- **JDK Target**: Java 17
- **Key Libraries**:
  - `androidx.core:core-ktx:1.15.0`
  - `androidx.core:core-splashscreen:1.0.1`
  - `androidx.appcompat:appcompat:1.7.0`
  - `androidx.recyclerview:recyclerview:1.3.2`
  - `androidx.viewpager2:viewpager2:1.1.0`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
  - `com.squareup.okhttp3:okhttp:4.12.0`
- **Current Version**: `versionCode = 16`, `versionName = "1.2.4"`

---

## 🔑 Production Keystore & Signing Configuration

All release builds must be signed with the dedicated production keystore:
- **Keystore Path**: `app/instasave-release.jks`
- **Store Password**: `InstaSaveRelease2026!`
- **Key Password**: `InstaSaveRelease2026!`
- **Key Alias**: `instasave`
- **Algorithm**: RSA 2048-bit, 10,000 days validity
- **Certificate DN**: `CN=InstaSave Software, OU=Mobile Security, O=InstaSave Global, L=San Francisco, ST=California, C=US`
- **Signature Schemes**: APK Signature Scheme v1, v2, and v3 enabled.

---

## 🏗️ Architecture & Key Subsystems Breakdown

### 1. Application Life Cycle & Engine Pre-Warming
- **File**: `app/src/main/java/com/instadown/assist/InstaSaveApp.kt`
- Extends `android.app.Application`.
- Provides global `InstaSaveApp.applicationScope` for un-cancelable background downloads.
- Calls `HeadlessWebBridgeResolver.preWarm(this)` in `onCreate()`.

### 2. High-Speed Headless Web Bridge Resolver
- **File**: `app/src/main/java/com/instadown/assist/resolver/HeadlessWebBridgeResolver.kt`
- Implements `InstagramResolver`.
- Manages pre-warmed background WebKit instance.
- Spoofs Desktop Chrome User-Agent (`Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0.0.0 Safari/537.36`) to suppress mobile app banners.
- Injects prototype value setter, dispatches `input`/`change` events, and triggers Vue 3 `proxy.handleSearchButtonClick()`.
- Automates `.load-more-trigger` clicks and reads Vue component state to retrieve all 15+ carousel items.
- Extracts lightweight thumbnail previews (~20 KB) for instant tile rendering.

### 3. Composite Resolution Hierarchy
- **File**: `app/src/main/java/com/instadown/assist/resolver/CompositeInstagramResolver.kt`
- **Tier 1 (Primary)**: `HeadlessWebBridgeResolver` (~800 ms – 1,200 ms).
- **Tier 2 (Fallback)**: `EmbedFallbackResolver` (Fast 2.5s timeout).
- **Tier 3 (Fallback)**: `WebPageSjsResolver` (Fast 2.5s timeout).

### 4. Interactive Media Selection Activity
- **File**: `app/src/main/java/com/instadown/assist/ui/MediaSelectionActivity.kt`
- Accepts `preloadedResult` via `MediaSelectionActivity.start(context, url, preloadedResult)`.
- Renders grid in 0 ms when preloaded.
- Tapping "Download Selected" passes `preResolvedResult` to coordinator, dispatches job to `InstaSaveApp.applicationScope`, and calls `finish()` immediately.

### 5. Universal Persistent Thumbnail Cache
- **Files**:
  - `app/src/main/java/com/instadown/assist/util/MediaThumbnailLoader.kt`
  - `app/src/main/java/com/instadown/assist/ui/DownloadHistoryActivity.kt`
  - `app/src/main/java/com/instadown/assist/data/db/DownloadRepository.kt`
- Thumbnails saved to: `context.filesDir/thumbnails/${shortcode}.jpg`.
- Two-tier cache: Memory `LruCache<String, Bitmap>` + Disk file cache.
- Decodes using `Bitmap.Config.RGB_565` for 2x faster decode speed and 50% RAM savings.
- `DownloadHistoryActivity.backfillThumbnailsInBackground` runs parallel bounded coroutines (`Semaphore(3)`) to backfill missing thumbnails in 2–3 seconds.

### 6. In-App Software Update Subsystem
- **Files**:
  - `app/src/main/java/com/instadown/assist/util/AppUpdateManager.kt`
  - `release-manifest.json`
- Queries `release-manifest.json` on GitHub.
- Compares `remoteVersionCode > localVersionCode`.
- Downloads release APK and prompts native PackageInstaller.

### 7. In-App Diagnostic Logs
- **Files**:
  - `app/src/main/java/com/instadown/assist/util/AppLogger.kt`
  - `app/src/main/java/com/instadown/assist/ui/SettingsActivity.kt`
  - `app/src/main/res/layout/dialog_diagnostic_logs.xml`
- Rolling in-memory ring buffer (up to 1,000 log events).
- One-tap clipboard copy and file share.

### 8. Tier 0 Native OkHttp GraphQL & Socket Pre-warming (<400ms)
- **Files**:
  - `app/src/main/java/com/instadown/assist/resolver/NetworkPrewarmer.kt`
  - `app/src/main/java/com/instadown/assist/resolver/DirectOkHttpInstagramResolver.kt`
  - `app/src/main/java/com/instadown/assist/resolver/CompositeInstagramResolver.kt`
- DNS pre-resolution via `DnsResolver` (API 29+) and TLS 1.3 / HTTP/2 connection pooling pre-warmed on app startup.
- Queries unauthenticated GraphQL endpoint with fast fallback on 302/401/429 to Tier 1 headless bridge.

### 9. Zero-Allocation Media Streaming Pipeline
- **Files**:
  - `app/src/main/java/com/instadown/assist/download/ZeroAllocMediaPipeline.kt`
- Off-heap direct NIO buffer pool (`DirectBufferPool`, 64 KB).
- Streams from OkHttp source directly into `MediaStore` `FileChannel` without JVM heap byte array allocations.
- Atomic rollback eliminates corrupt zero-byte records on abort/network failure.

### 10. OEM Durability & Accessibility Watchdog
- **Files**:
  - `app/src/main/java/com/instadown/assist/service/AccessibilityBinderWatchdog.kt`
  - `app/src/main/java/com/instadown/assist/service/InstaAccessibilityService.kt`
- Tracks binder state to distinguish `HEALTHY`, `ZOMBIE_UNBOUND`, and `DISABLED_BY_USER`.
- Provides OEM-specific autostart settings resolution (HyperOS, ColorOS, One UI).
- Complies with Android 15/16 Foreground Service restrictions via `specialUse`.

### 11. Motion Physics, Predictive Back & Shared Element Transitions
- **Files**:
  - `app/src/main/java/com/instadown/assist/overlay/SpringFloatingDockController.kt`
  - `app/src/main/java/com/instadown/assist/ui/PredictiveBackExtensions.kt`
  - `app/src/main/java/com/instadown/assist/ui/SharedMediaTransition.kt`
- Velocity-aware spring-damper docking for floating overlay using AndroidX `DynamicAnimation`.
- Predictive back swipe progress physics on Android 14–16 (`OnBackPressedCallback` / `BackEventCompat`).
- Bidirectional shared element transition between Gallery grid and Media Viewer.

### 12. Automated CI/CD Release Workflow
- **File**: `.github/workflows/release.yml`
- Automated release workflow with dynamic `aapt2` truth metadata extraction, SHA-256 calculation, and GitHub Releases publication.

### 13. Dynamic Island-Style Floating Progress HUD Pill
- **Files**:
  - `app/src/main/java/com/instadown/assist/overlay/FloatingHudState.kt`
  - `app/src/main/java/com/instadown/assist/overlay/FloatingHudPillView.kt`
  - `app/src/main/java/com/instadown/assist/overlay/FloatingHudOverlayController.kt`
- Floats at the top center of the screen directly beneath camera cutouts (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`).
- Uses zero-focus-theft WindowManager flags (`FLAG_NOT_FOCUSABLE`, `FLAG_NOT_TOUCH_MODAL`, `FLAG_LAYOUT_IN_SCREEN`) and Android 12+ `FLAG_BLUR_BEHIND`.
- Powered by reactive `FloatingHudBus` (StateFlow) providing instant visual feedback (link detected, resolving, downloading progress ring, saved to gallery) across all apps without screen blocking.

### 14. Smart Differential Re-Download Selection Bottom Sheet
- **Files**:
  - `app/src/main/java/com/instadown/assist/ui/ReDownloadSelectionBottomSheet.kt`
  - `app/src/main/res/layout/bottom_sheet_redownload_selection.xml`
  - `app/src/main/res/layout/item_redownload_selection.xml`
- Inspects MediaStore and `IS_TRASHED` (API 30+) to detect `DOWNLOADED`, `MISSING`, or `CORRUPTED` (0 KB) files.
- Displays interactive bottom sheet with color badges and pre-selects missing/corrupted files for 1-tap restore.
- Restores only selected indices (`selectedIndices`), eliminating data waste and duplicate files.

### 15. Sub-300ms Thumbnail Grid & Extractor
- **Files**:
  - `app/src/main/java/com/instadown/assist/resolver/DirectOkHttpInstagramResolver.kt`
  - `app/src/main/java/com/instadown/assist/util/MediaThumbnailLoader.kt`
- Extracts GraphQL `thumbnail_resources` targeting ~320px (~25 KB) instead of 3 MB `display_url` images.
- Employs `Semaphore(4)` concurrency limit, in-flight deduplication via `ConcurrentHashMap<String, Deferred<Bitmap?>>`, disk LRU cache, and `RGB_565` bitmap configuration for silky smooth 120 Hz grid scrolling.

### 16. Modern Material 3 Settings UI with Hero Storage & Watchdog
- **Files**:
  - `app/src/main/res/layout/activity_settings.xml`
  - `app/src/main/java/com/instadown/assist/ui/SettingsActivity.kt`
- Edge-to-edge layout with Hero Storage Card calculating dynamic storage footprint and offering 1-tap MediaStore re-indexing and cache cleaning.
- Real-time Accessibility Binder Watchdog telemetry card reflecting active service health, OEM kills (`ZOMBIE_UNBOUND`), and direct settings resolution.
- Modern categorized preference cards for Floating Assistant & Island HUD, Downloads & Audio Extraction, and OTA Updates.

### 17. Native Lossless Reel Audio Extractor (.m4a)
- **Files**:
  - `app/src/main/java/com/instadown/assist/download/ReelAudioExtractor.kt`
  - `app/src/main/java/com/instadown/assist/ui/MediaViewerActivity.kt`
  - `app/src/main/res/layout/activity_media_viewer.xml`
- Uses Android's native `MediaExtractor` and `MediaMuxer` (API 26+) to losslessly demux AAC audio from MP4 Reels directly into `MediaStore.Audio.Media` (`Music/InstaSave/`).
- 0 KB added APK bloat, 0 audio generation loss, executed in <150 ms. Available via 1-tap in Media Viewer and auto-extraction preference.

### 18. Storage-Only Media Deletion & Deep Archive Purge Architecture
- **Files**:
  - `app/src/main/java/com/instadown/assist/data/db/DownloadRepository.kt` (`deleteDeviceStorageOnly`)
  - `app/src/main/java/com/instadown/assist/ui/HistoryItemOptionsBottomSheet.kt`
- Implements decoupled storage deletion: removes physical media from `MediaStore` to free up phone storage while completely preserving the history database record, author username, post shortcode, and private cover thumbnail (`filesDir/thumbnails/${shortcode}.jpg`).
- Switches item state to `EXTERNAL_DELETED`, enabling 1-tap differential re-download anytime.
- Deep-tucks permanent history deletion inside an "Archive Management (Danger Zone)" accordion with explicit confirmation, preventing accidental history purges.

### 19. Modern Glassmorphic Options & Actions Bottom Sheet
- **Files**:
  - `app/src/main/res/layout/bottom_sheet_history_item_options.xml`
  - `app/src/main/java/com/instadown/assist/ui/HistoryItemOptionsBottomSheet.kt`
  - `app/src/main/res/layout/item_history_card.xml` (`btnMoreOptions`)
  - `app/src/main/java/com/instadown/assist/ui/DownloadHistoryAdapter.kt`
- Replaces legacy black-and-white `AlertDialog` popups with a Material 3 dark glassmorphic bottom sheet (`#161422`).
- Features rich post cover thumbnail preview, live on-device vs deleted status badge, 1-tap "Free Up Phone Storage", "Open in Instagram", "Copy Link", and expandable Danger Zone.

### 20. Zero-Flicker Native Splash Screen (API 31-36 Compliant)
- **Files**:
  - `app/src/main/res/values/themes.xml` (`Theme.InstaSave.Starting`)
  - `app/src/main/java/com/instadown/assist/ui/MainActivity.kt` (`installSplashScreen`)
  - `app/src/main/AndroidManifest.xml`
- Fully adheres to Android 12–16 window splash specifications using `androidx.core:core-splashscreen`.
- Eliminates legacy trampoline `SplashActivity` double-window flashes and maintains native Predictive Back physics.
- Directly routes incoming external `ACTION_SEND` share sheets into `MainActivity` in 0 ms with unstripped `ClipData` URI grants.

### 21. Hardened Enterprise Self-Updater with EMA Rate Smoothing
- **Files**:
  - `app/src/main/java/com/instadown/assist/util/HardenedApkInstaller.kt`
  - `app/src/main/java/com/instadown/assist/util/EmaTransferEstimator.kt`
  - `app/src/main/java/com/instadown/assist/util/AppUpdateManager.kt`
  - `release-manifest.json`
- Protects users from APK tampering by cryptographically checking APK signing certificates against the running app's certificates.
- Complies with Android Enterprise restrictions (`UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES`).
- Features cross-process `ClipData.newRawUri` grants for dual-installer OEM environments (e.g. Xiaomi HyperOS).
- Smooths network bursts and download progress rendering with an Exponential Moving Average filter ($\alpha = 0.25$, 250 ms throttling).

### 22. Android 16 Scoped Storage Deletion Consent Coordinator
- **Files**:
  - `app/src/main/java/com/instadown/assist/data/db/StorageDeletionCoordinator.kt`
  - `app/src/main/java/com/instadown/assist/data/db/DownloadRepository.kt`
  - `app/src/main/java/com/instadown/assist/ui/DownloadHistoryActivity.kt`
  - `app/src/main/java/com/instadown/assist/ui/MediaViewerActivity.kt`
- Seamlessly manages Android 11–16 MediaStore ownership loss (e.g. after reinstalls or restores).
- Employs `MediaStore.createDeleteRequest` targeting valid Images/Video/Audio tables to request OS user consent without throwing crashes on download volumes.
- Features `SelfDeleteGuard` to prevent self-deletion race conditions with `reconcileWithMediaStore()` `ContentObserver` callbacks.
- Atomically finalizes DB row updates to `EXTERNAL_DELETED` only for confirmed deleted files.

### 23. Bulletproof Dynamic Island HUD State Machine
- **Files**:
  - `app/src/main/java/com/instadown/assist/overlay/ResilientHudStateMachine.kt`
  - `app/src/main/java/com/instadown/assist/overlay/FloatingHudOverlayController.kt`
  - `app/src/main/java/com/instadown/assist/overlay/FloatingHudPillView.kt`
  - `app/src/main/java/com/instadown/assist/download/InstagramDownloadCoordinator.kt`
- Implements monotonic `operationId` lease tokens via `AtomicLong` to mathematically prevent stale or out-of-order coroutines from clobbering newer HUD states.
- Declares strict per-state finite TTL watchdogs (`LinkDetected = 4s`, `Resolving = 10s`, `Downloading = 18s stall timeout`, `Success = 2.4s`, `Error = 3.5s`) running inside `collectLatest` so continuous progress resets the watchdog while hangs or silent drops self-clean automatically back to `Hidden`.
- Wraps coordinator execution in structured `runHudTrackedOperation` with `NonCancellable` terminal emission guards for timeouts, cancellations, and unexpected errors.
- Provides 1-tap manual escape hatch dismissing the overlay immediately across any application without stealing window focus.

### 24. Fast-Fail Circuit Breakers & Multi-Pattern Media Extraction
- **Files**:
  - `app/src/main/java/com/instadown/assist/resolver/CompositeInstagramResolver.kt`
  - `app/src/main/java/com/instadown/assist/resolver/EmbedFallbackResolver.kt`
  - `app/src/main/java/com/instadown/assist/resolver/DirectOkHttpInstagramResolver.kt`
- `TierCircuitBreaker` tracks unauthenticated challenges (HTTP 302 login redirects, 401, 429 rate limits) and bypasses stalled tiers in <1ms with a 10-minute cooldown, avoiding IP throttling and battery drain.
- Prioritizes fast 250ms captioned embed extraction before slow headless WebViews, extracting high-res photos and Reels instantly.
- Expanded regex patterns for modern Instagram video tags (`<video src="...">`), JSON `video_versions`, and CDN MP4 links, complete with unicode and slash unescaping (`\/`, `\u0026`).
- Defers multi-item carousels to `HeadlessWebBridgeResolver` for full 15+ item DOM and Vue state extraction.

---

## ⚡ Mandatory Tooling Directives

1. **RTK (Rust Token Killer) Mandatory Proxying**:
   - All shell and CLI commands executed in terminal must be routed through `rtk`:
     - `rtk git status`, `rtk git diff`, `rtk git commit -m "..."`, `rtk git push`
     - `rtk ./gradlew test`, `rtk ./gradlew assembleRelease`
     - `rtk ls ...`, `rtk find ...`, `rtk grep ...`
2. **Headroom Context Compression**:
   - For large text outputs, verbose logs, or RAG results, compress via `headroom_compress` before placing in context.
