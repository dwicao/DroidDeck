<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="artwork/droiddeck-banner-dark.svg">
    <img alt="DroidDeck" src="artwork/droiddeck-banner-light.svg" width="100%">
  </picture>
</p>

<h3 align="center">Blazing Fast Performance</h3>

DroidDeck brings the SteamOS experience to Android: Valve's Steam client in Big Picture on your Adreno handheld, with Windows games through Valve's ARM64 Proton.

> **This fork.** This tree carries the 25 fork commits through `7dbf0842` plus the upstream 0.5.0 merge. The APKs published here are test-key builds in [this fork's Releases](https://github.com/dwicao/DroidDeck/releases); upstream releases do not include these changes.

<p align="center"><a href="https://discord.gg/JRGAvawjsm"><img src="https://img.shields.io/badge/Discord-Join%20the%20community-5865F2?logo=discord&logoColor=white" alt="Join the DroidDeck Discord"></a></p>

<p align="center"><img src="docs/releases/media/0.2.0/launch-into-steam.gif" width="80%" alt="Tapping DroidDeck on the Android home screen and landing in Steam Big Picture"></p>

> Note: DroidDeck does not have a stand-alone website. Do not click on any download links from websites claiming to be the DroidDeck team. 

## Requirements and install

Use Android 9 or newer on a supported Adreno device (730 or newer, or 8xx). Adreno 6xx is experimental: DirectX 11 uses DXVK 2 and may run, and DirectX 12 games can still crash. Mali, Xclipse, PowerVR, and Adreno 710 are unsupported. No root is required. Allow about 3 GB for the runtime and 1.1 GB more for the desktop and emulators. Install the APK from [Releases](https://github.com/dwicao/DroidDeck/releases), install the Linux runtime, then press **Play** and sign in. Steam downloads on first launch. Install **Desktop & apps** to use the desktop and emulators. The **Store** installs Linux apps and games from Flathub (ARM64 builds) with Flatpak; with additional options to install Appimages and set up scripts. **Stores** (Setup › Stores › "Show Stores in the rail") signs in to GOG, Epic Games and Amazon Games and downloads games into the Games storage. Epic keeps its direct API install path. Opening Epic Stores prepares Epic’s official Linux launcher preview. When installed, Epic games launched from DroidDeck use that client, and games installed in the client are imported into DroidDeck’s library and shortcuts. The x86-64 client runs through FEX on ARM and DroidDeck retains its Proton hooks; this preview and ARM setup are outside Epic’s listed Linux support. The cog on a store’s chip row sets its opening tab and download speed tier.

On Android 14 and newer, before Steam launches, you must turn on **Disable child process restrictions** in Developer options. If this option is not available in developer settings (Android 12 and 13 devices), first launch of Steam will present a "Fix it for me" button, which will help automate the setup process.

## Smart glasses

Use **Setup → Smart glasses** for app-wide virtual-screen settings across DroidDeck menus, launchers, desktop sessions, and games. Supported VITURE, RayNeo, and XREAL connections provide 3DoF; Luma Ultra adds optional experimental 6DoF and stereo-assisted hand/Joy-Con tracking. Model support and SDK requirements differ: see the [smart-glasses support matrix](docs/smart-glasses-support.md) for supported devices, controls, setup, and limitations.

## Performance and thermals

The **Performance** page can hold the GPU at the top level the platform allows. **Thermal guard** is
off by default: when enabled, it releases that hold as the thermal headroom reaches the limit and
restores it after the device cools, trading the top clock for a softer landing instead of letting
the platform drop straight to its floor. Neither setting can raise a level the platform's thermal
engine has already taken away.

On Samsung devices the vendor game policy also applies. Set **Gaming Hub → ⋮ → Game Booster → Game
optimisation → Performance**; `device.txt` reports the active `Game Mode` and the hottest thermal
zone. On an SM-F946B running NSUNS4 at 1280x720, 0.4.11 held 30 FPS (60 with LSFG 2x) at moderate
thermal, mostly at 475 MHz with occasional 295 MHz. A cooler mount and a charger with headroom
remain the reliable ways to keep a higher ceiling.

## Fork changes

The 25 fork-only commits through `7dbf0842` fall into four groups; this build merges them with upstream 0.5.0. The latest build is **0.5.0 (21)**; the complete list is at the end of this section.

### Session pacing, GPU, and thermals

- Guest pacing follows the panel's real refresh rate instead of the UI thread's vsync average, so
  DeX sessions do not drift below the panel when the phone's screen sleeps. LSFG derives the guest's
  base rate from the panel and multiplier, a display rate is snapped to a mode the panel actually
  has, and a chosen limit that cannot land on the panel is reported instead of silently changed.
- `perf.csv` is sampled every two seconds with each cluster's clock against the platform ceiling,
  the GPU clock/floor/load/power level/temperature, thermal status and headroom, online mask,
  memory, battery current/power/temperature, and the hottest thermal zone. `device.txt` gains the
  paced numbers and a pacing block on every plan change, including at the end of the session.
- The GPU pin is on by default, re-asserts through the session, and cycles KGSL's `PWRCTRL` when the
  platform lifts a clamp, so a clock left at 475 MHz while the ceiling is back at 719 MHz is taken
  back. The report names the lever, the platform level, and why the level node refused a write.
- The SM8550/QCS8550 default core set no longer keeps the prime core away from games: the client
  keeps core 7 and the game may share it.
- **Thermal guard** is opt-in (Performance -> GPU). It releases the pin at 0.89 headroom once the
  framework is at LIGHT, and restores it at 0.82 after at least eight seconds. Reports also carry
  the active Game Mode, hottest zone, battery current/power, and guard state. The first 1280x720
  field session on 0.4.11 held a stable 30 FPS base and 60 FPS with LSFG at MODERATE thermal, mostly
  at 475 MHz with occasional 295 MHz.

### x86 Linux games and Steam sessions

- x86-64 titles work through FEX with Steam Linux Runtime 3.0 on both `sniper_platform_*` and
  `soldier_platform_*` layouts. RootFS is prepared every Steam session, and Valve's
  `fex-compat-tool` is supported as well as DroidDeck's wrapper.
- x86 Linux titles register a `droiddeck-x86` mapping that drops `GLIBC_TUNABLES`, avoiding the
  glibc `sbrk()` abort under FEX. **Components > FEX > x86 Linux titles** chooses Steam FEX or
  DroidDeck FEX and can enable FEX logging.
- Setup names missing FEX/runtime depots and offers to install them. A stray Steam client from a
  session that did not shut down is reaped before the next launch, so the new session does not
  forward its arguments to the old client and exit.
- Imported installs finish stalled staged updates, carry DepotDownloader configuration, and search
  by normalized folder names or the game's own `steam_emu.ini` when the store listing spelling
  differs. Import fingerprints tolerate snapshots recorded before `steam_id` was added.

### Game kits, installs, and launch diagnostics

- Game kit export/import packages a Steam install with its manifest and `InstalledDepots` as a
  `tar.zst` archive, and writes the manifest last on import so Steam adopts the copied install
  without downloading it again. **Export kit** is a per-game button; import is under **Setup >
  Storage > Steam games**.
- Each game launch writes `games/<app id>.log` with the command, prefix, install folder,
  environment, Proton/Wine/Unity output, exit timing, Player/crash logs, and a module report that
  flags patched or shadowing Steam DLLs. Per-game environment switches are `DROIDDECK_SYNC=off`,
  `DROIDDECK_AUDIO=off`, `DROIDDECK_VERBOSE=1`, and `DROIDDECK_RESET_PREFIX=1`.
- A launch that stops early is measured against its manifest and, for Unity Addressables titles,
  its catalog; a short install is named with the missing bundle count instead of looking like a game
  that simply exited.

### Build and documentation

- CI hosts the Debian bullseye image used by the preload build from a release rather than Docker Hub,
  avoiding the rate limit that failed the build.
- Documentation and tests in the range cover the pacing table, GPU ceiling, chosen-limit note,
  short-install record, and the 0.4.11 field session. This release is 0.5.0 (21).

<details>
<summary><strong>All 25 commits through <code>7dbf0842</code></strong></summary>

| Commit | Area | Change |
| --- | --- | --- |
| [`18fdcc7a`](https://github.com/dwicao/DroidDeck/commit/18fdcc7a) | Pacing | Pace the guest from the panel's own refresh rate |
| [`86bbb4c1`](https://github.com/dwicao/DroidDeck/commit/86bbb4c1) | Game kits | Export/import a Steam install and manifest |
| [`527ca3dc`](https://github.com/dwicao/DroidDeck/commit/527ca3dc) | x86 | Name a missing x86 runtime instead of letting the game exit |
| [`e69928fa`](https://github.com/dwicao/DroidDeck/commit/e69928fa) | Steam | Reap a stray client before the next session |
| [`95bb912b`](https://github.com/dwicao/DroidDeck/commit/95bb912b) | x86 | Run on the soldier runtime; expose Export kit per game |
| [`31a129b1`](https://github.com/dwicao/DroidDeck/commit/31a129b1) | x86 | Support Valve's `fex-compat-tool`; prepare RootFS every session |
| [`31629dc8`](https://github.com/dwicao/DroidDeck/commit/31629dc8) | x86 / imports | Drop `GLIBC_TUNABLES` for x86 titles; finish stalled imports; fix store search |
| [`44c11b66`](https://github.com/dwicao/DroidDeck/commit/44c11b66) | Imports | Keep imports confirmed by older fingerprints |
| [`c3d2760d`](https://github.com/dwicao/DroidDeck/commit/c3d2760d) | CI | Host the Debian image the preload build loads |
| [`5f2085e2`](https://github.com/dwicao/DroidDeck/commit/5f2085e2) | Diagnostics | Per-game launch log and post-exit switches |
| [`7d822c7f`](https://github.com/dwicao/DroidDeck/commit/7d822c7f) | Diagnostics | Report an install that is short of its own manifest |
| [`a4eed074`](https://github.com/dwicao/DroidDeck/commit/a4eed074) | Docs | Explain what a short install does |
| [`4a775f16`](https://github.com/dwicao/DroidDeck/commit/4a775f16) | Pacing | Pace for the panel and record platform caps |
| [`45ff5edf`](https://github.com/dwicao/DroidDeck/commit/45ff5edf) | CPU | Stop excluding the prime core from the game's core set |
| [`e14c4246`](https://github.com/dwicao/DroidDeck/commit/e14c4246) | Release | 0.4.8 |
| [`ced7a1b9`](https://github.com/dwicao/DroidDeck/commit/ced7a1b9) | Docs | Say which numbers the pacing report carries |
| [`ba5a3bcc`](https://github.com/dwicao/DroidDeck/commit/ba5a3bcc) | GPU | Hold the GPU clock by default and re-assert it |
| [`1fe12f94`](https://github.com/dwicao/DroidDeck/commit/1fe12f94) | Tests | Report a chosen limit that overshoots the panel |
| [`54159630`](https://github.com/dwicao/DroidDeck/commit/54159630) | Docs | Read the GPU ceiling, not its floor |
| [`ad350d97`](https://github.com/dwicao/DroidDeck/commit/ad350d97) | GPU | Take the GPU's top back when the platform lifts a clamp |
| [`3b2eee95`](https://github.com/dwicao/DroidDeck/commit/3b2eee95) | Docs | Record what 0.4.10 found in the 0.4.9 session |
| [`2f179b59`](https://github.com/dwicao/DroidDeck/commit/2f179b59) | Release | 0.4.10 |
| [`02623e76`](https://github.com/dwicao/DroidDeck/commit/02623e76) | Thermal | Report GOS mode and thermal data; add the opt-in guard |
| [`13e5a875`](https://github.com/dwicao/DroidDeck/commit/13e5a875) | Thermal | Gate the guard on thermal LIGHT and word a released pin |
| [`7dbf0842`](https://github.com/dwicao/DroidDeck/commit/7dbf0842) | Docs | Record the 1280x720 thermal-guard result |

</details>

## Community

Join the [DroidDeck Discord](https://discord.gg/JRGAvawjsm) for help, Preview builds, and device reports. Bug reports go in its **#bug-reports** forum; attach the zip from **Share logs** (Setup, or the session drawer) so the logs come with it.

## Build

Run `tools/build_local.sh` with Docker, Java 17, the Android SDK/NDK, Rust through rustup, GitHub CLI, Python 3, and `zstd` installed. It prepares the same native libraries, pinned components and audio sinks as CI, then checks the APK's assets, JNI exports and dependencies. The APK is at `app/build/outputs/apk/release/app-release.apk`. Set `DROIDDECK_PA13_SOURCE_DIR` to an existing PulseAudio 13.0 source directory to skip downloading it. Set `DROIDDECK_SIGNING_ENV` to a signing environment file to install over a release build; relative keystore paths resolve beside that file. To install on an attached device, run `tools/deploy_local.sh`.

After preparation, ordinary `./gradlew :app:assembleRelease` builds can reuse the verified inputs. If native sources, component pins or staged files change, packaging stops with instructions to rerun the helper. Generated audio stays under `app/build/`; the tracked base bundle is never modified. `./gradlew -PskipRust=true :app:testDebugUnitTest` needs no native preparation. For an APK without store engines, use `DROIDDECK_SKIP_RUST=1 tools/build_local.sh`; it omits cached engine libraries too.

For the prepared Windows checkout, run `./tools/run_windows.ps1` (optionally with `-Serial <adb-serial>`), or select **DroidDeck XR** in Android Studio and run the **app** module on the connected phone. Machine-specific options live in the ignored `local.properties`: `droiddeck.python` selects a Python executable, `droiddeck.skipRust=true` omits the optional store download engines, and `droiddeck.debugApplicationIdSuffix=.xr` installs **DroidDeck XR Dev** alongside the signed release. The development app has its own runtime and Steam login. The local Java selection is in `.gradle/config.properties`.

`python tools/prepare_windows_runtime.py` restores this checkout's audited runtime inputs from the pinned CI APK, three locally rebuilt preload libraries, and the checksum-pinned Gamescope archive in `build/windows-setup/`. Start Docker Desktop's Linux engine first: preparation now rebuilds DroidDeck's ARM64 VR driver and Vulkan layer from current source and runs their native regression tests. The compiler image is built automatically on first use. Vulkan headers come from the NDK beside `--llvm-strip`, or from an explicit `--vk-include <NDK-sysroot-include>` directory.

To reuse that build, pass `--rebuilt-vr build/windows-setup/vr`. Preparation verifies its `vr-build.json` against the current Git revision, source files, Vulkan headers, output checksums, and ARM64 architecture; missing or stale builds fail instead of falling back to the donor's VR libraries. The script checks the actual donor CI merge tree, still rejects unrelated unaudited runtime changes, and records component provenance in `app/build/windows-runtime-provenance.json`. Preserve these ignored preparation files when rebuilding locally; `clean` removes the prepared assets and their verification manifest.

Run the helper and preload regression suite with `bash tools/test_local.sh`. On macOS it runs in Linux through Docker, matching the helpers' runtime and CI instead of compiling Linux preloads against macOS headers.

## Limits

Compatibility and performance vary by device; hardware validation is limited. Desktop compositing uses software rendering. Firefox sandboxing is reduced under proot. Logs are kept app-private (`files/logs/`, the last 30 sessions); **Share logs** packs the newest session, with the stores and tool logs, into one scrubbed zip.

## Credits and licence

GPL-3.0. Runtime, shim, input, and controller work build on WinNative and Bannerlator (maxjivi05). Epic Games and GOG support build on [Phobos’s](https://github.com/phobos665) adaptation of [Legendary](https://github.com/legendary-gl/legendary) and [gogdl](https://github.com/Heroic-Games-Launcher/heroic-gogdl), respectively. The registry entries a GOG game's install script (`goggame-<id>.script`) sets are read the way [Heroic Games Launcher](https://github.com/Heroic-Games-Launcher/HeroicGamesLauncher)'s GOG setup (GPL-3.0) reads them. LSFG frame generation is from the work of Camille LaVey and the [Eden](https://eden-emu.dev) emulator project, following [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk), ported to WinNative and DroidDeck by [@maxjivi05](https://github.com/maxjivi05); it needs your own copy of [Lossless Scaling](https://store.steampowered.com/app/993090/) and ships none of its shaders. x86 AppImages, and any whose own runtime cannot unpack them, are unpacked with [uruntime](https://github.com/VHSgunzo/uruntime) by VHSgunzo (MIT), shipped unmodified with its licence, which also starts the arm64 emulator AppImages whose own runtime cannot run its extractor on Android. The Steam client's audio goes through [@The412Banner](https://github.com/The412Banner)'s [DirectAudio](https://github.com/The412Banner/directaudio) PulseAudio sink (LGPL-2.1-or-later), built from `tools/directaudio/sink` with its request size kept to its ring so clients are not asked to queue 100 ms ahead. mmap ring-buffer controller input was developed by ribbit384 ([WinNative #345](https://github.com/WinNative-Emu/WinNative/pull/345)). Why the x86-64 Steam client refused the arm64 client's sign-in was checked by reading Steam's saved token with the `ConnectCache` format from [deadlock-api-ingest](https://github.com/deadlock-api/deadlock-api-ingest). x86 programs that link OpenSSL 3 under FEX use the copy in the [Steam Linux Runtime 4.0](https://gitlab.steamos.cloud/steamrt/steamrt) platform. Hooks written with `WriteProcessMemory` into code [FEX](https://github.com/FEX-Emu/FEX) has translated go through `/proc/<pid>/mem` again when the kernel refuses `process_vm_writev`, as [Wine](https://gitlab.winehq.org/wine/wine)'s `server/ptrace.c` wrote them before Wine 11. x86 Linux games under FEX get the same `/etc/asound.conf` that [pressure-vessel](https://gitlab.steamos.cloud/steamrt/steam-runtime-tools) writes into the Steam Linux Runtime, so ALSA plays through PulseAudio. Games render with [Mesa](https://gitlab.freedesktop.org/mesa/mesa) Turnip's own per-render-pass choice between tile memory and system memory, while the Steam client keeps `TU_DEBUG=sysmem`; [FEX](https://github.com/FEX-Emu/FEX)'s code disk cache was checked in its source and is already on through Proton. See [LICENSE](LICENSE). Steam and Proton belong to Valve Corporation; this project is not affiliated with Valve.

XR glasses support builds on WinNative's VITURE glasses work. VITURE protocol details were checked against [XRLinuxDriver](https://github.com/wheaney/XRLinuxDriver) by [@wheaney](https://github.com/wheaney), including the VITURE protocol header it ships, [ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs) by [@badicsalex](https://github.com/badicsalex) and the [go-macos/iokit VITURE package](https://pkg.go.dev/github.com/go-macos/iokit/viture); the optional VITURE SDK belongs to VITURE and is not included. RayNeo support adapts [@informalTechCode](https://github.com/informalTechCode)'s [RayNeo-Air-Series-3DoF-Scaffold](https://github.com/informalTechCode/RayNeo-Air-Series-3DoF-Scaffold) (Apache-2.0) and references [@verncat](https://github.com/verncat)'s [RayNeo-Air-3S-Pro-OpenVR](https://github.com/verncat/RayNeo-Air-3S-Pro-OpenVR) (MIT) and [Monado](https://gitlab.freedesktop.org/monado/monado) (BSL-1.0); see [docs/third-party/rayneo](docs/third-party/rayneo/README.md). XREAL 1S support references [XRLinuxDriver](https://github.com/wheaney/XRLinuxDriver) and [xrealOneDeviceKit](https://github.com/wheaney/xrealOneDeviceKit) by @wheaney, [xreal_one_driver](https://github.com/rohitsangwan01/xreal_one_driver) by [@rohitsangwan01](https://github.com/rohitsangwan01) (MIT) and [xr-tools](https://github.com/0xcaff/xr-tools) by [@0xcaff](https://github.com/0xcaff). The REDMAGIC glasses-display findings draw on [@mekhontsev](https://github.com/mekhontsev)'s [MagicDesk investigation](https://github.com/mekhontsev/magicdesk/blob/main/docs/xr-resolution-fix.md) and [@Coding-BR](https://github.com/Coding-BR)'s [decompiled Nubia kernel](https://github.com/Coding-BR/android_kernel_nubia_sm8850_qwjujube). VITURE, RayNeo, XREAL and REDMAGIC are trademarks of their owners; this project is not affiliated with them.

Joy-Con VR controllers port the arm model of Google's [Google VR SDK for Unity](https://github.com/googlevr/gvr-unity-sdk) (Apache-2.0), adapt the hand skeleton of Valve's [OpenVR](https://github.com/ValveSoftware/openvr) `handskeletonsimulation` sample (BSD-3-Clause), which follows Moshi Turner's hand simulation in [Monado](https://gitlab.freedesktop.org/monado/monado), and use the Index controller offsets from Monado's `vive_poses.c` (BSL-1.0). Joy-Con behaviour was researched in Linux's [hid-nintendo](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-nintendo.c) driver by Daniel J. Ogorchock, [@dekuNukem](https://github.com/dekuNukem)'s [Nintendo_Switch_Reverse_Engineering](https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering), [SDL](https://github.com/libsdl-org/SDL) and the Android Open Source Project, and checked against [ALVR](https://github.com/alvr-org/ALVR), [OpenComposite](https://gitlab.com/znixian/OpenOVR), [PSMoveSteamVRBridge](https://github.com/HipsterSloth/PSMoveSteamVRBridge), [@F8LEFT](https://github.com/F8LEFT)'s [JoyConVr](https://github.com/F8LEFT/JoyConVr), [@krnelpan1c](https://github.com/krnelpan1c)'s [Daydream-Controller-SteamVR](https://github.com/krnelpan1c/Daydream-Controller-SteamVR), [@yoyobuae](https://github.com/yoyobuae)'s [joyconvr](https://github.com/yoyobuae/joyconvr), [Moonlight](https://github.com/moonlight-stream/moonlight-android) and [Dolphin](https://github.com/dolphin-emu/dolphin); see [docs/third-party/joycon](docs/third-party/joycon/README.md). Nintendo Switch and Joy-Con are trademarks of Nintendo; this project is not affiliated with Nintendo.

Camera hand tracking runs Google's [MediaPipe](https://github.com/google-ai-edge/mediapipe) Tasks Vision library with its Pose Landmarker (Lite) model, BlazePose GHUM 3D, and its Hand Landmarker model (all Apache-2.0), on frames from AndroidX [CameraX](https://developer.android.com/jetpack/androidx/releases/camera) (Apache-2.0); MediaPipe's usage reports are switched off. The design was checked against [@F8LEFT](https://github.com/F8LEFT)'s [JoyConVr](https://github.com/F8LEFT/JoyConVr) camera app (MIT), and the short Joy-Con movements follow the accelerometer mode of the [Google VR SDK for Unity](https://github.com/googlevr/gvr-unity-sdk)'s arm model, Eric Foxlin's zero-velocity updates and Frosio, Pedersini and Borghese's accelerometer autocalibration, and the camera and Joy-Con are joined by a Kalman filter (R. E. Kalman, 1960). Bare camera hands take their gestures after [ALVR](https://github.com/alvr-org/ALVR)'s `hand_gestures.rs` (MIT) and Meta Quest's menu pinch, with distances checked on photos from the [HaGRID](https://github.com/hukenovs/hagrid) gesture set (CC BY-SA 4.0, not included), fit the palm with Horn's quaternion method (1987) and smooth it with the 1€ filter (Casiez, Roussel and Vogel, 2012); the white glove model follows Valve's OpenVR render model reference and Index controller poses, is meshed with surface nets (Gibson, 1998; Mikola Lysenko) and blends and shades it with Inigo Quilez's smooth minimum and distance-field ambient occlusion, porting his polygon distance (MIT); see [docs/third-party/handtracking](docs/third-party/handtracking/README.md).

The Store installs and runs apps with [Flatpak](https://github.com/flatpak/flatpak) (LGPL-2.1). Its sandbox goes through DroidDeck's own bubblewrap shim, which follows how Flatpak hands bwrap its environment.
