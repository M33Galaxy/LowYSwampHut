package project.benchmark;

/** 低负载稳定性 profile。 */
public final class StressProfileA implements StressProfile {
    public String name() { return "A-low-load"; }
    public int threads() { return 2; }
    public long iterations() { return 1_000_000L; }
    public int width() { return 1_000; }
    public int height() { return 1_000; }
    public int maxChunkCacheSize() { return 1_024; }
    public int worldgenConcurrency() { return 2; }
}
