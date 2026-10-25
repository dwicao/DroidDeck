# Steam game environment

Open **Games → Game environment**, directly below **FEX preset**, in Steam
settings or the in-session drawer. Choose all Proton games, an installed game, or enter a Steam app ID. For a
non-Steam shortcut, use its numeric `compatdata` directory ID (unsigned 32-bit).
The drawer opens the editor on its selected game profile. That editing scope stays
fixed if Steam's selected game changes in the background; use the editor's own
profile picker to switch deliberately.
Changes apply on the next game launch, including when Steam is already running.
A running game must be restarted. Native Linux games and Steam itself are outside
this editor's scope.

Opening **Components** from a game's launch settings selects that game's profile.
Opening Components from the navigation rail selects **Default profile**. Use the
**Configuration** dropdown to switch profiles explicitly. **Default profile** owns the
default Proton and FEX preset. An installed game can inherit those defaults or
choose its own Proton and FEX preset. Proton choices are applied through Steam's
per-app compatibility mapping, including while Steam is running when its live
compatibility helper is attached. A newly installed Proton that the running
client has not discovered yet applies when Steam next starts.

Each game can also override the stored FEX, DXVK and VKD3D-Proton package used
by its selected Proton. The launch wrapper applies that game’s component files
immediately before Proton starts, removes managed files that are absent from the
selection, and restores Default component choices for the next game. **Use
Default** removes the game override. Component packages must first be downloaded
or imported on the Default Components page.

Before publishing game component choices, DroidDeck preserves each Proton build's
original components, including an explicitly empty bundle when a component is not
shipped. Those restore choices remain after the last override is cleared. A missing
or unreadable original is an error, not permission to delete the component's files.
If an original is missing after a build may already have used a game override,
DroidDeck refuses to capture those potentially modified files as originals.

## x86 Linux titles

Linux titles built for x86-64 are started through FEX. The session registers a
DroidDeck-owned compatibility tool for them, because the client's own choice for
these titles is a non-user mapping named `native` that the user cannot change.
The tool's launcher intercepts the path lookups the client makes and otherwise
starts Valve's `fex-compat-tool` from the FEX depot. It strips `GLIBC_TUNABLES`
first: the session sets it for proot, and a glibc up to 2.34 calls `__sbrk` while
parsing those tunables, which fails under FEX's guest `brk` emulation
(`sbrk() failure while processing tunables`) and kills the game at startup.
**Games → Components → FEX → x86 Linux titles** chooses between that Valve FEX
(`BL_FEX_TOOL=valve`, the default) and DroidDeck's own FEX build
(`BL_FEX_TOOL=droiddeck`, with the selected FEX preset) for the next session. Its
Gamelog toggle sets `BL_FEX_VERBOSE=1`, which adds FEX logging to the session logs.
`droiddeck-fex diagnose` probes FEX startup and a guest program with and without
the tunables, and writes `diagnose.json` beside the session log bundle.

Component selections and the launch table are saved with a recovery record.
Publishing the launch table commits the selection: if the app stops before its
settings file is updated, the next component read recovers the matching settings.
An interruption before publication leaves the previous selections in place.
Unused staged packages are removed only after both files have been saved.

**Reset game profile** removes all overrides owned by the selected game: Proton,
FEX preset, component packages, environment variables, texture filtering and
Windows components. The game then inherits the Default profile again.

The order is inherited process environment, selected FEX preset and built-in
settings, shared edits, then game-specific edits. Editor entries therefore win
over matching Steam launch-option variables. **Remove** explicitly unsets a
variable. **Restore inherited settings** removes the override. The environment
editor's **Clear environment overrides** action clears only the selected
environment entries and is intentionally narrower than **Reset game profile**.
Values are literal strings, with no shell expansion; quotes are only needed when
the consuming program expects them.

A Unity game under FEX (`UnityPlayer.dll` for Windows builds under Proton,
`UnityPlayer.so` or `GameAssembly.so` for native Linux builds started by
**Linux x86 (FEX)**) keeps FEX's TSO on when the Default profile's FEX preset
turns it off: without TSO such games can deadlock or crash (seen with Megabonk
and Crab Game). A FEX preset chosen for the game itself still applies.

## Defaults and available suggestions

The initial configuration enables Mesa shader caching with
`MESA_SHADER_CACHE_DISABLE=false`, sets `VKD3D_FEATURE_LEVEL=12_2` and
`VKD3D_SHADER_MODEL=6_9`, and preserves the selected FEX preset. Existing edits
and explicit removals take precedence over these defaults. FEX's own default
remains the default preset. Shader caching in VKD3D/DXVK, synchronization, ray
tracing and diagnostic logging otherwise retain the runtime's defaults.

On an Adreno 6xx, Turnip does not expose `storageBuffer8BitAccess`, which DXVK 3
requires. A Steam or desktop session installs DXVK 2.7.1 into any Proton whose
DXVK is still the copy that Proton shipped, including after a Proton update put
that copy back. A DXVK package chosen in Components stays for that Proton build.

The variable-name picker offers 32 predefined variables and a Custom entry. Like
WinNative, known variables use toggles, value dropdowns, multi-select lists, or
numeric/text fields. Feature-level and shader-model dropdowns also accept custom
values. Custom variables have editable names and literal values.

| Variable | Use |
| --- | --- |
| `VKD3D_FEATURE_LEVEL` | D3D12 capability override; defaults to `12_2`. |
| `VKD3D_SHADER_MODEL` | Shader-model override; defaults to `6_9`. |
| `VKD3D_CONFIG` | Per-game workarounds such as `nodxr`; not a universal performance preset. |
| `MESA_SHADER_CACHE_MAX_SIZE` | Storage budget for Mesa's shader cache, for example `1G`. |
| `mesa_glthread` | OpenGL threading; test with the affected game. |
| `DXVK_CONFIG` | DXVK configuration options; recent builds accept `dxvk.maxFrameRate = 60`. |
| `VKD3D_FRAME_RATE` | D3D12 frame-rate limit. |
| `DXVK_HUD`, `PROTON_LOG` | Optional diagnostics; leave unset for ordinary play. |
| `PROTON_USE_WINED3D` | D3D9–11 OpenGL fallback for compatibility testing. |
| `PROTON_USE_XALIA` | Proton's gamepad-navigation helper toggle. |
| `DROIDDECK_SYNC` | `off` launches this game on stock Proton, without DroidDeck's sync libraries. |
| `DROIDDECK_AUDIO` | `off` keeps DirectAudio out of this game's prefix and uses Proton's own audio. |
| `DROIDDECK_VERBOSE` | `1` has Proton keep a log of this game's launch with the session's logs. |
| `DROIDDECK_RESET_PREFIX` | `1` recreates this game's Proton prefix before the next launch, once. |

Forcing `12_2` or `6_9` changes reported capabilities; it cannot implement a missing
Vulkan feature. Remove either entry to use automatic detection for that capability.
Other picker suggestions are editable starting values and are enabled only when
added and saved. Older or
custom components can support a different set of options. Android Wine wrapper,
ALSA-server, Box64 and patched async-DXVK options are identified in the editor as
requiring a different/custom component; custom entries remain allowed. The old
`DXVK_FRAME_RATE` environment variable was removed from current DXVK, so it is
not offered as a suggestion. Prefer the session frame limiter for ordinary play.

## Launch path and references

WinNative's Linux session filters Android-only options, merges user variables
over the FEX preset, and passes the result to `env -i` before starting Steam. Its
Windows/Wine graphics helper also maps the selected feature level to
`VKD3D_FEATURE_LEVEL`. Reviewed source: [WinNative Linux session](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/runtime/display/XServerDisplayActivity.java),
[variable editor](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/shared/ui/widget/EnvVarsView.java),
[graphics configuration](https://github.com/maxjivi05/WinNative/blob/c9fbbb342f2689c852046804f4f5c9afa45b5dcb/app/src/main/feature/settings/drivers/DXVKConfigUtils.java).

DroidDeck instead publishes an atomic JSON snapshot at
`/root/.config/droiddeck/game-environment.json`. Both its Valve ARM64 Proton
wrapper and adopted third-party Proton wrappers execute `droiddeck-game-env`,
which reads that snapshot for each real game launch and starts Proton as its own
child. Probe prefix `compatdata/0` and non-launch verbs are unchanged. Malformed
configuration falls back to the inherited environment without evaluating its
contents. Both component and environment selection normalize signed non-Steam
prefix IDs to unsigned IDs.

Because the engine keeps Proton as a child rather than replacing itself with it,
it can see the launch through: it copies the game's and Wine's output into the
session log and into `games/<app id>.log`, records the exit status and how long
the game lasted, and passes a stop on to the game. A game that closes in seconds
therefore leaves its reason behind - Wine's `err:module` line for a library it
could not load, `err:seh` for a fault, Unity's own log, or Proton's log when
`DROIDDECK_VERBOSE=1` is set. Games always launch with
`WINEDEBUG=-all,+seh,+module` for those two error classes unless the game, the
shared profile or the launch environment already sets `WINEDEBUG`.

A stop from the client is forwarded to the game, and the session waits only for
the game's own process: output that a leftover process still holds open cannot
keep the client waiting after the game has ended.

The switches reach the launcher through `/usr/local/bin/droiddeck-game-setting`,
which reads the same snapshot for the app the prefix belongs to.
`DROIDDECK_SYNC=off` also ends a Wine server still running for that prefix, since
a server left from the previous launch is reused whichever binaries the next
launch names. `DROIDDECK_AUDIO=off` removes the driver from the prefix's own
`Drivers` registry key, where DirectAudio pins itself, so Proton's own audio
applies whether or not the registry entry was written earlier. Both are decided
before Proton starts, the prefix reset by deleting `pfx` and `version` and then
clearing itself from the app's settings.

After the launch, the session keeps the game's own diagnostic files beside the
session's other logs: `Player.log`, `Player-prev.log`, `output_log.txt`, crash
dumps under `LocalLow` and the user's `Temp`, and Proton's log when it was asked
for. Each is kept as its last megabyte (Proton's as the last four) with at most
sixteen files per launch, so a large log cannot fill the session's artifacts. A
listing of the game's own folder is kept with them as
`games/<app id>-install.txt`, including any Steamworks emulator files and whether
`steam_api64.dll` and `steamclient64.dll` carry an Authenticode signature: a
repack's copies are patched and unsigned, and their `steamclient64.dll` shadows
the client library Proton provides.

The same report answers the other way a game cannot start: a copied install
carries the manifest it came with - and Steam adopts a staged depot record of its
own the same way - so the client records the whole build as installed and never
asks for the part that never arrived. The folder's own size is read against that
record, and a Unity game's own Addressables catalog is read against the bundles
beside it, naming how many are missing and which parts of the game they belong
to. A game that stops within a minute while its folder is short says so in its
launch log as well, because nothing it prints can explain a load of a file that
is not there.

Per-game values are also read by the launcher itself for the DirectAudio switch
and by `droiddeck-game-env` for the sync switch, so both work without any client
change; the editor only writes the snapshot.

The published file also carries generated `dxvkConfig` and `dxvkConfigGames`
values the app's own file never has. The session menu's texture filtering
(`core/TextureFiltering`) becomes `d3d9/d3d11.samplerAnisotropy` and
`samplerLodBias` options. Default choices apply to games without overrides; each
game can save either choice independently or return it to **Use Default**. The
launcher appends the selected options to `DXVK_CONFIG` after the environment
profile, so a user's own `DXVK_CONFIG` entry keeps its options. "Auto" texture
sharpness is `-log2(panel / session)`, derived when the session is sized, and
applies from the next launch.

Upstream references: [VKD3D capability parsing](https://github.com/HansKristian-Work/vkd3d-proton/blob/master/libs/vkd3d/device.c),
[VKD3D options](https://github.com/HansKristian-Work/vkd3d-proton#environment-variables),
[Proton runtime options](https://github.com/ValveSoftware/Proton/tree/proton_11.0#runtime-config-options),
[Mesa variables](https://docs.mesa3d.org/envvars.html),
[DXVK variables](https://github.com/doitsujin/dxvk#environment-variables),
[DXVK frame-limiter changes](https://github.com/doitsujin/dxvk/releases).

Validation: `./gradlew testDebugUnitTest` and
`python3 -m unittest discover -s tools/tests -p test_game_environment.py`.
Actual game compatibility depends on the installed Proton, VKD3D and Vulkan driver;
these checks do not establish that every device supports feature level 12_2 or SM 6_9.
