package project;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.ThreadContext;

/**
 * 启动器类，用于在类加载之前设置系统属性
 * 这个类必须在任何其他类之前被加载
 */
public class Launcher {
    static {
        // 在类加载时立即设置系统属性
        // 这些属性必须在任何 log4j 类被加载之前设置
        System.setProperty("log4j2.isThreadContextMapInheritable", "true");
        System.setProperty("log4j2.disable.jmx", "true");
        System.setProperty("log4j2.formatMsgNoLookups", "true");
        System.setProperty("log4j2.callerClass", Launcher.class.getName());
        // 禁用线程本地存储，避免在 Shadow JAR 中无法获取调用类的问题
        System.setProperty("log4j2.enable.threadlocals", "false");
        // 禁用直接编码器，避免调用者类查找问题
        System.setProperty("log4j2.enable.direct.encoders", "false");
    }
    
    public static void main(String[] args) {
        // 首先设置 ThreadContext，为 log4j 提供调用者信息
        ThreadContext.put("callerClass", Launcher.class.getName());
        
        // 初始化 log4j，获取一个 logger 来触发初始化
        // 这必须在任何使用 log4j 的类之前完成
        try {
            LogManager.getLogger(Launcher.class);
        } catch (Exception e) {
            System.err.println("Warning: Could not initialize log4j: " + e.getMessage());
            e.printStackTrace();
        }
        
        // 预先加载并初始化 net.minecraft.SharedConstants 类
        // 这必须在主线程中完成，以确保 log4j 可以正确找到调用类
        try {
            // 使用反射来预先加载 SharedConstants 类
            // 这会在主线程中触发其静态初始化，此时 ThreadContext 已经设置好
            Class.forName("net.minecraft.SharedConstants");
        } catch (ClassNotFoundException e) {
            System.err.println("Warning: Could not find SharedConstants class: " + e.getMessage());
        } catch (ExceptionInInitializerError e) {
            // 如果初始化失败，尝试使用反射来设置调用者类
            System.err.println("Warning: SharedConstants initialization failed: " + e.getMessage());
            Throwable cause = e.getCause();
            if (cause != null) {
                cause.printStackTrace();
            }
            // 继续执行，让 SeedCheckerInitializer 处理
        } catch (Exception e) {
            System.err.println("Warning: Error loading SharedConstants: " + e.getMessage());
            e.printStackTrace();
        }
        
        // 调用实际的 main 方法
        LowYSwampHutForFixedSeed.main(args);
    }
}

