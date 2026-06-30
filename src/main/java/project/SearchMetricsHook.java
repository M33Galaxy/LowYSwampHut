package project;

/**
 * 搜索过程的可选观测接口。生产环境默认使用 {@link #NO_OP}，不会采集或保留任何指标。
 */
public interface SearchMetricsHook {
    SearchMetricsHook NO_OP = new SearchMetricsHook() {};

    default void regionWorkerStarted() {}
    default void regionWorkerStopped() {}
    default void structureGenerationStarted() {}
    default void seedCheckerCreated(Object checker) {}
    default void seedCheckerReleased(Object checker) {}
    default void chunkCacheObserved(Object checker, int size) {}
}
