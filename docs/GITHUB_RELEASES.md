# Tagged development APKs

Pushing a version tag or starting a manual check triggers `.github/workflows/tagged-apk.yml`. Normal pushes to `main` do not publish anything and do not increment versions automatically. Every tagged build checks out that exact commit, runs the Rust/JVM checks and Android lint, builds all three Android ABIs, signs an optimized APK and attaches it plus its SHA-256 checksum to a GitHub **prerelease**. Development builds are not marked Latest and are not submitted to an app store.

## Check on GitHub before releasing

1. Open [the APK workflow in GitHub Actions](https://github.com/ninos-apk/RUSTREAM/actions/workflows/tagged-apk.yml).
2. Click **Run workflow**, select `main` (or the branch you want to check) and start the run.
3. Open the run to inspect each step. A successful manual run finishes without creating a tag, release or release asset.

With GitHub CLI installed and authenticated (`gh auth login`), the equivalent check-only command is:

```powershell
gh workflow run tagged-apk.yml --ref main --repo ninos-apk/RUSTREAM
```

To list its runs and find their results:

```powershell
gh run list --workflow tagged-apk.yml --repo ninos-apk/RUSTREAM
```

`git push origin main` triggers neither the manual check nor publication. Pushing a new version tag, for example `git push origin v0.5.1`, triggers the tag flow, which publishes a development prerelease only after all its checks pass. The full tag-creation commands are below.

The manual check uses the same build and verification steps as a tagged release:

- Rust formatting, Clippy and the Rust workspace tests.
- Android JVM unit tests and Android lint.
- The optimized release APK with ARM64, ARMv7 and x86_64 native libraries.
- Development signing, signature verification, APK version checks, JNI exports and 16-KiB alignment.

Android emulator/instrumentation tests and checks on a physical TV are separate and are not included in this workflow. Run those locally using the existing device-test scripts when needed.

**If a setup step, test, lint check, build, signing operation or verification fails, the publication step is skipped.** Publication additionally requires a successful tag-push event; a manual run cannot publish even when every check passes. Existing releases are left unchanged. Once satisfied with the results, tag the same source commit and push the tag. The tag run repeats all checks before publishing its prerelease.

## Setup status and verified releases

On **17 October 2026**, the workflow was committed and pushed to `main`, and the encrypted repository Actions secret `DEVELOPMENT_KEYSTORE_BASE64` was configured and verified in `ninos-apk/RUSTREAM`. It contains the existing development signing key, which remains outside Git. The one-time signing setup below is already complete; it only needs repeating if you deliberately replace or remove the secret.

The first tagged run, [`v0.5.0`](https://github.com/ninos-apk/RUSTREAM/actions/runs/39029104797), succeeded on **17 October 2026**. All configured checks passed before its [development prerelease](https://github.com/ninos-apk/RUSTREAM/releases/tag/v0.5.0) was published with the APK and checksum. The `v0.5.0` tag is already used; keep it pointing at that source commit.

The first [manual check-only run](https://github.com/ninos-apk/RUSTREAM/actions/runs/39029471514) also succeeded on **17 October 2026**, including APK signing and verification. GitHub reported its publication step as **skipped**, confirming that manual checks do not publish. The `v0.5.0` prerelease came from the separate tag-push run.

For a future version such as `0.5.1`, first change `versionName` to `0.5.1` and increase `versionCode` above `11` in `app/build.gradle.kts`, then commit and push those changes. After a successful manual check, publish by creating and pushing the new tag:

```powershell
git switch main
git status
git push origin main
git tag -a v0.5.1 -m "RUSTREAM IPTV PLAYER 0.5.1 development"
git push origin v0.5.1
```

Create the tag only after all intended changes are committed. The workflow builds the commit marked by the tag. Follow the build in [GitHub Actions](https://github.com/ninos-apk/RUSTREAM/actions/workflows/tagged-apk.yml), then find the matching APK and checksum under [GitHub Releases](https://github.com/ninos-apk/RUSTREAM/releases). A manual run can verify the GitHub-hosted build before creating a release tag.

## One-time signing setup

The repository Actions secret `DEVELOPMENT_KEYSTORE_BASE64` must contain the Base64 encoding of the project's existing `.tools/android-user/debug.keystore`. Its development alias is `androiddebugkey`; its key/store passwords are `android`. This is a testing identity, not a production signing key. Keep the same key for all development builds so Android can install updates over previous test versions.

With GitHub CLI authenticated, configure the secret from PowerShell without printing the key:

```powershell
$keyPath = Join-Path $PWD '.tools/android-user/debug.keystore'
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath)) | gh secret set DEVELOPMENT_KEYSTORE_BASE64 --repo ninos-apk/RUSTREAM
```

The key remains outside Git. The workflow fails before building if the secret is missing, restores it only on the temporary runner and removes it at the end. Repository Actions must be enabled, with permission for the workflow to write release contents. The workflow uses a standard Windows runner and GitHub's provided token; no personal access token is stored in the workflow.

## Publish a development version

1. Set `versionName` and an increasing `versionCode` in `app/build.gradle.kts`, then commit your changes. For the current source, these are `0.5.0` and `11`.
2. Create an annotated tag whose name is `v` followed by that exact `versionName`.
3. Push the source and tag:

```powershell
git switch main
git status
git push origin main
git tag -a v0.5.1 -m "RUSTREAM IPTV PLAYER 0.5.1 development build"
git push origin v0.5.1
```

The command example assumes you have already changed and committed the app version to `0.5.1` with an increased version code. Use a new tag for each new build. Explicit suffixes such as `v0.6.0-beta.1` are supported when the source version is `0.6.0-beta.1`. All versions from this workflow remain development prereleases regardless of their tag suffix.

Check progress in the repository's **Actions** tab. After success, download the versioned APK (for example, `RUSTREAM-IPTV-PLAYER-0.5.1.apk`) from **Releases**. A failed check publishes no release. Existing releases and assets are never overwritten, even on a workflow rerun. Published tags should continue to point at their original source commit.

The tag validator can also run locally:

```powershell
./scripts/check-release-tag.ps1 -Tag v0.5.0
```

GitHub's automatically generated source archives correspond to the same tag. SDKs, private playlists, signing keys and compiled APKs stay out of Git; the APK is a release asset.
