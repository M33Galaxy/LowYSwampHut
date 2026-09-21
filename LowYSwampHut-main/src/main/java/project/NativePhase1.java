package project;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * C 内核（{@code lysh.dll}）的加载器 + 版本探针。
 *
 * <p>这一层**不含任何判定逻辑**，现在也不再暴露阶段 1 的扫描入口：产品只走
 * {@link NativePhase2#gradeScanNative}（阶段 1 + 阶段 2 一趟跑完），
 * 历史上那个阶段 1-only 的 `scanNative` 没有任何调用方，已删除。
 * 本类剩下的唯一 native 方法是 {@code coreVersion()}，用来确认库已加载以及版本。
 *
 * <p>加载顺序（第一个成功者胜出）：
 * <ol>
 *   <li>系统属性 {@code -Dlowyswamphut.nativeLib=<path to lysh.dll>}</li>
 *   <li>环境变量 {@code LYSH_NATIVE_LIB}</li>
 *   <li>工作目录下的 {@code lysh-c/build/cmake/lysh.dll}</li>
 *   <li>{@code build/cmake/lysh.dll}（以 lysh-c 为工作目录时）</li>
 *   <li>jar / classes 目录旁边及其 {@code native/} 子目录</li>
 *   <li>jar **内嵌**的 {@code /native/lysh.dll}：释放到每用户缓存目录后再 {@code System.load}</li>
 *   <li>{@code System.loadLibrary("lysh")}（即 {@code -Djava.library.path=...}）</li>
 * </ol>
 *
 * <p>因为有了内嵌那一档，**产品就是一个自足的单文件 jar**：把 jar 拷到任何目录都能跑，
 * 旁边没有 {@code lysh.dll} 也行。释放路径按资源内容的 SHA-256 分目录，所以不同版本
 * 永不互相覆盖、同一个版本也不会重复释放。旁边的 {@code lysh.dll} 仍然优先——那是
 * 留给"手工换内核 / 修一份坏掉的库"的显式覆盖位。
 *
 * <p>⚠️ 加载失败**没有 Java 回退路径**：产品已经被裁成"只走原生"，
 * 所以这里**绝不抛异常**、只记录原因，由调用方（{@code CmdLineRunner} / GUI 的
 * {@code main}）判定"缺库 = 不能搜索"并明确报错。{@link #describe()} 输出的就是那句话。
 *
 * <p>阶段 2 的桥（{@link NativePhase2}）**复用本类的加载结果**：同一份 {@code lysh.dll}
 * 里同时导出两个 Java 类的 native 方法，所以 {@link #loadLibrary()} 是包级可见的，
 * 供 {@code NativePhase2} 调用，避免两处各抄一份查找顺序。
 */
public final class NativePhase1 {

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("lowyswamphut.nativePhase1", "true"));

    private static final boolean AVAILABLE;
    private static final String STATUS;
    private static final String VERSION;

    static {
        boolean ok = false;
        String status;
        String version = null;
        if (!ENABLED) {
            status = "disabled by -Dlowyswamphut.nativePhase1=false";
        } else {
            String loaded = loadLibrary();
            if (loaded != null) {
                ok = true;
                status = "loaded from " + loaded;
                try { version = coreVersion(); } catch (Throwable t) { version = null; }
            } else {
                status = "not found; tried: " + String.join(" | ", loadAttempts());
            }
        }
        AVAILABLE = ok;
        STATUS = status;
        VERSION = version;
    }

    private NativePhase1() {
    }

    /**
     * 按既定顺序加载 {@code lysh.dll}，返回实际加载的路径（都失败则返回 {@code null}）。
     *
     * <p>包级可见：{@link NativePhase2} 用它来加载**同一份**库。
     * JNI 的 native 方法与类名绑定，同一个库只能被同一个 ClassLoader 加载一次，
     * 所以调用方必须先检查 {@link #isAvailable()}，不要重复加载。
     */
    static synchronized String loadLibrary() {
        for (String cand : candidates()) {
            if (cand == null) continue;
            try {
                System.load(cand);
                return cand;
            } catch (Throwable t) {
                // 继续试下一个
            }
        }
        // jar 内嵌的那一份：产品是单文件 jar 的关键。
        // 注意 System.load 收的是**磁盘路径** —— Windows 的加载器不认 jar 里的条目，
        // 所以必须先释放出来再加载。
        try {
            String extracted = extractBundled();
            if (extracted != null) {
                System.load(extracted);
                return bundledAttempt != null ? bundledAttempt : extracted;
            }
        } catch (Throwable t) {
            bundledAttempt = "releasing the embedded " + libFileName() + " failed: " + t;
        }
        try {
            System.loadLibrary("lysh");
            return "loadLibrary(\"lysh\")";
        } catch (Throwable t) {
            return null;
        }
    }

    /** 内嵌那一档的结果，只在全部失败时写进状态串，便于判断是"没内嵌"还是"释放失败"。 */
    private static volatile String bundledAttempt;

    /** 供 {@link #loadLibrary()} 失败时把 "到底找过哪些路径" 写进状态串。 */
    static List<String> loadAttempts() {
        List<String> tried = new ArrayList<>(candidates());
        tried.add("the " + libFileName() + " embedded in the jar (released to the per-user cache)");
        if (bundledAttempt != null) tried.add(bundledAttempt);
        tried.add("loadLibrary(\"lysh\")");
        return tried;
    }

    private static List<String> candidates() {
        List<String> out = new ArrayList<>();
        String prop = System.getProperty("lowyswamphut.nativeLib");
        if (prop != null && !prop.isEmpty()) out.add(prop);
        String env = System.getenv("LYSH_NATIVE_LIB");
        if (env != null && !env.isEmpty()) out.add(env);

        String lib = libFileName();
        out.add(Paths.get("lysh-c", "build", "cmake", lib).toAbsolutePath().toString());
        out.add(Paths.get("build", "cmake", lib).toAbsolutePath().toString());

        // jar / classes 目录旁边
        try {
            Path self = Paths.get(NativePhase1.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            if (dir != null) {
                out.add(dir.resolve(lib).toString());
                out.add(dir.resolve("native").resolve(lib).toString());
            }
        } catch (Throwable ignored) {
            // 拿不到位置就算了
        }
        return out;
    }

    /**
     * 把 jar 内嵌的 {@code /native/<lib>} 释放到每用户缓存目录，返回可交给
     * {@code System.load} 的绝对路径；jar 里没有该资源（或释放不了）则返回 {@code null}。
     *
     * <p>为什么不直接加载：{@code System.load} 要的是**磁盘上的文件**，操作系统加载器
     * 不会去读 jar 条目。所以只能"释放一次、缓存复用"。
     *
     * <p>缓存目录名用资源内容的 SHA-256 前 16 位：
     * <ul>
     *   <li>换内核 ⇒ 换目录，不会用到上一版的旧库；</li>
     *   <li>同一版本第二次启动 ⇒ 命中已存在的文件，直接加载，不重写；</li>
     *   <li>多进程同时首启 ⇒ 都写各自的临时文件，再原子改名，先到者胜，后来的复用。</li>
     * </ul>
     *
     * <p>本方法**绝不抛异常**（与 {@link #loadLibrary()} 同一契约）：任何失败都返回
     * {@code null}，由调用方继续试下一档。
     */
    private static String extractBundled() {
        String lib = libFileName();
        Path root = cacheRoot();
        if (root == null) {
            bundledAttempt = "no writable place to release " + lib
                    + " (tried %LOCALAPPDATA%, ~/.cache, java.io.tmpdir)";
            return null;
        }
        try {
            try (InputStream in = NativePhase1.class.getResourceAsStream("/native/" + lib)) {
                if (in == null) {
                    bundledAttempt = "the jar embeds no /native/" + lib + " resource";
                    return null;
                }

                Path tmp = Files.createTempFile(root, lib + ".", ".part");
                long total = 0L;
                byte[] buf = new byte[1 << 16];
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (java.io.OutputStream out = Files.newOutputStream(tmp)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (n == 0) continue;
                        md.update(buf, 0, n);
                        out.write(buf, 0, n);
                        total += n;
                    }
                }

                Path dir = root.resolve(hex(md.digest()).substring(0, 16));
                Files.createDirectories(dir);
                Path dest = dir.resolve(lib);
                if (Files.isRegularFile(dest) && Files.size(dest) == total) {
                    Files.deleteIfExists(tmp);
                    bundledAttempt = dest + "  (released from inside the jar)";
                    return dest.toString();
                }
                try {
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE,
                               StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable atomicFailed) {
                    // 本卷不支持原子改名，或另一个进程刚放好同名文件。
                    if (!(Files.isRegularFile(dest) && Files.size(dest) == total)) {
                        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.deleteIfExists(tmp);
                }
                bundledAttempt = dest + "  (released from inside the jar)";
                return dest.toString();
            }
        } catch (Throwable t) {
            bundledAttempt = "could not release the embedded " + lib + ": " + t;
            return null;
        }
    }

    /**
     * 找一个**实测可写**的缓存根；都不行则返回 {@code null}。
     *
     * <p>只在"环境变量缺失"时降级是不够的：目录存在但**不可写**（只读挂载、组策略、
     * 受限沙箱）时 {@code createDirectories} 会抛异常，产品就整个跑不起来。所以要
     * 逐个候选**真写一个探针文件**再决定用哪个。
     */
    private static Path cacheRoot() {
        List<Path> roots = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String base = os.contains("win") ? System.getenv("LOCALAPPDATA") : null;
        if (base != null && !base.isEmpty()) {
            roots.add(Paths.get(base, "LowYSwampHut", "native"));
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            roots.add(Paths.get(home, ".cache", "LowYSwampHut", "native"));
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isEmpty()) {
            roots.add(Paths.get(tmp, "lowyswamphut-native"));
        }
        for (Path p : roots) {
            try {
                Files.createDirectories(p);
                Path probe = Files.createTempFile(p, "probe", ".tmp");
                Files.deleteIfExists(probe);
                return p;
            } catch (Throwable ignored) {
                // 换下一个
            }
        }
        return null;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String libFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "lysh.dll";
        if (os.contains("mac")) return "liblysh.dylib";
        return "liblysh.so";
    }

    // ------------------------------------------------------------------
    // native
    // ------------------------------------------------------------------

    private static native String coreVersion();

    // ------------------------------------------------------------------
    // public
    // ------------------------------------------------------------------

    public static boolean isAvailable() { return AVAILABLE; }

    /** 供 CLI / GUI 打印，确认到底走的是哪条路径。 */
    public static String describe() {
        if (!AVAILABLE) return "native phase 1: OFF (" + STATUS + ")";
        return "native phase 1: ON  [" + VERSION + "]  " + STATUS;
    }
}
