package project;

import com.seedfinding.mccore.version.MCVersion;

/**
 * 游戏版本选择。26.2 在 mc_core 中无对应枚举，底层仍映射为 v1_21。
 */
public enum GameVersion {
    V26_2(MCVersion.v1_21, "26.2"),
    V1_21_TO_26_1(MCVersion.v1_21, "1.21.x~26.1"),
    V1_20_1(MCVersion.v1_20_1, "1.20.x"),
    V1_19_2(MCVersion.v1_19_2, "1.19.x"),
    V1_18_2(MCVersion.v1_18_2, "1.18.x");

    private final MCVersion mcVersion;
    private final String displayName;

    GameVersion(MCVersion mcVersion, String displayName) {
        this.mcVersion = mcVersion;
        this.displayName = displayName;
    }

    public MCVersion getMcVersion() {
        return mcVersion;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static GameVersion fromDisplayName(String name) {
        if (name == null) {
            return V1_21_TO_26_1;
        }
        for (GameVersion version : values()) {
            if (version.displayName.equals(name)) {
                return version;
            }
        }
        // 兼容旧版 UI 字符串
        return switch (name) {
            case "26.2" -> V26_2;
            case "1.21.1" -> V1_21_TO_26_1;
            case "1.20.1" -> V1_20_1;
            case "1.19.2" -> V1_19_2;
            case "1.18.2" -> V1_18_2;
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
