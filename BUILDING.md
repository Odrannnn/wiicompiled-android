# Reproducible build and release guide

This document is the canonical setup record for the WiiCompiled Android workbench. Run commands from the repository root in PowerShell 7. Do not substitute tool versions without updating the lock files and validating a clean build.

## Publication boundary

The repository and game-free APKs may contain the Android application, NOD disc reader, signed ARM64 translator/compiler tools, reusable open-source runtime archives, and upstream license texts. They must not contain any of the following:

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
| Builder LLVM/Clang/LLD | 21.1.8 | `toolchain.lock.json` |
| Native AOT Android runtime | 8.0.30 | `toolchain.lock.json` |
| Native AOT build NDK | 25.1.8937393 | `toolchain.lock.json` |
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
| `docker/llvm-android/` | Pinned relocatable ARM64 Clang/LLD builder | Tracked |
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

The Port Lab contains disc extraction, diagnostics, the SDL game host, mod management, and runtime-pack import. The Play button remains visible, but launch is gated until a verified private runtime exists. It contains no translated game runtime, ROM, Retro Rewind distribution, or custom driver payload.

## Build the self-contained Builder edition

Run:

```powershell
.\scripts\Build-BuilderApk.ps1
```

The script builds four game-free payloads and then signs `artifacts/WiiCompiled-Builder-debug.apk`:

1. the WiiCompiled translator as a .NET Native AOT `linux-bionic-arm64` executable;
2. relocatable ARM64 Clang, LLD, and llvm-ar 21.1.8 in the pinned Docker image;
3. a case-safe compressed NDK 29 sysroot and Clang resource directory;
4. the reusable Android runtime support archive and its open-source Aurora, Dawn, SDL, Crypto++, and support libraries.

The compiler executables are stored as APK native libraries so Android extracts them onto an executable, signed application path. The app creates private symlinks named `clang++` and `ld.lld`; it never executes a binary copied into writable storage. The SDK stays zipped in the APK and is expanded into app-private storage on the first build. This avoids Android's writable-code execution restrictions and avoids Termux's package-name-specific prefix.

The verified `0.3.0-alpha.5` debug Builder APK is 232,025,218 bytes (221.3 MiB). Its SHA-256 is `6dd086b5b3961ba80c94318fdb0e7ecd2f58157de8a0109330126e73fab5c13c`. Size changes when the pinned tools or runtime closure changes, so use the size printed by the build script as authoritative for later releases.

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

On-device extraction supplies the private inputs. In the Builder edition, choose **Build private runtime** after extraction. Keep the tablet connected to power and leave at least 4 GiB of free internal storage. A foreground data-sync service keeps the work alive while the activity is backgrounded and exposes progress plus cancellation. If the pinned Retro Rewind profile is enabled, the builder automatically includes it. Enabling Retro Rewind after a base-only build requires one full mod-aware regeneration; once the combined pack is active, no rebuild is needed until the pinned code profile changes.

Changes inside `patches/wiicompiled-android.patch` that affect Aurora input or the runtime SDK, including controller hotplug synchronization and gyro gestures, require **Build private runtime** once after installing the updated Builder APK. The existing extracted disc, installed mods, NAND, saves, and cached compiler SDK remain in place. A rebuilt combined base/Retro Rewind pack contains the same controller runtime changes in both libraries. APK-only activity changes, such as touch layout or Android event routing, do not by themselves require regenerating the private runtime.

The adaptive pipeline scheduler and renderer diagnostics are also runtime-SDK changes, so an older private runtime will continue using its old fixed prewarming policy after only an APK update. Install the new Builder, run **Build private runtime** once, and keep the existing extraction and profiles. The generated base and Retro Rewind libraries then both receive:

- a one-second frame-time window with average, p95, and jitter values;
- CPU-side surface lock, acquire, command encoding, finish, submit, pacing-wait, and present timings;
- urgent/background shader queue and active-worker diagnostics;
- a two-worker Android prewarm ceiling with frame-budget hysteresis;
- Android thermal-status limits and an API-33+ performance-hint session, with automatic fallback when unsupported;
- background priority for prewarm and SQLite cache-writer threads, while first-use workers retain normal priority.

Open **Settings > Graphics** while the game is running to read the detailed values. The corner FPS overlay remains compact and shows FPS plus average and p95 frame time. A queued-shader notice explicitly says when cached prewarming is paused to protect the frame rate. These counters add no file logging by default and do not contain game data.

For local split-screen, connect the gamepads and open **Settings > Controller settings**. Select Port 1 through Port 4 and assign one connected controller to each port. Assignments persist by controller identity. Motion controls are available in the same menu when SDL exposes a gyro; **Shake controller for wheelies / tricks** produces a short D-pad Up input and the threshold is adjustable in radians per second. Generic Bluetooth controllers without an exposed sensor continue to work with buttons and sticks but cannot provide this gesture.

Retro Rewind builds include the production Retro-WFC payload. The Builder downloads it from the fixed `play.rwfc.net` endpoint, refuses redirects, limits it to 16 MiB, checks its `WWFC/Payload` size header, and verifies its RSA/SHA-256 signature against the pinned production key before passing the private file to `translate-mod --retro-wfc-payload`. A previously verified payload is retained in app-private storage and may be reused after both download attempts fail. The Android manifest permits Java cleartext traffic only to that fixed payload host; runtime game sockets continue through the native network HLE.

Launching `libRetroRewind.so` atomically updates `[network] enabled = true` in the existing `Config.toml`; launching the base runtime restores it to `false`. The Android socket implementation supports Retro-WFC's 443-to-80 plaintext route for NAS, SAKE, game-stats, and race services with bounded reads and writes. A runtime pack generated by an older Builder omitted these static payload patches, so install the updated APK and run **Build private runtime** once with Retro Rewind enabled. Live account creation, login, matchmaking, reconnect, and long-session behavior still require validation against the production service and should be treated as experimental.

The device performs these steps in app-private storage:

1. verifies that the extracted `main.dol` and `StaticR.rel` are present;
2. expands the pinned compiler and reusable runtime SDKs;
3. stages the enabled pinned Retro Rewind `Code.pul`, `Loader.pul`, and XML inside the disposable workspace when present;
4. downloads and verifies the signed production Retro-WFC payload when Retro Rewind is enabled;
5. runs `translate-recursive`, `emit-base-manifest`, `generate-data-init`, optional `translate-mod`, and `emit-build-shards`;
6. compiles the generated base and optional Retro Rewind shards for `aarch64-linux-android30` with at most four translation threads and one compiler process at a time;
7. links and strips `libWiiCompiled.so` and, when requested, `libRetroRewind.so` with 16 KiB ELF page alignment;
8. hashes the products and dependencies into one private runtime-pack manifest, atomically activates it, and installs the bootstrap resources;
9. removes the successful build's generated sources, objects, and link outputs while retaining the reusable compiler SDK, signed payload cache, and build log;
10. enables the existing **Launch Mario Kart Wii** button.

The source ROM remains wherever the user selected it. Extracted disc files, the reusable compiler SDK, the build log, and the active runtime pack remain under the application's private directories. Failed build workspaces remain for diagnosis and are replaced by the next attempt. Successful build workspaces, object files, and intermediate link outputs are deleted. Termux and a PC are not used by this device-side procedure. The PC toolchain described above is only needed by maintainers to produce the distributable Builder APK. The Mods page installs Retro Rewind through a separate foreground service, so changing pages or recreating the activity does not stop its large download or staging pass; its notification and Mods card both expose cancellation.

## Legacy PC-built private runtime

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

Use one commit per logical change. Before publishing the normal and Builder editions, keep this order so the release candidate is always the final output:

```powershell
git status --short
.\scripts\Build-Android.ps1
.\scripts\Build-BuilderApk.ps1
```

Both scripts clean the shared Gradle debug output before assembling and enforce opposite payload gates. The public Port Lab rejects Builder tool archives and executable payloads; the Builder requires its compiler/runtime SDK and ARM64 tools while rejecting game-derived material. Do not replace these with a bare `assembleDebug`, because the `withBuilder` configuration property changes source sets without changing the Gradle variant name.

Then verify the candidate APK:

```powershell
$apk = 'artifacts/WiiCompiled-Builder-debug.apk'
Get-FileHash -Algorithm SHA256 -LiteralPath $apk
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs $apk
```

`Build-BuilderApk.ps1` inspects the outer APK and rejects `libWiiCompiled.so`, `libRetroRewind.so`, disc binaries, and ROM formats. The Builder may contain the SDL game activity, `libwiicompiled_probe.so`, the Native AOT translator, compiler executables, compiler SDK archives, and reusable open-source libraries. Inspect nested SDK archives when changing their construction; neither archive may contain generated translation output or private game/mod inputs.

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

### Builder reports that its payload is incomplete

Run `Build-AndroidTranslator.ps1`, `Build-AndroidCompiler.ps1`, and `Build-AndroidRuntimeSdk.ps1`, then rebuild with `-PwithBuilder`. Do not substitute Termux executables: Termux packages embed that application's private prefix and are not a relocatable SDK for another Android package.

### Builder stops for storage or Android kills it

Free at least 4 GiB in internal app storage and connect power. The builder now also preserves a 384 MiB working reserve during every compilation stage and automatically pauses at Android thermal status `SEVERE` or higher. Start the build again from the Play page. The current workspace is disposable; the previously activated runtime and extracted disc remain intact. Do not clear application data, because that removes the extracted disc, profiles, and private runtime.

### Builder reports exit 133 or 134

Exit 133 is `SIGTRAP`, normally an assertion or explicit trap in the translator/compiler. Exit 134 is `SIGABRT`, normally a fatal diagnostic followed by an abort. Changing pages cannot cause either code because the build belongs to its foreground service. Read the bounded **Last output** shown in the build card, then use **Tools > Export latest build log** for the complete command output. The same Tools page can export the latest native game log for crashes and Retro-WFC diagnosis.

### Retro-WFC reports Wii error 20100

Confirm Android itself has validated internet access and that the selected runtime profile has networking enabled. Releases before `0.3.0-alpha.9` can resolve Retro Rewind's legacy `nas.play.rwfc.net` login target to an endpoint that no longer accepts the connection; update the Builder APK and run **Build private runtime** again so the new HLE networking code is compiled into the private runtime. Installing the APK alone updates the Builder and Android Java layer but does not replace an already generated `libWiiCompiled.so` or `libRetroRewind.so`.

### Overlay is tiny or hidden under a system bar

Use `0.3.0-alpha.9` or newer. The Android overlay now falls back to resolution-based scaling and republishes real safe-area insets after fullscreen and display changes. The Java inset fix activates when the APK is installed; the native overlay scaling change requires **Build private runtime** once. When diagnosing a device, the runtime log's startup configuration line records `displayScale` and `safeArea`.
