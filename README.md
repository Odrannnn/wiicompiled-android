# WiiCompiled Android port workbench

This workspace is an experimental GPL-3.0 port of pinned WiiCompiled and WheelWizard revisions. It never downloads or commits game data. The normal artifact is a diagnostic APK; a separate ignored local APK can be built from a user-owned clean PAL `RMCP01` disc.

## Verified milestone

On the Lenovo TB520FU (Android 16, Snapdragon SM8650, ARM64, 4 KiB pages):

- WiiCompiled's ARM64 paired-single and FP control helpers pass native smoke checks.
- An Android-safe AArch64 coroutine backend completes 20,000 cross-thread yields while preserving FPCR.
- The fixed 4 GiB guest reservation at 64 GiB, dual aliases, and memory protections work in the app sandbox.
- Real protected-page loads/stores are decoded correctly from Android's AArch64 ESR signal record.
- The native library has 16 KiB ELF load alignment, a non-executable stack, and RELRO.
- The supplied RVZ validates as lossless PAL `RMCP01` revision 0. Extracted `main.dol` and `StaticR.rel` exactly match upstream pins.
- Static translation emitted 29,637 functions in 72 shards. Upstream's 570 translator tests pass.
- The full game boots through Vulkan on the tablet and holds approximately 60 FPS in the main menu.
- The game build can load a user-selected ARM64 Vulkan driver through libadrenotools, with automatic system-driver fallback.
- An Android WheelWizard profile can import ZIP mods, toggle them, and mount enabled full-file/Riivolution overlays without changing the extracted base game. WheelWizard tagged SZS bundles are decoded and merged into PAL U8 archives in profile priority order.
- The Android catalogue searches WheelWizard's GameBanana v12 source for Mario Kart Wii mods, shows bounded paged results and details, and downloads HTTPS ZIP releases into the same safe importer. Remote IDs and versions are retained so later catalogue versions can update an installed profile while preserving its enabled state and priority.
- Retro Rewind uses WheelWizard's official install, version, and deletion feeds. The Android installer stages the base and ordered update ZIPs, applies version-scoped deletions only inside the staging root, rejects incomplete update chains, records the target version, and then swaps one managed profile into the mod list. The pinned Retro Rewind 6.12.4 `Code.pul` is translated into a second ARM64 runtime with 32,870 resolved dispatch entries and 580 overlay images; the installed profile reports all 23 executable requirements translated.
- App-managed NAND can create and reopen Mario Kart's save data, including the required FaceLib database and offline WiiConnect24 scheduler services.
- A touch GameCube pad supplies analog steering and the main buttons; Mario Kart passes controller detection and reaches the main menu without a physical controller. Bluetooth gamepad motion is routed above the touch overlay and normalized through the same pad bridge so the physical left stick can steer without regenerating the private runtime.

The Port Lab remains useful without a playable runtime. It uses Android's document picker and pinned NOD to extract a user-selected ISO, RVZ, WBFS, WIA, CISO, or GCZ directly into app-private storage. The extractor requires clean PAL `RMCP01` revision 0, validates Wii partition hashes while reading, verifies the pinned `main.dol` and `StaticR.rel`, and atomically preserves the previous extraction on failure. It requests no broad-storage permission and does not bundle game data.

The Builder edition completes the rest on the tablet. It packages signed ARM64 Native AOT translator and Clang/LLD tools plus a game-free runtime SDK, translates the extracted binaries, compiles and links a private runtime, and activates it without Termux or a PC. When the pinned Retro Rewind profile is enabled, one mod-aware build produces both `libWiiCompiled.so` and `libRetroRewind.so`. Switching an existing base-only installation to Retro Rewind requires this full build once because the base translation must preserve mod-patch boundaries. The compiler SDK and active runtime pack remain cached; generated source, objects, and link outputs are removed after success and retained after failure for diagnosis. The verified `0.3.0-alpha.4` Builder APK is 232,024,326 bytes (221.3 MiB).

## Reproduce

In PowerShell from the repository root:

```powershell
.\scripts\Bootstrap.ps1
.\scripts\Setup-HostTools.ps1
.\scripts\Build-Android.ps1
.\scripts\Install-Probe.ps1 -Serial '<adb serial>'
```

To build the self-contained edition distributed to Android users:

```powershell
.\scripts\Build-BuilderApk.ps1
```

See [BUILDING.md](BUILDING.md) for the exact toolchain versions, Docker-based NOD build, private signing-key location, on-device extraction flow, release checklist, and failure recovery instructions.

To create a private local game build:

```powershell
.\scripts\Prepare-Game.ps1 -GameImage 'X:\path\to\your-own-PAL-RMCP01.rvz'
.\scripts\Build-GameRuntime.ps1
.\scripts\Deploy-LocalGame.ps1 -Serial '<adb serial>'
```

`Build-GameRuntime.ps1` now regenerates and packages both the base and pinned Retro Rewind ARM64 runtimes. Pass `-BaseOnly` when deliberately building an APK without executable mod support. Packaging does not run the deferred unit-test suite.

`private/WiiCompiled-local-game.apk`, `private/disc`, `upstream/wiicompiled/Assets`, and generated translation output contain or derive from the user's game and must not be published. The workbench `.gitignore` excludes them. Only distribute source changes and the game-free Port Lab APK under GPL-3.0.

## Optional custom Vulkan driver

The game uses Android's system Vulkan driver by default. In the Port Lab, **Import custom Vulkan driver ZIP** accepts a user-provided package containing an ARM64 `libvulkan_freedreno.so`, `vulkan.freedreno.so`, `vulkan.adreno.so`, `libvulkan.so`, or another Vulkan/Turnip-named shared library. The library may appear anywhere in the ZIP. When a package contains several ABIs, the importer prefers `arm64-v8a`/AArch64 candidates and validates each ELF instead of rejecting the ZIP after encountering an x86 or ARMv7 copy first. It extracts the complete package under app-private storage so sibling driver libraries remain available, and rejects unsafe paths and oversized archives.

The next game start routes Dawn's Vulkan loader through libadrenotools. If the custom loader fails, WiiCompiled opens the system Vulkan driver and records the fallback in the Graphics driver status. **Use system Vulkan driver** clears the selection without deleting imported packages. The APK includes only loader support; it does not bundle or download a Turnip driver.

## WheelWizard-compatible mod profiles

**Import WheelWizard mod ZIP** extracts a selected archive under app-private `files/wheelwizard/mods/`. ZIP paths and expansion sizes are validated. A WheelWizard `[Mod]` INI contributes its display name and author. The original ZIP is never executed, and the extracted PAL disc remains read-only input to the mod manager.

The current Android launch service supports:

- Riivolution packs containing a `riivolution` directory and their normal virtual-SD layout. The importer reads each XML's default option choices, honors a packaged `riivolution/config/RMCP.xml`, inherits option/choice/patch parameters, and materializes safe `<file>` and `<folder>` redirects into the Android disc overlay; non-recursive folders are limited to their direct children. Absolute SD paths, XML-relative paths, patch `root` values, PAL built-in variables, case-insensitive payload lookup, filename-only disc lookup, and offset/file-offset/length/resize/create semantics follow Dolphin's host-file behavior. Partial resources are rebuilt against the clean PAL file during import, and resulting U8/Yaz0 SZS files are converted to member-level patches for cross-profile composition. The retained private package exposes its choices through **Configure Riivolution options**; applying selections performs a staged rebuild with rollback. Memory, DOL, REL, savegame, and Dolphin system files remain outside this file-overlay stage and are reported as skipped or recompilation-dependent rather than copied incorrectly.
- Disc-shaped replacement trees such as `DATA/files/Race/...` or `files/Scene/...`.
- Flat full-file mods: a filename is mapped only when it occurs exactly once in the supplied PAL disc tree.
- Full Yaz0/U8 SZS replacements are diffed against the clean PAL archive during import and stored as member patches, allowing independent changes from multiple mods to compose. Raw SZS resources stay as whole-file replacements.
- Multiple enabled mods. Later imports start at higher priority; each profile can be raised, lowered, disabled, or removed independently.

WheelWizard tagged archives such as `modname.Common.szs` are retained as partial patches. Loose members such as `[button]icon.tpl.Channel` and empty `[button]old.tpl.delete.Channel` deletion markers use the same composition path. Existing U8 member names are matched without case sensitivity while preserving their original spelling, avoiding duplicate entries from packs whose filename case differs. The manager decodes the clean PAL target, applies enabled patches from lower to higher priority, rebuilds a deterministic U8/Yaz0 archive, and mounts the generated result above the ordinary overlays. The generated archive is cached in app-specific external storage and rebuilt on the background worker when profiles change, keeping heavy work off game startup and leaving the clean extracted disc unchanged. The one duplicated PAL tag, `Award`, is resolved from its package directory; a flat ambiguous `Award` patch is skipped rather than guessed.

Tagged `modname.revo_kart.szs` bundles and loose `[fileId]name.brwsd`/`.brbnk`/`.brseq` files are applied to `sound/revo_kart.brsar`. The composer accepts Pulsar-supported `RBNK`, `RSEQ`, and `RWSD` members, separates aligned `RWAR` wave data, rebuilds the FILE groups, and updates INFO sizes and offsets. A later full BRSAR replacement resets lower-priority sound patches before higher-priority members are applied.

Every imported profile receives a persistent executable-requirement manifest. It inventories Riivolution `<memory>` writes, `Loader.pul`, `Code.pul`, DOL replacements, and REL replacements with addresses, sizes, and SHA-256 hashes. The mod screen exposes the per-item compatibility result. The APK selects `libRetroRewind.so` only for `Code.pul` SHA-256 `ea93f9b8bf6d7696a807c1da5be724f1b0ec3eea563c1fdc1adfab10cb6c98e2`; the known PAL Pulsar loader branches are replaced by direct runtime selection. A different code revision or an arbitrary DOL/REL replacement blocks launch instead of being silently ignored or executed as PowerPC code. Two addresses (`0x800077C8` and `0x801938FC`) deliberately retain native HLE winners in the resolved profile.

## Current scope and remaining work

The base game and Retro Rewind now boot and accept touch input on the target tablet. Warm races and heavy menus have been observed at approximately 60 FPS. The complete Android-only base-runtime build has been verified on the target Lenovo tablet: it translated 29,637 functions, compiled 92 ARM64 units, linked an 88,232,312-byte private runtime, and activated it successfully. The Android-only Retro Rewind build is also verified: it translated 29,637 base and 3,837 mod functions, compiled 207 ARM64 units, linked an 88,303,336-byte base library and a 95,896,664-byte Retro Rewind library, activated the combined pack, and removed its successful-build intermediates. Cancellation, thermal, and low-storage behavior still need extended validation. Android background pipeline prewarming is limited to two workers so first-launch cache reconstruction does not starve gameplay; pipelines required by the current frame retain priority access to the worker pool. It remains an experimental port: extended races still need accuracy and ghost comparisons, lifecycle and suspend/resume need stress testing, audio latency needs tuning, NAND durability needs repeated interruption tests, and long-session thermals still need measurement. Online play is disabled in the Android configuration and has not been validated. The touch layout provides analog steering and the main GameCube controls, but still needs remapping and item gestures before it is a comfortable daily-use build.

WheelWizard is a .NET 10 Avalonia desktop application. Its mod metadata, priority, launch-overlay behavior, GameBanana catalogue and ZIP downloads, staged Retro Rewind distribution updater, full-SZS conversion, U8/Yaz0 tagged-archive application, and Pulsar-compatible BRSAR file-ID composition now have Android implementations using document URIs and app-private storage. Broader settings services remain to be adapted. The desktop lifetime, executable launch model, Windows setup contract, app auto-updater, URL registration, and pop-up/window assumptions cannot be repackaged unchanged. Setup-EXE process calls need in-process/JNI installation services on Android.

Upstream locks are in `upstream.lock.json`; tool downloads and hashes are in `toolchain.lock.json`. Patches are exported under `patches/`; never commit the ignored upstream checkouts or private artifacts. The full reproducible workflow is recorded in `BUILDING.md`.
