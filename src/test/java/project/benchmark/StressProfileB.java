package project.benchmark;

/** 标准搜索压力 profile。 */
public final class StressProfileB implements StressProfile {
    public String name() { return "B-standard"; }
    public int threads() { return 4; }
    public long iterations() { return 10_000_000L; }
    public int width() { return 10_000; }
    public int height() { return 1_000; }
    public int maxChunkCacheSize() { return 1_024; }
    public int worldgenConcurrency() { return 2; }
    public double maxHeight() { return 60.0; }
}
