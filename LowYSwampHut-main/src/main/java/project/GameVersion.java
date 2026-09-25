package project;

/**
 * 游戏版本选择。
 *
 * <p>本枚举只保存"给用户看的名字"和"给 C 内核的版本标志"，不再持有
 * {@code com.seedfinding.mccore.version.MCVersion} —— 那是 Java 老路径
 * （mc_feature 结构定位 / SeedChecker）才需要的东西，已随老路径一起删除。
 *
 * <p>传给 {@link NativePhase2#gradeScanNative} 的标志只有两个版本分支：
 * {@code mc1182}（1.18.2）与 {@code v262}（26.2~26.3）。其余版本两者都为 0，
 * 由 C 内核按「1.19~26.1」默认处理。UI 展示也按这三条逻辑路径合并：
 * {@code 26.2~26.3} / {@code 1.19.x~26.1} / {@code 1.18.x}。
 */
public enum GameVersion {
    V26_2("26.2~26.3"),
    V1_19_TO_26_1("1.19.x~26.1"),
    V1_18_2("1.18.x");

    private final String displayName;

    GameVersion(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 把用户给的版本串解析成枚举。
     *
     * <p>解析顺序：先精确匹配下拉框的展示名，再吃旧版 UI / CLI 用过的别名。
     * <b>认不出来一律回落到默认的 {@link #V26_2}</b> —— 26.2~26.3 是本产品的默认版本，
     * 而且 C 内核的 opts 全零就是 26.2 语义，两处必须一致。
     *
     * <p>⚠️ `--version 1.21`（CLI 的写法）与 `1.19` / `1.20` 这类只有主次版本的串
     * 以前会掉进 default 变成 26.2 —— 那是**静默**的错版本。现在按前缀映射到
     * 对应分支：CLI 的 `--version` 合法值见 `lysh --help` / Java CLI 帮助。
     */
    public static GameVersion fromDisplayName(String name) {
        if (name == null) {
            return V26_2;
        }
        String v = name.trim();
        for (GameVersion version : values()) {
            if (version.displayName.equals(v)) {
                return version;
            }
        }
        // 兼容旧版 UI / CLI 字符串，以及合并前的展示名
        return switch (v) {
            case "26.2", "26.3", "26.2~26.3" -> V26_2;
            case "1.19", "1.19.2", "1.19.x",
                 "1.20", "1.20.1", "1.20.x",
                 "1.21", "1.21.1", "1.21.x~26.1", "1.21.5~26.1",
                 "1.19.x~26.1", "26.1" -> V1_19_TO_26_1;
            case "1.18", "1.18.1", "1.18.2", "1.18.x" -> V1_18_2;
            default -> V26_2;
        };
    }

    public static String[] displayNames() {
        GameVersion[] versions = values();
        String[] names = new String[versions.length];
        for (int i = 0; i < versions.length; i++) {
            names[i] = versions[i].displayName;
        }
        return names;
    }
}
