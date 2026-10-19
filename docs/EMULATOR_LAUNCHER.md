# Emulator launcher: Quick Boot failure

19 October 2026. Applies to `Start-Emulator.cmd` and `scripts/start-emulator.ps1`.

## Observed failure

The original windowed launch tried to restore the AVD's saved `default_boot` state. Its emulator stdout reported:

```text
Loading snapshot 'default_boot'...
Error -22 while loading VM state
Failed to load snapshot 'default_boot'
```

ADB remained offline. The launcher then failed after its 180-second boot timeout. Windows Application Error/Windows Error Reporting events did not contain an emulator crash in the inspected time window. The failure occurred before the IPTV app started.

A separate issue was found during reproduction: the launcher reused a running `-no-window` test emulator and printed that the app was ready even though no interactive emulator window existed.

## Fix

- Newly launched interactive emulators use `-no-snapshot`. This bypasses Quick Boot load/save while preserving the AVD's installed apps, settings and app data. No `-wipe-data`, snapshot deletion or AVD reset is performed.
- The launcher identifies the exact AVD and SDK process. An existing headless test instance is stopped and replaced with a visible cold boot. If its console is not yet reachable, the launcher explains how to retry.
- A visible instance that is still booting is reused instead of launching a duplicate AVD. Offline devices can be identified through their emulator console.
- Error messages identify both stdout and stderr logs. The snapshot failure was in stdout; checking stderr alone missed it.
- The latest-APK candidates include the film-details debug APK. `ANDROID_HOME` and `ANDROID_SDK_ROOT` both point to the project's bundled SDK for this process.

Double-click `Start-Emulator.cmd` again. An already running, stuck Quick Boot instance should first be closed through its emulator window; the next start uses the corrected cold boot. App data remains intact.

## Verification

| Check | Result |
| --- | --- |
| Original windowed launcher | Reproduced: invalid saved VM state, ADB offline, 180-second timeout |
| Fixed visible launch | Passed; emulator log: `Boot completed in 25288 ms`; APK installed and MainActivity launched |
| Actual CMD wrapper from `C:\Users\ninos\Documents` | Passed, exit code 0; project paths resolved independently of the working directory |
| Emulator after launcher exit | Responsive visible QEMU window titled `Android Emulator - iptv-google-tv-16k:5554`; `sys.boot_completed=1`; app process present |
| Script regression checks | 10 passed, using real launcher helpers and a synthetic ADB executable stub |

Regression command (does not launch or stop a real emulator):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/test-emulator-launcher.ps1
```

The checks cover cold boot without data wipe, visible launch arguments, exact AVD/SDK matching, headless recognition, literal AVD-name matching, offline-device selection, successful native stderr, actionable ADB failures and transient-error handling. The headless restart decision is tested with process fixtures; the live acceptance above covers a fresh visible launch and reuse of a visible instance.

Current emulator output is under `.tools/emulator-launcher.stdout.log` and `.tools/emulator-launcher.stderr.log`; each fresh launch replaces these files. Regression output is retained in `artifacts/emulator-launcher-tests.log`.
