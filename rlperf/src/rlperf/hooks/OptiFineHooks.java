package rlperf.hooks;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faster equivalents of three hot spots in OptiFine's ShaderPackParser. Each returns exactly what
 * the original code returns; they only avoid redoing work per option / per macro.
 */
public final class OptiFineHooks {
    private OptiFineHooks() {}

    /** Same pattern as ShaderOptionSwitch.PATTERN_IFDEF. */
    private static final Pattern IFDEF = Pattern.compile("^\\s*#if(n)?def\\s+([A-Za-z0-9_]+)(\\s*)?$");

    private static WeakReference<String[]> lastLines = new WeakReference<String[]>(null);
    private static HashSet<String> lastNames;

    /**
     * ShaderPackParser.isOptionUsed for a ShaderOptionSwitch: is there an #ifdef/#ifndef line naming it?
     * The original regex-scans every line once per option; this scans the file once and keeps the names.
     */
    public static synchronized boolean isIfdefUsed(String name, String[] lines) {
        if (lastLines.get() != lines) {
            HashSet<String> names = new HashSet<String>();
            for (String line : lines) {
                if (line.indexOf("#if") < 0) continue;
                Matcher m = IFDEF.matcher(line);
                if (m.matches()) names.add(m.group(2));
            }
            lastNames = names;
            lastLines = new WeakReference<String[]>(lines);
        }
        return lastNames.contains(name);
    }

    /** All four option patterns need "#define" or "const"; anything else cannot be an option line. */
    public static boolean cannotBeOption(String line) {
        return line.indexOf("#define") < 0 && line.indexOf("const") < 0;
    }

    private static Object[] cachedMacros;
    private static String[] cachedNames;
    private static String cachedPrefix;

    /**
     * ShaderPackParser.findMacros: the macros (in array order) whose name occurs in the line.
     * The original runs one full substring search per macro over what is often a whole included file;
     * this finds the occurrences of the names' common prefix once and tests the names only there.
     */
    public static synchronized Object[] findMacros(String line, Object[] macros) throws Exception {
        if (macros != cachedMacros) {
            String[] names = new String[macros.length];
            if (macros.length > 0) {
                Method getName = macros[0].getClass().getMethod("getName");
                for (int i = 0; i < macros.length; i++) names[i] = (String) getName.invoke(macros[i]);
            }
            String prefix = names.length == 0 ? "" : names[0];
            for (String n : names) {
                int k = 0;
                while (k < prefix.length() && k < n.length() && prefix.charAt(k) == n.charAt(k)) k++;
                prefix = prefix.substring(0, k);
            }
            cachedNames = names;
            cachedPrefix = prefix;
            cachedMacros = macros;
        }
        String[] names = cachedNames;
        boolean[] hit = new boolean[names.length];
        int count = 0;
        if (cachedPrefix.isEmpty()) {
            for (int i = 0; i < names.length; i++) {
                if (line.contains(names[i])) { hit[i] = true; count++; }
            }
        } else {
            for (int p = line.indexOf(cachedPrefix); p >= 0 && count < names.length; p = line.indexOf(cachedPrefix, p + 1)) {
                for (int i = 0; i < names.length; i++) {
                    if (!hit[i] && line.startsWith(names[i], p)) { hit[i] = true; count++; }
                }
            }
        }
        Object[] result = (Object[]) Array.newInstance(macros.getClass().getComponentType(), count);
        for (int i = 0, j = 0; i < names.length; i++) {
            if (hit[i]) result[j++] = macros[i];
        }
        return result;
    }
}
