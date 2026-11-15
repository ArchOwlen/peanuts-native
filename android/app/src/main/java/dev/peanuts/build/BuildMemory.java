package dev.peanuts.build;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * How many workers an on-device build may run for the phone's memory. The packer's workers are sized by cores (CpuCores: up to 4 split / translation workers,
 * 4 translator calls at once), and every worker holds its own copy of the profile's tables (about 100-250 MB, android/SHADERS.md) beside the parent's
 * scan and the translator's own memory; the rewrite alone peaks at about 2.7 GB. On a 16 GB phone that is nothing; on an 8 GB one (the Thor Base,
 * MemTotal about 7.4 GB) Android's low-memory killer ends the build's process at a random point. So the counts follow the phone's total memory, then
 * what is free now, then how many times Android already ended this job's process (Job mem_level: each time halves them).
 *
 * <pre>
 *   total memory (MemTotal)   split / shader workers   translator calls at once
 *   14 GB and more (16 GB)    CpuCores (4)             4         (unchanged)
 *   10-14 GB (12 GB)          CpuCores (4)             3
 *   6.5-10 GB (8 GB)          2                        2
 *   under 6.5 GB              1                        1
 *   MemAvailable under 2.5 GB: at most 2 and 2; under 1.5 GB: 1 and 1;   each earlier kill: halved (never under 1)
 * </pre>
 */
public final class BuildMemory {
    public static final class Plan {
        public final int workers, xlate;
        public final String why;
        Plan(int workers, int xlate, String why) { this.workers = workers; this.xlate = xlate; this.why = why; }
    }

    private BuildMemory() {}

    /** The counts for a phone with totalMb / availMb (0 or less: not known: the CPU's counts stay) and kills earlier kills of the job's process; cpuWorkers is CpuCores' count */
    public static Plan plan(long totalMb, long availMb, int kills, int cpuWorkers) {
        int w = Math.max(1, cpuWorkers), x = 4;
        if (totalMb > 0) {
            if (totalMb >= 14000) { }
            else if (totalMb >= 10000) x = 3;
            else if (totalMb >= 6500) { w = Math.min(w, 2); x = 2; }
            else { w = 1; x = 1; }
        }
        if (availMb > 0) {
            if (availMb < 1500) { w = 1; x = 1; }
            else if (availMb < 2500) { w = Math.min(w, 2); x = Math.min(x, 2); }
        }
        int k = Math.min(Math.max(kills, 0), 3);
        for (int i = 0; i < k; i++) { w = Math.max(1, w / 2); x = Math.max(1, x / 2); }
        return new Plan(w, x, "memory " + totalMb + " MB, free " + availMb + " MB, " + k + " earlier kills: " + w + " workers, " + x + " translator calls");
    }

    /** A kB figure of a /proc/meminfo line ("MemTotal:  7654321 kB") in MB, 0 when it is not there */
    public static long meminfoMb(String meminfo, String key) {
        for (String l : meminfo.split("\n")) {
            if (!l.startsWith(key + ":")) continue;
            String[] p = l.substring(key.length() + 1).trim().split("\\s+");
            try { return Long.parseLong(p[0]) / 1024; } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    public static String readMeminfo() {
        try { return new String(Files.readAllBytes(new File("/proc/meminfo").toPath()), StandardCharsets.US_ASCII); }
        catch (Exception e) { return ""; }
    }

    /** Caps a count taken from env.txt (a test's own, or an older Prepare's fixed line) at the plan's: a non-number stays as it is */
    public static String cap(String value, int max) {
        try { return String.valueOf(Math.min(Integer.parseInt(value.trim()), max)); } catch (NumberFormatException e) { return value; }
    }
}
