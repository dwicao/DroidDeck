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

KGSL says the same thing about the GPU, in the same shape: the *ceiling* moves, and the clock follows
it. `max_clock_mhz=719`, `min_clock_mhz=475`, `clock_mhz=475`, `thermal_pwrlevel=0`, `throttling=0`,
`temp=44200` at idle; in the 0.4.8 session the platform took `thermal_pwrlevel` from 0 to 7 at the
same moment it capped the CPU clusters, and `max_clock_mhz` fell 719 → 295 → 348 MHz. Every sample of
that session had `gpu_mhz == gpu_max_mhz`: the clock always sat on the ceiling the platform allowed,
including while the ceiling was coming down. So a low GPU clock is not the GPU's governor failing to
scale — it is the ceiling. `min_clock_mhz` is the floor, and it is the number that moved in the
earlier idle measurements.

## What 0.4.8 changed

### `perf.csv`, one row every two seconds, in the session folder

Written by `ThrottleWatch`, next to `session.log` in the session bundle:

- `game_fps` — from the compositor's frame counter.
- `thermal`, `headroom` — `PowerManager.currentThermalStatus` and `getThermalHeadroom(10)`.
- `online` — `/sys/devices/system/cpu/online`, because a core taken offline looks like a slow core.
- `gpu_mhz`, `gpu_busy_pct`, `gpu_gov_load`, `gpu_pin`, `gpu_temp_c`, `gpu_min_mhz`, `gpu_max_mhz`,
  `gpu_pwrlevel`, `gpu_throttle`, `bcl`, `lm` — KGSL's own counters. `gpu_pin` is 1 while a session
  holds the GPU's power level (see below), `gpu_max_mhz` is the ceiling the platform allows.
- `cpu_temp_c`, `ddr_temp_c` — thermal zones.
- `mem_avail_kb`, `swap_free_kb` — `/proc/meminfo`.
- Three columns per cluster: the mean `scaling_cur_freq`, the highest one, and
  `scaling_max_freq` (the platform's ceiling right now).

`gpu_busy_pct` is not a load signal on the measured device: the node kept a window that closed only
when the GPU idled, and read 13% both while idle and while a 3D game drew frames. `gpubusy` is the
same counters, so `gpu_gov_load` (`devfreq/gpu_load`, the estimate the clock governor itself follows)
is recorded as a second opinion; the useful load-adjacent number in a row is `gpu_mhz` next to
`gpu_max_mhz`.

A `# clusters:` header line names the cores in each cluster and the top frequency it can reach, so
a row can be read without asking what `c1` was.

Temperatures were also wrong before: the sampler divided sysfs millidegrees by 100, so a 48.5 °C GPU
was printed as "485.0 C". `thermalCelsius` now picks the scale by magnitude and drops anything
outside 1–150 °C instead of printing it.

### A log line when a limit moves

`ThrottleWatch` remembers the last per-cluster ceiling, the GPU's floor and ceiling, the online mask
and the thermal status, and logs the moment any of them changes:

```
ThrottleWatch: cap: c0 1555→1900 MHz, c1 2054→2457 MHz, c2 2342→2726 MHz
ThrottleWatch: cap: c1 1785→1171 MHz, c2 2092→1478 MHz, gpu top 719→295 MHz (held, the platform lowered the ceiling)
ThrottleWatch: game 8 fps | thermal none | cpu 1228/2016 MHz (cap 1900) ...
```

That line, with the timestamp of the log line around it, is the difference between "it got slower"
and "the platform took 750 MHz off the gold cluster at 12:31". The GPU's ceiling belongs in it, so
the second example says in one line that the clock came down because the platform moved the ceiling
while the app was holding the level — the case that otherwise reads as "the GPU pin did not work".

### The paced numbers reach `device.txt` too

`device.txt` is written when the session folder opens, which is before the display mode has settled,
so it cannot carry the pacing from `build()`. `DeviceReport.appendPacing` appends a block once the
session's surface is up instead, and again whenever the plan changes:

```
Session pacing         guest 30 fps · layer 60.00 Hz · display 60.00 Hz · LSFG 2×
Session display        id 48 · HDMI screen · 75.00 Hz · modes 75 Hz, 60 Hz
GPU clock pin          held at its top (ioctl)
Pacing note            guest 38 fps × 2 = 76 Hz above the 75.00 Hz panel, frames are held back
```

The point is that a report pasted into an issue carries the numbers the session was actually paced
at, not the settings it was meant to use. In 0.4.8 the block was only written while the session had
not started yet, which skipped it on any surface re-creation — the case that mattered. The session's
own display is named here too, because the `Display` section above describes the *phone's* panel.

The `Battery` and `Battery temperature` lines come from the same idea: a phone on a charger that is
warm, or one drawing from the cell, changes what the platform is willing to give, and a report that
does not say which was happening leaves that to guesswork.

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

`refreshHz()` read `Display.getRefreshRate()`, which on this device's own panel reports rates the mode
list does not contain (75 Hz was measured while the phone's modes were 60/48/30/24). Pacing the guest
to a rate with no matching mode makes the compositor hold every frame for a vsync that cannot come. It
now snaps to the fastest supported mode at or below the reported rate, and logs when it does:

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
3. Did `gpu_max_mhz` fall, or `gpu_min_mhz` fall below 475, or `gpu_pwrlevel` rise? That is the
   GPU's ceiling, taken the same way. `gpu_pin` says whether a session was holding the level while
   it moved, and `ThrottleWatch`'s `cap:` line says so in words.
4. Did `thermal` move from `none` to `moderate`/`severe` first? Then the caps are downstream of it.
   On the measured device they were *not* downstream of it: the 0.4.8 cliff arrived with the pack at
   59–61 °C and falling, so the platform was holding a sustained-power budget rather than reacting to
   a hot spot.
5. If none of that moved, the drop is in the guest (its own load) or in the host's compositor —
   compare against the compositor's `perf:` line in the same `session.log`.
6. `device.txt`: `Session pacing` and `Pacing note` say what the session asked for and where that
   cannot land on the panel; `Session display` says which display, at which rate, with which modes.

## What 0.4.9 changed

### The GPU's level is held by default, and re-asserted

`gpuClockPin` ("Hold the GPU at its top clock") is on by default now, and 0.4.9 gave it three things
it did not have: a second lever, a re-assert, and a trail.

- The sysfs lever is tried first: `/sys/class/kgsl/kgsl-3d0/thermal_pwrlevel` = 0 holds the level
  directly, with no ioctl involved. On a stock SM-F946B that write is refused — the node is `644
  root` — and the refusal is logged once, with its reason, rather than swallowed:
  `GpuClockPin: hold: /sys/class/kgsl/kgsl-3d0/thermal_pwrlevel refused (… Permission denied), asking
  the driver instead`.
- The ioctl lever is `KGSL_PROP_PWRCTRL=0` (adrenotools' turbo), the only switch an unprivileged app
  has. It is reachable: `/dev/kgsl-3d0` is `crw-rw-rw-`, world read/write, because every app opens the
  GPU device to draw.
- `ThrottleWatch` re-asserts the hold when the GPU's ceiling moves, and every third sample (about
  6 s) anyway: a level written once is not held forever, and whether the re-assert still takes is
  visible in the next row as `gpu_mhz` against `gpu_max_mhz` with `gpu_pin=1`.
- `stop()` and `clearLeftover()` clear the ioctl and the marker file, so a session that died with the
  GPU pinned does not leave the next app start governing a pinned GPU.

What this cannot do is raise the ceiling. When the platform sets the top level to 295 MHz, "held at
its top" means held at 295 MHz, and the honest line in the report is
`gpu top 719→295 MHz (held, the platform lowered the ceiling)` rather than a claim that the pin
failed. `KGSL_PROP_PWR_CONSTRAINT` is defined in the vendored header but unused: its payload is not
documented for this kernel, and a wrong vote there could hold the *low* level instead.

### A panel that does not divide the guest's rate

The derived base rate for a fixed multiplier is rounded to the nearest whole frame rate, except when
that rounding lands *above* the panel: 75 Hz ÷ 2 = 37.5 rounds to 38, and 38 fps × 2 = 76 Hz is a
frame a second the panel never takes, which the generator has to hold back. It now steps down to 37
(74 Hz, one repeated frame a second). A fractional panel keeps the rounding: 59.94 ÷ 2 = 29.97 → 30,
because 60 Hz is what that panel takes.

`SessionPacing.note` puts the case in words when a *user-chosen* limit cannot land on the panel, so
the next report says which of the two it was:

```
Pacing note            guest 38 fps × 2 = 76 Hz above the 75.00 Hz panel, frames are held back
```

A rate well under the panel (`guest 30 fps × 2 = 60 Hz below the 75.00 Hz panel, frames are
repeated`) is reported too; a frame rate one short of the panel is not, because that is what an
indivisible panel costs whoever paces it.

### The compositor's vsync is the phone's, not the session display's

An earlier reading of this file treated the compositor's `vsync average` (16.64 ms ≈ 60 Hz) as a
measurement of the session display. It is not: `CompositorHost` posts frames through the UI thread's
`Choreographer`, which on this device is the phone's own panel — the comment in `compositor.c` already
warned that in DeX these callbacks come from the phone's display. The session ran on a 1920×1080
external display reported at 75 Hz, and the phone's panel is 60 Hz. The rate a report should be read
for is `Session refresh` / `Session display`, not the vsync average.

## Levers that remain

- The frame rate the guest is asked for: a lower base rate is less heat, and with a fixed-multiplier
  generator it is also the same picture. `Pacing note` says when a chosen rate cannot land on the
  panel.
- `gpuClockPin` ("Hold the GPU at its top clock" in Performance settings, on by default as of 0.4.9)
  asks KGSL to stop managing its own power level, and re-asserts that hold while the session runs.
  `perf.csv` shows what it does: `gpu_pin` says whether it was held, `gpu_mhz` against `gpu_max_mhz`
  says whether it held at the ceiling, and `gpu_temp_c` says what it costs.
- The core lists, which decide which clusters the guest's threads are allowed to use at all.

What none of them can do is lift a cap the platform has already taken away: the measured 0.4.8 cliff
took the GPU's ceiling from 719 to 295 MHz and every cluster's `scaling_max_freq` down with it, in the
same second, with the device at 59–61 °C and cooling. A session that meets that cliff can hold its
rate only by asking the guest for less work.
