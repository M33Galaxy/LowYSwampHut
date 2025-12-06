# LowYSwampHut Search Tool

A GUI program for searching low Y-coordinate Swamp Huts in Minecraft Java Edition. This program supports versions 1.18.2, 1.19.2, 1.20.1, and 1.21.1.

# 低y女巫小屋搜索工具

一个用于搜索 Minecraft Java 版低 Y 坐标女巫小屋的 GUI 程序。本程序支持 1.18.2、1.19.2、1.20.1 和 1.21.1 版本。

## How to Run / 如何运行

**This program requires Java 17 or higher.** Please make sure you have Java installed.

**此程序需要 Java 17 或更高版本。** 请确保您已安装 Java。

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

**You need to wait 10-20 seconds for the program to fully start.** This is because it needs to initialize SeedChecker first, which takes a long time. Please do not perform other operations during this stage.

**您需要等待 10-20 秒才能完全启动这个程序。** 这是因为它需要先初始化 SeedChecker，这会花费较长时间。在此阶段请不要进行其他操作。

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

**Version**: The Minecraft version to use. Options: 1.21.1, 1.20.1, 1.19.2, 1.18.2 (default: 1.21.1).

**版本**：要使用的 Minecraft 版本。选项：1.21.1、1.20.1、1.19.2、1.18.2（默认：1.21.1）。

**MinX/MaxX/MinZ/MaxZ (x512)**: The coordinate range to search for Swamp Huts. The default values are the world boundaries (-58594 to 58593). Valid range is -30,000,000 to 30,000,000.

**MinX/MaxX/MinZ/MaxZ (x512)**：搜索女巫小屋的坐标范围。默认值为世界边界（-58594 到 58593）。有效范围为 -30,000,000 到 30,000,000。

**Precise Generation Check (Slightly Affects Efficiency)**: When enabled, the program will check whether the Swamp Hut can actually generate at each coordinate. Coordinates that cannot generate will be marked with "无法生成" (cannot generate). **Note: Enabling this option will slightly reduce search efficiency, but it helps filter out coordinates that cannot actually generate huts.**

**精确检查生成情况(略微影响效率)**：启用后，程序将检查每个坐标是否能够实际生成女巫小屋。无法生成的坐标将被标记为"无法生成"。**注意：启用此选项会略微降低搜索效率，但有助于筛选出无法实际生成小屋的坐标。**

The top-right part shows the **Search Results**. The results are displayed in the format `/tp x y z`, where y is the Y-coordinate of the Swamp Hut. **Note: The actual hut Y-coordinate may be within ±1 block of the output coordinate. When precise generation check is enabled, the program can indicate whether the hut can actually generate.**

右上角部分显示**检查结果**。结果以 `/tp x y z` 格式显示，其中 y 是女巫小屋的 Y 坐标。**注意：真实小屋 y 值可能会处于输出坐标 ±1 格以内。开启精确搜索后可提示小屋是否能真实生成。**

右上角部分显示**检查结果**。结果以 `/tp x y z` 格式显示，其中 y 是女巫小屋的 Y 坐标。**注意：真实小屋 y 值可能会处于输出坐标 ±1 格以内，且不能保证每个坐标都能实际生成小屋。**

At the bottom, you can see the **Progress Bar**, which shows the search progress, elapsed time, and remaining time.

在底部，您可以看到**进度条**，它显示搜索进度、已过时间和剩余时间。

**Export**: Export all search results to a text file.

**导出**：将所有搜索结果导出到文本文件。

**Sort**: Sort the results by Y-coordinate from low to high. If precise generation check is enabled, coordinates that cannot generate will be sorted to the end.

**排序**：按 Y 坐标从低到高排序结果。如果启用了精确检查生成情况，无法生成的坐标将被排到最后。

**Reset Search Area to World Boundary**: Reset the coordinate range to the default world boundary values.

**重置搜索区域为世界边界**：将坐标范围重置为默认的世界边界值。

### Search from Seed List / 从种子列表搜索

Click the **Select File** button to choose a seed list file (one seed per line, text file format). Then click the **Start Search** button to start batch searching.

点击**选择文件**按钮选择种子列表文件（每行一个种子，文本文件格式）。然后点击**开始搜索**按钮开始批量搜索。

The parameter settings are similar to Single Seed Search, but with different default coordinate ranges:

参数设置与单种子搜索类似，但默认坐标范围不同：

**MinX/MaxX/MinZ/MaxZ (x512)**: Default values are -128 to 128. You can modify them as needed.

**MinX/MaxX/MinZ/MaxZ (x512)**：默认值为 -128 到 128。您可以根据需要修改它们。

The search will process each seed in the list sequentially. The progress bar shows the number of completed seeds and the total number of seeds.

搜索将按顺序处理列表中的每个种子。进度条显示已完成的种子数和总种子数。

**Export**: Export all search results (including seeds and coordinates) to a text file.

**导出**：将所有搜索结果（包括种子和坐标）导出到文本文件。

**Export Seed List**: Export only the seed list (without `/tp` coordinates) to a text file. This is useful for filtering seeds that have low Y-coordinate Swamp Huts.

**导出种子列表**：仅导出种子列表（不含 `/tp` 坐标）到文本文件。这对于筛选具有低 Y 坐标女巫小屋的种子很有用。

**Sort by Lowest Y**: Sort the results by the lowest Y-coordinate of each seed. If precise generation check is enabled, seeds with all huts marked as "cannot generate" will be sorted to the end. Seeds with some generatable huts will be sorted by the lowest Y-coordinate of the generatable huts.

**按最低y排序**：按每个种子的最低 Y 坐标排序结果。如果启用了精确检查生成情况，所有小屋都被标记为"无法生成"的种子将被排到最后。部分小屋可生成的种子将按可生成小屋中的最低 Y 坐标排序。

**Sort by Distance**: Sort the results by the distance from the origin (0, 0). If precise generation check is enabled, seeds with all huts marked as "cannot generate" will be sorted to the end. Seeds with some generatable huts will be sorted by the nearest distance of the generatable huts.

**按距离排序**：按距离原点 (0, 0) 的距离排序结果。如果启用了精确检查生成情况，所有小屋都被标记为"无法生成"的种子将被排到最后。部分小屋可生成的种子将按可生成小屋中的最近距离排序。

**Reset Search Area to Default**: Reset the coordinate range to the default values (-128 to 128).

**重置搜索区域为默认值**：将坐标范围重置为默认值（-128 到 128）。

## Features / 功能特点

- **Multi-threaded search**: Supports multi-threaded parallel search to improve efficiency
- **Pause/Resume**: Supports pausing and resuming the search process
- **Progress tracking**: Real-time display of search progress, elapsed time, and estimated remaining time
- **Result sorting**: Sort results by Y-coordinate or distance
- **Export functionality**: Export search results or seed lists
- **Version support**: Supports multiple Minecraft versions (1.18.2, 1.19.2, 1.20.1, 1.21.1)
- **Batch processing**: Process multiple seeds from a list file

- **多线程搜索**：支持多线程并行搜索以提高效率
- **暂停/恢复**：支持暂停和恢复搜索过程
- **进度跟踪**：实时显示搜索进度、已过时间和预计剩余时间
- **结果排序**：按 Y 坐标或距离排序结果
- **导出功能**：导出搜索结果或种子列表
- **版本支持**：支持多个 Minecraft 版本（1.18.2、1.19.2、1.20.1、1.21.1）
- **批量处理**：从列表文件处理多个种子

## Libraries mainly used in this program / 此程序主要使用的库

- [SeedFinding](https://github.com/hube12/SeedFinding) - For structure generation calculations
- [SeedChecker](https://github.com/jellejurre/seed-checker) - For precise height checking
- [Noise Sampler](https://github.com/KalleStruik/noise-sampler) - For noise calculations

- [SeedFinding](https://github.com/hube12/SeedFinding) - 用于结构生成计算
- [SeedChecker](https://github.com/jellejurre/seed-checker) - 用于精确高度检查
- [Noise Sampler](https://github.com/KalleStruik/noise-sampler) - 用于噪声计算

## Credits / 致谢

- [M33Galaxy](https://github.com/M33Galaxy) - Author of the core algorithm
- [SunnySlopes](https://github.com/SunnySlopes) - Inspiration and UI development
- [jellejurre](https://github.com/jellejurre) - SeedChecker library
- [KalleStruik](https://github.com/KalleStruik) - Noise Sampler library
- Font: 江城黑体

- [M33三角座星系](https://github.com/M33Galaxy) - 核心程序开发
- [SunnySlopes](https://github.com/SunnySlopes) - 灵感和UI开发
- [jellejurre](https://github.com/jellejurre) - SeedChecker 库
- [KalleStruik](https://github.com/KalleStruik) - Noise Sampler 库
- 字体：江城黑体

## Notes / 注意事项

- The search results show the theoretical Y-coordinate of the Swamp Hut. The actual Y-coordinate may vary by ±1 block.
- Not every coordinate in the results can guarantee the actual generation of a Swamp Hut in the game. When "Precise Generation Check" is enabled, coordinates that cannot generate will be marked with "无法生成" (cannot generate).
- Enabling "Precise Generation Check" will slightly reduce search efficiency, as it requires additional checks for each coordinate. If you prioritize search speed, you can disable this option.
- For large coordinate ranges, the search may take a long time. It is recommended to use appropriate thread counts based on your computer's performance.
- When searching from a seed list, it is not recommended to load a list with more than 10 million seeds.

- 搜索结果显示的是女巫小屋的理论 Y 坐标。实际 Y 坐标可能会有 ±1 格的偏差。
- 结果中的每个坐标不能保证在游戏中实际生成女巫小屋。当启用"精确检查生成情况"时，无法生成的坐标将被标记为"无法生成"。
- 启用"精确检查生成情况"会略微降低搜索效率，因为它需要对每个坐标进行额外检查。如果您优先考虑搜索速度，可以禁用此选项。
- 对于大范围的坐标搜索，可能需要较长时间。建议根据您的计算机性能使用适当的线程数。
- 从种子列表搜索时，不建议加载超过 1000 万种子的列表。

---

**Every seed has a dream of a low Y Swamp Hut.**
**每一个种子都有一个低y女巫小屋的梦想。**

