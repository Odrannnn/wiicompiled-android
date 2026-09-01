# Reproducible build and release guide

This document is the canonical setup record for the WiiCompiled Android workbench. Run commands from the repository root in PowerShell 7. Do not substitute tool versions without updating the lock files and validating a clean build.

## Publication boundary

The repository and game-free Port Lab APK may contain the Android application, the NOD disc reader, and upstream license texts. They must not contain any of the following:

- a Mario Kart Wii ISO, RVZ, WBFS, WIA, CISO, or GCZ;
- extracted disc files, `main.dol`, or `StaticR.rel`;
- generated WiiCompiled translation output or a playable `libWiiCompiled.so`;
- Retro Rewind payloads such as `Code.pul` or `Loader.pul`;
- imported Vulkan drivers;
- signing keys.

All such material belongs under ignored `private/`, `upstream/`, `.tools/`, or app-private Android storage. Never attach `private/WiiCompiled-local-game.apk` to a GitHub release.

## Required host software

The canonical Windows build uses:

| Component | Required version | Source of truth |
| --- | --- | --- |
| PowerShell | 7.x | Host installation |
| Git for Windows | Current maintained version | Host installation |
| Docker Desktop | Linux-container mode | Host installation |
| JDK | 17 | `toolchain.lock.json` |
| Android compile SDK | 36 | `toolchain.lock.json` and `android/app/build.gradle` |
| Android build tools | 36.0.0 | Local Android SDK |
| Android NDK | 29.0.14206865 | `toolchain.lock.json` and Gradle |
| Android CMake | 3.31.5 | `toolchain.lock.json` and Gradle |
| Rust in the extractor container | 1.88.0 | `toolchain.lock.json` |
| .NET SDK | 8.0.424 | `toolchain.lock.json` |
| NOD / nodtool | v2.0.0-alpha.10 | both lock files |

The NOD builder is intentionally Linux-hosted in Docker. NOD's official vendored-crates archive is filtered for Linux hosts. Building it directly with Windows Cargo can fail on omitted Windows crate files or require a global MSVC toolchain. Do not work around that by installing random crate versions or committing a prebuilt library.

The container bases are pinned by digest in `docker/nod-android/Dockerfile`. The image installs Rust 1.88.0 and Android NDK 29.0.14206865, then Cargo builds offline from NOD's checksum-locked vendor archive.

## Workspace layout

| Path | Purpose | Git policy |
| --- | --- | --- |
| `android/` | Android source and Gradle wrapper | Tracked |
| `docker/nod-android/` | Pinned ARM64 NOD builder | Tracked |
| `scripts/` | Reproducible setup, build, signing, and deployment commands | Tracked |
| `patches/` | Android changes applied to pinned WiiCompiled | Tracked |
| `upstream/` | Pinned upstream checkouts and generated files | Ignored |
| `.tools/` | Downloaded tools, Docker outputs, Cargo target data | Ignored |
| `.gradle-user/` | Repository-local Gradle cache | Ignored |
| `.android-user/` | Repository-local Android build state | Ignored |
| `private/` | Disc extraction, translated runtime, playable APK, signing key | Ignored |
| `artifacts/` | Local release candidates and verification downloads | Ignored |

The tablet-compatible signing key is kept at `private/signing/debug.keystore`. Its public certificate SHA-256 is:

```text
A6B1924D07EFDE502F72F25D32A3A83DF3D97B6B79829B107BC057963C213C39
```

Back up the key privately. Replacing it prevents an in-place update of the installed application. The build and install scripts compare the certificate before installation and never uninstall or clear application data as a fallback.

## First setup

Install Android SDK 36, build-tools 36.0.0, NDK 29.0.14206865, CMake 3.31.5, platform-tools, and JDK 17. Confirm Docker Desktop is running in Linux-container mode. Then run:

```powershell
.\scripts\Bootstrap.ps1
.\scripts\Setup-HostTools.ps1
```

`Bootstrap.ps1` clones every revision in `upstream.lock.json`, verifies the exact commit, initializes required submodules, and applies the matching tracked patch once. It refuses to replace a checkout whose commit differs.

`Setup-HostTools.ps1` downloads the pinned .NET SDK, nodtool, and NOD vendored-crates archive into `.tools/`, then verifies their recorded SHA-256 or SHA-512 values. A checksum failure is a hard stop; do not edit the expected hash merely to continue.

The first extractor build creates a reusable local Docker image and downloads NDK 29 inside it:

```powershell
.\scripts\Build-NodAndroid.ps1
```

The result is `.tools/nod-android/lib/arm64-v8a/libnod.a`. CMake links it into `libwiicompiled_probe.so`; it is not committed as a binary.

## Build the game-free Port Lab

Run:

```powershell
.\scripts\Build-Android.ps1
```

This command performs the following operations:

1. verifies the pinned Android SDK, NDK, and CMake directories;
2. builds NOD through the pinned Docker image and offline Cargo vendor tree;
3. writes only `android/local.properties` with the selected SDK path;
4. uses ignored `.gradle-user/` and `.android-user/` state, copying the canonical private key into the latter only for signing;
5. runs `assembleDebug`, `testDebugUnitTest`, and `lintDebug`;
6. copies the game-free APK to `artifacts/WiiCompiled-PortLab-debug.apk`.

Install it only on an explicitly named ADB device:

```powershell
.\scripts\Install-Probe.ps1 -Serial '<adb serial>'
```

The Port Lab build contains the on-device disc extractor and diagnostics. It has no `GameActivity`, translated game runtime, ROM, Retro Rewind distribution, or custom driver payload.

## On-device disc extraction

In the app's **Play** page, choose **Select and extract disc image**. Android's system document picker grants access to only the selected document; the app requests no broad storage permission.

The extractor supports ISO, RVZ, WBFS, WIA, CISO, and GCZ through pinned NOD. It reads the document by seekable file descriptor without first copying the whole image. Some cloud document providers expose only a forward-only pipe; download the image to local tablet storage if the provider reports a random-access error.

Extraction follows these checks:

1. NOD opens the selected container and verifies it is a Wii disc.
2. The header must be PAL `RMCP01`, disc 0, revision 0.
3. NOD opens the data partition with Wii hash validation enabled.
4. Files are written under `game/disc.importing` in app-private external storage.
5. Unsafe FST path segments are rejected.
6. Extracted `main.dol` and `StaticR.rel` must match the tracked clean-disc SHA-256 pins.
7. The staging directory replaces `game/disc` atomically. A previous working extraction is restored if activation fails.

Keep enough free tablet storage for the extracted data in addition to the source image. Closing or force-stopping the app interrupts extraction; the next attempt removes stale staging data and preserves or restores the previous installed disc.

On-device extraction supplies runtime data. It does not translate PowerPC game code. A playable local APK still requires the private host translation step below.

## Build the private playable APK

Use only a user-owned clean PAL `RMCP01` revision-0 image:

```powershell
.\scripts\Prepare-Game.ps1 -GameImage 'X:\path\to\your-own-RMCP01.rvz'
.\scripts\Build-GameRuntime.ps1
```

`Prepare-Game.ps1` uses pinned nodtool with full validation, verifies the two supported game binaries, and generates translated source under ignored directories. `Build-GameRuntime.ps1` builds the base and pinned Retro Rewind ARM64 runtimes, signs with `private/signing/debug.keystore`, verifies the certificate, and writes `private/WiiCompiled-local-game.apk`.

Install the APK without replacing existing app data:

```powershell
.\scripts\Sign-And-Install-LocalGame.ps1 -Serial '<adb serial>' -Launch
```

You can then use the on-device picker to extract the disc. The older ADB deployment path remains available for recovery or controlled comparison:

```powershell
.\scripts\Deploy-LocalGame.ps1 -Serial '<adb serial>' -ReplaceGameData
```

`-ReplaceGameData` deliberately removes the package's deployed game directory before streaming. Omit it for normal APK updates.

## Release procedure

Use one commit per logical change. Before publishing:

```powershell
git status --short
.\scripts\Build-Android.ps1
```

Then verify the candidate APK:

```powershell
$apk = 'artifacts/WiiCompiled-PortLab-debug.apk'
Get-FileHash -Algorithm SHA256 -LiteralPath $apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs $apk
```

Inspect the ZIP entries and DEX before upload. The release candidate may contain `lib/arm64-v8a/libwiicompiled_probe.so` with NOD, but must not contain `libWiiCompiled.so`, `libRetroRewind.so`, a game activity, disc files, `.dol`, `.rel`, `.pul`, ROM formats, or driver libraries.

Create the GitHub release only from committed and pushed source. Mark experimental versions as prereleases and attach both the renamed APK and a `.sha256` file. Download the published APK once and compare its SHA-256 with the pre-upload value.

## Common failures

### Git submodule reports missing `basename`, `sed`, or `git-sh-setup`

Git for Windows is incomplete or its helper environment is damaged. Repair or reinstall Git for Windows, open a new PowerShell session, and rerun `Bootstrap.ps1`. Do not skip a required submodule or change its locked commit.

### PowerShell download reports `SEC_E_NO_CREDENTIALS`

This is a Windows Schannel credential failure. Do not disable checksum verification. Download the exact URL from `toolchain.lock.json` through a working authenticated browser or GitHub CLI into the expected `.tools` filename, run `Get-FileHash`, and rerun `Setup-HostTools.ps1` so it performs the authoritative comparison.

### Cargo reports `link.exe` missing or a vendored Windows source is empty

The NOD build was started directly on Windows. Stop and use `scripts/Build-NodAndroid.ps1`. The supported build host is the pinned Linux Docker image.

### Docker cannot access its named pipe

Start Docker Desktop, select Linux containers, and ensure the current Windows account can access Docker. Confirm `docker version` works before rebuilding.

### CMake cannot find `libnod.a`

Run `scripts/Build-NodAndroid.ps1`. Do not copy a library from another ABI or NDK revision into `.tools/nod-android`.

### Gradle chooses `C:\.gradle` or `C:\.android`

Use `scripts/Build-Android.ps1`. It sets `GRADLE_USER_HOME` and `ANDROID_USER_HOME` to ignored workspace directories for the duration of the build and restores the previous environment afterward.

### APK certificate mismatch

Stop. Do not uninstall the app, clear its data, or generate a replacement key. Restore the backed-up `private/signing/debug.keystore` whose public certificate matches the recorded SHA-256.
