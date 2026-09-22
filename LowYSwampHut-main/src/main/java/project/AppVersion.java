package project;

/**
 * 版本号（与 C 内核 lysh、窗口标题、打包 jar 名共用）。
 * {@code build-dist.ps1} 解析本常量，生成 {@code LowYSwampHut-<VERSION>.jar}。
 */
public final class AppVersion {

    public static final String VERSION = "2.0.0";

    private AppVersion() {
    }

    /** 窗口标题等展示用：基础文案 + 空格 + 版本号。 */
    public static String titled(String baseTitle) {
        if (baseTitle == null || baseTitle.isBlank()) {
            return VERSION;
        }
        return baseTitle.trim() + " " + VERSION;
    }
}
