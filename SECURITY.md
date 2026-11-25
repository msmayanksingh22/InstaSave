# Security & Privacy Policy

## Local-First Privacy Architecture

InstaSave is designed to run completely on-device without remote analytics, cloud accounts, or tracking servers:

- **Sandboxed Instagram Session**: Optional Instagram sign-in inside **Browse Feed** stays strictly inside Android's system `WebView` `CookieManager`. Cookies and credentials are never extracted, stored outside `WebView`, logged, or transmitted to third-party servers.
- **Verified Self-Updates (OTA)**: `HardenedApkInstaller` and `AppUpdateManager` validate downloaded update APKs against the running app's signing certificates and the SHA-256 checksum before launching the Android system PackageInstaller.
- **In-App Diagnostic Logs**: `AppLogger` stores events only in an in-memory rolling buffer and exports logs solely when you explicitly choose **Copy** or **Share**.

## Reporting a Vulnerability

If you discover a security vulnerability, privilege bypass, signature check weakness, or privacy issue:

1. **Do not** disclose exploit details publicly in a public issue before a fix is ready.
2. Report privately via [GitHub Security Advisories](https://github.com/msmayanksingh22/InstaSave/security/advisories/new).
3. Include steps to reproduce, affected version (`Settings -> Check for Updates`), and any relevant diagnostics.
