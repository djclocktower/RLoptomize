package rlperf.hooks;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Called around every server tick. Two jobs:
 *
 * Pacing. Vanilla lets chunk loading and generation use 50 ms of every tick, which is the whole
 * tick, so mobs run at half speed for as long as chunks are coming in. Here the server keeps
 * count of how far behind schedule it is and chunk work only gets the time that is left.
 *
 * Slow tick log (off unless slowTickLog=true). When a tick runs long, the server thread's stack
 * is sampled until the tick ends and the path it spent its time in is written to
 * logs/rlperf-slow-ticks.log.
 */
public final class TickWatch {
    private TickWatch() {}

    private static final long SAMPLE_AFTER_NANOS = 40_000_000L;   // start sampling once a tick passes 40 ms
    private static final int MAX_REPORTS = 2000;

    private static final long TICK_NANOS = 50_000_000L;
    private static final long CHUNK_BUDGET_NANOS = 45_000_000L;   // most a tick may spend bringing chunks in
    private static final long MAX_BEHIND_NANOS = 250_000_000L;    // one huge tick must not starve chunk loading for long

    private static long behindNanos;              // server thread only
    private static boolean skippedLastTick;

    private static boolean recording;
    private static long thresholdNanos = 100_000_000L;
    private static File logFile;
    private static volatile long tickStart;        // 0 while no tick is running
    private static volatile Thread serverThread;
    private static final List<StackTraceElement[]> samples = new ArrayList<StackTraceElement[]>();
    private static long samplesFor;
    private static Thread sampler;
    private static int reports;
    private static long windowStart, windowTicks, windowMaxNanos, windowMaxBehind;   // server thread only

    public static void configure(File gameDir, long thresholdMs) {
        recording = true;
        logFile = new File(gameDir, "logs/rlperf-slow-ticks.log");
        thresholdNanos = thresholdMs * 1_000_000L;
    }

    /**
     * Runs on the server thread before a world starts loading. Roguelike Dungeons reads all of its
     * dungeon settings the first time a chunk is populated, a 1.5 s freeze just after joining;
     * loading the class here moves that behind the "Loading world" screen. It only reads config files.
     */
    public static void serverStarting() {
        try {
            Class.forName("greymerk.roguelike.dungeon.Dungeon", true, TickWatch.class.getClassLoader());
        } catch (Throwable t) {
            // mod not installed, or its own problem to report when it is first used
        }
    }

    /** Nanoseconds this tick may spend loading and generating chunks for players. */
    public static long chunkBudgetNanos() {
        return Math.max(0L, CHUNK_BUDGET_NANOS - behindNanos);
    }

    /** False when the server is a whole tick behind and brought chunks in last tick: let this one catch up. */
    public static boolean mayLoadChunks() {
        if (behindNanos > TICK_NANOS && !skippedLastTick) {
            skippedLastTick = true;
            return false;
        }
        skippedLastTick = false;
        return true;
    }

    public static void tickStart() {
        if (recording && sampler == null) startSampler();
        serverThread = Thread.currentThread();
        tickStart = System.nanoTime();
    }

    public static void tickEnd() {
        long start = tickStart;
        tickStart = 0;
        if (start == 0) return;
        long took = System.nanoTime() - start;
        behindNanos = Math.min(MAX_BEHIND_NANOS, Math.max(0L, behindNanos + took - TICK_NANOS));
        if (!recording) return;
        long now = start + took;
        if (windowStart == 0) windowStart = now;
        windowTicks++;
        windowMaxNanos = Math.max(windowMaxNanos, took);
        windowMaxBehind = Math.max(windowMaxBehind, behindNanos);
        if (now - windowStart >= 2_000_000_000L) {
            // Only worth a line when the two seconds were not perfectly ordinary.
            if (windowMaxNanos >= thresholdNanos && reports < MAX_REPORTS) {
                line(String.format("[%s] %.1f ticks/s over %.1f s, longest tick %d ms, furthest behind %d ms%n",
                        new SimpleDateFormat("HH:mm:ss").format(new Date()), windowTicks * 1e9 / (now - windowStart),
                        (now - windowStart) / 1e9, windowMaxNanos / 1_000_000L, windowMaxBehind / 1_000_000L));
            }
            windowStart = now;
            windowTicks = windowMaxNanos = windowMaxBehind = 0;
        }
        if (took < thresholdNanos) return;
        List<StackTraceElement[]> mine;
        synchronized (samples) {
            mine = samplesFor == start ? new ArrayList<StackTraceElement[]>(samples) : new ArrayList<StackTraceElement[]>();
            samples.clear();
        }
        if (reports++ < MAX_REPORTS) write(took, mine);
    }

    private static synchronized void startSampler() {
        if (sampler != null) return;
        sampler = new Thread("RLPerf tick watch") {
            @Override public void run() {
                while (true) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { return; }
                    long start = tickStart;
                    Thread t = serverThread;
                    if (start == 0 || t == null || System.nanoTime() - start < SAMPLE_AFTER_NANOS) continue;
                    StackTraceElement[] stack = t.getStackTrace();
                    synchronized (samples) {
                        if (samplesFor != start) { samples.clear(); samplesFor = start; }
                        if (tickStart == start && samples.size() < 2000) samples.add(stack);
                    }
                }
            }
        };
        sampler.setDaemon(true);
        sampler.start();
    }

    private static void line(String text) {
        try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
            out.print(text);
        } catch (Exception e) {
            // a diagnostic must never break the game
        }
    }

    /** Follows the stack from the bottom for as long as most samples agree, then lists where they split. */
    private static void write(long tookNanos, List<StackTraceElement[]> stacks) {
        try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
            out.printf("[%s] tick took %d ms (%d samples)%n", new SimpleDateFormat("HH:mm:ss").format(new Date()),
                    tookNanos / 1_000_000L, stacks.size());
            List<StackTraceElement[]> group = stacks;
            List<String> path = new ArrayList<String>();
            for (int depth = 0; !group.isEmpty(); depth++) {
                Map<String, List<StackTraceElement[]>> next = new LinkedHashMap<String, List<StackTraceElement[]>>();
                for (StackTraceElement[] s : group) {
                    int i = s.length - 1 - depth;
                    if (i < 0) continue;
                    String frame = s[i].getClassName() + "." + s[i].getMethodName();
                    List<StackTraceElement[]> l = next.get(frame);
                    if (l == null) next.put(frame, l = new ArrayList<StackTraceElement[]>());
                    l.add(s);
                }
                String best = null;
                for (Map.Entry<String, List<StackTraceElement[]>> e : next.entrySet()) {
                    if (best == null || e.getValue().size() > next.get(best).size()) best = e.getKey();
                }
                if (best == null) break;
                if (next.get(best).size() * 2 < stacks.size()) {       // no majority below here: show the split
                    for (Map.Entry<String, List<StackTraceElement[]>> e : next.entrySet()) {
                        if (e.getValue().size() * 10 >= stacks.size()) {
                            path.add(String.format("  %3d%% %s", 100 * e.getValue().size() / stacks.size(), e.getKey()));
                        }
                    }
                    break;
                }
                path.add(String.format("  %3d%% %s", 100 * next.get(best).size() / stacks.size(), best));
                group = next.get(best);
            }
            // The bottom of every server stack is the same boilerplate; the last 30 frames are the story.
            for (String line : path.subList(Math.max(0, path.size() - 30), path.size())) out.println(line);
            out.println();
        } catch (Exception e) {
            // a diagnostic must never break the game
        }
    }
}
