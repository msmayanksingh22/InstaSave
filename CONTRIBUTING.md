# Contributing to InstaSave

Thank you for helping improve **InstaSave**! Contributions that improve reliability, Android compatibility, accessibility, or UI polish are welcome.

## Development Setup

1. Fork and clone the repository:
   ```bash
   git clone https://github.com/<your-username>/InstaSave.git
   cd InstaSave
   ```
2. Open the project in Android Studio (JDK 17, Android SDK Platform 36) or use the included Gradle wrapper (`./gradlew`).
3. Create a focused feature or fix branch from `main`:
   ```bash
   git checkout -b fix/my-change
   ```

## Architectural & Quality Guidelines

- **100% Free & Local**: Never introduce paid APIs, analytics SDKs, or external proxy backends.
- **Session Privacy**: Cookies inside Browse Feed must stay strictly within Android `WebView` `CookieManager` — never export, log, or exfiltrate session credentials.
- **Responsive UI (`360dp` Baseline)**: Verify layouts on compact `360dp` screen widths so buttons and labels never ellipsize or wrap unexpectedly.
- **Keep Core Layers Clean**: Extraction/download packages (`resolver`, `download`, `engine`) should remain decoupled from UI classes.

## Verification Checklist Before Opening a PR

Run all unit tests and release lint locally:

```bash
./gradlew testReleaseUnitTest lintVitalRelease
```

If modifying UI or Android interactions, also test on a physical Android device or emulator (`minSdk 26`, `targetSdk 36`) and attach screenshots or screen recordings to your pull request.
