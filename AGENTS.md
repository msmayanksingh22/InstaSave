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
  - `androidx.appcompat:appcompat:1.7.0`
  - `androidx.recyclerview:recyclerview:1.3.2`
  - `androidx.viewpager2:viewpager2:1.1.0`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`
  - `com.squareup.okhttp3:okhttp:4.12.0`
- **Current Version**: `versionCode = 10`, `versionName = "1.1.7"`

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

---

## ⚡ Mandatory Tooling Directives

1. **RTK (Rust Token Killer) Mandatory Proxying**:
   - All shell and CLI commands executed in terminal must be routed through `rtk`:
     - `rtk git status`, `rtk git diff`, `rtk git commit -m "..."`, `rtk git push`
     - `rtk ./gradlew test`, `rtk ./gradlew assembleRelease`
     - `rtk ls ...`, `rtk find ...`, `rtk grep ...`
2. **Headroom Context Compression**:
   - For large text outputs, verbose logs, or RAG results, compress via `headroom_compress` before placing in context.
