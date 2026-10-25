# Upstream triage — what the fork has tackled

Source: https://github.com/Droid-Deck/DroidDeck/issues and /pulls, reviewed 2026-10-25.
Rule: only universal (non-device, non-game-specific) fixes and features are
ported. Game-specific workarounds live in `BUILTIN_GAME_ENV`
(`droiddeck-game-env`) instead; device-specific drivers/panels are out of scope.

## Ported to fork main (done, tested by CI)

| Upstream | What | Fork commit(s) |
|---|---|---|
| #1177 (GOG saves one folder too deep) | Fixed via #1178: strip the location name on list/get, re-add on put, migrate old nested layout + baselines, new tests | `2273fe43` (+ `GogCloudNamesTest`, `CloudSavesMigrationTest`) |
| #1410 (VKD3D 12_2 advertises mesh shaders → UE5 crash) | Default `VKD3D_FEATURE_LEVEL` 12_2 → 12_1 | `feat: safer graphics defaults…` (+ `GameEnvironmentTest`, `GameEnvironmentStoreTest`) |
| #1499 (Steam idle redraw ~2.4k frames/10 s) | CapsuleFan plays its float once, then holds still | same commit as above |
| #1433 (Browse buttons do nothing) | Port of upstream `5a56d9f7` + `8d66570d`: GTK chooser bridge, spaces in paths, cancel without GTK 3 | `bcefab50`, `098f4da9` (+ `test_steam_file_dialog`) |
| #1486 (GOG login Cloudflare bot check) | Port of upstream #1500: sign-in uses the WebView's own UA | `b8539d16` |
| #1354 (zero-copy launch log drawer switch) | Port as-is | `26733257` |
| #1527 (build local image when Podman missing) | Port as-is | `49927a37` |
| #1493 (stores description fill height) | Port as-is | `d98c163f` |
| #1113 (drawer game settings without Steam) | Port of 4 commits; combined Steam/added guards in follow-up | `5cd93fc5`, `5f93ed1e`, `8f2be49e`, `f9072619` |
| #1015 (install session status receiver) | Port of 4 commits | `177624c1` … (4 commits) |
| #1026 (chosen game art survives account switch) | Port as-is | `efe67937` |
| #1067 (update-channel guess) | Port of 2 commits | `ac8957ae`, `9b176753` |
| #1107 (reuse shortcut compatdata prefix) | Port of 4 commits | `66f41e08` … (4 commits) |
| #1209 (session keyboard out of password field) | Port of 3 commits | `35bf89d6` … (3 commits) |
| #1144 (refuse overwriting in-use components) | Core fix ported; unpacked-cache parts already on fork (equivalent), skipped as no-ops | `78cf16ff` |
| #1024 (agent env must not block session start) | Port of 7 commits; merged with fork TU_DEBUG/TpUbwc lines | `ee69546a` … (7 commits) |
| #1406 (zRAM compress only after game focused) | Port of 2 commits | `0a11427b`, `2071c297` |
| #1474 (optional HTTP proxy for downloads) | Port of 4 commits | `8a05feb2` … (4 commits) |
| #1445 (Ubisoft via Connect, Epic prereq installer) | Port of 6 commits | `0f92ca9a` … (6 commits) |
| #1048 (launcher busy gates for installs/saves) | Port of 7 commits; kept fork activity name (no rename) | `f9072619` … (7 commits) |
| #1062 (runtime update keeps imports/AppImages) | Port of 8 commits | `1cf9c627` … (8 commits) |
| #1174 (native ARM GOG installer extraction) | Port as-is (incl. innoextract binaries) | `b8fe8cdc` |
| #1521 (Xbox sign-in diagnostics + loopback) | Port of 5 commits | `d379768f` … (5 commits) |
| #1509 (share games arm64/x86-64) | Port of 3 commits | `85b16398` … (3 commits) |
| #1464 (external display, experimental) | Port of 4 commits | `e0352134` … (4 commits) |
| #947 (statx on kernels < 4.11) | Port as-is (byte-identical): fastpath falls back to newfstatat + manual stat-to-statx fill | `1eaea293` |
| #970 (GOG Galaxy SDK cloud saves, empty locations) | Adapted to the fork's location-root layout: empty `locations` + enabled resolves to the SDK `%LocalAppData%` folder under `__default` (reuses the location prefix strip/re-add), cached-none retried after a day, GET peels GOG PUT-gzip, ISO-8601 `last_modified` honored | `c7af2498` (+ `GogCloudEncodingTest`) |
| #1380 (Steam SIGILL exit 132, gamescope without a Vulkan device) | Port of log classifier (`SessionLaunchFailure`), failure-screen hints, Turnip auto-selection leaving non-Adreno GPUs on the system driver, CPU `Features` in device reports, web-helper CPU check in `droiddeck-steam-x64` + tests | `bfa6178b` (+ `SessionLaunchFailureTest`, `DeviceReportCpuTest`, `TurnipDriverAutoTest` additions, `test_steam_x64_cpu.py`, `docs/development/steam-launch-hardware.md`) |
| #1413 (tags in About) | GitHub repo topics set (android, steam, proton, wine, linux-gaming, emulation, vulkan, adreno, game-launcher) | no code change |
| #1487 (stretch screen) | Already on fork: `stretch16x9` pref + ModeSettingsDialog UI + gamescope `-S stretch` | no code change |
| #1468 (download resume) | `Downloader` already resumes with strong-ETag validation + checksum checkout and all component callers pass `resume=true`. Length-based resume without a validator deliberately NOT added (unsafe join). | no code change |
| #1478 (USB SSD not detected, S25) | Already covered by fork StorageRoots (`/proc/mounts` fallback, no-UUID drop); replied on issue asking for filesystem + mounts output | no code change |

## Deliberately skipped (with reason)

- #1350 NTFS over USB host (+3522): huge native feature; out of scope. Revisit only with hardware to test.
- #744 USB storage (root mounts, +1165): superseded on fork by the no-root enumeration work; root-mount half not wanted.
- #1003 Steam CDN resumable stack (21 commits): already upstream (= on fork via sync: #623 + #587 cover resume/fallback). Port attempt aborted as duplicate.
- #1443 do-not-mount phone storage: conflicts with fork Xbox binds/fast-path; needs a native reimplementation. Deferred.
- #671 prevent overlapping startup/teardown: DEFERRED until upstream merges. Fork session code diverged (storage-anywhere move stack, pacing block, Launch Doctor, OrphanReaper keep/release) and the PR author still flags device testing. Revisit post-merge as an adaptation, not a line-port.
- #564 target API 36: DEFERRED until upstream merges. Atomic platform migration (SELinux `untrusted_app` domain loses `execute_no_trans`/`execmod`, noexec preload rewrite, FGS types, AllFilesAccess centralization); the fork's storage-anywhere stack is unverified under the new domain and CI cannot test enforcing SELinux + USB drives. Revisit post-merge with on-device validation.
- #881 16KB ELF alignment: BLOCKED. The six 4 KB `jniLibs` (PulseAudio bundle, libltdl, libsndfile) need an NDK rebuild recipe that is not in the repo, plus an audio runtime test CI cannot provide. Confirmed present at 0x1000 in-tree; everything else is 0x4000+.
- #1461 X11 keyboard dead after taps: INVESTIGATED, no port. Root cause is in gamescope's wlserver touch emulation (emulated button press without `XSetInputFocus`); the fix belongs in the compositor C++ + rebuild + device test, not in the launcher. Same class as skipped input overhauls.
- #1123 intermittent Xwayland start failure: no Doctor rule — the failure is stuck-at-loading (no session end, no signature in the report). Needs a startup watchdog, not a post-mortem rule.
- #1541 clean exit-0 death (~3.5 min, license/timed check): no programmatic fix; documents a Launch Doctor blind spot (exit 0 = success path).
- #1533 session LOW_MEMORY-killed after zRAM compress: MONITOR. Possible correlation with the ported #1406 (compress after focus); no code change without reporter logs proving causation.
- #1439, #1202, #1216, #1305, #1529 (input overhauls, +1k–7k): too big/risky to port blind; upstream should land them first.
- #1460 merge Libraries (+2244), #1502 Qualcomm backend (+6011), #1442 syncthing (+2448), #1061 debug logs (+1278), #1074 diagnostics (+215), #1209 done above; #1042 cancelled download (−), #1007 3 Mbps (needs diagnosis, maybe same as #1003 area).
- Game-specific: #1522 Skyrim XACT (needs component install, not just env), #1472 Cyberpunk TU_DEBUG, #1471, #1477, #1490, #1489, #1526, #1037, #1040, #1043, #1126, #1069, #1093, #1120, #1142, #1147, #1180, #1185, #1196, #1212, #1223 (same class as #1478, covered), #1059 (same class as #1478, covered).
- Device-specific: #1526 Mail-G720, #1408/#1201 Adreno 850, #969/#1019 SD8 Gen6, #974 Adreno 630, #988 MT6877, #934/#1082/#1109/#1131 MediaTek/Xiaomi/RedMagic, #1106 MatePad, #1123 Meizu, #1011 Exynos/MTK, #1108 Honor, #1072 Pad 8, #1073 SD710, #1012 OnePlus 5T, #922 Nothing Phone, #940 A55, #1436 Retroid Nova, #1052 Odin 2, #1228 A16, #936 Dimensity, #934 Redmi, #1469 SD8Elite Gen6, #1470 Quest 2 VR, #944 Quest Link, #1394 HyperOS phantom (upstream-owned), #1465 Xbox store (needs account; untestable here), #1476 launcher-as-home (product decision for upstream), #1475 proton version request (vague), #1448/#1051/#1008/#1021/#985/#1119 (no actionable content), #1176 launcher-as-home dup.

## To do next (universal, not yet implemented)

- #1450 custom CLI args for “Start without Steam” (needs per-game UI: storage + drawer field + wire-in).
- #1451 file-browser history/Back/game-save shortcuts (FileManager UX work).
- #1447 Arabic BiDi rendering (scope check first).
- #960 Steam overlay FPS vs LSFG overlay (investigated, likely not a bug: Steam counts game-presented frames = base FPS, LSFG/device overlay counts compositor output frames = 2x with LSFG 2x. Matches the reporter's numbers. Action: FAQ line, no code).
- #950 display pinned to 60 Hz + CPU ramp (investigated: `PerfMode` already requests the best display mode via `preferredDisplayModeId`, `refreshHz()` prefers requested over running rate, and gamescope gets `-r $BL_FPS/$BL_REFRESH`. On the reporter's device the panel may not expose 165 Hz to the app, or the OS holds 60 Hz without touch input. Needs `dumpsys display` + session log from the reporter; no blind change).
- #1507 gamepad halves FPS (hypothesis: input/polling cost or vsync interplay on SD Elite; needs per-device profiling with MangoHud frametime split. No blind change).
- #1187 Xbox library limited to 11 games (needs Xbox account; untestable locally — help wanted).
- #1468 download resume: investigated — `Downloader` already resumes with strong-ETag validation and checksum checkout; length-fallback without a validator deliberately NOT added (unsafe). Remaining work is diagnosis, not code.
- Deferred #1443 port (see above) and deferred move-safety stack (#1520 follow-up: journal/cancel for moves).
- #1501 missing OpenAL32.dll (DiRT 3): DONE via the Launch Doctor missing-DLL rule — installs the providing component (oalinst/xact/d3dx9), enables it in the game's picks, and retries.
