package rlperf.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Swaps Better Caves' noise containers (boxed lists and hash maps per block) for array-backed ones
 * and moves the two carveColumn loops onto them. The noise maths is unchanged, so caves come out
 * block-for-block the same. Only applied to bettercaves-1.12.2-2.0.4, checked by class file hash.
 */
final class BetterCavesPatch {
    private BetterCavesPatch() {}

    static final String PKG = "com.yungnickyoung.minecraft.bettercaves.";

    /** Every class that touches the noise containers, with its SHA-1 in bettercaves-1.12.2-2.0.4.jar. */
    private static final Map<String, String> EXPECTED = new HashMap<String, String>();
    /** Classes replaced outright by the versions under /rlperf/donor. */
    static final List<String> REPLACE = Arrays.asList(
            "noise.NoiseTuple", "noise.NoiseColumn", "noise.NoiseCube", "noise.NoiseGen");
    /** Classes that keep their own code except for these methods, taken from /rlperf/donor. */
    static final Map<String, List<String>> TRANSPLANT = new HashMap<String, List<String>>();

    static {
        EXPECTED.put("noise.NoiseTuple", "f77d8daa346ced589e34a05596fe70815447dd80");
        EXPECTED.put("noise.NoiseColumn", "a08c4688c6b502af9fe59aff4e75fedc97dbf96d");
        EXPECTED.put("noise.NoiseCube", "e9d10d00eacccc99249f0c84a6f0239754c05bd2");
        EXPECTED.put("noise.NoiseGen", "e291c595a5bc44714a73624b1f836baabe1f83a1");
        EXPECTED.put("world.carver.cave.CaveCarver", "75d4c76eab1829e3bfb87f4d39dd750286f34ad0");
        EXPECTED.put("world.carver.cavern.CavernCarver", "4ec42657c13c3bac8eac29f97c63a2c823bfb3fa");
        EXPECTED.put("world.CaveCarverController", "c347d89c0a6bebe302f3d47d9cb12009f9412e4b");
        EXPECTED.put("world.CavernCarverController", "5ddb58189c53673b23a18efba97e62cb2b128844");
        EXPECTED.put("world.carver.CarverNoiseRange", "10d5b9c2cc9232b4112e18dd8983267a3489efc2");
        TRANSPLANT.put("world.carver.cave.CaveCarver", Arrays.asList("carveColumn", "rlperf$thresholds", "rlperf$preprocess"));
        TRANSPLANT.put("world.carver.cavern.CavernCarver", Arrays.asList("carveColumn"));
    }

    private static Boolean compatible;

    private static synchronized boolean compatible() {
        if (compatible == null) {
            boolean ok = true;
            try {
                for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
                    if (!e.getValue().equals(RLPerfTransformer.rawSha1(PKG + e.getKey()))) { ok = false; break; }
                }
            } catch (Exception ex) {
                ok = false;
            }
            compatible = ok;
            RLPerfTransformer.LOG.info(ok ? "Better Caves 1.12.2-2.0.4 found; using array-backed cave noise"
                    : "Better Caves is not 1.12.2-2.0.4; cave noise patch skipped");
        }
        return compatible;
    }

    static byte[] apply(String className, byte[] bytes) throws Exception {
        String key = className.substring(PKG.length());
        boolean replace = REPLACE.contains(key);
        List<String> methods = TRANSPLANT.get(key);
        if (!replace && methods == null) return bytes;
        if (!compatible()) return bytes;

        byte[] donor = RLPerfTransformer.resource("/rlperf/donor/" + className.replace('.', '/') + ".class.bin");
        if (donor == null) throw new IllegalStateException("missing donor class for " + className);
        return replace ? donor : transplant(bytes, donor, methods);
    }

    /** Copies the named methods from the donor class into the target class, replacing same-signature ones. */
    static byte[] transplant(byte[] targetBytes, byte[] donorBytes, List<String> methods) {
        ClassNode target = RLPerfTransformer.read(targetBytes);
        ClassNode source = RLPerfTransformer.read(donorBytes);
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
