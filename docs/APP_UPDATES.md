# App updates on Android TV and Google TV

Version 0.5.8 (code 19) introduces the GitHub APK updater. Install this baseline manually. Only later versions with the new release assets can be offered inside the app. Existing releases need no changes. The development channel accepts GitHub prereleases, including tags without a beta suffix. Nothing is uploaded to Google Play.

## App behavior

- The bell beside Settings has a red exclamation badge while a trusted compatible update is available. App updates is also reachable from Settings before a playlist has been configured.
- Check on entering the foreground and once the twelve-hour interval expires while active. The periodic wake-up only checks local timestamps. No background job, system notification, notification permission or foreground service is added.
- One check at a time. Persist the last check/attempt, API response ETags and signed selected manifest. Manual checks are throttled for one minute; normal network failures back off automatically, but manual retries are allowed after that minute. Server rate limits also block manual retries until their retry time.
- Query the public releases list with pagination; skip drafts and legacy releases without manifests. Sort semantic versions numerically, inspect newest first, and require a higher Android version code, supported API/ABI and the installed signing identity. CI requires both names and codes to increase across tags.
- Download only after the user chooses Update now. Stream to private no-backup storage, check size and SHA-256, package name, version and signing certificate. Submit to PackageInstaller; Android validates the APK signature and requires user confirmation. Declaring REQUEST_INSTALL_PACKAGES does not grant its special access automatically.
- Home/standby cancels checks and unfinished downloads. Retrying starts an interrupted download again; completed APKs are reused only after full validation. The private staged file and signed manifest survive process recreation. Existing app data is preserved by an ordinary same-key upgrade.
- Installation session callbacks are explicit and non-exported. A mutable PendingIntent is necessary for target API 36. Confirmation UI is launched only while the app is in the foreground. An interrupted session can be reopened using Continue installation. After self replacement, the actual installed version determines success.

Native Android Views implement the modal reader, a focusable scroll area, fixed actions and focus restoration. The red badge has an exclamation mark and an accessible update description. Changes use native text rather than HTML. Long notes, 200% text and RTL need device acceptance alongside the automated tests.

## Release assets and trust

Every new release contains:

1. `RUSTREAM-IPTV-PLAYER-<version>.apk` — universal ARM64/ARMv7/x86_64 APK.
2. The APK's `.sha256` file — convenient manual integrity check.
3. `update.json` — schema 1; package, development channel, tag, name/code, minimum API, ABIs, APK name/size/hash, signer certificate hash, German and English notes.
4. `update.json.sig` — Base64 detached signature over the **exact UTF-8 bytes** of the manifest. RSA uses SHA256withRSA; EC uses SHA256withECDSA.

The APK signing key also signs the manifest. The installed app's certificate supplies the trusted public key; the server cannot introduce another trusted key. The manifest's certificate fingerprint must match it, as must the downloaded APK's signer. Cached manifests are signature-verified again before restoring the badge. A checksum by itself is not a publisher identity check.

Update transport has its own OkHttp client, HTTPS-only URL policy and bounded bodies. Only the configured repository/API paths and GitHub asset delivery hosts are permitted. HTTPS-to-HTTP redirects, foreign hosts and embedded URL credentials are rejected. IPTV's intentionally permissive HTTP transport is separate. No personal access token or signing secret is embedded in the APK.

## Writing the notes

Before creating a tag, generate a review draft:

```powershell
./scripts/generate-release-notes.ps1 -Version 0.5.9 -PreviousTag v0.5.8 -CreateDraft
```

The script reads optional `Changelog-de:` and `Changelog-en:` commit trailers and gathers feat/fix/perf subjects as **review candidates**. Edit `docs/releases/0.5.9.json`, keep 1–12 short user-facing points per language, and set `reviewed` to true. Commit this file together with the version bump. CI refuses missing or unreviewed notes. Technical commit subjects are never silently shown as the app's changelog. Both app notes and the human-readable GitHub release body use the same reviewed source. GitHub may append its PR changelog to the release body; the app does not display that technical section.

## Workflow and keys

The tag workflow checks full history, requires the tagged commit to be reachable from origin/main, and requires an increasing version name and code. Publication is serialized. It builds and verifies the APK, creates/signs/verifies the manifest, creates a draft with all four assets, and publishes that complete draft. Manual workflow runs still do not publish. Existing releases/assets are not overwritten. A failed draft upload requires inspecting/removing the incomplete draft before retrying; no automatic replacement is attempted.

The existing DEVELOPMENT_KEYSTORE_BASE64 remains the default for the current test channel. This retains compatibility with same-key test installations. A production identity is optional via four Actions secrets, which must be configured together:

- RELEASE_KEYSTORE_BASE64
- RELEASE_KEY_ALIAS
- RELEASE_STORE_PASSWORD
- RELEASE_KEY_PASSWORD

Choose and back up a permanent key before wider distribution. Changing identity requires a new manual baseline install; the current updater deliberately rejects a different signer. Removing an installation to change keys loses local app data. Key rotation/multiple signers are outside schema 1. No key or secret was created or uploaded as part of implementation.

## Verification

```powershell
./scripts/test-updates.ps1
./scripts/test-updates.ps1 -DeviceTests -Serial emulator-5554
./scripts/test-update-installer.ps1 -Serial emulator-5554
```

This runs JVM tests, lint, debug/release builds, test APK compilation, signs a local R8 release copy and generates/verifies the actual manifest. Optional TV tests install the debug/test APKs onto a test emulator, exercise remote scrolling/actions/focus restoration with large text, and save `artifacts/updates-large-text.png`. App data is not cleared. No public release is created. The script repairs malformed quote characters in this process's inherited Windows PATH only.

The separate installer test builds a signed higher-version fixture through a temporary Gradle output override, without changing source versions. It stages that APK using the real PackageInstaller API, verifies that Android requests user confirmation through the non-exported callback, and abandons the session before replacement. It restores the install-source app-op and update preferences and recreates the normal debug output. Never distribute `artifacts/update-fixture.apk`. It is not a complete installed-version replacement test.

Implementation verification on 20 October 2026: JVM suite (including 10 update tests), debug/R8 release builds, Android lint with zero errors, release metadata generation/signature check, actionlint, all three ABI package/alignment checks, two D-pad/large-text tests and the real installer-confirmation test passed on the API 36 Google-TV emulator. Physical-device acceptance, API 23–25 behavior, a complete self replacement and a GitHub-hosted run of the modified workflow remain unverified. Screen-reader naming/roles and scroll actions are checked structurally; an end-to-end TalkBack session has not been run.

Before distributing, additionally test a complete same-key upgrade between two versions on physical Android TV and Google TV: per-app unknown-source access denied/allowed, system installer confirmation/cancel, low storage, network loss, Home/process termination, app restart and preservation of sources, favorites and playback progress. OEM settings/installer screens and device restrictions vary; this cannot be certified from JVM tests or one emulator. API 23–25 uses the older system unknown-sources setting; API 26+ uses per-app access. No browser, mouse or touch should be needed.

Google Play is a later distribution adapter. A Play build should omit self-install permission and use the TV Play listing; the current Play in-app update library does not list Android TV support. Keep the same signing identity across distribution routes if seamless migration is required.
