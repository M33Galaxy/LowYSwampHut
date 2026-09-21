package project;

/**
 * 启动器：分派 CLI / GUI。
 *
 * <p>本类只依赖 JDK。历史版本在这里初始化 log4j 并预加载 {@code net.minecraft.SharedConstants}
 * （那时搜索走 SeedChecker，日志栈也来自它）；现在计算全在 C 内核（{@code lysh.dll}）里，
 * log4j / Minecraft 类都不再需要，因此这些初始化全部删除。
 *
 * <p>{@code isCliInvocation} 与 {@link CmdLineRunner} 的选项表保持一致：
 * 出现任一 CLI 选项就走命令行模式，否则打开 Swing GUI。
 */
public class Launcher {

    public static void main(String[] args) {
        if (isCliInvocation(args)) {
            CmdLineRunner.run(args);
        } else {
            LowYSwampHutForFixedSeed.main(args);
        }
    }

    private static boolean isCliInvocation(String[] args) {
        for (String arg : args) {
            switch (arg) {
                case "--seed", "-s", "--help", "-h",
                        "--max-y", "--min-x", "--max-x", "--min-z", "--max-z", "--square-side",
                        "--version", "--preset", "--output", "-o", "--threads",
                        "--no-progress", "--seeds-file", "-f", "--export-seeds", "--lang" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }
}
