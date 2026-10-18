# Remote navigation and header polish — 18 October 2026

Scope: the Android TV preview player, the programme details and connection footer, category/channel labels, and the toolbar brand. This is a focused review, not a whole-app accessibility certification.

## Focus order (better-accessibility)

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, fixed | `app/src/main/java/de/iptv/tv/PlayerPane.kt:237`, `app/src/main/java/de/iptv/tv/MainActivity.kt:352` | Up from the preview returned to the list on its left; Down on the control bar stayed on the same button. | Up reaches Settings; Settings → Down returns to the preview. Down goes through controls → programme title → guide → Refresh. Up reverses the details path; Up from controls returns to the video. | WCAG 2.4.3, Focus Order: remote navigation follows the vertical arrangement of the media area. |
| LOW, fixed | `app/src/main/java/de/iptv/tv/ConnectionDetails.kt:19`, `app/src/main/java/de/iptv/tv/ConnectionDetails.kt:44` | Every statistic was a focusable stop despite having no action. | Statistics are native, non-focusable text. Guide → Down reaches enabled Refresh directly; Refresh → Up or Left returns to the guide. Disabled Refresh keeps focus on the guide. | Focus-order usability: only the footer action participates in D-pad navigation; text remains available to accessibility services. |

Left from preview still returns to the remembered library entry. Fullscreen keeps Down within the controls; Up from controls returns to the video. Native focus backgrounds and the preview outline remain visible. The toolbar image has the app name as its accessible description and does not add a D-pad stop.

## Typography (better-typography)

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| LOW, fixed | `app/src/main/java/de/iptv/tv/MainActivity.kt:1478` | Category and channel list labels were 10sp. | List labels are 11sp. Poster titles remain 12sp; movie/series metadata remains 8sp. | The requested type hierarchy improves list readability while retaining the existing metadata density. Android sp continues to honor the system font scale. |

The header replaces the stacked app-name text with the existing `rustream_startup_logo` at 56×44dp. Its outer background is transparent (PNG corner alpha 0); no new branding artwork was generated.

## Footer layout (better-layout)

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| LOW, fixed | `app/src/main/java/de/iptv/tv/ConnectionDetails.kt:24`, `app/src/main/java/de/iptv/tv/ConnectionDetails.kt:34` | A full-size Refresh button occupied the left side of a separate footer line. | A compact 11sp pill button with a 28dp minimum height shares the statistics row at the trailing edge, above the right-aligned developer credit. Statistics take the remaining width and wrap between metrics at larger font sizes. | Grouping and alignment: the playback action sits beside its status, stays visible, and does not consume another full control row. |

Refresh uses a wrap-content width/height and a distinct filled focusable surface. The statistics are unrestricted wrapping text rather than an unfocusable scroll region. Layout uses a weighted text column, a logical start margin, and trailing credit alignment. This prevents values becoming hidden after removal of statistic focus stops.

## Installation language

`app/src/main/java/de/iptv/tv/AppLanguage.kt:14` now defaults to English when no valid language preference exists. Saved German or English choices continue to override the default. Legacy UI test fixtures explicitly select German and restore the previous preference; a separate test clears the language preference to verify actual English startup and then checks an explicit German choice across relaunch.

## App Info

The About dialog now contains the supplied English introduction, Rust/Kotlin playback description, independent-development statement, support section, PayPal email, feedback invitation, and closing thanks. German has a corresponding translation. Version is taken from `BuildConfig.VERSION_NAME` (0.5.3 for this release), and the developer is Ninos Oshana. The logo uses the same transparent `rustream_startup_logo` as the header.

Paragraphs use 14sp text, 1.45 line spacing, the requested bold emphasis, and a native scrolling region. The PayPal address is a remote-reachable button opening the supplied `mailto:` destination. If the TV has no email app, a dialog offers to copy the address. Dismissing About restores focus to its entry point.

App Info validation: build and Android lint passed (`artifacts/about-build.log`); all 3 existing branding/language tests passed (`artifacts/about-device-tests.log`). Remote navigation reached the email button, and a separate English walkthrough reached every closing paragraph and the thank-you text. Email delivery and the copy fallback were not exercised. The English preview is saved as `artifacts/about-english-preview.png`.

## Verification

- Build, debug APK, test APK, and Android lint: passed (`artifacts/footer-build.log`).
- JVM unit tests: 50 passed, no failures or skips.
- Google TV API 36 emulator: all 10 tests passed in `UiDesignTest`, `NavigationStateTest`, `BrandingTest`, and `ContentVisualTest` (`artifacts/footer-device-tests.log`).
- Real D-pad checks passed for preview → Settings and back, controls → details → Refresh and back, skipping all non-actionable statistics, unavailable Refresh, remembered channel focus, movie playback, and fullscreen containment. Refresh reconnect, hold-to-seek, English first startup, and saved German across relaunch also passed.
- Runtime font assertions verified category/channel names at 11sp and movie card metadata at 8sp. Normal and 200% system font layouts passed the existing visibility checks across Live, movie grids/details, series grids/details, and fullscreen. Series card metadata uses the same unchanged 8sp constructor.
- Layout assertions passed at normal and 200% system font size: the statistics and Refresh share a row, the full Refresh button remains visible, all statistic lines remain visible, and the developer credit sits below Refresh with a shared trailing edge.
- Final footer screenshots: `artifacts/footer-refresh-preview.png`, `artifacts/footer-live.png`, and `artifacts/footer-large-text-live.png`.
- Updated installable debug APK: `artifacts/RUSTREAM-IPTV-debug.apk`; release 0.5.3 also exports the optimized `artifacts/RUSTREAM-IPTV-PLAYER-0.5.3.apk` with the existing development signing key.
- Not verified: TalkBack speech output, physical TV/remote behavior, RTL mirroring, other viewport widths, and application areas outside this change. The app's supported UI languages are English and German; browser CSS/ARIA and 320px reflow checks do not apply to this native Android TV UI.

Approve — reviewed code paths and the emulator flows above only. No unresolved HIGH findings in this scope.
