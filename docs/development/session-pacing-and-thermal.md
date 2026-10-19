# Session pacing, core allocation and what a phone's thermal engine does

Started 2026-10-19 for 0.4.8, after NSUNS4 (349040) on an SM-F946B (Snapdragon 8 Gen 2,
Adreno 740) held 30 fps for a few minutes and then settled at 14–18 fps with LSFG 2x armed.
Findings are added as they are measured on the device, so this reads in order.

## The question

A session that starts fast and settles lower looks identical from the panel whether the guest is
paced too slowly, the platform is taking clocks away, or the host's own work is stealing the frame.
0.4.7 could only say "thermal moderate" next to a frame rate; it could not say what changed.
So: what is measurably happening, and which of it is ours to fix?

## What is ours and what is not

Ours:

- How fast the guest is asked to draw, and how often the compositor's layer is sampled.
- Which cores each side of the session gets.
- How much work the guest is asked to do that the panel never shows (a frame rate above what the
  panel can present, or above what a frame generator can consume).

Not ours:

- `scaling_max_freq`, `scaling_min_freq` and the GPU's power levels. These are the platform's
  thermal engine, writing straight into cpufreq and KGSL. No app-side switch removes them; Android
  exposes no API for it and the nodes are not writable by an app.
- The floor under every cluster. On the SM-F946B the little cluster idles *at* 1228 MHz and the
  performance clusters are held above 1.2 GHz even when nothing runs: the platform keeps a minimum
  frequency high enough that the device is warm before a session starts.

The app can lower the load and hold the rate it asks for. It cannot raise the ceiling.

## What the platform is doing, measured

`cat /sys/devices/system/cpu/cpu{0,3,7}/cpufreq/{scaling_cur_freq,scaling_max_freq,scaling_min_freq}`
on the SM-F946B, 2026-10-19, twice 15 minutes apart:

| | first reading | later |
| --- | --- | --- |
| little (0–2) top | 2016 MHz | 2016 MHz |
| little `scaling_max_freq` | **1555 MHz** | 2016 MHz (uncapped again) |
| gold (3–6) top | 2803 MHz | 2803 MHz |
| gold `scaling_max_freq` | **2054 MHz** | 2803 MHz (uncapped again) |
| prime (7) top | 3360 MHz | 3360 MHz |
| prime `scaling_max_freq` | **2342 MHz** | 2957 MHz (still short of its top) |
| every cluster `scaling_min_freq` | 1228–1286 MHz | 1228–1286 MHz |

The same device, same idle load, moved from `1900/2457/2726` to `1555/2054/2342` and back within
seconds. So the cap is real, it is dynamic, and it is visible in exactly one place: when
`scaling_max_freq` falls below `cpuinfo_max_freq`, the clock has been taken away; when it does not,
a low `scaling_cur_freq` is the governor choosing, or a core sitting on the platform's floor.

KGSL says the same thing about the GPU: `max_clock_mhz=719`, `min_clock_mhz=475`,
`clock_mhz=475`, `thermal_pwrlevel=0`, `throttling=0`, `temp=44200`. The GPU's clock sits on its own
floor of 475 MHz, and once the device is hot that floor itself moves down to ~348 MHz — the same
shape as the 30 → 14 fps drop.

## What 0.4.8 changed

### `perf.csv`, one row every two seconds, in the session folder

Written by `ThrottleWatch`, next to `session.log` in the session bundle:

- `game_fps` — from the compositor's frame counter.
- `thermal`, `headroom` — `PowerManager.currentThermalStatus` and `getThermalHeadroom(10)`.
- `online` — `/sys/devices/system/cpu/online`, because a core taken offline looks like a slow core.
- `gpu_mhz`, `gpu_busy_pct`, `gpu_min_mhz`, `gpu_max_mhz`, `gpu_pwrlevel`, `gpu_throttle`, `bcl`,
  `lm`, `gpu_temp_c` — KGSL's own counters.
- `cpu_temp_c`, `ddr_temp_c` — thermal zones.
- `mem_avail_kb`, `swap_free_kb` — `/proc/meminfo`.
- Three columns per cluster: the mean `scaling_cur_freq`, the highest one, and
  `scaling_max_freq` (the platform's ceiling right now).

A `# clusters:` header line names the cores in each cluster and the top frequency it can reach, so
a row can be read without asking what `c1` was.

Temperatures were also wrong before: the sampler divided sysfs millidegrees by 100, so a 48.5 °C GPU
was printed as "485.0 C". `thermalCelsius` now picks the scale by magnitude and drops anything
outside 1–150 °C instead of printing it.

### A log line when a limit moves

`ThrottleWatch` remembers the last per-cluster ceiling, the GPU's floor, the online mask and the
thermal status, and logs the moment any of them changes:

```
ThrottleWatch: cap: c0 1555→1900 MHz, c1 2054→2457 MHz, c2 2342→2726 MHz
ThrottleWatch: game 8 fps | thermal none | cpu 1228/2016 MHz (cap 1900) ...
```

That line, with the timestamp of the log line around it, is the difference between "it got slower"
and "the platform took 750 MHz off the gold cluster at 12:31".

### The paced numbers reach `device.txt` too

`device.txt` is written when the session folder opens, which is before the display mode has settled,
so it cannot carry the pacing from `build()`. `DeviceReport.appendPacing` appends one line once the
plan is known instead:

```
Session pacing         guest 30 fps · layer 60.00 Hz · display 60.00 Hz · LSFG 2×
```

The point is that a report pasted into an issue carries the numbers the session was actually paced
at, not the settings it was meant to use.

### The guest's rate and the panel's rate are separate numbers

`nativeSetFpsLimit` (the compositor's release pace) and `nativeSetLayerFrameRate` (the rate voted to
SurfaceFlinger) used to be derived from the same number. With frame generation they must not be:
the generator interpolates between guest frames, so the guest draws at the panel rate divided by the
multiplier while the layer still updates at the panel rate. Voting the divided rate upward left
SurfaceFlinger sampling every other generated frame.

`SessionPacing.plan` now returns both, and `CompositorHost.pace` takes both:

| frame generation | user's limit | guest draws | layer votes |
| --- | --- | --- | --- |
| off | none | panel rate | panel rate |
| off | 30 | 30 | 30 |
| LSFG 2x, armed | none | panel / 2 | panel |
| LSFG 2x, armed | 60 | 60 | panel |
| adaptive target | any | the target's own base | panel |
| LSFG 2x, armed (desktop session) | none | panel rate | panel |

The derived base only applies when the engine can actually arm (`FrameGen.ready`), and a session
whose engine failed to arm releases the cap rather than leaving the guest at half rate for frames
nothing fills. The user's own limit is never overridden. A desktop session is left at the panel rate
on purpose: a cursor wants the least delay, not the most frames, and the desktop is the one place in
a session where the user is not looking at a game's frame rate.

### The display rate must be a mode the display has

`refreshHz()` read `Display.getRefreshRate()`, which on this device reports rates the mode list does
not contain (75 Hz was measured while the modes were 60/48/30/24). Pacing the guest to a rate with
no matching mode makes the compositor hold every frame for a vsync that cannot come. It now snaps to
the fastest supported mode at or below the reported rate, and logs when it does:

```
SessionActivity: refresh: display reports 75.0 Hz but has no such mode, pacing at 60.0 Hz
```

### The game gets the prime core

`CpuAllocationDefaults` gave the game the four gold cores and left core 7 to the client. The
platform already moves a busy thread to the prime core (that is what the Odin 3's ADPF hints work
around), so the split cost the guest a fifth of its cores for no reason. The default for SM8550 /
QCS8550 is now client `7`, game `3,4,5,6,7` — the same set `droiddeck-session`'s own fallback picks
when no list is given. The client keeps the prime core, so gamescope and the Steam client keep the
core they had; a game may now share it.

A saved `gameCpus` value still wins, so anyone who chose their own list keeps it.

## Reading a report

1. `perf.csv`, the row where `game_fps` falls.
2. Did `c1_limit_mhz` (or any cluster's) fall at the same time? That is the platform.
3. Did `gpu_min_mhz` fall below 475, or `gpu_pwrlevel` rise? That is the GPU's thermal level.
4. Did `thermal` move from `none` to `moderate`/`severe` first? Then the caps are downstream of it.
5. If none of that moved, the drop is in the guest (its own load) or in the host's compositor —
   compare against the compositor's `perf:` line in the same `session.log`.

## Levers that remain

- The frame rate the guest is asked for: a lower base rate is less heat, and with a fixed-multiplier
  generator it is also the same picture.
- `gpuClockPin` ("Hold the GPU at its top clock" in Performance settings, off by default) asks KGSL to
  stop managing its own power level, which holds the GPU at its top clock for the length of the
  session. `perf.csv` now shows whether it holds, and what it costs in `gpu_temp_c`.
- The core lists, which decide which clusters the guest's threads are allowed to use at all.
