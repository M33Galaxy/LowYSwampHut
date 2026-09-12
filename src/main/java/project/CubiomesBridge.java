package project;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JNI bridge to cubiomes: structure position + climate three-params only.
 * Cont / cave ladders / 5-probe density / aquifer remain in Java.
 */
public final class CubiomesBridge {

    private static final Object LOCK = new Object();
    private static boolean loaded = false;
    private static boolean available = false;
    private static String loadError;

    static {
        tryLoad();
    }

    private CubiomesBridge() {
    }

    public static boolean isAvailable() {
        return available;
    }

    public static String getLoadError() {
        return loadError;
    }

    private static void tryLoad() {
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            loaded = true;
            try {
                loadNativeLibrary();
                available = true;
            } catch (UnsatisfiedLinkError e) {
                available = false;
                loadError = e.getMessage();
            }
        }
    }

    private static void loadNativeLibrary() {
        try {
            System.loadLibrary("libLowYSwampHutJ");
            return;
        } catch (UnsatisfiedLinkError firstError) {
            if (tryLoadFromBundledResources()) {
                return;
            }
            Path base = Paths.get(System.getProperty("user.dir", "."));
            Path[] candidates = {
                    base.resolve("native").resolve("windows"),
                    base.resolve("native"),
                    base.resolve("jni").resolve("native").resolve("jni").resolve("build").resolve("native"),
            };
            for (Path dir : candidates) {
                for (String libName : getNativeLibNamesForCurrentOs()) {
                    File f = dir.resolve(libName).toFile();
                    if (f.exists()) {
                        System.load(f.getAbsolutePath());
                        return;
                    }
                }
            }
            throw new UnsatisfiedLinkError(
                    "LowYSwampHut native library not found. Build with: jni/build-jni.bat. "
                            + firstError.getMessage());
        }
    }

    private static boolean tryLoadFromBundledResources() {
        String osFolder = getOsResourceFolder();
        if (osFolder == null) {
            return false;
        }
        for (String libName : getNativeLibNamesForCurrentOs()) {
            String resourcePath = "/native/" + osFolder + "/" + libName;
            if (tryLoadSingleBundledResource(resourcePath, libName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tryLoadSingleBundledResource(String resourcePath, String libName) {
        try (InputStream is = CubiomesBridge.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return false;
            }
            Path tempDir = Files.createTempDirectory("lowyswamphut-native-");
            Path tempLib = tempDir.resolve(libName);
            Files.copy(is, tempLib, StandardCopyOption.REPLACE_EXISTING);
            tempLib.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();
            System.load(tempLib.toAbsolutePath().toString());
            return true;
        } catch (IOException | UnsatisfiedLinkError ex) {
            return false;
        }
    }

    private static String getOsResourceFolder() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "macos";
        }
        if (os.contains("nix") || os.contains("nux") || os.contains("aix") || os.contains("linux")) {
            return "linux";
        }
        return null;
    }

    private static List<String> getNativeLibNamesForCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> names = new ArrayList<>();
        if (os.contains("win")) {
            names.add("libLowYSwampHutJ.dll");
            names.add("LowYSwampHutJ.dll");
        } else if (os.contains("mac")) {
            names.add("libLowYSwampHutJ.dylib");
        } else {
            names.add("libLowYSwampHutJ.so");
        }
        return names;
    }

    public static int gameVersionOrdinal(GameVersion version) {
        return version.ordinal();
    }

    public static int worldPresetOrdinal(WorldPresetMode mode) {
        return mode.ordinal();
    }

    /**
     * @return int[2]={blockX, blockZ}, or null if native unavailable / failure
     */
    public static int[] getHutInRegion(long seed, int regX, int regZ, GameVersion gameVersion) {
        ensureAvailable();
        return nativeGetHutInRegion(seed, regX, regZ, gameVersionOrdinal(gameVersion));
    }

    /**
     * Structure + erosion/temp/weirdness. Cont / ladders / density / aquifer stay in Java.
     * @return hut block [x,z] if passed, else null
     */
    public static int[] climateRegion(long seed, int regX, int regZ,
                                      GameVersion gameVersion, WorldPresetMode worldPreset) {
        ensureAvailable();
        return nativeClimateRegion(seed, regX, regZ,
                gameVersionOrdinal(gameVersion), worldPresetOrdinal(worldPreset));
    }

    /** Climate-3 checks for a known hut position. */
    public static boolean climateFilter(long seed, int hutX, int hutZ,
                                        GameVersion gameVersion, WorldPresetMode worldPreset) {
        ensureAvailable();
        return nativeClimateFilter(seed, hutX, hutZ,
                gameVersionOrdinal(gameVersion), worldPresetOrdinal(worldPreset));
    }

    private static void ensureAvailable() {
        if (!available) {
            throw new IllegalStateException(
                    "cubiomes JNI unavailable: " + (loadError != null ? loadError : "unknown"));
        }
    }

    private static native int[] nativeGetHutInRegion(long seed, int regX, int regZ, int gameVersion);

    private static native int[] nativeClimateRegion(long seed, int regX, int regZ,
                                                    int gameVersion, int worldPreset);

    private static native boolean nativeClimateFilter(long seed, int hutX, int hutZ,
                                                      int gameVersion, int worldPreset);
}
