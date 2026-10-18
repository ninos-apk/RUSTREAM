# Playlist import progress — 18 October 2026

Scope: first-time and explicit playlist imports, their inline loading-screen UI, and the measurements feeding it.

## Loading state feedback (better-ui)

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, fixed | `app/src/main/java/de/iptv/tv/MainActivity.kt:952`, `app/src/main/java/de/iptv/tv/ImportProgress.kt:21` | Import showed changing status text; source-entry forms could obscure the startup indicator. | The existing loading screen shows the current stage, a progress bar and a percentage or bytes-read count. No progress dialog or Cancel button is shown. | Consistent loading feedback across startup, local files, URL forms, and source replacement. |
| LOW, fixed | `app/src/main/java/de/iptv/tv/StartupPane.kt:50`, `app/src/main/java/de/iptv/tv/ImportProgress.kt:35` | Turning off animations hid the startup bar. | Measured progress stays visible and updates immediately. Unknown totals use static stage text with animations off and an indeterminate bar when enabled. | State is always conveyed in text, including when motion is disabled. |

## What the bar measures

1. Reading/downloading: bytes actually written compared with a known file size or HTTP content length. Unknown lengths show bytes read and no invented percentage. Xtream spans multiple endpoints, so it shows cumulative bytes rather than treating one response as the whole download.
2. M3U processing: bytes consumed by the Rust parser. Buffered read-ahead does not advance the counter. It stays below 100% while indexes and the catalog commit remain. Xtream processing has no known denominator and remains indeterminate.
3. Finalizing: index construction, catalog verification and opening. These stages are named without an invented time estimate.

Percentages describe the current step, not a weighted estimate of total import time. The three-step caption makes stage changes explicit. Download callbacks are throttled to roughly 100ms; a separate atomic JNI snapshot lets Kotlin poll native processing without waiting on the import's engine mutex. No source addresses or credentials are exposed in progress text.

Progress lives directly on the existing logo/loading screen. URL and Xtream forms hide after valid submission so that they do not cover it; failures restore the form with its inputs and error message. The library returns after its first page is ready. There is no extra progress window and no Cancel button. Activity teardown retains the existing coroutine/network/native cleanup; unsuccessful imports do not commit a partial catalog. EPG and background-refresh progress cannot overwrite a foreground playlist import. The bar and captions do not add remote focus stops.

## Verification

- Rust workspace: 81 passed, one existing benchmark ignored. Includes consumed-vs-prefetched byte tracking and reading progress while the engine mutex is held (`artifacts/import-rust-tests.log`). Rust formatting passed.
- Final Android build and lint passed (`artifacts/import-inline-build.log`); all 51 JVM unit tests passed (`artifacts/import-build.log`).
- All three final inline progress emulator tests passed (`artifacts/import-inline-progress-tests.log`): known/unknown HTTP size, measured native processing, actual URL-form submission and handoff to the loading screen, no Cancel/progress dialog, loading until the first channel is ready, and static feedback with animations disabled. The English URL-field selector was corrected to use its localized resource before the successful rerun.
- All 10 existing `UiDesignTest` and `TvEndToEndTest` checks passed on the final inline implementation, covering remote playback/Refresh, paging, source imports, redirects, Xtream, XMLTV, and retry behavior (`artifacts/import-inline-tests.log`).
- Package verification passed for ARM64, ARMv7 and x86_64: 16-KiB ELF alignment and every JNI export, including `nativeImportProgress` (`artifacts/import-package-check.log`).
- Runtime screenshots: `artifacts/import-progress-download.png`, `artifacts/import-progress-processing.png`. Installable debug APK: `artifacts/RUSTREAM-IPTV-navigation-debug.apk`.
- Not verified: TalkBack speech output, physical TVs, or a precise remaining-time estimate (none is shown). Browser animation tooling does not apply to this native Android view.

Approve — inspected import source paths and tested emulator flows only. No unresolved HIGH UI-polish findings in this scope.
