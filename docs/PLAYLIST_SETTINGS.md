# Playlist wizard and Settings

Settings now groups its options into **Playlists**, **Playback**, and **App**.

- **Add playlist** opens a wizard with **Add file**, **Add URL**, and **Xtream sign-in**. Each method has a short explanation and opens the existing file chooser or import form.
- **Manage playlists** replaces **Manage sources**. Existing playlists can still be loaded or removed. With no saved playlists, the empty state offers **Add playlist**.
- The TMDB settings and manual EPG import entries are hidden from Settings. Automatic metadata and the existing guide-loading behavior are preserved.

Cancelling a URL/Xtream form or the file chooser returns to the wizard with that method focused. Back from the wizard returns to Settings with Add playlist focused. Successful submissions continue through the existing import/progress flow. Devices without a file picker show an explanation before returning to the wizard.

Settings uses a side panel; the wizard uses a wider, centered dialog. Both are limited to the available display area. Controls wrap and grow with translated text. Group headings and gaps carry the hierarchy. The scrolling area has a persistent scrollbar, and Close/Back remains outside it. On the wizard, the heading and explanation scroll with the options so that large text cannot consume the action viewport. Layout uses relative padding and START/END gravity for RTL.

## Layout review

| Severity | Location | Before | After | Why |
| --- | --- | --- | --- | --- |
| MEDIUM, fixed | `app/src/main/java/de/iptv/tv/MainActivity.kt:650` | Import methods and advanced entries shared one flat Settings list. | One Add playlist entry, a three-method wizard, and three settings groups. | **One primary action per view / Group with space:** playlist setup is easier to find and follow. |
| HIGH, fixed | `app/src/main/java/de/iptv/tv/TvMenuDialog.kt:45` | At 320 dp and 200% text size, a fixed wizard heading left a 111 px viewport for a 246 px action. | The wizard heading scrolls; Back remains outside the scroll area. | **Plan for growth and clipping:** long German options remain readable and reachable with the remote. |

Verification: all eight targeted Android instrumentation tests passed, including English/German startup and language switching, App Info, invalid URL submission, cancellation, empty management, source-removal cancellation, and a 320 dp RTL dialog with 200% text size. Each method in the narrow dialog fits fully and has no ellipsis; Back is reachable with one Down press. Android lint, APK build, ZIP alignment and 16-KiB native-library checks passed. The app is installed in the running TV emulator. The English Settings panel, English wizard and German RTL stress view were also checked visually at 1920 × 1080. The previously active saved playlist was selected again after the fixture-based tests.

The emulator returns immediately from its document-picker resolver. The file method's return path was verified; selecting an actual file with a full system document picker was **Not verified** on this image. Physical TV hardware and other viewport/font combinations were **Not verified**.

No actionable layout findings remain in the inspected scope. Approve.
