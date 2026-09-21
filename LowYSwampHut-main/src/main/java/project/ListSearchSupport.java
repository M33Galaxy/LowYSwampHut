package project;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * 与图形界面「从种子列表搜索」相同的解析与并行度计算。
 */
final class ListSearchSupport {
    static final int DEFAULT_MIN_X = -128;
    static final int DEFAULT_MAX_X = 128;
    static final int DEFAULT_MIN_Z = -128;
    static final int DEFAULT_MAX_Z = 128;
    static final long SEED_PARALLEL_MAX_AREA = 150_000L;
    static final long TARGET_CELLS_PER_THREAD = 4096L;

    private ListSearchSupport() {
    }

    static Long parseSeedLine(String line) {
        if (line == null) {
            return null;
        }
        line = line.trim();
        if (line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        try {
            return Long.parseLong(line);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 统计有效种子行数。`stillRunning` 为 false 时提前结束 —— GUI 的"停止"按钮靠它
     * 中断一个已经很长的种子文件统计（CLI 传 {@code () -> true}，即不中断）。
     */
    static long countValidSeeds(File file, BooleanSupplier stillRunning) throws IOException {
        long count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file), 1 << 20)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (stillRunning != null && !stillRunning.getAsBoolean()) {
                    break;
                }
                if (parseSeedLine(line) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    static int computeConcurrentSeeds(long area, int threadCount) {
        if (threadCount < 1) {
            return 1;
        }
        if (area <= SEED_PARALLEL_MAX_AREA) {
            return threadCount;
        }
        return (int) Math.min(threadCount,
                Math.max(1L, threadCount / Math.max(1L, area / TARGET_CELLS_PER_THREAD)));
    }

    static int computeThreadsPerSeed(int threadCount, int concurrentSeeds) {
        return Math.max(1, threadCount / Math.max(1, concurrentSeeds));
    }
}
