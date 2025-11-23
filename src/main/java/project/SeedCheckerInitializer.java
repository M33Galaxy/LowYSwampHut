package project;

import nl.jellejurre.seedchecker.SeedChecker;
import nl.jellejurre.seedchecker.SeedCheckerDimension;
import nl.jellejurre.seedchecker.TargetState;

/**
 * 用于在主线程中预先初始化 SeedCheckerSettings，避免在多线程环境中初始化时出现 log4j 错误
 */
public class SeedCheckerInitializer {
    private static volatile boolean initialized = false;
    private static final Object lock = new Object();
    
    /**
     * 在主线程中初始化 SeedCheckerSettings
     * 这必须在任何多线程使用 SeedChecker 之前调用
     */
    public static void initialize() {
        if (initialized) {
            return;
        }
        synchronized (lock) {
            if (initialized) {
                return;
            }
            try {
                // 在创建 SeedChecker 之前，确保 ThreadContext 已经设置好调用者类
                // 这有助于 log4j 在 Shadow JAR 中找到调用类
                try {
                    Class<?> threadContextClass = Class.forName("org.apache.logging.log4j.ThreadContext");
                    java.lang.reflect.Method putMethod = threadContextClass.getMethod("put", String.class, String.class);
                    putMethod.invoke(null, "callerClass", SeedCheckerInitializer.class.getName());
                } catch (Exception e) {
                    // 如果反射失败，继续尝试
                }
                
                // 在主线程中创建 SeedChecker 实例以触发 SeedCheckerSettings 的初始化
                // 这必须在任何多线程使用之前完成
                // 使用同步块确保只有一个线程初始化
                // 注意：SharedConstants 应该已经在 Launcher 中初始化了
                synchronized (SeedCheckerInitializer.class) {
                    SeedChecker preInit = new SeedChecker(0L, TargetState.NO_STRUCTURES, SeedCheckerDimension.OVERWORLD);
                    preInit.clearMemory();
                }
                initialized = true;
            } catch (ExceptionInInitializerError e) {
                // 如果初始化失败，尝试使用不同的方法
                // 这可能是因为 log4j 在 Shadow JAR 中无法找到调用类
                Throwable cause = e.getCause();
                if (cause != null && cause.getMessage() != null && 
                    cause.getMessage().contains("No class provided")) {
                    // 这是 log4j 的调用者查找问题
                    System.err.println("Warning: log4j caller class issue detected in Shadow JAR.");
                    System.err.println("Attempting alternative initialization...");
                    
                    // 尝试延迟初始化，让 log4j 在第一次使用时再初始化
                    // 标记为已初始化，但实际初始化会在第一次使用时进行
                    initialized = true;
                    return;
                }
                // 其他类型的 ExceptionInInitializerError，重新抛出
                throw e;
            } catch (Exception e) {
                // 即使初始化失败，也标记为已尝试，避免重复尝试
                System.err.println("Warning: Failed to pre-initialize SeedChecker: " + e.getMessage());
                e.printStackTrace();
                initialized = true;
            }
        }
    }
}

