package rlperf.core;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Patches that swap in rewritten code for other mods. The rewritten classes are compiled from
 * rlperf/donor and shipped under /rlperf/donor as data. A mod is only touched if every class the
 * rewrite depends on has the SHA-1 it had in the version the rewrite was written against.
 */
final class DonorPatches {
    private DonorPatches() {}

    static final class Mod {
        final String configKey, pkg, description;
        /** Class (relative to pkg) -> SHA-1 of its class file in the supported jar. */
        final Map<String, String> expected = new LinkedHashMap<String, String>();
        /** Classes replaced outright by the donor version. */
        final List<String> replace;
        /** Classes that keep their own code except for these methods (and any donor field named rlperf$...). */
        final Map<String, List<String>> transplant = new LinkedHashMap<String, List<String>>();
        private Boolean compatible;

        Mod(String configKey, String pkg, String description, String... replace) {
            this.configKey = configKey;
            this.pkg = pkg;
            this.description = description;
            this.replace = Arrays.asList(replace);
        }

        synchronized boolean compatible() {
            if (compatible == null) {
                boolean ok = true;
                try {
                    for (Map.Entry<String, String> e : expected.entrySet()) {
                        if (!e.getValue().equals(RLPerfTransformer.rawSha1(pkg + e.getKey()))) { ok = false; break; }
                    }
                } catch (Exception ex) {
                    ok = false;
                }
                compatible = ok;
                RLPerfTransformer.LOG.info(description + (ok ? ": patch applied" : ": different version installed, patch skipped"));
            }
            return compatible;
        }
    }

    /**
     * Better Caves 1.12.2-2.0.4: array-backed noise containers and carve loops. Same noise maths, so
     * caves come out block-for-block the same. Lists every class that touches the noise containers.
     */
    static final Mod BETTER_CAVES = new Mod("betterCavesNoise", "com.yungnickyoung.minecraft.bettercaves.", "Better Caves cave noise",
            "noise.NoiseTuple", "noise.NoiseColumn", "noise.NoiseCube", "noise.NoiseGen");
    /** Lycanites Mobs 2.0.8.9: the block scan behind block-based spawners. Same positions found. */
    static final Mod LYCANITES = new Mod("lycanitesSpawnerScan", "com.lycanitesmobs.core.spawner.location.", "Lycanites Mobs spawner scan");
    static final Mod[] MODS = {BETTER_CAVES, LYCANITES};

    static {
        BETTER_CAVES.expected.put("noise.NoiseTuple", "f77d8daa346ced589e34a05596fe70815447dd80");
        BETTER_CAVES.expected.put("noise.NoiseColumn", "a08c4688c6b502af9fe59aff4e75fedc97dbf96d");
        BETTER_CAVES.expected.put("noise.NoiseCube", "e9d10d00eacccc99249f0c84a6f0239754c05bd2");
        BETTER_CAVES.expected.put("noise.NoiseGen", "e291c595a5bc44714a73624b1f836baabe1f83a1");
        BETTER_CAVES.expected.put("world.carver.cave.CaveCarver", "75d4c76eab1829e3bfb87f4d39dd750286f34ad0");
        BETTER_CAVES.expected.put("world.carver.cavern.CavernCarver", "4ec42657c13c3bac8eac29f97c63a2c823bfb3fa");
        BETTER_CAVES.expected.put("world.CaveCarverController", "c347d89c0a6bebe302f3d47d9cb12009f9412e4b");
        BETTER_CAVES.expected.put("world.CavernCarverController", "5ddb58189c53673b23a18efba97e62cb2b128844");
        BETTER_CAVES.expected.put("world.carver.CarverNoiseRange", "10d5b9c2cc9232b4112e18dd8983267a3489efc2");
        BETTER_CAVES.transplant.put("world.carver.cave.CaveCarver", Arrays.asList("carveColumn", "rlperf$thresholds", "rlperf$preprocess"));
        BETTER_CAVES.transplant.put("world.carver.cavern.CavernCarver", Arrays.asList("carveColumn"));

        LYCANITES.expected.put("BlockSpawnLocation", "59a518d23c95a0ca40a2d800dc5d388040ee0ede");
        LYCANITES.expected.put("SpawnLocation", "617d7be813d5a80b672dfbf66b9fab2c6e4cef05");
        LYCANITES.transplant.put("BlockSpawnLocation", Arrays.asList("getSpawnPositions", "rlperf$matches"));
    }

    static byte[] apply(String className, byte[] bytes) throws Exception {
        for (Mod mod : MODS) {
            if (!className.startsWith(mod.pkg)) continue;
            String key = className.substring(mod.pkg.length());
            boolean replace = mod.replace.contains(key);
            List<String> methods = mod.transplant.get(key);
            if (!replace && methods == null) return bytes;
            if (!RLPerfTransformer.enabled(mod.configKey) || !mod.compatible()) return bytes;
            byte[] donor = RLPerfTransformer.resource("/rlperf/donor/" + className.replace('.', '/') + ".class.bin");
            if (donor == null) throw new IllegalStateException("missing donor class for " + className);
            return replace ? donor : transplant(bytes, donor, methods);
        }
        return bytes;
    }

    /** Copies the named methods (and rlperf$ fields) from the donor class into the target class. */
    static byte[] transplant(byte[] targetBytes, byte[] donorBytes, List<String> methods) {
        ClassNode target = RLPerfTransformer.read(targetBytes);
        ClassNode source = RLPerfTransformer.read(donorBytes);
        for (FieldNode f : source.fields) {
            if (f.name.startsWith("rlperf$")) target.fields.add(f);
        }
        for (String name : methods) {
            int moved = 0;
            for (MethodNode m : source.methods) {
                if (!m.name.equals(name)) continue;
                MethodNode old = RLPerfTransformer.method(target, m.name, m.desc);
                if (old != null) target.methods.remove(old);
                target.methods.add(m);
                moved++;
            }
            if (moved != 1) throw new IllegalStateException("donor has " + moved + " methods named " + name);
        }
        return RLPerfTransformer.write(target);
    }
}
