package project.benchmark;

/** 压力测试参数定义。 */
public interface StressProfile {
    String name();
    int threads();
    long iterations();
    int width();
    int height();
    int maxChunkCacheSize();
    int worldgenConcurrency();

    default double maxHeight() {
        return 0.0;
    }

    default long seed() {
        return 3278057578772408745L;
    }
}
