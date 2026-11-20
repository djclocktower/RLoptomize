# RLCraft 2.9.3 — performance build

RLCraft 1.12.2 v2.9.3 (by Shivaxi) with a purpose-built patch mod, performance tuning, and
[Complementary Shaders – Unbound](https://www.complementary.dev/) r5.10 set up for it.
Gameplay, mobs, loot and world generation are unchanged.

The first commit is the stock pack, so `git diff 732b3bf` shows every change.

## Install

1. Zip `manifest.json`, `modlist.html` and `overrides/` (the three items themselves, not the folder
   around them) and import the zip in the CurseForge app: *Create Custom Profile → Import*.
   CurseForge downloads the mods and the shader pack.
2. Download **OptiFine 1.12.2 HD U G5** from optifine.net and drop the jar in the profile's `mods`
   folder. It cannot be bundled, and 1.12.2 shaders do not work without it.
3. Give the profile 4–6 GB of RAM and paste the JVM arguments below.

The shader is selected and configured on first launch. Without OptiFine the pack still runs, just
without shaders.

## RLPerf: the patch mod

`overrides/mods/rlperf-1.1.0.jar`, source in [`rlperf/`](rlperf). It was written from profiles of
this pack: stack samples of startup, of the server thread while standing still, and of the server
thread while flying through ungenerated terrain. It contains three speed patches, described here,
and the fixes for mob stutter described in the next section.

**1. OptiFine shader option parsing.** With a shader pack selected, the stretch of every launch where
OptiFine reads the pack's options took 28 s: for each option it regex-scanned every line of every
shader file again, and for each line it ran a substring search per GL extension macro. The patch
scans each file once. That stretch now takes 8 s, and the same saving applies whenever the shader
pack is reloaded. The parsed result is identical: OptiFine logs the same warnings, line for line,
with and without the patch (5,196 "ambiguous option" lines among them).

**2. Better Caves noise.** Cave carving was a third of all chunk-generation time. Better Caves
stored its noise as an `ArrayList` of boxed `Double`s per block inside a `HashMap` per column,
allocating new ones at every interpolation step. The patch keeps the same noise functions and the
same arithmetic but stores the values in flat arrays. A test in `rlperf/test` runs the original and
patched code side by side on 600 randomised cave configurations: every noise value is
bit-identical and all 361,745 dig decisions match, in under half the time (about 310 ms against
140 ms).

**3. Cascade guard.** While one chunk is being populated, mods place blocks at its edge with
neighbour updates on. Each update aimed at a chunk that does not exist yet made the game generate
that chunk on the spot, which did the same to its neighbours, up to five chunks deep. Ice and
Fire's own block placement was the main trigger (its sapphire ore goes in without the usual
8-block offset). The patch drops a neighbour update only when its target
chunk is not loaded and a chunk is being populated; nothing else about block updates changes. The
blocks placed are the same. The one visible difference would be a block just inside an
ungenerated chunk not reacting to a neighbour change at generation time (for example sand not
falling until something else touches it).

Each patch checks that it is looking at the exact code it was written for (class file hash for
OptiFine G5, Better Caves 2.0.4 and Lycanites Mobs 2.0.8.9, method shape for Minecraft) and
otherwise leaves the class alone and says so in the log. Each can be switched off in
`config/rlperf.properties`.

## Mob stutter: the server falling behind

The symptom: you hit a pig and its knockback plays out in jerks over a second or two, while your
own movement and the frame rate stay smooth. In single player the world is simulated by a server
thread separate from rendering. When one of its ticks takes longer than 50 ms, every mob waits.
RLPerf has a recorder for this (`slowTickLog=true`); on the stock pack it logged 200 ticks over
60 ms in a four-minute session. Three causes could be fixed:

- **Chunk loading took the whole tick.** Minecraft 1.12 lets chunk loading and generation run for
  50 ms inside every tick, which is the entire tick, before anything else has run. For as long as
  chunks are coming in (after joining, and whenever you move into new terrain) the server ran at
  roughly half speed. RLPerf keeps count of how far behind schedule the server is and gives chunk
  work only what is left; when it is a full tick behind it lets one tick pass without loading
  chunks. Chunks still arrive at close to the same rate, because the time used to be spent
  sleeping between over-long ticks.
- **Lycanites spawner scans.** Sixteen spawners in this pack (pumpkin, lava, flower, ...) each
  scan a 65×65×65 block cube around the player on a timer: 274,625 blocks, with two block lookups,
  a sky check and a freshly built name string for each. That was an 80–190 ms freeze a few times a
  session. The scan now does one lookup per block and remembers the name test per block type. It
  returns the same positions in the same order; the test compares 900 random scans.
- **Roguelike Dungeons' first chunk.** It read all its dungeon settings the first time a chunk was
  populated, a 1.4 s freeze shortly after joining. That now happens while the world loads.

Same pack, same route (20 jumps of 48 blocks through new terrain), these fixes off and on:

| | Off | On |
|---|---|---|
| After joining | 0.6 to 10 ticks/s for about 16 s | normal within about 5 s |
| While travelling | swings between 14 and 26 ticks/s | 17 to 23 ticks/s, outside the structure freezes below |
| Slow ticks from spawner scans, per session | 2 to 5 | 0 to 1 |

A normal server runs 20 ticks/s. These are single runs on a machine where software rendering
competes with the server for CPU, so each generated chunk costs more here than it will on yours.

**Still there:**

- A big Recurrent Complex structure generates in a single tick. On the test route one took 3.7 to
  5.1 s in three of five runs, with or without the fixes (it reads the ground under its whole
  footprint, which generates every chunk beneath it), and others took 0.7 to 3 s. That is a genuine freeze of a few seconds when you first
  approach such a structure, and it is the way the pack configures Recurrent Complex
  (`Generate Structures Partially=false` in `rltweaker.cfg`); I left that setting alone.
- Autosave writes every changed chunk in one tick, about 0.4 s here, every few minutes.
- Any single chunk that takes more than 50 ms to generate still makes one long tick.

If the stutter comes back, set `slowTickLog=true` in `config/rlperf.properties`, play until it
happens, and look at `logs/rlperf-slow-ticks.log`: it names what the server was doing in every
slow tick and the tick rate around it.

## Other changes

| Change | Why |
|---|---|
| Added **Nothirium** 0.4.9 | Batches chunk draw calls. Active with shaders off; it steps back to a compatibility path while shaders are on. |
| **Entity Culling** 6.3.1 → 6.5.0, **RenderLib** 1.2.8 → 1.4.5, **Particle Culling** 1.4.1 → 1.4.3 | Newer builds of the culling mods the pack already ships. |
| Added **Complementary Unbound r5.10** through the manifest | Installed by CurseForge, unmodified, as its license requires. |
| `optionsshaders.txt` | Selects the shader; turns off normal/specular map loading (no pack here has them). |
| `shaderpacks/ComplementaryUnbound_r5.10.zip.txt` | Shader settings, see below. |
| `options.txt` | Render distance 10 → 8, particles All → Decreased, vanilla entity shadows off. |
| `optionsof.txt` | Smart animations, lazy chunk loading, smart trees on. Dynamic lights Fancy → Fast, connected textures Fancy → Fast. Custom items, custom entity models, random entities, custom GUIs, custom sky off (no resource pack in RLCraft uses them). GL error chat spam off. |
| Better Foliage: extra leaf geometry off | The most expensive cosmetic in the pack, and drawn twice when shader shadows are on. Reeds, algae, coral, falling leaves stay. |
| Dynamic Surroundings: effect scan range 24 → 16 | The scanned volume around the player is about 3.4× smaller. |
| Fancy Block Particles: 3 → 2 particles per axis, max age 100 → 55 | 27 → 8 physics particles per broken block, and they despawn sooner. |

### Shader settings

Starting from the shader's default (its "High" profile):

| Setting | Default | Here |
|---|---|---|
| Real-time shadows | Medium | Low |
| Shadow distance | 12 chunks | 6 chunks |
| Entity shadows | Regular entities | Off |
| Light shaft quality | Medium | Low |
| Block reflections | High | Low |
| Cloud quality | Medium | Low |

Entity shadows matter most in RLCraft: the shader's own description says they can cost over half
the frame rate, and this pack is full of large modelled mobs.

## What was measured

Everything was run in a headless Linux container: 4 cores, software OpenGL, no GPU, no audio
device. Each build was booted to the main menu and into the same world.

**Launch**, OptiFine and shader pack on, second and third launch in a row:

| Build | Load to menu |
|---|---|
| Stock pack, shader at its defaults | 111–112 s |
| This pack without RLPerf | 109–112 s |
| This pack | 88–92 s |
| This pack with `-Xverify:none` | 81 s (one run; 94 s without it in the same session) |

None of the config changes moved launch time; the 20 s came from the shader parser patch.

**Exploring**, shaders off, spectator mode, eight 320-block jumps through ungenerated terrain on
the same route, server thread sampled throughout:

| | Without RLPerf | With RLPerf |
|---|---|---|
| Server time spent in nested (cascading) chunk generation | 14 % | 4 % |
| Deepest nesting seen | 4 chunks | 3 chunks |
| Server-thread samples inside Better Caves | 58 | 29 |
| Server ticks skipped ("Can't keep up") | 669 | 238 |

One run each, so read these as direction and rough size, not as precise figures.

**Not measured:**

- Frame rate. Software rendering runs the shader at about 1 fps whatever the settings, so the
  shader and video settings rest on what each one is known to cost. The log does confirm they are
  applied (shadow distance 96, shadow map 1024 instead of 192 / 2048) and that every shader
  program compiles on 1.12.2.
- Long play sessions, multiplayer, a dedicated server, the Nether, the End, and the CurseForge
  import itself.

### Profiled and left alone

- **Mixin loader swap** to allow VintageFix, Alfheim and similar mods. It boots, and launch came
  in about 13 s faster. It also means removing FoamFix and Phosphor and changing the framework
  under more than a dozen mods, which a few minutes of testing cannot vouch for.
- **Recurrent Complex and Ruins.** Recurrent Complex is about a tenth of chunk generation, spread
  through its placement logic with no single hot spot. The cascades that remain come from both
  mods reading terrain they really need.
- **Tick handlers.** With the player standing still no event handler takes more than about 3.5 % of
  either thread; there is nothing to single out.
- **Startup beyond the shader parser.** The remaining 90 s is class loading and a long tail of mods
  taking 2–7 s each.

## JVM arguments

Set the profile's memory to 4–6 GB and add:

```
-Xverify:none -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M -XX:+DisableExplicitGC
```

The pack boots and joins a world with this exact line. `-Xverify:none` skips bytecode
verification and is where the launch saving above comes from; the cost is that a corrupt mod jar
crashes harder instead of reporting a clean error, so drop it if you are debugging a crash. The
G1 settings are the usual ones for modded 1.12.2 and their effect on stutter was not measured
here. There is nothing to gain past 6–8 GB.

## If you want more speed, or more looks

Fastest first:

- *Shaders → Render Quality* 0.75x: the biggest single frame-rate lever, at the cost of sharpness.
- *Shader Options → Performance*: shadows Very Low, or pick the built-in Potato profile.
- Render distance 6.
- Shaders off entirely: Nothirium's fast path takes over.

To put things back:

- Bushy leaves: `config/betterfoliage.cfg`, section `leaves`, `B:enabled=true`.
- Entity shadows and longer shadow range: *Shader Options → Performance*.
- Resource packs that use custom items, entity models or sky: re-enable those under
  *Video Settings → Quality*.

Modded blocks (ores, lights, Dynamic Trees leaves) do not get the shader's special material
effects. That needs edits to the shader's block list, which its license does not allow a modpack
to ship.

## Building RLPerf

Needs a JDK 9 or newer, a Minecraft `libraries` folder with Forge 1.12.2 installed, and the Better
Caves and Lycanites Mobs jars:

```bash
LIBS=~/.minecraft/libraries BC_JAR=bettercaves-1.12.2-2.0.4.jar LY_JAR=lycanitesmobs-1.12.2-2.0.8.9.jar rlperf/build.sh
```

```bash
LIBS=~/.minecraft/libraries BC_JAR=bettercaves-1.12.2-2.0.4.jar LY_JAR=lycanitesmobs-1.12.2-2.0.8.9.jar rlperf/test.sh
```

The test prints `ALL EQUIVALENT` when the patched code produces the same output as the original.

## Credits

- RLCraft by Shivaxi and the RLCraft team.
- [Complementary Shaders – Unbound](https://www.complementary.dev/) by EminGT and Complementary
  Development, enabled by default here. It is installed unmodified from CurseForge; only its
  settings file is part of this repository. Any problem that comes from using it in this pack is
  this pack's responsibility, not theirs.
- YUNG's Better Caves by YUNGNICKYOUNG (LGPL-3.0-only). RLPerf replaces four of its classes and two
  methods at load time with rewritten versions, and is LGPL-3.0 itself for that reason.
- Lycanites Mobs by Lycanite. RLPerf replaces one method of its spawner code at load time.
- Nothirium, RenderLib and Entity Culling by meldexun; Particle Culling by bl4ckscor3.
