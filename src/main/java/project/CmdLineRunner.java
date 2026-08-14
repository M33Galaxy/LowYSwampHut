package project;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LowYSwampHut 命令行入口（基于 SearchCoords 引擎）
 * 用法: java -jar LowYSwampHut.jar --seed <种子> [选项]
 */
public class CmdLineRunner {

    public static void main(String[] args) {
        // 1. 初始化 log4j 和 SharedConstants（安全顺序）
        initLogging();

        // 2. 默认参数
        long seed = 0;
        int maxY = -40;
        int minX = -58594, maxX = 58593;
        int minZ = -58594, maxZ = 58593;
        String outputFile = "result.txt";
        String versionName = "26.2";
        boolean checkGen = false;
        int threads = Math.max(1, Math.min(
            Runtime.getRuntime().availableProcessors() / 2,
            8
        ));

        // 3. 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--seed", "-s" -> seed = Long.parseLong(args[++i]);
                case "--max-y" -> maxY = Integer.parseInt(args[++i]);
                case "--min-x" -> minX = Integer.parseInt(args[++i]);
                case "--max-x" -> maxX = Integer.parseInt(args[++i]);
                case "--min-z" -> minZ = Integer.parseInt(args[++i]);
                case "--max-z" -> maxZ = Integer.parseInt(args[++i]);
                case "--version" -> versionName = args[++i];
                case "--output", "-o" -> outputFile = args[++i];
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--check-gen" -> checkGen = true;
                case "--help", "-h" -> { printHelp(); return; }
                default -> {
                    System.err.println("未知参数: " + args[i]);
                    printHelp();
                    System.exit(1);
                }
            }
        }

        if (seed == 0) {
            System.err.println("错误: 必须指定 --seed");
            printHelp();
            System.exit(1);
        }

        // 4. 版本映射
        GameVersion gameVersion = GameVersion.fromDisplayName(versionName);
        if (gameVersion == null) {
            System.err.println("错误: 不支持的版本 " + versionName);
            System.exit(1);
        }
        WorldPresetMode preset = WorldPresetMode.DEFAULT;

        // 5. 创建 SearchCoords 并启动搜索
        System.out.println("开始搜索种子 " + seed + "，最大Y=" + maxY + "，版本=" + versionName);
        long startTime = System.currentTimeMillis();
        SearchCoords searcher = new SearchCoords(gameVersion, preset);
        List<CoordResult> rawResults = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger count = new AtomicInteger(0);
        AtomicInteger lastPrinted = new AtomicInteger(0);

        searcher.startSearch(
            seed,
            threads,
            minX, maxX, minZ, maxZ,
            maxY,
            progress -> {
                int pct = (int) (progress.percentage() * 100);
                int stage = progress.stage();
                if (pct >= lastPrinted.get() + 1) {
                    System.out.printf("\r阶段 %d 进度: %d%% (结果:%d)", stage, pct, count.get());
                    lastPrinted.set(pct);
                }
            },
            result -> {
                rawResults.add(result);
                count.incrementAndGet();
            },
            checkGen
        );

        // 6. 等待搜索完成（轮询 isRunning）
        try {
            while (searcher.isRunning()) {
                Thread.sleep(1000);
                if (count.get() > 0 && count.get() % 100 == 0) {
                    System.out.printf("\r已找到 %d 个结果...", count.get());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("\n搜索被中断");
            System.exit(1);
        }
        System.out.println("\n搜索完成，耗时 " + (System.currentTimeMillis() - startTime) / 1000.0 + " 秒");

        // 7. 排序并输出结果
        if (rawResults.isEmpty()) {
            System.out.println("未找到符合条件的女巫小屋。");
            return;
        }

        // 按 Y 坐标排序（从低到高）
        rawResults.sort(Comparator.comparingInt(CoordResult::y));

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile))) {
            writer.println("找到 " + rawResults.size() + " 个女巫小屋：");
            for (CoordResult r : rawResults) {
                writer.printf("/tp %d %d %d%n", r.x(), r.y(), r.z());
            }
            CoordResult lowest = rawResults.get(0);
            writer.printf("\n最低 Y 坐标的小屋: /tp %d %d %d%n", lowest.x(), lowest.y(), lowest.z());
            System.out.println("结果已保存到: " + outputFile);
        } catch (IOException e) {
            System.err.println("写入文件失败: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 安全的 log4j + SharedConstants 初始化（顺序必须正确）
     */
    private static void initLogging() {
        System.setProperty("log4j2.isThreadContextMapInheritable", "true");
        System.setProperty("log4j2.disable.jmx", "true");
        System.setProperty("log4j2.formatMsgNoLookups", "true");
        System.setProperty("log4j2.enable.threadlocals", "false");
        System.setProperty("log4j2.enable.direct.encoders", "false");
        System.setProperty("max.bg.threads", "2");

        // 先触发 log4j 初始化
        org.apache.logging.log4j.LogManager.getContext(false);

        // 再加载 Minecraft 的 SharedConstants
        try {
            Class.forName("net.minecraft.SharedConstants");
        } catch (Exception ignored) {
        }
    }

    private static void printHelp() {
        System.out.println("LowYSwampHut 命令行搜索工具");
        System.out.println("用法: java -jar LowYSwampHut.jar --seed <种子> [选项]");
        System.out.println("选项:");
        System.out.println("  --seed, -s <种子>   必需，要搜索的种子 (整数)");
        System.out.println("  --max-y <数值>     最大Y坐标，默认 -40");
        System.out.println("  --min-x <数值>     X范围最小值，默认 -58594");
        System.out.println("  --max-x <数值>     X范围最大值，默认 58593");
        System.out.println("  --min-z <数值>     Z范围最小值，默认 -58594");
        System.out.println("  --max-z <数值>     Z范围最大值，默认 58593");
        System.out.println("  --version <版本>   Minecraft版本，默认 26.2");
        System.out.println("  --output, -o <文件> 输出文件，默认 result.txt");
        System.out.println("  --threads <数量>   线程数，默认 CPU核心数/2 (上限8)");
        System.out.println("  --check-gen        精确检查生成（较慢但更准）");
        System.out.println("  --help, -h         显示此帮助");
    }
}