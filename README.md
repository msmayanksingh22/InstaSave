# InstaSave

> **"Save What Inspires You"**  
> An ultra-reliable, private, high-speed on-device Android utility engineered to save Instagram media directly to your device storage with zero remote dependencies, instant 1-second link resolution, in-app self-updates, and universal persistent thumbnails.

---

## 🔗 Official Repository & Download Links
- **GitHub Repository**: [https://github.com/msmayanksingh22/InstaSave](https://github.com/msmayanksingh22/InstaSave)
- **Direct Release APK (v1.1.4 Build 6)**: [Download app-release.apk](https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/app-release.apk)
- **Release Manifest (OTA Updates)**: [release-manifest.json](https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/release-manifest.json)
- **GitHub Releases Page**: [https://github.com/msmayanksingh22/InstaSave/releases](https://github.com/msmayanksingh22/InstaSave/releases)

---

## 📖 Table of Contents
- [Overview](#-overview)
- [What's New in v1.1.4 (Build 6)](#-whats-new-in-v114-build-6)
- [Core Philosophy & Guarantees](#-core-philosophy--guarantees)
- [System Architecture](#-system-architecture)
- [Key Subsystems & Features](#-key-subsystems--features)
  - [1. Instant 1-Second Link Resolution Engine](#1-instant-1-second-link-resolution-engine)
  - [2. Multi-Item Carousel Extraction (15+ Items)](#2-multi-item-carousel-extraction-15-items)
  - [3. Interactive Media Selection & Background Saving](#3-interactive-media-selection--background-saving)
  - [4. Universal Persistent Thumbnails (Even for Deleted Posts)](#4-universal-persistent-thumbnails-even-for-deleted-posts)
  - [5. In-App Self-Updating System (OTA without PC / USB Debugging)](#5-in-app-self-updating-system-ota-without-pc--usb-debugging)
  - [6. In-App Diagnostics & Log Exporter (Debugging without ADB)](#6-in-app-diagnostics--log-exporter-debugging-without-adb)
  - [7. Production Signing & Security Hardening](#7-production-signing--security-hardening)
- [Project Directory Structure](#-project-directory-structure)
- [Building & Installation](#-building--installation)
- [Verification & Documentation Suite](#-verification--documentation-suite)

---

## 🌟 Overview

**InstaSave** is an advanced Android utility built for modern Android operating systems (Android 14 through Android 16 / API 34–36). It bridges Instagram links and on-screen posts with a high-performance, login-free extraction engine, saving high-resolution photos, 15+ item carousels, and HD reels directly into user-accessible `MediaStore` storage (`Pictures/InstaSave/` by default).

InstaSave operates entirely locally on your physical hardware, eliminating paid APIs, third-party backend servers, analytics tracking, and account logins.

---

## 🚀 What's New in v1.1.4 (Build 6)

- ⚡ **Ultra-Fast 1-Second Link Resolution**: Persistent pre-warmed background WebView engine initializes upon app startup; resolves links and extracts media URLs in under 1 second without cold network page loads.
- 🖼️ **Full 15+ Carousel Extraction**: Deep Vue 3 state inspection (`comp.props?.data`) and DOM expansion auto-load all carousel items without capping at 6.
- ⚡ **Instant Media Selection Sheet**: Passing pre-resolved results directly between activities completely eliminates the previous 10–15s re-resolution freeze (0ms sheet launch).
- 📲 **Detached Background Saving**: Tapping "Download Selected" closes the sheet instantly; downloads run in the background with real-time notification bar progress.
- 🖼️ **Universal Persistent Thumbnails in History**: Low-resolution (~20 KB) cover thumbnails are permanently saved to internal private storage (`filesDir/thumbnails/${shortcode}.jpg`). Posts deleted from device storage maintain their thumbnails; missing thumbnails are backfilled concurrently in 2–3 seconds.
- 🔄 **In-App Self-Updating (OTA)**: Automatic update checks against `release-manifest.json` with one-tap background download and install without requiring USB debugging or a PC.
- 🩺 **In-App Diagnostic Logs**: Built-in rolling log viewer and one-tap clipboard/file sharing allows comprehensive debugging without terminal ADB.
- 🛡️ **Production Release Signing**: Signed with a dedicated 2048-bit RSA production key (`instasave-release.jks`) using APK Signature Scheme V2 and V3; hardened manifest with private component export controls.

---

## 🛡️ Core Philosophy & Guarantees

1. **100% Free & Local Execution**: Zero paid APIs, zero subscriptions, zero remote servers.
2. **MediaStore Authority**: SQLite stores logical history records, while system `MediaStore` is the physical source of truth with automatic background reconciliation.
3. **Decoupled Architecture**: Extraction, download execution, storage writing, and UI layers operate completely asynchronously.
4. **Persistent Private Caches**: Thumbnails are stored in internal private app storage, surviving gallery deletions and device cleanup utilities.

---

## 🏗️ System Architecture

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 ENTRY & INTERACTION LAYER                              │
│                                                                                        │
│   ┌───────────────────────────┐    ┌──────────────────────────┐    ┌────────────────┐  │
│   │ MainActivity              │    │ FloatingTriggerService   │    │ System Share   │  │
│   │ • Clipboard Auto-Capture  │    │ • Movable Overlay Widget │    │ • Direct URL   │  │
│   │ • Manual Link Paste       │    │ • specialUse Foreground  │    │   Intent Send  │  │
│   └─────────────┬─────────────┘    └────────────┬─────────────┘    └────────┬───────┘  │
└─────────────────┼───────────────────────────────┼───────────────────────────┼──────────┘
                  ▼                               ▼                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              COMPOSITE RESOLUTION PIPELINE                             │
│                                                                                        │
│   ┌────────────────────────────────────────────────────────────────────────────────┐   │
│   │ Tier 1: HeadlessWebBridgeResolver (Instant Pre-Warmed Engine)                   │   │
│   │ • Persistent Background WebView in memory from app start                       │   │
│   │ • Desktop Chrome UA (suppresses mobile app installation banners)               │   │
│   │ • Vue 3 Reactive State Inspection + Prototype Event Dispatch                   │   │
│   │ • Auto-Clicks .load-more-trigger to extract 15+ carousel items                 │   │
│   │ • Resolution Time: ~800ms - 1200ms                                             │   │
│   └───────────────────────────────────────┬────────────────────────────────────────┘   │
│                                           │ (Fallback only if Tier 1 empty)            │
│   ┌───────────────────────────────────────▼────────────────────────────────────────┐   │
│   │ Tier 2 & 3: Embed & WebPage SJS Fallbacks (Secondary Tiers)                    │   │
│   └────────────────────────────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                             DOWNLOAD & STORAGE SUBSYSTEM                               │
│                                                                                        │
│   ┌────────────────────────────────┐            ┌──────────────────────────────────┐   │
│   │ MediaSelectionActivity         │            │ InstagramDownloadCoordinator     │   │
│   │ • 0ms sheet launch             │            │ • Bypasses re-resolution         │   │
│   │ • Multi-select item grid       │───────────►│ • Parallel OkHttp streams (3-4x) │   │
│   │ • Background job dispatch      │            │ • MediaStore scoped storage write│   │
│   └────────────────────────────────┘            └─────────────────┬────────────────┘   │
│                                                                   ▼                     │
│                                                 ┌──────────────────────────────────┐   │
│                                                 │ DownloadRepository & SQLite      │   │
│                                                 │ • Permanent private thumbnail    │   │
│                                                 │   filesDir/thumbnails/[id].jpg   │   │
│                                                 │ • MediaStore reconciliation      │   │
│                                                 └──────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 📱 Key Subsystems & Features

### 1. Instant 1-Second Link Resolution Engine
- **Pre-Warmed Headless Engine**: Initialized inside `InstaSaveApp.onCreate()`. The background WebView is fully rendered, hydrated, and ready in memory.
- **Desktop User-Agent**: Configured with `Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/128.0.0.0 Safari/537.36`. Eliminates mobile app promotion popups.
- **Automated Popup Dismissal**: JavaScript proactively strips any modals, overlays, or ads before query injection.

### 2. Multi-Item Carousel Extraction (15+ Items)
- Inspects Vue 3 component proxy state (`comp.props?.data`, `comp.proxy?.data`) and auto-triggers `.load-more-trigger` clicks, ensuring full carousels with 10–15+ items extract completely.

### 3. Interactive Media Selection & Background Saving
- **Single-Flight Resolution**: `MainActivity` passes pre-resolved metadata to `MediaSelectionActivity`, rendering the preview grid in 0 ms.
- **Background Execution**: Tapping "Download Selected" launches downloads via `InstaSaveApp.applicationScope` and immediately closes the sheet (`finish()`). Real-time progress is streamed via Android's notification drawer.

### 4. Universal Persistent Thumbnails (Even for Deleted Posts)
- Lightweight previews (~20 KB) are permanently stored in internal app-private storage: `context.filesDir/thumbnails/${shortcode}.jpg`.
- When photos or reels are deleted from the device gallery, `DownloadHistoryActivity` retains and renders their cover thumbnails offline.
- Missing thumbnails are backfilled using bounded 3-worker parallel coroutines in 2–3 seconds.

### 5. In-App Self-Updating System (OTA without PC)
- Queries `https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/release-manifest.json`.
- Compares remote `versionCode` against the installed version.
- Downloads the release APK in the background and prompts native package installation with one tap.

### 6. In-App Diagnostics & Log Exporter
- In-memory circular log ring buffer capturing up to 1,000 log events.
- In-app log dialog with real-time level filtering (`VERBOSE`, `DEBUG`, `INFO`, `WARN`, `ERROR`).
- One-tap "Copy to Clipboard" and "Share Logs via File" actions for instant debugging without USB debugging or ADB commands.

### 7. Production Signing & Security Hardening
- Signed with official dedicated RSA-2048 production keystore (`instasave-release.jks`) with 10,000 days validity.
- Full APK Signature Scheme V2 and V3 enabled.
- Hardened manifest with `android:exported="false"` on all private activities and receivers.

---

## 🛠️ Building & Installation

### Building the Release APK
```powershell
# Run Unit Test Suite
rtk ./gradlew test

# Assemble Release APK (signed with instasave-release.jks)
rtk ./gradlew assembleRelease
```

### Installation
- **Direct Sideload**: Download [app-release.apk](https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/app-release.apk) directly on your Android phone and install.
- **Via ADB**:
  ```powershell
  adb install -r app-release.apk
  ```

---

## 📚 Documentation Suite
- 📐 **[Architecture Blueprint](docs/ARCHITECTURE.md)**: Deep dive into the headless pre-warmed bridge, parallel downloader, and thumbnail cache.
- 📋 **[Technical Specifications](docs/SPECIFICATIONS.md)**: Formal specifications for background services, database schema, and OTA manifest schema.
- 🔬 **[Verification & Testing Guide](docs/VERIFICATION_AND_TESTING.md)**: Test suites and hardware validation matrix.
- 🤖 **[Master AI Agent Dossier](AGENTS.md)**: Comprehensive consolidated task prompt and engineering guide for AI agents.
