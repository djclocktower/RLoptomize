# RLCraft 2.9.3 — performance build

RLCraft 1.12.2 v2.9.3 (by Shivaxi) with performance changes and
[Complementary Shaders – Unbound](https://www.complementary.dev/) r5.10 set up and tuned for it.
Gameplay, mobs, loot and world generation are untouched.

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

## What changed

| Change | Why |
|---|---|
| Added **Nothirium** 0.4.9 | Batches chunk draw calls. Active with shaders off; it steps back to a compatibility path while shaders are on. |
| **Entity Culling** 6.3.1 → 6.5.0, **RenderLib** 1.2.8 → 1.4.5, **Particle Culling** 1.4.1 → 1.4.3 | Newer builds of the culling mods the pack already ships. |
| Added **Complementary Unbound r5.10** through the manifest | Installed by CurseForge, unmodified, as its license requires. |
| `optionsshaders.txt` | Selects the shader; turns off normal/specular map loading (no pack here has them). |
| `shaderpacks/ComplementaryUnbound_r5.10.zip.txt` | Shader settings, see below. |
| `options.txt` | Render distance 10 → 8, particles All → Decreased, vanilla entity shadows off. |
| `optionsof.txt` | Smart animations, lazy chunk loading, smooth world, smart trees on. Dynamic lights Fancy → Fast, connected textures Fancy → Fast. Custom items, custom entity models, random entities, custom GUIs, custom sky off (no resource pack in RLCraft uses them). GL error chat spam off. |
| Better Foliage: extra leaf geometry off | The single most expensive cosmetic in the pack, and drawn twice when shader shadows are on. Reeds, algae, coral, falling leaves stay. |
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

Tested in a headless Linux container: 4 cores, software OpenGL, no GPU. Every build below was
booted to the main menu and into the same world.

| Build | Load to menu | World join |
|---|---|---|
| Stock, no OptiFine | 96 s | 26 s |
| This pack, no OptiFine | 96 s | 27 s |
| This pack + OptiFine, shaders off | 101 s | 26 s |
| Stock + OptiFine + shader at defaults | 138–146 s | 25–26 s |
| This pack + OptiFine + shader | 135–136 s | 26–27 s |

- **Load time did not improve.** No config change moved it. Having a shader pack selected adds
  about 35–40 s here, because OptiFine parses the whole pack at startup. Picking *OFF* in the
  shader menu removes that on the next launch.
- **Frame rate could not be measured.** Software rendering runs the shader at about 1 fps whatever
  the settings, so the frame-rate changes rest on what each setting is known to cost, not on a
  number from this machine. The log confirms the shader settings are applied (shadow distance 96,
  shadow map 1024 instead of 192 / 2048) and all shader programs compile on 1.12.2.
- Not tested: long play sessions, multiplayer, a dedicated server, other dimensions.

### Tried and left out

Replacing the pack's Mixin loader so VintageFix, Alfheim and similar mods can run. It boots, and a
warm launch came in at 83 s instead of 96 s. It also means removing FoamFix and Phosphor and
swapping the framework under more than a dozen mods, which a few minutes of testing cannot vouch for. Not
worth 13 seconds.

## JVM arguments

Not tested here; these are the usual Java 8 G1 settings for a pack that allocates this heavily
(about 230 MB/s in-game). Set the profile's memory to 4–6 GB and add:

```
-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M -XX:+DisableExplicitGC
```

There is nothing to gain past 6–8 GB.

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

## Credits

- RLCraft by Shivaxi and the RLCraft team.
- [Complementary Shaders – Unbound](https://www.complementary.dev/) by EminGT and Complementary
  Development, enabled by default here. It is installed unmodified from CurseForge; only its
  settings file is part of this repository. Any problem that comes from using it in this pack is
  this pack's responsibility, not theirs.
- Nothirium, RenderLib and Entity Culling by meldexun; Particle Culling by bl4ckscor3.
