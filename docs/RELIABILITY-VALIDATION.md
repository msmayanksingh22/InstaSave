# Reliability fixes and release checks

This change keeps the existing UI theme edits and focuses on selection behavior,
resolution latency, thumbnail recovery, and truthful download completion.

- Carousel embeds now try native GraphQL before the WebView bridge.
- Composite resolver cancellation propagates instead of starting fallback work.
- Blocking GraphQL and SJS calls have explicit total network deadlines.
- Thumbnail memory keys match across prefetch and view binding. Prefetch runs
  concurrently within the existing four-request limit; damaged disk entries are
  fetched again and temporary files are unique and cleaned up.
- Selection updates avoid tile animations and stale recycled thumbnails.
- Downloads request identity encoding and fail verification if MediaStore cannot
  publish or reopen the saved file.

## Device acceptance required before publication

No device was connected during this change. Do not infer launch readiness from
unit tests or a successful APK build. Check on a physical device:

1. Resolve public photos, Reels, 15+ item carousels, and opaque share links.
2. Check thumbnail scrolling, selection toggles, rotation, large fonts, and
   small-screen layouts across Home, Gallery, History, and Settings.
3. Save a selection, leave the app, and verify notifications and Gallery files.
4. Interrupt Wi-Fi, retry, and check for accurate failures and no pending files.
5. Delete gallery media and confirm private history thumbnails remain visible.
6. Test an OTA upgrade signed with the installed production certificate.

The release workflow triggers on `v*.*.*` tags or manual dispatch, not ordinary
branch pushes. Before publication, increment the app version and generate the
manifest/checksum from the final APK through the release workflow. Existing lint
baseline entries remain technical debt; passing baseline-aware lint does not
mean the application has no lint findings.
