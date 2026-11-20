package rlperf.core;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import rlperf.hooks.OptiFineHooks;

/**
 * Checks that the patches change speed and nothing else.
 * args: bettercaves.jar, test-doubles dir, scenario dir, donor classes dir, vecmath.jar
 */
public class EquivalenceTest {
    public static void main(String[] a) throws Exception {
        optifineHooks();
        betterCaves(new File(a[0]), new File(a[1]), new File(a[2]), new File(a[3]), new File(a[4]));
        System.out.println("ALL EQUIVALENT");
    }

    // ---- Better Caves: original classes vs patched classes, same randomised scenario ----------

    private static void betterCaves(File bcJar, File doubles, File scenario, File donor, File vecmath) throws Exception {
        URL[] urls = {doubles.toURI().toURL(), scenario.toURI().toURL(), bcJar.toURI().toURL(), vecmath.toURI().toURL()};
        final Map<String, byte[]> patched = new HashMap<String, byte[]>();
        try (URLClassLoader jar = new URLClassLoader(new URL[] {bcJar.toURI().toURL()}, null)) {
            for (String key : BetterCavesPatch.REPLACE) {
                String name = BetterCavesPatch.PKG + key;
                patched.put(name, Files.readAllBytes(new File(donor, name.replace('.', '/') + ".class").toPath()));
            }
            for (Map.Entry<String, List<String>> e : BetterCavesPatch.TRANSPLANT.entrySet()) {
                String name = BetterCavesPatch.PKG + e.getKey();
                byte[] original;
                try (InputStream in = jar.getResourceAsStream(name.replace('.', '/') + ".class")) { original = in.readAllBytes(); }
                byte[] donorBytes = Files.readAllBytes(new File(donor, name.replace('.', '/') + ".class").toPath());
                patched.put(name, BetterCavesPatch.transplant(original, donorBytes, e.getValue()));
            }
        }
        ClassLoader original = new URLClassLoader(urls, null);
        ClassLoader rewritten = new URLClassLoader(urls, null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = patched.get(name);
                return b != null ? defineClass(name, b, 0, b.length) : super.findClass(name);
            }
        };
        String[] before = run(original), after = run(rewritten);
        System.out.println("Better Caves original: " + before[0] + "  (" + before[1] + " ms)");
        System.out.println("Better Caves patched:  " + after[0] + "  (" + after[1] + " ms)");
        if (!before[0].equals(after[0])) throw new AssertionError("Better Caves patch changes the output");
    }

    @SuppressWarnings("unchecked")
    private static String[] run(ClassLoader loader) throws Exception {
        Supplier<String> s = (Supplier<String>) loader.loadClass("Scenario").getDeclaredConstructor().newInstance();
        String first = s.get();   // also warms up the JIT
        s.get();
        long t = System.nanoTime();
        s.get();
        return new String[] {first, String.valueOf((System.nanoTime() - t) / 1000000)};
    }

    // ---- OptiFine hooks vs the code they stand in for ------------------------------------------

    public static class Macro {
        final String name;
        Macro(String name) { this.name = name; }
        public String getName() { return name; }
    }

    private static void optifineHooks() throws Exception {
        // The four option patterns and the #ifdef pattern, as they appear in OptiFine 1.12.2 HD U G5.
        Pattern[] option = {
            Pattern.compile("^\\s*(//)?\\s*#define\\s+([A-Za-z0-9_]+)\\s*(//.*)?$"),
            Pattern.compile("^\\s*#define\\s+(\\w+)\\s+(-?[0-9\\.Ff]+|\\w+)\\s*(//.*)?$"),
            Pattern.compile("^\\s*const\\s*bool\\s*([A-Za-z0-9_]+)\\s*=\\s*(true|false)\\s*;\\s*(//.*)?$"),
            Pattern.compile("^\\s*const\\s*(float|int)\\s*([A-Za-z0-9_]+)\\s*=\\s*(-?[0-9\\.]+f?F?)\\s*;\\s*(//.*)?$")};
        Pattern ifdef = Pattern.compile("^\\s*#if(n)?def\\s+([A-Za-z0-9_]+)(\\s*)?$");
        String[] tokens = {"#define", "//", "#ifdef", "#ifndef", "#if", "#elif", "const", "bool", "float", "int", "FOO", "BAR_1", "=", "true",
                "1.0", ";", " ", "  ", "\t", "MC_GL_A", "MC_GL_AB", "MC_RENDER_X", "MC_", "x", "defined", "(", ")", "\n"};
        String[] names = {"FOO", "BAR_1", "x", "MC_GL_A", "NOPE", "bool"};
        Macro[] macros = {new Macro("MC_GL_AB"), new Macro("MC_GL_A"), new Macro("MC_RENDER_X"), new Macro("MC_NEVER"), new Macro("MC_GL_ABC")};
        Random r = new Random(7);
        for (int round = 0; round < 200; round++) {
            String[] lines = new String[200];
            for (int i = 0; i < lines.length; i++) {
                StringBuilder sb = new StringBuilder();
                for (int k = r.nextInt(8); k >= 0; k--) sb.append(tokens[r.nextInt(tokens.length)]);
                lines[i] = sb.toString();
            }
            for (String line : lines) {
                boolean any = false;
                for (Pattern p : option) any |= p.matcher(line).matches();
                if (OptiFineHooks.cannotBeOption(line) && any) throw new AssertionError("cannotBeOption wrong for: " + line);

                List<Macro> expect = new ArrayList<Macro>();
                for (Macro m : macros) if (line.contains(m.getName())) expect.add(m);
                Object[] got = OptiFineHooks.findMacros(line, macros);
                if (!(got instanceof Macro[]) || !expect.equals(java.util.Arrays.asList(got))) throw new AssertionError("findMacros wrong for: " + line);
            }
            for (String name : names) {
                boolean expect = false;
                for (String line : lines) {
                    java.util.regex.Matcher m = ifdef.matcher(line);
                    if (m.matches() && m.group(2).equals(name)) { expect = true; break; }
                }
                if (OptiFineHooks.isIfdefUsed(name, lines) != expect) throw new AssertionError("isIfdefUsed wrong for " + name);
            }
        }
        System.out.println("OptiFine hooks match the original logic on 40000 generated lines");
    }
}
