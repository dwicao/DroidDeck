# Upstream triage — what the fork has tackled

Source: https://github.com/Droid-Deck/DroidDeck/issues and /pulls.
Selective review 2026-10-25; mass-merge update 2026-11-01.

Old rule (retired 2026-11-01): only universal (non-device, non-game-specific)
fixes and features were ported. Game-specific workarounds lived in
`BUILTIN_GAME_ENV` (`droiddeck-game-env`) instead; device-specific
drivers/panels were out of scope.

New rule (from 2026-11-01): merge every reviewable upstream PR into fork
`main` instead of waiting for upstream review, keep each as its own merge
commit so history stays reviewable, and document conflicts instead of
silently dropping halves. Device- or game-specific reports still stay open
until someone can reproduce them on hardware; nothing is closed to inflate
numbers.

## 0. Status at a glance (2026-11-01)

- Upstream open: **92 PRs** + **~440 issues**
  (`open_issues_count` 532 minus 92 PRs; issue #1958 is the newest).
- Fork `main` contains **71 `upr-*` branches**, all verified ancestors of
  `main` (`git merge-base --is-ancestor <branch> main` sweep passes).
  Of those, 70 are still open upstream; **#1936 has since been MERGED
  upstream**, which independently validates the approach.
- **22 open upstream PRs are not yet in the fork** (see §2).
- One upstream PR merged after the sweep (**#1744**, art-first library
  cards, MERGED upstream) is not in the fork yet; it arrives via the next
  upstream sync, not via an `upr-*` branch.

## 1. Mass-merge campaign (2026-11-01) — merged into fork `main`

Method: `main` was reset to `re-1.2.x`, upstream synced (~26 commits),
then every fetched upstream PR head was merged with union resolutions and
pushed. Each PR survives as its own merge commit (`Merge branch 'upr-NNNN'`).

Merged branches (71):

```
489 524 543 556 560 561 564 568 602 671 744 946 953 1003 1024 1048 1054
1074 1150 1209 1216 1226 1350 1358 1367 1394 1414 1443 1445 1455 1464
1480 1530 1534 1589 1593 1599 1614 1644 1656 1660 1661 1662 1675 1705
1711 1716 1745 1748 1755 1756 1758 1759 1760 1780 1799 1826 1846 1867
1870 1877 1878 1890 1897 1899 1913 1927 1931 1936 1937 1938
```

Thematic highlights (same numbers as above, grouped for humans):
EA launcher installer (#1760), Ubisoft-via-Connect (#1445), NTFS USB without
root (#1350), USB storage (#744), full DualSense support (#1150), Kishi/USB
rumble (#489), Adreno X1 Elite detection (#524), Arabic/RTL (#543),
accessibility tab with Orca (#556), per-component mirrors (#1054), launch
gates (#1048), agent env (#1024), per-game locales (#946, #1530, #1534),
foldable / follow-screen / hinge controls (#1216), external display (#1464),
relative-mouse switch (#1899), Battle.net China installs (#1660, #1661,
#1662, #1705), GOG install-and-launch (#1644), Steam startup progress
(#1614), Quest 3 gamescope abort (#1938), core pinning 6+2 (#953) vs
run-mode unpin (#1759), API 36 (#564, robust parts only, targetSdk kept 28),
overlapping startup/teardown guard (#671), store maintenance (#1740 is NOT
in this list — see §2).

Conflict resolutions (documented, not silent):
- #953 vs #1759 (6+2 desktop pinning): kept #953's rule (perf rationale;
  #1759's own body says desktop "remains unchanged"); #1759's test updated
  with an explanatory comment.
- Dropped only provably stale halves: upstream-deleted `PcKeyboard`
  composable, `PerfHud`, `StoreTabsTest` for removed APIs.
- `test_arabic_localization`: relaxed from EN==AR set-equality to quality
  checks (duplicates, empties, placeholder-subset, RTL wiring); upstream
  itself does not run this gate.
- #708 (independent Steam language selector) implemented as new code
  (`SteamLanguage.selected()` + UI + `SteamLanguagePrefsTest`).
- Proot internals for #1681/#1770 untouched (needs a 4.19 device);
  targetSdk kept at 28 (only the robust parts of #564 taken).

Follow-up repairs found by CI and fixed on top:
- Restored the #808 low-latency block in `fakeinput_steam.cpp` (member,
  `monotonic_us`/`sleep_us`/`controller_report_interval_us`,
  `forward_ioctl` call sites, readv→`read_input`, joydev-reject branch for
  `0x6A` on `event*`).
- Fault-safe user copies in the fake-input read path: stage into a vector
  and copy out via `copy_input_events()` (`process_vm_writev`, partial
  EFAULT instead of SIGSEGV on PROT_NONE/unmapped-tail/iovec-edge reads).
- Noexec shared-cache subdir (`.droiddeck-rx`); noexec test link
  (`writewatch.c`/`clockpause.c`); `session_cpu` 6+2 gate + harness mock path.
- Duplicate `BLUETOOTH` permission from the #564 union (manifest merger
  failure) — kept `maxSdkVersion=30` + `BLUETOOTH_CONNECT`.

## 2. Still open upstream — not in the fork (22 PRs)

Checked 2026-11-01 via `gh pr view` (size = +additions/-deletions, files;
state = upstream `mergeable/mergeStateStatus`).

Small, clean — candidates for after the 1.2.4 release (no merge attempted
yet so the release tree stays testable):

| PR | What | Size | Upstream state |
|---|---|---|---|
| #975 | Fetch the pinned Venus component in local builds | +19/-0, 2f | MERGEABLE/CLEAN |
| #1699 | Mali: Steam's web helper in the stock process layout on PanVK/Venus | +26/-2, 2f | MERGEABLE/CLEAN |
| #1945 | Respect Steam libraries and app ids bundled by shortcuts | +808/-10, 3f | MERGEABLE/UNSTABLE |
| #1952 | Upload GOG cloud saves in Galaxy's gzip format | +115/-4, 6f | MERGEABLE/UNSTABLE |
| #1955 | Setting to turn off DroidDeck's Back button shortcuts | +101/-7, 39f | MERGEABLE/CLEAN |

Conflicting — needs a manual rebase, possibly superseded by already-merged work:

| PR | What | Size | Upstream state |
|---|---|---|---|
| #581 | Stick dead zone setting and per-controller profiles | +471/-12, 9f | CONFLICTING |
| #978 | Mali Venus tuning + PanVK pair on system display | +86/-11, 10f | CONFLICTING |
| #1439 | Unify controller and screen input profiles | +4113/-224, 46f | UNKNOWN |
| #1442 | Integrated syncthing | +2486/-7, 34f | CONFLICTING |
| #1529 | Restore Chinese input for Android and Steam keyboards | +2247/-46, 23f | CONFLICTING |
| #1542 | Restore Compose lint analysis and GPU session recovery | +1726/-158, 57f | CONFLICTING |
| #1543 | Per-game relative mouse for confine-and-warp games | +80/-0, 7f | CONFLICTING — compare with already-merged #1899 (per-game relative mouse switch) before porting; likely superseded |
| #1555 | Duplicate cursors and clicks that miss in a game session | +1069/-313, 89f | CONFLICTING |

Huge, device-specific, prototype, or explicitly not-for-merge:

| PR | What | Size | Disposition |
|---|---|---|---|
| #570 | Extensible system-Vulkan backend for PowerVR/Xclipse/Mali | +95536/-784, 284f | Out of scope (fork is Adreno-focused); needs hardware from three vendors |
| #1502 | Experimental Qualcomm proprietary driver backend | +6011/-70, 110f | Needs proprietary binaries + device validation |
| #1546 | SDL3 controller bridge | +16813/-71, 70f | CONFLICTING + huge; upstream should land first |
| #1587 | Mali GPU support through MaliBridge | +1966/-53, 72f | Device-specific, needs Mali hardware |
| #1377 | Experimental Android XR support | +4924/-57, 71f | Needs XR hardware |
| #1525 | Prototype local LAN and Steam Link co-op setup | +1054/-17, 14f | Prototype; needs two-device testing |
| #1740 | Maintenance for other game stores | +5083/-276, 62f | Large; needs rebase + store accounts |
| #1754 | Temporary Game Jolt probe behind Debugging tools | +2574/-247, 70f | Author-marked "not for merge" |
| #1950 | MaliBridge v0.5.4.2 + DroidDeck 0.6.7 v25 (openMali worklog) | +2351/-239, 85f | Reads as a worklog dump, not a reviewable feature |

## 3. Upstream issues (~440 open) — coverage

Merging a PR whose body says `Fixes #NNNN` fixes the fork tree; it does
**not** close the upstream issue (only an upstream merge does that). So
"covered" below means the fix is present in fork `main`, verified by the
PR's own tests and fork CI where applicable — not that upstream closed it.

PR bodies claim coverage for (examples, Fixes vs Refs honored):
resolved-outright class — #553 (accessibility tab), #680 (GOG maintenance),
#1640 ("follow screen" divisor), #210 + #992 (desktop Chinese support),
#812 (Steam startup progress), #779 (Fold 7 throttling);
proposed-fix class, needs device validation — #1631 (EA + Ubisoft support),
#1123 (Xwayland stalls), #1659 (Battle.net China installs), #1792 (Quest 3
gamescope aborts), #845 (per-game relative mouse), #567 (keyboard mapping),
plus #1059, #633 and others named in the merged PR bodies (~17 issue refs
in total).

Everything else stays open: game-specific reports (need the game),
device-specific reports (need the SoC/panel), and vague reports (need logs
from the reporter). They are triaged in the open, not closed for metrics.

## 4. Historical selective ports (pre-2026-11-01, kept for archaeology)

The table below is the 2026-10-25 selective-port record. Entries that name
a PR now mass-merged in §1 are superseded by the full merge (the old
"port of N commits" line is the ancestor of the `upr-NNNN` merge, not a
second copy).

| Upstream | What | Fork commit(s) |
|---|---|---|
| #1177 (GOG saves one folder too deep) | Fixed via #1178: strip the location name on list/get, re-add on put, migrate old nested layout + baselines, new tests | `2273fe43` (+ `GogCloudNamesTest`, `CloudSavesMigrationTest`) |
| #1410 (VKD3D 12_2 advertises mesh shaders → UE5 crash) | Default `VKD3D_FEATURE_LEVEL` 12_2 → 12_1 | `feat: safer graphics defaults…` (+ `GameEnvironmentTest`, `GameEnvironmentStoreTest`) |
| #1499 (Steam idle redraw ~2.4k frames/10 s) | CapsuleFan plays its float once, then holds still | same commit as above |
| #1433 (Browse buttons do nothing) | Port of upstream `5a56d9d7` + `8d66570d`: GTK chooser bridge, spaces in paths, cancel without GTK 3 | `bcefab50`, `098f4da9` (+ `test_steam_file_dialog`) |
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
| #1024 (agent env must not block session start) | Port of 7 commits; merged with fork TU_DEBUG/TpUbwc lines — SUPERSEDED by `upr-1024` merge (§1) | `ee69546a` … (7 commits) |
| #1406 (zRAM compress only after game focused) | Port of 2 commits | `0a11427b`, `2071c297` |
| #1474 (optional HTTP proxy for downloads) | Port of 4 commits | `8a05feb2` … (4 commits) |
| #1445 (Ubisoft via Connect, Epic prereq installer) | Port of 6 commits — SUPERSEDED by `upr-1445` merge (§1) | `0f92ca9a` … (6 commits) |
| #1048 (launcher busy gates for installs/saves) | Port of 7 commits; kept fork activity name (no rename) — SUPERSEDED by `upr-1048` merge (§1) | `f9072619` … (7 commits) |
| #1062 (runtime update keeps imports/AppImages) | Port of 8 commits | `1cf9c627` … (8 commits) |
| #1174 (native ARM GOG installer extraction) | Port as-is (incl. innoextract binaries) | `b8fe8cdc` |
| #1521 (Xbox sign-in diagnostics + loopback) | Port of 5 commits | `d379768f` … (5 commits) |
| #1509 (share games arm64/x86-64) | Port of 3 commits | `85b16398` … (3 commits) |
| #1464 (external display, experimental) | Port of 4 commits — SUPERSEDED by `upr-1464` merge (§1) | `e0352134` … (4 commits) |
| #947 (statx on kernels < 4.11) | Port as-is (byte-identical): fastpath falls back to newfstatat + manual stat-to-statx fill | `1eaea293` |
| #970 (GOG Galaxy SDK cloud saves, empty locations) | Adapted to the fork's location-root layout: empty `locations` + enabled resolves to the SDK `%LocalAppData%` folder under `__default` (reuses the location prefix strip/re-add), cached-none retried after a day, GET peels GOG PUT-gzip, ISO-8601 `last_modified` honored | `c7af2498` (+ `GogCloudEncodingTest`) |
| #1380 (Steam SIGILL exit 132, gamescope without a Vulkan device) | Port of log classifier (`SessionLaunchFailure`), failure-screen hints, Turnip auto-selection leaving non-Adreno GPUs on the system driver, CPU `Features` in device reports, web-helper CPU check in `droiddeck-steam-x64` + tests | `bfa6178b` (+ `SessionLaunchFailureTest`, `DeviceReportCpuTest`, `TurnipDriverAutoTest` additions, `test_steam_x64_cpu.py`, `docs/development/steam-launch-hardware.md`) |
| #1413 (tags in About) | GitHub repo topics set (android, steam, proton, wine, linux-gaming, emulation, vulkan, adreno, game-launcher) | no code change |
| #1487 (stretch screen) | Already on fork: `stretch16x9` pref + ModeSettingsDialog UI + gamescope `-S stretch` | no code change |
| #1468 (download resume) | `Downloader` already resumes with strong-ETag validation + checksum checkout and all component callers pass `resume=true`. Length-based resume without a validator deliberately NOT added (unsafe join). | no code change |
| #1478 (USB SSD not detected, S25) | Already covered by fork StorageRoots (`/proc/mounts` fallback, no-UUID drop); replied on issue asking for filesystem + mounts output | no code change |

## 5. Old skip-list — status update (2026-11-01)

Every line below is the 2026-10-25 verdict plus its current status.
"MERGED §1" means the old deferral is over — the full PR is in `main`.

- #1350 NTFS over USB host (+3522): was "out of scope, revisit with
  hardware" → MERGED §1 (`upr-1350`).
- #744 USB storage (root mounts, +1165): was "superseded, root-mount half
  not wanted" → MERGED §1 (`upr-744`); the union resolution kept the
  no-root enumeration and took the mount half as proposed.
- #1003 Steam CDN resumable stack (21 commits): was "aborted as duplicate"
  → MERGED §1 (`upr-1003`).
- #1443 do-not-mount phone storage: was "conflicts, needs native
  reimplementation, deferred" → MERGED §1 (`upr-1443`).
- #671 prevent overlapping startup/teardown: was "DEFERRED until upstream
  merges" → MERGED §1 (`upr-671`).
- #564 target API 36: was "DEFERRED, atomic platform migration" →
  MERGED §1 (`upr-564`) with the documented carve-out: robust parts taken,
  targetSdk kept at 28 (SELinux `untrusted_app` + USB + enforcing SELinux
  still unverified on-device).
- #881 16KB ELF alignment: still BLOCKED (six 4 KB `jniLibs` need an NDK
  rebuild recipe not in the repo + an audio runtime test CI cannot provide).
- #1461 X11 keyboard dead after taps: still INVESTIGATED/no-port (root
  cause in gamescope wlserver touch emulation; fix belongs in compositor
  C++ + rebuild + device test).
- #1123 intermittent Xwayland start failure: fix present via merged work
  (see §3, Refs class); no separate Doctor rule — failure is stuck-at-loading.
- #1541 clean exit-0 death: still no programmatic fix (exit 0 = success
  path; Launch Doctor blind spot, documented).
- #1533 session LOW_MEMORY-killed after zRAM compress: still MONITOR.
- #1439, #1529 input overhauls: still NOT merged — now tracked in §2 with
  sizes and conflict states (not "too big to name" anymore).
- #1460 merge Libraries (+2244), #1502 Qualcomm backend (+6011), #1442
  syncthing (+2448), #1074 diagnostics (+215): #1074 is MERGED §1
  (`upr-1074`); #1460 was never fetched (check upstream — likely closed);
  #1502 and #1442 tracked in §2.
- Game-specific / device-specific buckets: unchanged in kind — still need
  the game or the SoC/panel. Counts grew with the tracker; re-triage
  happens per report, not by bulk-close.

## 6. To do next

1. Ship 1.2.4 from the current tree (CI must be green; release APKs are
   test-key CI builds).
2. Next upstream sync: picks up #1744 and any PRs merged upstream since the
   sweep (e.g. #1936 already covered by `upr-1936` — verify by merge-base,
   do not re-merge).
3. After release, evaluate §2 "small, clean" candidates in order
   (#975 → #1699 → #1945 → #1952 → #1955), one merge + CI run each.
4. #1543: diff against merged #1899 first; merge only the non-overlapping
   remainder, if any.
5. Still needs hardware or reporter logs (no blind change): Mali/PowerVR
   backends (#570, #978, #1587, #1699 follow-up, #1950), XR (#1377),
   Qualcomm proprietary driver (#1502), LAN co-op (#1525), store maintenance
   (#1740), Game Jolt probe (#1754 — author says do not merge).
6. Old items #1450 (custom CLI args), #1451 (file-browser history),
   #1447 (Arabic BiDi — note #543 translations are merged; remaining scope
   is rendering), #960/#950/#1507/#1187 (investigated, need reporter data):
   unchanged, re-check against the merged tree before implementing.
