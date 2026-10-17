# Tagged development APKs

Only pushing a version tag triggers `.github/workflows/tagged-apk.yml`. Normal pushes to `main` do not publish anything and do not increment versions automatically. Every tagged build checks out that exact commit, runs the Rust/JVM checks and Android lint, builds all three Android ABIs, signs an optimized APK and attaches it plus its SHA-256 checksum to a GitHub **prerelease**. Development builds are not marked Latest and are not submitted to an app store.

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
git tag -a v0.5.0 -m "RUSTREAM IPTV PLAYER 0.5.0 development build"
git push origin v0.5.0
```

Use a new tag for each new build, such as `v0.5.1` after changing the source version to `0.5.1` and increasing its version code. Explicit suffixes such as `v0.6.0-beta.1` are supported when the source version is `0.6.0-beta.1`. All versions from this workflow remain development prereleases regardless of their tag suffix.

Check progress in the repository's **Actions** tab. After success, download `RUSTREAM-IPTV-PLAYER-0.5.0.apk` from **Releases**. A failed check publishes no release. Existing releases and assets are never overwritten, even on a workflow rerun. Published tags should continue to point at their original source commit.

The tag validator can also run locally:

```powershell
./scripts/check-release-tag.ps1 -Tag v0.5.0
```

GitHub's automatically generated source archives correspond to the same tag. SDKs, private playlists, signing keys and compiled APKs stay out of Git; the APK is a release asset.
