# LowYSwampHut Search Tool

**Version 2.0.0** — the C core and the Java product share this version number.
**Default Minecraft version: 26.2~26.3** (the version selector opens on 26.2~26.3; see the
"Version" option below).

**版本 2.0.0** —— C 内核与 Java 产品同号。
**默认 Minecraft 版本：26.2~26.3**（版本下拉框默认选中 26.2~26.3，详见下方 "版本" 选项）。

A GUI program for searching low Y-coordinate Swamp Huts in Minecraft Java Edition. This program supports versions 1.18 and above.

# 低y女巫小屋搜索工具

一个用于搜索 Minecraft Java 版低 Y 坐标女巫小屋的 GUI 程序。本程序支持 1.18及以上 版本。

## How to Run / 如何运行

**This program requires Java 17 or higher.** Please make sure you have Java installed.

**此程序需要 Java 17 或更高版本。** 请确保您已安装 Java。

> ✅ **The runnable jar is built into `dist\`** by double-clicking `build.bat`
> (or `LowYSwampHut-main\build-dist.ps1`). The script builds `lysh.dll` then
> packs `LowYSwampHut-<AppVersion>.jar` (e.g. `LowYSwampHut-2.0.0.jar`).
> After that, `java -jar dist\LowYSwampHut-2.0.0.jar`
> (or double-clicking `dist\run.bat`) opens the GUI. See **Command Line / 命令行** below.
>
> ✅ **可直接运行的 jar：双击仓库根目录 `build.bat`**（或跑
> `LowYSwampHut-main\build-dist.ps1`）生成到 `dist\`。脚本会先编译 `lysh.dll`，
> 再打出 `LowYSwampHut-<AppVersion>.jar`（例如 `LowYSwampHut-2.0.0.jar`）。
> 之后 `java -jar dist\LowYSwampHut-2.0.0.jar`（或双击 `dist\run.bat`）即可打开 GUI。
> 详见下方 **Command Line / 命令行**。
>
> The product is **dependency-free and self-contained**: every terrain / structure judgement is made
> by the C core, which travels **inside** the jar (`/native/lysh.dll`) and is released to a per-user
> cache on first run. A loose `lysh.dll` next
> to the jar is optional — it only overrides the embedded one.
>
> 本产品**不依赖任何第三方 jar，且是自足的单文件**：所有地形 / 结构判定都由 C 内核完成，
> 内核**内嵌在 jar 里**（`/native/lysh.dll`），首次运行时释放到每用户缓存目录。jar 旁边的 `lysh.dll` 是可选的 —— 它只用于
> 覆盖内嵌的那一份。

You can right-click the jar file and select "Java(TM) Platform SE binary" as the opening method.

您可以右键单击 jar 文件，并选择 "Java(TM) Platform SE binary" 作为打开方式。

You can also use cmd to run: Open cmd.exe (you can search for it in the search bar), then use the following command to run:

您也可以使用 cmd 来运行：打开 cmd.exe（您可以在搜索栏中搜索它），然后使用以下命令运行：

Type "java -jar", then type a **space**, drag the jar file into the window and press Enter.

输入 "java -jar"，然后输入**空格**，将 jar 文件拖到窗口中并按回车。

Type "java -Xms2048m -Xmx4096m -jar", then type a **space**, drag the jar file into the window and press Enter. This is for allocating memory.

输入 "java -Xms2048m -Xmx4096m -jar"，然后输入**空格**，将 jar 文件拖到窗口中并按回车。这是用于分配内存的。

You can change -Xms2048m -Xmx4096m to the memory size you want. It is recommended to allocate at least 4GB of memory for this program.

您可以将 -Xms2048m -Xmx4096m 更改为您想要的内存大小。建议为此程序分配至少 4GB 的内存。

**Startup is immediate.** All terrain and structure judgement is done by the C core (`lysh.dll`), which loads in milliseconds.

**启动是即时的。** 所有地形 / 结构判定由 C 内核（`lysh.dll`）完成，毫秒级加载。

## How to Use? / 如何使用？

The program has two tabs: **Single Seed Search** and **Search from Seed List**.

程序有两个标签页：**单种子搜索**和**从种子列表搜索**。

### Single Seed Search / 单种子搜索

Click the **Start Search** button to start searching, click the **Pause** button to pause, and click the **Stop** button to stop searching.

点击**开始搜索**按钮启动搜索，点击**暂停**按钮暂停，点击**停止**按钮停止搜索。

The top-left part is the **Parameter Settings** area, which includes:

左上角为**参数设置**区域，包含：

**Seed**: The seed value to search. Must be an integer within the range of -2^63 to 2^63-1.

**种子**：要搜索的种子值。必须是 -2^63 到 2^63-1 范围内的整数。

**Thread Count**: The number of threads to use. Valid range is 1 to your computer's maximum thread count.

**线程数**：使用的线程数量。有效范围为 1 至您计算机的最大线程数。

**Swamp Hut Height Filter**: The maximum Y-coordinate for the Swamp Hut. The higher the value, the slower the search. Options: 0, -10, -20, -30, -40 (default: -40).

**筛选女巫小屋高度**：女巫小屋的最大 Y 坐标。值越高，搜索越慢。选项：0、-10、-20、-30、-40（默认：-40）。

**Version**: The Minecraft version to use. Options: 26.2~26.3, 1.19.x~26.1, 1.18.x (default: 26.2~26.3).

**版本**：要使用的 Minecraft 版本。选项：26.2~26.3、1.19.x~26.1、1.18.x（默认：26.2~26.3）。

**MinX/MaxX/MinZ/MaxZ/Square side length (x512)**: The coordinate range to search for Swamp Huts. The default values are the world boundaries (-58594 to 58593). Valid range is -30,000,000 to 30,000,000. Square side length option can search specified length of square centered at (0,0) (default 117188).

**MinX/MaxX/MinZ/MaxZ/正方形区域边长 (x512)**：搜索女巫小屋的坐标范围。默认值为世界边界（-58594 到 58593）。有效范围为 -30,000,000 到 30,000,000。正方形区域边长可以直接输入以原点为中心指定长度的区域进行搜索（世界边界为117188）。

**Precise Generation Check**: **this control has been removed.** Candidates which cannot generate a swamp hut never reach the results: there is no `×` / "x" "cannot generate" marker any more.

**精确检查生成情况**：**这个控件已删除。** 无法实际生成女巫小屋的候选根本不会进入结果：`×` / "x"（"无法生成"）标记已不存在。

The top-right part shows the **Search Results**. The results are displayed in the format `/tp x y z`, where y is the exact average footprint height of the Swamp Hut (`avg_y`, from the C core).

右上角部分显示**检查结果**。结果以 `/tp x y z` 格式显示，其中 y 是女巫小屋 footprint 的精确平均高度（C 内核给出的 `avg_y`）。

At the bottom, you can see the **Progress Bar**. It is a **single bar covering the whole search** — Phase 1 and phase 2 run in one pass. It shows the merged progress, elapsed time, and remaining time.

在底部，您可以看到**进度条**。它是**一条覆盖整个搜索的进度条** —— 阶段 1 与阶段 2 是一趟跑完的。它显示合并后的进度、已过时间和剩余时间。

**Export**: Export all search results to a text file. **导出**：将所有搜索结果导出到文本文件。

**Sort**: Sort the results by Y-coordinate from low to high. (The old "cannot generate" marking is gone, so every line sorts as a valid result.)

**排序**：按 Y 坐标从低到高排序结果。（旧的"无法生成"标记已不存在，所有结果行都按有效结果排序。）

**Reset Search Area to World Boundary**: Reset the coordinate range to the default world boundary values.

**重置搜索区域为世界边界**：将坐标范围重置为默认的世界边界值。

### Search from Seed List / 从种子列表搜索

Click the **Select File** button to choose a seed list file (one seed per line, text file format). Then click the **Start Search** button to start batch searching.

点击**选择文件**按钮选择种子列表文件（每行一个种子，文本文件格式）。然后点击**开始搜索**按钮开始批量搜索。

The parameter settings are similar to Single Seed Search, but with different default coordinate ranges: **MinX/MaxX/MinZ/MaxZ (x512)** default to **-128 to 128**, and you can modify them as needed.

参数设置与单种子搜索类似，但默认坐标范围不同：**MinX/MaxX/MinZ/MaxZ (x512)** 默认值为 **-128 到 128**，您可以根据需要修改。

The search will process each seed in the list sequentially. The progress bar shows the number of completed seeds and the total number of seeds.

搜索将按顺序处理列表中的每个种子。进度条显示已完成的种子数和总种子数。

**Export**: Export all search results (including seeds and coordinates) to a text file. **导出**：将所有搜索结果（包括种子和坐标）导出到文本文件。

**Export Seed List**: Export only the seed list (without `/tp` coordinates) to a text file. This is useful for filtering seeds that have low Y-coordinate Swamp Huts.

**导出种子列表**：仅导出种子列表（不含 `/tp` 坐标）到文本文件。这对于筛选具有低 Y 坐标女巫小屋的种子很有用。

**Sort by Lowest Y**: Sort the results by the lowest Y-coordinate of each seed. **按最低y排序**：按每个种子的最低 Y 坐标排序结果。

**Sort by Distance**: Sort the results by the distance from the origin (0, 0). **按距离排序**：按距离原点 (0, 0) 的距离排序结果。

**Reset Search Area to Default**: Reset the coordinate range to the default values (-128 to 128).

**重置搜索区域为默认值**：将坐标范围重置为默认值（-128 到 128）。

### Command Line / 命令行

The same JAR supports both the GUI and command-line search. **Launching without arguments opens the GUI.** To search from the command line, pass `--seed` and optional parameters.

同一个 JAR 同时支持图形界面与命令行搜索。**不带参数启动时打开 GUI。** 命令行搜索需传入 `--seed` 及可选参数。

One way to build (offline, no Gradle, no network) — double-click `build.bat`, or:

只有一种构建方式（离线，无需 Gradle 与网络）—— 双击仓库根目录的 `build.bat`，或：

```powershell
# Builds lysh.dll (CMake + gcc) then the jar. Use -SkipNative to reuse an existing DLL.
powershell -NoProfile -ExecutionPolicy Bypass -File LowYSwampHut-main\build-dist.ps1
# Plain javac --release 17 + jar, offline. The product needs no third-party jar today; if a
# future version does, add -classpath at the marked spot in build-dist.ps1 -- nothing blocks it.
```

It writes `dist\` / 它会生成 `dist\`：

| File / 文件 | What it is / 说明 |
|---|---|
| `LowYSwampHut-<VERSION>.jar` | product classes + resources **+ the embedded C core**; `VERSION` = `project.AppVersion.VERSION`（含内嵌 C 内核，单文件即可运行） |
| `lysh.dll` | optional: overrides the embedded core / 可选：覆盖内嵌内核 |
| `run.bat` | double-click launcher / 双击启动 |
| `README.txt` | layout and requirements / 布局与依赖说明 |

`project.NativePhase1` looks for the library in this order: `-Dlowyswamphut.nativeLib`,
`LYSH_NATIVE_LIB`, `<cwd>\lysh-c\build\cmake\lysh.dll`, `<cwd>\build\cmake\lysh.dll`,
`<jar folder>\lysh.dll`, `<jar folder>\native\lysh.dll`, **the `lysh.dll` embedded in the jar**
(released to `%LOCALAPPDATA%\LowYSwampHut\native\<sha256-16>\`), then `System.loadLibrary("lysh")`.
That embedded fallback is why one jar runs anywhere with no environment variables — but note
that **the `<cwd>` entries win over everything else**, so when testing a specific build always pin
`-Dlowyswamphut.nativeLib=<absolute path>`.

`project.NativePhase1` 的查找顺序：`-Dlowyswamphut.nativeLib`、`LYSH_NATIVE_LIB`、
`<工作目录>\lysh-c\build\cmake\lysh.dll`、`<工作目录>\build\cmake\lysh.dll`、
`<jar 所在目录>\lysh.dll`、`<jar 所在目录>\native\lysh.dll`、**jar 内嵌的 `/native/lysh.dll`**
（释放到 `%LOCALAPPDATA%\LowYSwampHut\native\<sha256-16>\`），最后是 `System.loadLibrary("lysh")`。
内嵌那一档就是"单文件 jar 拷到哪都能跑、不需要任何环境变量"的原因 —— 但要注意**`<工作目录>` 那两条
赢过其它所有候选**，所以测某个特定构建时一定要用 `-Dlowyswamphut.nativeLib=<绝对路径>` 钉死。

**Search range note:** `--min-x/--max-x/--min-z/--max-z` are **region coordinates**
(32×32 chunks each), not block coordinates — the C core scans that region grid
. `--square-side` is in the same unit.

**搜索范围单位**：`--min-x/--max-x/--min-z/--max-z` 是**区域坐标**（每个区域 32×32 区块），
不是方块坐标 —— C 内核直接按这个区域网格扫描。

**Basic usage / 基本用法**

```bash
java -jar dist\LowYSwampHut-2.0.0.jar --seed [你的种子]
```

**Common options / 常用参数**

| Option                      | Description / 说明                      | Default / 默认值                |
| --------------------------- | --------------------------------------- | ------------------------------- |
| `--seed`, `-s`              | Single seed / 单种子搜索                | One of `--seed` or `--seeds-file` / 与种子列表二选一 |
| `--seeds-file`, `-f`        | Seed list file / 种子列表文件（每行一个） | One of `--seed` or `--seeds-file` / 与单种子二选一 |
| `--max-y`                   | Max Swamp Hut Y filter / 女巫小屋最大 Y | `-40`                           |
| `--threads`                 | Thread count / 线程数                   | CPU core count / CPU 核心数     |
| `--version`                 | Minecraft version / 版本                | `26.2~26.3`                     |
| `--preset`                  | World preset / 世界类型                 | `normal`                        |
| `--lang zh\|en`             | Language override / 覆盖语言            | System default / 跟随系统（与 GUI 相同规则） |
| `--output`, `-o`            | Full results file / 完整结果文件        | `result.txt`                    |
| `--export-seeds`            | Export hit seeds only / 仅导出命中种子  | Off / 不导出                    |
| `--no-progress`             | Disable progress output / 关闭进度输出  | progress enabled / 默认显示进度 |

**Version values / 版本可选值:** `26.2~26.3`, `1.19.x~26.1`, `1.18.x`
**Preset values / 世界类型可选值:** `normal` (普通世界), `large-biomes` (巨型生物群系), `single-biome` (单生物群系(沼泽))

**Search area / 搜索范围** — if you do not specify any range options, **Single seed / 单种子** uses the
full world boundary (`-58594 ~ 58593`) and **Seed list / 多种子** uses `-128 ~ 128`, the same defaults
as the corresponding GUI tabs. 若不指定范围参数，单种子用世界边界、多种子用 `-128 ~ 128`，与 GUI 两个
标签页的默认值一致。

| Option                                     | Description / 说明                                           |
| ------------------------------------------ | ------------------------------------------------------------ |
| `--min-x`, `--max-x`, `--min-z`, `--max-z` | Custom coordinate bounds / 自定义坐标范围                    |
| `--square-side`                            | Square side length centered at (0, 0); overrides min/max / 以 (0,0) 为中心的正方形边长，会覆盖 min/max |

**Examples / 示例**

```bash
java -jar dist\LowYSwampHut-2.0.0.jar --seed 123456789                       # full world / 整个世界
java -jar dist\LowYSwampHut-2.0.0.jar --seed 123456789 --square-side 1024 --max-y -40   # 1024×1024 at origin
java -jar dist\LowYSwampHut-2.0.0.jar --seed 123456789 --min-x -128 --max-x 128 --min-z -128 --max-z 128 --threads 8 -o result.txt
java -jar dist\LowYSwampHut-2.0.0.jar --seeds-file seeds.txt -o result.txt --export-seeds hits.txt
```

CLI language follows the same rule as the GUI: Simplified/Traditional Chinese system locales use Chinese, otherwise English. Override with `--lang zh` or `--lang en`. The detected language is printed at startup.

命令行语言规则与 GUI 相同：系统为中文（简体/香港/台湾）时用中文，否则英文。可用 `--lang zh` 或 `--lang en` 覆盖。启动时会打印检测到的默认语言。

During CLI search, progress is printed to the terminal (single merged progress percentage, processed/total, elapsed time, remaining time, result count) — there is no stage label, because phase 1 and phase 2 are computed in one pass. Show all options with `--help`.

命令行搜索时，终端会输出进度（单一合并的进度百分比、已完成/总量、已过时间、剩余时间、结果数）—— 没有阶段标签，因为阶段 1 与阶段 2 是一趟跑完的。用 `--help` 查看全部参数。

## Features / 功能特点

- **Multi-threaded search** / **多线程搜索**：支持多线程并行搜索以提高效率
- **Pause/Resume** / **暂停/恢复**：支持暂停和恢复搜索过程
- **Progress tracking** / **进度跟踪**：实时显示搜索进度、已过时间和预计剩余时间
- **Result sorting** / **结果排序**：按 Y 坐标或距离排序结果
- **Export functionality** / **导出功能**：导出搜索结果或种子列表
- **Version support** / **版本支持**：支持多个 Minecraft 版本（1.18.x、1.19.x~26.1、26.2~26.3）
- **Batch processing** / **批量处理**：从列表文件处理多个种子

## Libraries mainly used in this program / 此程序主要使用的库

- The **C core** `lysh.dll` (sources in `lysh-c/`) — performs all phase 1 / phase 2 computation: climate, cave ladder, continentalness, exact footprint average height, aquifer flood judgement, the **real biome gate**, and the **cave/canyon carving layer** (the hut Y is the post-carve surface). Its full technical documentation is in [`lysh-c/README.md`](lysh-c/README.md).
- [SeedFinding](https://github.com/hube12/SeedFinding), [SeedChecker](https://github.com/jellejurre/seed-checker) and [Noise Sampler](https://github.com/KalleStruik/noise-sampler) were the libraries of the deleted Java fallback path; their algorithms now live inside the C core. No Mojang or SeedChecker jar is shipped or required any more.
- The swamp-hut biome gate needs Minecraft's own multi-noise biome lookup. Its per-version **R-tree parameter data** is vendored from [cubiomes](https://github.com/Cubitect/cubiomes) (MIT) into `lysh-c/src/biome_tree_{18,215,262}.h`, with provenance in each file.

- **C 内核 `lysh.dll`**（源码在 `lysh-c/`）—— 承担全部阶段 1 / 阶段 2 计算：气候、洞穴梯子、大陆性、精确 footprint 平均高度、含水层判定、**真实群系门**，以及**洞穴 / 峡谷雕刻层**（小屋 Y 取的是挖过之后的表面）。完整的技术文档见 [`lysh-c/README.md`](lysh-c/README.md)。
- [SeedFinding](https://github.com/hube12/SeedFinding)、[SeedChecker](https://github.com/jellejurre/seed-checker)、[Noise Sampler](https://github.com/KalleStruik/noise-sampler) 属于已删除的 Java 回退路径；其算法已进入 C 内核。现在既不打包、也不需要任何 Mojang / SeedChecker jar。
- 群系门需要 Minecraft 自己的多噪声群系查找：它**按版本**的 R 树参数表 vendored 自 [cubiomes](https://github.com/Cubitect/cubiomes)（MIT），位于 `lysh-c/src/biome_tree_{18,215,262}.h`，出处写在每个文件头部。

## Credits / 致谢

- [M33Galaxy](https://github.com/M33Galaxy) - Author of the core algorithm / 核心程序开发
- [SunnySlopes](https://github.com/SunnySlopes) - Inspiration and UI development / 灵感和UI开发
- [KalleStruik](https://github.com/KalleStruik) - Noise Sampler library / Noise Sampler 库（仅供参考，非依赖）
- Font / 字体：江城黑体

- [M33三角座星系](https://github.com/M33Galaxy) - 核心程序开发
- [SunnySlopes](https://github.com/SunnySlopes) - 灵感和UI开发
- [KalleStruik](https://github.com/KalleStruik) - Noise Sampler 库（仅供参考，非依赖）
- 字体：江城黑体

## Notes / 注意事项

- The search runs phase 1 + phase 2 **in one pass** inside the C core (`lysh.dll`); results are emitted as each Z-band completes, which is why hits can appear before the single progress bar reaches 100%.
- The reported Y is the exact average footprint height (`avg_y`) from the C core — which equals to swamp huts' true Y values.
- The jar contains no Mojang / SeedChecker code, and it is **self-contained**: `java -jar dist\LowYSwampHut-2.0.0.jar` is enough on its own — the C core is embedded and released to the per-user cache on first run.
- The "Precise Generation Check" checkbox and the `--check-gen` flag have been **removed** . There is no `×` / "x" "cannot generate" marker any more — it is structurally impossible.
- The C core is required at run time but ships **inside** the jar, so there is nothing to install. A loose `lysh.dll` (or `-Dlowyswamphut.nativeLib` / `LYSH_NATIVE_LIB`) overrides the embedded one. If no core can be loaded, the GUI still opens but reports that the native core is unavailable.
- For large searching area, the search may take a long time. It is recommended to use appropriate thread counts based on your computer's performance.
- When searching from a seed list, it is not recommended to load a list with more than 10 million seeds.

- 搜索在 C 内核（`lysh.dll`）里把阶段 1 + 阶段 2 **一趟跑完**；结果在每个 Z 带完成时输出，这就是为什么进度条到 100% 之前就可能出现命中。
- 输出的 Y 是 C 内核给出的精确 footprint 平均高度（`avg_y`）—— 等于女巫小屋的真实Y值。
- jar 内不含任何 Mojang / SeedChecker 代码，且**自足**：`java -jar dist\LowYSwampHut-2.0.0.jar` 单独就能跑 —— C 内核内嵌在 jar 里，首次运行时释放到每用户缓存目录。
- "精确检查生成情况"控件与 `--check-gen` 开关已**删除**；`×` / "x"（"无法生成"）标记也已彻底不存在 —— 它在结构上已不可能产生。
- C 内核是运行时必需的，但它**内嵌在 jar 里**，无需额外安装。jar 旁边的 `lysh.dll`（或 `-Dlowyswamphut.nativeLib` / `LYSH_NATIVE_LIB`）会覆盖内嵌的那一份。一份都加载不到时 GUI 仍能打开，但会提示原生内核不可用。
- 对于大范围的坐标搜索，可能需要较长时间。建议根据您的计算机性能使用适当的线程数。
- 从种子列表搜索时，不建议加载超过 1000 万种子的列表。

---

**Every seed has a dream of a low Y Swamp Hut.**
**每一个种子都有一个低y女巫小屋的梦想。**
