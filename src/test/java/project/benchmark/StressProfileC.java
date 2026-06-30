package project.benchmark;

/** 极限 cache 与 worldgen 压力 profile。 */
public final class StressProfileC implements StressProfile {
    public String name() { return "C-extreme"; }
    public int threads() { return 4; }
    public long iterations() { return 500_000_000L; }
    public int width() { return 25_000; }
    public int height() { return 20_000; }
    public int maxChunkCacheSize() { return 512; }
    public int worldgenConcurrency() { return 2; }
}
