# lysh — LowYSwampHut 的 C 内核 技术文档

> **版本：2.0.0**（C 内核与 Java 产品同号；`lysh --help` 的 banner、`NativePhase1/2.coreVersion()` 均报 `lysh 2.0.0`）。
> **默认 Minecraft 版本：26.2**（不写 `--version` 就是 26.2，见 §0.0）。
> **陷阱日志见 §6**（52 条字节码/浮点陷阱；只留结论与规格常数，取证过程已压缩）。

低 Y 女巫小屋搜索的**完整 C 实现**（阶段 1 + 阶段 2）。Java 侧只剩 GUI / CLI 外壳：
**阶段 1 与阶段 2 都经 JNI 调本内核**。C 核心是**完整程序**：`lysh scan` 跑阶段 1（候选筛选）
+ 阶段 2（精确 footprint 高度 + 灌水判定）`lysh hut --seed S --x X --z Z` 评估单个候选；
JNI 暴露 `project.NativePhase2`（+ `NativePhase1` 只提供 `coreVersion()`）。

## 0.0 ⭐⭐ 默认版本 = **26.2**

- **CLI**：`lysh scan` / `lysh hut` 不写 `--version` 时就是 26.2（`main.c` 的 `parse_version`
  只在*不是* 26.2 时才动 `pre_26_2`）。帮助文本已同步。
- **原生层（结构性保证）**：`lysh_phase1_opts` 里那个开关是**反向**的 —— `pre_26_2`，
  **0（= 全零初始化 = “没传”）就是 26.2 语义**。所以任何 `memset(&opts, 0, sizeof(opts))`
  的调用方拿到的都是 26.2，不可能悄悄退回老分支。JNI 把 Java 的 `v262` 反着映射进来
  （`pre_26_2 = v262 ? 0 : 1`）。
- **Java 产品**：`CmdLineRunner.Options.versionName = "26.2"`，GUI 默认选中 26.2。。

## 0. ⛔ 版本权威规则

L1 噪声派生 / L2 `TerrainNoisePoint` / L3 `sampleNoiseColumn` / L4 `base_3d_noise` / L5 含水层 /
L6 高度图的**1.18 路线全部 ⛔ 默认无效**，仅在 `1.18.2` 分支保留；1.21+ 按 JSON 重新参数化
（陷阱 38 见 §6.9）。

## 1. 铁律：以「游戏数据」为唯一权威

**权威来源**：① **1.21+（默认）** = 客户端 jar 的 `data/minecraft/worldgen/*.json`
（`noise_settings` / `density_function` / `noise`）。实测 1.21.1 与 26.1.2 的
`density_function/**`（35 个）与 `noise/**`（60 个）**逐字节相同**，所以“一次实现覆盖
1.21.1 ~ 26.1”有据。② ~~1.18.1 字节码~~ → 降级为**仅 `1.18.2` 分支的历史取证**。
**阶段 1 的判定权威是另一回事** —— 它是**现有 Java 程序自己的 `SearchCoords.check()`**，其中的
洞穴梯子（`Entrance`/`Entrance2`/`Cheese`）与含水层 floodedness 本来就是启发式近似，“把它逐位
复现出来”才是正确目标（与 §0 不冲突：复现的是**这个 Java 程序的行为**）。

## 2. 已验证的层（状态表）

> `通用` = 与 MC 地形版本无关，默认有效；`⛔ 1.18` = 1.18 地形语义，**默认无效**，仅显式选
> `1.18.2` 时保留。⚠️ “验证”列是**历史实测结果**（对拍程序已随 `tools/` 删除）。

| 层 | 内容 | 归属 | 验证（历史） | 状态 |
|---|---|---|---|---|
| 0a | Xoroshiro128++ / deriver / nextInt / nextDouble / MD5 | 通用 | 参考向量逐位对拍 | ✅ **48/48** |
| 0b | Perlin / OctavePerlin / DoublePerlin 求值 | 通用 | Java dump 对拍 | ✅ **100000/100000** |
| 0c | 噪声参数表（name/firstOctave/amplitudes/lacunarity/persistence） | 通用 | 表一致性（含漂移检查） | ✅ 已 dump |
| 1b | 女巫小屋区域放置（spacing/separation/salt + LCG） | 通用 | setRegionSeed / getInRegion / setLargeFeatureSeed | ✅ **6000/6000** |
| 2 | **阶段 1 完整 `check()`**（气候门 + 洞穴梯子 + 大陆性 + floodedness） | 通用（复现现有 Java 程序） | 逐格 × 38 个原始 double **逐位** + 最终判定 | ✅ **594,064 格 / 22.6M 值 / 0 差异** |
| 2b | **阶段 1 全图扫描 CLI + 多线程** | 通用 | 6M 格 A/B 命中集合完全相同 | ✅ 全图 1.373e10 格 / 4,047 幸存 |
| 2c | **JNI 桥**（`lysh.dll` → `project.NativePhase1`） | 通用 | Java/C 命中集合逐个比对 | ✅ 1 线程 1.43× / 8 线程 5.71× |
| 2d | **⭐ 阶段 2 在 C 核心内接通**（CLI `scan` 默认 + `hut` + `lysh_eval_hut` + JNI `NativePhase2`） | 通用 | §2.5 + L4/L5/L6 | ✅ 端到端 **11/11** |
| 1 / 3 / 3a–3d | 每 octave 状态派生 / `sampleNoiseColumn` / 样条树 / `TerrainNoisePoint` / `InterpolatedNoiseSampler` | ⛔ **1.18** | 796/796、20,000 点逐位、40 octave + 20,000 值逐位 | ✅ 但**仅 1.18.2 分支**；规格已作废 |
| 4 | 含水层（**26.1.2** `Aquifer$NoiseBasedAquifer`） | 通用 | `computeFluid` / `computeSubstance` 各 30000 点逐位 | ✅ GREEN |
| 5 | `getBaseHeight(MOTION_BLOCKING_NO_LEAVES)`（26.1.2 `interpolated`） | 通用 | footprint 平均高度逐候选比较 | ✅ **11/11 + 1681/1681**，平均绝对差 0.00 格 |
| 5b | **雕刻层**（`carver.c`：Cave + Canyon，挖进 footprint 会改列顶） | 通用 | 逐列对拍真实生成器 | ✅ 最差列差 0 |
| 6 | 精确生成校验（真游戏 / 无头服务端） | 通用 | — | ⏳ 未实现 |

> **一句话**：默认有效的是 0a/0b/0c/1b/2/2b/2c + 26.1.2 的 L4/L5/L6；所有 `⛔ 1.18` 行只在显式选
> `1.18.2` 时参与。

## 2.5 ⭐ 阶段 2 的正确形态

> **阶段 2 = 对小屋 footprint（63 列）求 `MOTION_BLOCKING_NO_LEAVES` 高度图，取整数平均。**
> 不需要整块地形、不需要洞穴系统、不需要含水层到“逐位”。

1. **原点** = `(hutX, hutZ)`，不做任何偏移（真实 piece 的 `boundingBox.minX()/minZ()` 恒等于它）。
2. **形状** = `7(x)×9(z)` 或 `9(x)×7(z)`，**由朝向轴决定**：`dir.getAxis() == Axis.Z` ⇒ 7×9，
   否则 9×7（两者都是 63 列，但覆盖范围不同，用错形状平均值就偏）。
3. **朝向** = `dir = WorldgenRandom.setLargeFeatureSeed(worldSeed, hutX/16, hutZ/16).nextInt(4)`
   （0=N 1=E 2=S 3=W；`axisZ = (dir==0||dir==2)`）。
4. **高度** = footprint 平均值的 **`trunc(sum/63)`** —— **Minecraft 的整数除法**（§2.5.4）。

**26.1.2 的算法**（`javap` 确认；⚠️ 1.18 的类名在这个版本已经改了，见 §6.8 陷阱 37：
`SwampHutFeature`→`SwampHutStructure`、`SwampHutGenerator`→`SwampHutPiece`、
`ShiftableStructurePiece`→`ScatteredFeaturePiece`、`adjustToAverageHeight`→
`updateAverageGroundHeight`、`createBox`→`makeBoundingBox` —— **算法本身没变**）：

```java
// ScatteredFeaturePiece.updateAverageGroundHeight(accessor, box, 0)：  Y 初值=64，不是最终值
for (z = minZ .. maxZ) for (x = minX .. maxX)              // 闭区间
    if (box.isInside(x, 64, z)) { sum += accessor.getHeightmapPos(MOTION_BLOCKING_NO_LEAVES,(x,64,z)).getY(); n++; }
heightPosition = sum / n;                                  // 整数除法
box.move(0, heightPosition - box.minY(), 0);
```

> 与 1.18 的一处**实质差异**：1.18 走 `getTopPosition`（**不含下半格**），26.1.2 走
> `getHeightmapPos`（**含下半格**）。对高度图类型无影响，但它是“按 1.18 写会差 1 格”的一个来源。

**朝向配方的展开**（别用 `nextFloat()` 启发式）：`setSeed(seed)` → `a = nextLong(); b = nextLong()`
→ `setSeed((chunkX*a) ^ (chunkZ*b) ^ seed)`，只用 `lysh_lcg` 现有原语即可。
⚠️ `nextLong()` 是 `((long)next(32) << 32) + next(32)`，**第二个 `next(32)` 是符号扩展**
（`i2l`），不是零扩展拼接；写成拼接会让约一半的种子差 2^32。该值同时决定朝向与 carver 播种。
端到端核对：11 个已知小屋的朝向与 `avg_y` 全部相符（逐点表见 §4.2）。

### 2.5.4 footprint 平均高度的取整口径（**纠错后**）

`avg_y` 是 **`sum / 63`，整数除法，向零截断**（`src/eval.c`）。这不是“为了对齐谁”而做的选择：
**它就是 Minecraft 的整数除法语义**，也是 26.1.2 oracle `updateAverageGroundHeight` 里那一行
`sum / n` 的字面翻译。Java 每列的 `sum_block` 是**方块 Y**，而 `getBaseHeight(...).getY()`
返回 **方块 Y + 1**，所以 `ceil(sum_block/63 + 1)` 与 C 的 `trunc(sum_base/63)` **描述的是
同一个量**；在**负 Y 段**，`ceil` 与向零截断**恒等** ⇒ **Java 老路径没有 off-by-one bug**。
需要复现 Java 产品 `/tp` 行口径时用 `lysh_hut_grade()` 的 `tp_y` 字段。

### 2.5.5 雕刻层（`src/carver.c`）—— 小屋 Y 必须算在挖之后

`ScatteredFeaturePiece.updateAverageGroundHeight` 读的是 **`LevelAccessor.getHeightmapPos`**
（活的世界），调用点在 `SwampHutPiece.postProcess` = **FEATURES 阶段**，而 carver 在更早的
**CARVERS 阶段**；FEATURES 开头的 `primeHeightmaps` 会从实时方块**重扫**高度图。
⇒ **小屋 Y 是挖过之后的列顶**，只算密度 + 含水层（挖前）会偏高，凡 footprint 有地表洞穴口
的候选都会被算高。`carver.c` 复刻 `NoiseBasedChunkGenerator.applyCarvers`：

- 17×17 origin 区块（硬编码 −8..8，dx 外 dz 内）× 该 origin 群系的 carver 列表
  （`swamp` = `cave` / `cave_extra_underground` / `canyon`，概率 **0.15 / 0.07 / 0.01**）。
- 播种 `setLargeFeatureSeed(worldSeed + carverIndex, origin.x, origin.z)`。
  ⚠️ **`setCarverSeed` 在 26.1.2 不存在，且任何版本都没有 `|1L`**；1.18.2~26.1.2 的这个函数
  逐指令相同 ⇒ **播种与版本无关**。配置值对 1.20+ 逐字段相同；**1.18.2 / 1.19.2 的
  `configured_carver` 本地无法验证**，目前沿用 26.1.2 的配置。
- 一张 `CarvingMask` 跨全部 289 个 origin 共享，位下标 `(x&15)|((z&15)<<4)|((y-minY)<<8)`。
- 挖出的方块：`y <= minGenY+8`（=−56）写岩浆，否则查 `Aquifer.computeSubstance(x,y,z, 0.0)`
  （**密度实参是 0.0**），返回空则**不写**。overworld 不写 `CAVE_AIR`。
- **邻居 origin 的雕刻会写进 target**（夹取到重叠区）——`carveEllipsoid` 没有 `ChunkPos` 参数，
  夹取基准是写入对象。所以 17×17 是**正确性必需**，不是只为 mask 副作用。
- 高度图**不要增量维护**：`Heightmap.update` 只会抬高列。算完雕刻后**重新自上而下扫**即可。
- `src/sin_table.h` 是 `Mth.SIN`（65536 项）的 dump，由 JVM 生成 ⇒ **依 JVM / 平台而异**，
  vanilla 自己那张表同理。改隧道形状的代码时应以本机游戏为准重验。
- 灌水判定与雕刻无关（采样平面在海平面附近，远高于 footprint 地表）⇒ 可先判灌水，
  已灌满的直接拒并**跳过雕刻层**（见 `eval.c` 的 guard，阈值 64）。**Y 门不可用于跳过**：
  雕刻只会**降低**列顶，挖前超门槛的候选挖后可能合格。

## 2.6 ⭐⭐ 真实群系门（`src/biome.c`）

阶段 1 的气候门只是**旧 Java 程序 `SearchCoords.check()` 的近似**（四个气候阈值 + 洞穴梯子），
**不是** MC 的真实群系判定；原来补这一刀的 `findGeneratedHutFloorY` 随 Java 老路径删除后，
就没有任何东西能拒掉“气候阈值全过、但群系不是沼泽”的候选 —— 症状是全图报出**少量假阳性**
（它们的阶段 2 完全正常，缺的是**上游**那一门）。

**做了什么**：实现 MC 真正决定小屋能不能放的那一步 —— `Structure.isValidBiome`（26.1.2 字节码）：

```java
int x = context.chunkPos().getMiddleBlockX();   // chunkX*16 + 8
int z = context.chunkPos().getMiddleBlockZ();
int y = chunkGenerator.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG,
                                              heightAccessor, randomState);   // = getBaseHeight - 1  ← 有 -1
validBiome.test(biomeSource.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y),
                                          QuartPos.fromBlock(z), randomState.sampler()));
```

- **高度**：`lysh_phase2_height` 就是那个 `WORLD_SURFACE_WG`（`NOT_AIR` 谓词，返回 `y+1`），
  经 `lysh_biome_height_fn` 回调传进 `biome.c`，**没有重复实现**。
- **六个气候参数**：`temperature`/`humidity(vegetation)` 是**新增的两个噪声**
  （`minecraft:temperature` / `minecraft:vegetation`，`shifted_noise` xz 0.25 / y 0）；
  `continentalness`/`erosion`/`weirdness` 复用 `lysh_terrain_info_at`（shift 也是同一对）；
  `depth = y_clamped_gradient(-64→320, 1.5→-1.5) + overworld/offset`（`lysh_overworld_offset`）。
  量化口径 `Climate.quantizeCoord(f) = (long)(f * 10000.0f)`（**乘法必须在 float 里做**）。
- **最近邻搜索走 R 树**（`Climate.ParameterList.findValue`），**不是**线性 argmin —— 参数表在原版里
  是**代码常量**（datapack 只写 `{"preset":"minecraft:overworld"}`），所以这里直接读 cubiomes 预先
  序列化好的每版 R 树（MIT；出处见 `src/biome_tree_*.h` 顶部），与 `Climate.RTree.search` 同构 ⇒
  **没有并列歧义**。
- **允许的群系只有一个**：`minecraft:swamp`（**不含 mangrove_swamp**）。
- **版本按表切换**（这一步不能省）：`1.18.x → btree18`、`1.21.5~26.1 → btree215`、
  **26.2（默认）→ btree262**。1.18.2 分支误用新表会把高温沼泽点判成 `mangrove_swamp`，而 1.18.2 的
  phase-1 门**没有温度上界** ⇒ **误杀 1.18.2 合法的小屋**。

**验收**（种子 `-143551518615525778`）：`--version 26.2`（默认）与 `--version 1.18.2`
**都是 11 ACCEPT / 4 REJECT**；4 个假阳性一律 `REJECT reason=biome: chunk-centre surface biome is
not minecraft:swamp`（群系 id 174 = `dripstone_caves`）。`dist\` 产品端到端同样 0/4 + 11/11。

**数据出处（必须知道）**：26.2 的**参数表**取自 **cubiomes 的 `tables/btree262.h`**（26.2 的参数
JSON 只有 `{"preset":"minecraft:overworld"}`，真正的表在代码里）；**C 的采样链**（六参数 + 量化）
是对着**真实 26.1.2 服务端 jar** 的 oracle 逐点验证的（探针在 `_archive/probe_20260921.zip` 里）。
⇒ **群系表是 26.2 的，采样链的 oracle 是 26.1.2 的**。换版本只需替换 `src/biome_tree_*.h` 并在
`biome.c` 里挂上。

## 3. 目录

```
lysh-c/  CMakeLists.txt → 三个目标：lysh_core / lysh / lysh_jni（**无测试**）
  src/  rng.c/.h(LCG+Xoroshiro+MD5) noise.c/.h noise_table.h terrain_table.h mcmath.h
        climate.c/.h caves.c/.h phase1.c/.h(完整 check + 38 值探针) structure.c/.h search.c/.h
        biome.c/.h(⭐ 真实群系门 = Structure.isValidBiome) biome_tree_18.h/_215.h/_262.h
                   （cubiomes 序列化的每版 R 树，MIT，出处见文件头）
        eval.c/.h(⭐ 产品入口：朝向 + footprint 精确平均高度 + 灌水判定 + 门槛)
        jni_bridge.c main.c(CLI) aquifer.c density.c column_top.c interp_noise.c spline.c/.h
        terrain.c/.h phase2.c(interpolated 三线性插值 + 含水层 + 高度)
```

## 4. 构建与运行

### 4.1 C 侧（三个目标，无测试）

```powershell
cmake -S lysh-c -B lysh-c\build\cmake
cmake --build lysh-c\build\cmake
# build\cmake\lysh.exe（CLI = 筛查程序本体）、lysh_core.lib、lysh.dll（JNI 桥，若找到 jni.h）
```

**⭐ 发布用的构建走 gcc，不是 MSVC。** MSVC 生成的 `lysh.exe` 在同一个 9M 格窗口上实测
**32.9 ns/格**，而 gcc（CMakeLists 的 `-O3 -mavx2 -flto -ffp-contract=off`）是 **22.6 ns/格**。
所以 `build\cmake\lysh.exe` / `lysh.dll`（`build-dist.ps1` 与本文命令都引用它们）是
**gcc 构建后拷过来的**：

```powershell
cmake -S lysh-c -B lysh-c\build\mingw -G "MinGW Makefiles" -DCMAKE_BUILD_TYPE=Release
cmake --build lysh-c\build\mingw
Copy-Item lysh-c\build\mingw\lysh.exe,lysh-c\build\mingw\lysh.dll lysh-c\build\cmake\ -Force
```

⚠️ VS 生成器把产物放在 `build\cmake\Release\`，**不会**刷新 `build\cmake\lysh.exe`；
只跑 MSVC 构建就会留下**旧的可执行文件**而你看不出来。改内核后务必确认你测的是哪一份。

**⚠️ 没有 `ctest`**：`tools/` 删除后测试目标与 `add_test` 一并移除，`ctest` / `--target test`
都没有意义。**编译纪律**：`-ffp-contract=off` 是硬性要求；`-O3 -mavx2 -flto` 不改变结果
（已实测），但**任何允许 FP 重结合或 FMA 收缩的选项都会破坏一致性**（MSVC 用默认
`/fp:precise`，绝不可 `/fp:fast`）。`-flto` 的代码生成发生在链接期，所以 `-ffp-contract=off`
必须**同时**出现在编译和链接标志里（`CMakeLists.txt` 已经这样配）。**环境**：gcc 15.2（本仓库
在 MSYS2 UCRT64 上验证）、CMake 3.16+、JDK 22（JNI 目标需要 `JAVA_HOME` 指向 JDK；
找不到 `jni.h` 时自动跳过并打印 `lysh: JNI bridge skipped`，不影响另外两个目标）。

### 4.2 ⭐ 唯一回归网：11 个已知低 Y 小屋

`tools/` 删掉后**只剩三样信号**：① `[engine]` / `[funnel]` 诊断数字不漂；
② **本表的 Y 与 dir**；③ Java 产品端到端的 `[funnel]` 行。改 `src/` 里任何东西之后都要跑：

```powershell
lysh.exe hut --seed -143551518615525778 --x <hutX> --z <hutZ> --version 26.2
```

**Y 与 dir 必须一字不变**（Y 变 = 行为变，dir 变 = 播种变；footprint 形状由 dir 的奇偶决定，
所以**只看 Y 会漏掉 dir 值错而奇偶相同的情形**）。

| # | hutX | hutZ | Y | dir | 形状 | # | hutX | hutZ | Y | dir | 形状 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | -18740992 | -18206128 | **-54** | 1 | 9×7 | 7 | -27133216 | 20324032 | **-46** | 2 | 7×9 |
| 2 | 15008 | -2784 | **-54** | 1 | 9×7 | 8 | 29190240 | -12559360 | **-53** | 2 | 7×9 |
| 3 | 7652592 | -26905808 | **-43** | 3 | 9×7 | 9 | 14535024 | -18059072 | **-47** | 2 | 7×9 |
| 4 | -12547840 | 13669712 | **-43** | 0 | 7×9 | 10 | -13654720 | -21911344 | **-53** | 3 | 9×7 |
| 5 | 8248000 | 17264960 | **-44** | 2 | 7×9 | 11 | -18756416 | 6244480 | **-43** | 3 | 9×7 |
| 6 | 12008512 | -681920 | **-47** | 1 | 9×7 | | | | | | |

这个种子同时是**群系门的验收点**：全图扫描必须**无假阳性**（气候门全过但群系不是沼泽的候选
一律 `REJECT reason=biome`）。**没有更重的验证手段了** —— `_archive\verify_tools_20260921.zip`
里的对拍工具已**不能对当前头文件编译**（见 §3），要复用只能自己按现在的接口改。

### 4.3 CLI（`lysh`）

```powershell
lysh.exe selftest <seed>
lysh.exe scan --seed <long> [--regions N | --rx0 A --rx1 B --rz0 C --rz1 D]
              [--threads T] [--max-y M] [--phase2-max-y Y] [--version V] [--preset P]
              [--list] [--quiet] [--repeat N]
lysh.exe hut  --seed <long> --x <hutX> --z <hutZ> [--max-y M] [--phase2-max-y Y]
              [--version V] [--preset P]
# --version  26.2(默认) | 1.21 | 1.20.1 | 1.19.2 | 1.18.2     --preset  NORMAL(默认)|LARGE_BIOMES|SINGLE_BIOME
# --max-y    阶段 1 的 maxHeight，默认 -40；给 -50/-54 时内部一律按 -50
# --phase2-max-y  阶段 2 的最终 Y 门槛（判据 avg_y <= 该值）；默认 = --max-y
# erosion 门**只有一条路**：分层提前退出（近似，§7.1）。预计算失败时自动回退到精确表达式
#   （climate.c 会往 stderr 报一行 "falling back to the exact path"）—— 没有开关，也不要加开关。
```

>
> ⚠️ **`--repeat N` 是故意保留的，别删它**：同一批扫 N 遍、报告 best/worst。ctest 删掉后它是
> 产品里**唯一**剩下的自检（同一份代码跑两遍必须同答案，见 `main.c` 的注释），而且 **§7.1 公布的
> 性能数字就是靠它多遍取最好值**得到的 —— 删了它等于同时删掉自检和那些数字的可复现性。

**`lysh scan` 就是完整流水线**：`lysh_scan_rect`（阶段 1，只收 funnel 统计）→
`lysh_grade_scan`（阶段 1 + 每幸存者 `lysh_eval_hut`，并在单线程里回放明细）。CLI、JNI、GUI
**走同一条 C 代码路径**，没有第二份判定。**单候选**输出形如：

```
> lysh.exe hut --seed <seed> --x <hutX> --z <hutZ>
  orientation : dir=<0-3>  footprint <7x9 或 9x7>
  footprint   : sum(h)=<sum> / 63 -> avg_y = <Y>
  aquifer     : <n>/63 columns flooded at y=62 -> all dry
  verdict     : ACCEPT (avg_y <= max-y) 或 REJECT reason=...
  /tp <hutX> <Y> <hutZ>
```

**`--list` 格式（唯一一种）**：`hit <rx> <rz> <x> <y> <z> <flooded>`；`x z` = 小屋原点方块坐标，
**`y` = footprint 平均高度**（就是 Java 产品 `/tp %d %.0f %d` 里的 Y）；`flooded`：1 = 整片
footprint 在 y=62 被水灌满 —— 这类候选在阶段 2 一律被拒，所以**正常搜索明细里恒为 0**。
⚠️ **格式契约**：旧四元组是 `hit <rx> <rz> <hutX> <hutZ>`（没有 `y`/`flooded`），且 `rx` 的语义
也不同（六列 `rx = hutX/32`，四元组 `rx` 是扫描区域下标 `hutX/512`）；依赖旧格式的脚本必须改，
**没有**“逐字节兼容旧格式”的退路了。

`scan` 的稳定摘要行（`scanned` / 幸存数 / 阶段 2 各计数 / erosion 档位直方图）是**改内核后的
必看项**：数字漂了就说明行为变了。`elapsed` / `throughput` 只是本机读数，抖动 ±5%，不作判据。

### 4.4 从 Java 调 C（JNI 桥）

```powershell
java -Dlowyswamphut.nativeLib=<绝对路径>\lysh.dll -cp ... project.Launcher ...   # 两条阶段都用 C
java -Dlowyswamphut.nativePhase2=false ...   # 只是把原生关掉；**没有** Java 回退，等于"不能搜索"
```

JNI 层（`src/jni_bridge.c`）**没有任何判定逻辑**，只拆参数、拷结果 —— 不可能与被逐位对拍过的
C 核心产生分歧。**加载失败 = 不能搜索**（Java 老路径已删除），CLI / GUI 都会明确报错，
绝不会静默回退或把错误信息当成“命中”写进结果文件。暴露的入口只有
`NativePhase2.gradeScanNative` / `gradeIntsPerHit` / `coreVersion` 与 `NativePhase1.coreVersion`。

### 4.5 C 侧产品入口（`src/eval.h` / `src/eval.c`）

GUI 与 CLI **共用同一个入口**，它不含任何世界生成数学，只做编排：

```c
void lysh_search_ctx_init(lysh_search_ctx *ctx, uint64_t world_seed,
                          const lysh_phase1_opts *opts, int max_height);  /* 每线程一份，别共享 */
void lysh_search_ctx_free(lysh_search_ctx *ctx);                          /* 可重复调用 */
lysh_hut_result lysh_eval_hut(lysh_search_ctx *ctx, int hut_x, int hut_z, int max_y);
int lysh_hut_orientation(uint64_t world_seed, int hut_x, int hut_z);      /* 朝向唯一一份配方 */
void lysh_grade_huts(uint64_t seed, const lysh_phase1_opts *opts, int max_height,
                     const int *hut_xz, int n_candidates, int max_y, lysh_hut_grade *grade);
void lysh_grade_scan(uint64_t seed, const lysh_phase1_opts *opts, int max_height,
                     int rx0, int rx1, int rz0, int rz1, int salt, int threads,
                     int max_y, lysh_hut_grade *grade);   /* grade->hits / hits_cap 由调用方给 */
```

`lysh_hut_result` 的关键字段：`dir` / `axis_z` / `size_x` / `size_z`（朝向：`nextInt(4)`；
`axis_z` ⇒ 7(x)×9(z)，否则 9(x)×7(z)）；**`avg_y`** = footprint 平均高度 =
`sum(63 列 height) / 63`（整数除法，向零截断 = MC 语义）；`sum_h` / `wet_columns` /
`flooded` / `all_dry`（`flooded ⇔ wet_columns == 63`）；`ok` / `reject`
（`ok ⇔ avg_y <= max_y && !flooded`；`reject ∈ {0 OK, 1 avg_y, 2 flood}`）；
`lysh_hut_tp_line(&r)` = 与 Java 产品同口径的一行 `/tp %d %.0f %d`。

`lysh_grade_scan` 内部就是 `lysh_scan_rect(phase2=1, hook=…)` + 每幸存者 `lysh_eval_hut` ——
CLI、JNI、基准**没有第二份实现**。**一个 ctx 不是线程安全的**（含水层网格 / cell 插值格是可变
缓存）：每条线程用自己的 ctx，也**不要**在热循环里反复 `lysh_search_ctx_init`（阶段 2 初始化
约 0.43 ms）。

### 4.6 JNI 接口（`project.NativePhase2`，+ `NativePhase1.coreVersion()`）

C 侧已实现（`src/jni_bridge.c`）；Java 侧的 `NativePhase1.java` / `NativePhase2.java` **已落地
并接进产品**。`NativePhase1` 现在只是一个**加载器 + 版本探针**（它的 `scanNative` / `ScanResult`
与 `NativePhase2` 的 `gradeHutNative` / `hitsCapacity` 都没有调用方，已删除）：

```java
// project.NativePhase1 —— 只剩这一个 native 方法
static native String coreVersion();

// project.NativePhase2 —— 产品唯一入口
static native int    gradeIntsPerHit();                        // = 12
static native String coreVersion();
static native long[] gradeScanNative(long seed, int mc1182, int singleBiome, int v262,
                                     int largeBiomes, int maxHeight, int salt, int threads,
                                     int rx0, int rx1, int rz0, int rz1, int maxY, int[] outHits);

// 用法（GUI 用的就是这两句）：salt 传 0 = 内核默认 swamp-hut salt，threads 传 0 = CPU 核数。
// gradeScanNative 返回 long[20] 扫描头；st[6] = hitsWritten，每条明细 12 个 int。
int[] buf = new int[4096];
long[] st = NativePhase2.gradeScanNative(seed, 1,0,0,0, -50, /*salt*/0, /*threads*/8,
                                         rx0, rx1, rz0, rz1, /*maxY*/ -40, buf);
for (int i = 0, n = (int) st[6]; i < n; i++) { int b = i * 12;
    emit(String.format("/tp %d %.0f %d", buf[b], (double) buf[b+5], buf[b+1])); }
```

**`int[12]` 明细布局**（C 侧 `grade_fill_hit` 与 `NativePhase2.F_*` 一一对应）：
`[0]hutX [1]hutZ [2]rx [3]rz [4]dir [5]avg_y [6]flooded [7]ok [8]wetColumns [9]sizeX [10]sizeZ [11]reject`

**`long[20]` 扫描头**：`0`scanned `1`phase1Accepted `2`evaluated(=幸存数) `3`accepted
`4`rejectedY `5`rejectedFlood `6`hitsWritten `7`capacityNeeded(=accepted×12；数组太小时为 **-1**
且 hitsWritten=0) `8`rx0 `9`rx1 `10`rz0 `11`rz1 `12`maxY `13`maxHeight `14`salt `15`threads
`16`msPhase1 `17`msPhase2(CPU 累计) `18`intsPerHit(=12) `19`floodedHits(正常搜索恒为 0)。

两个方法都是自包含的（内部建/销毁自己的 `lysh_search_ctx`，可并发调用）；反复跑小批次时用
C 侧的 `lysh_grade_huts` 自己持有 ctx 更省（省 ~0.43 ms/次）。
> ⚠️ **改 JNI 后必须手跑一次 Java 侧**：原本 `NativePhase2Smoke`（已随 `tools/` 删除）是
> “JNI 名字 / 签名 / 布局写错”唯一能被抓到的地方，现在只能靠 `project.Launcher` 的 CLI 端到端
> （`[funnel]` 行）确认接线没断。

### 4.7 Java 产品（`LowYSwampHut-main/`）—— 怎么构建、怎么启动

**架构现状：Java Swing GUI 外壳 + C 内核做全部计算。** 阶段 1、2 都由 C 出结果。

```powershell
# 构建（只需 JDK 17+；当前不带第三方依赖，无需 Gradle / 联网）；产物落在 dist\
powershell -NoProfile -ExecutionPolicy Bypass -File LowYSwampHut-main\build-dist.ps1

# 启动 GUI（双击 dist\run.bat 等价）
java -jar dist\LowYSwampHut.jar

# CLI（--min-x/--max-x/--min-z/--max-z 是【区域号】，不是方块坐标）
java -jar dist\LowYSwampHut.jar `
    --seed -143551518615525778 --version 26.2 --max-y -40 `
    --min-x 23451 --max-x 23458 --min-z -1335 --max-z -1328 --no-progress
```

产物在 `dist\`：`LowYSwampHut.jar`（**自足单文件，C 内核内嵌为 `/native/lysh.dll`，首次运行释放到
`%LOCALAPPDATA%\LowYSwampHut\native\<sha256-16>\`**）+ `lysh.dll`（可选，覆盖内嵌内核）+ `run.bat` + `README.txt`。
**`dist\` 就是交付物。** `java -jar` 可以直接用，**不需要 `-cp`**：`build-dist.ps1` 用
`javac --release 17` + `jar` 离线打包（不需要 Gradle / 联网）。当前产品不带任何第三方依赖；将来要加，就在
脚本里标了位置的地方补 `-classpath`，构建不会拦。**交付物没有任何必须同目录的外部文件**：jar 拷到哪都能跑。

> ⚠️ 已知陷阱：`NativePhase1.loadLibrary()` 的候选列表里，**`<cwd>\lysh-c\build\cmake\lysh.dll`
> 会赢过 jar 旁边的 DLL**，所以测性能 / 版本时务必用 `-Dlowyswamphut.nativeLib=<绝对路径>` 钉死
> （见 §7.4 末尾的真实事故）。Gradle（`build.gradle` / `gradlew`）仍在，但**需要联网**拉依赖，
> **不作为默认路径**。

#### 4.7.1 哪条路径、由哪个开关决定

| 系统属性 | 默认 | 作用 |
|---|---|---|
| `lowyswamphut.nativeLib` | — | 直接指定 `lysh.dll` 绝对路径（**推荐**） |
| `lowyswamphut.nativePhase2` | `true` | 设为 `false` 只是**关掉原生**；**没有** Java 回退，结果是“不能搜索” |
| `lowyswamphut.maxSearchAxisSpan` / `maxSearchIterations` / `maxSearchThreads` | 见 `SearchCoords` | 搜索范围/迭代数/线程数上限 |

> ⚠️ **已删除的系统属性（不要再用）**：`lowyswamphut.nativePhase1`、
> `lowyswamphut.densityHeightmap`、`lowyswamphut.densityPrefilter`、`lowyswamphut.phase2CGenCheck`。
> 它们都属于那条已删除的 **Java / SeedChecker 老路径**（`checkHeight` / `checkHeightByDensity` /
> `DensityHeightmap` / `findGeneratedHutFloorY` / 真实生成交叉校验）。那套 Java 代码**一行都没有
> 保留** —— 加载不到内核就是不能搜索（内核内嵌在 jar 里，所以“缺”指加载失败，不是“没放文件”）。

阶段 1 + 阶段 2 的唯一实现是 C：`NativePhase2.gradeScanNative`（→ `lysh_grade_scan` +
`lysh_eval_hut`）。`Y` 的口径统一到 C：`Result.height` 就是 C 的 `avg_y`（= `sum(h)/63`，§2.5.4）。
**诊断输出**（定位问题用，不进 i18n 资源）：

```
[engine] native phase 1: ON  [...] | native phase 2: ON  [...]
[funnel] phase1Candidates=1 nativePhase2Evaluated=1 hits=1
```

`funnel` 那行能一眼区分“阶段 1 没找到候选”和“阶段 2 判掉了”；`nativePhase2Evaluated` 非 0
就说明这个 Y 是 C 阶段 2 算出来的（**没有别的计算者**）。

#### 4.7.2 端到端验收：11 个已知小屋，Y 全部来自 C 阶段 2

`Launcher` 的 CLI 模式不需要显示器。对 **§4.2 表**里 11 个已知小屋各跑一次
（`--min-x/--max-x/--min-z/--max-z` 是**区域号**），期望与实得**全部 11/11 逐字相同**
（约 11 s，11 次独立 JVM 启动；每个 funnel = `1/1/1`）。判据就是 §4.2 表的 Y 与 dir。

> `-Dlowyswamphut.nativePhase2=false` 只会得到 `[engine] native phase 2: OFF (...)` 并**拒绝搜索**
> （“原生内核不可用”），不会有任何结果行；`x` 后缀标记也已彻底不存在。

#### 4.7.3 集成时踩到的两个坑

① **产品的 `--min-x/--max-x/--min-z/--max-z` 是「区域号」不是方块坐标** —— `SearchCoords` 的
循环是 `for (x = minX; x < maxX) swampHut.getInRegion(seed, x, z)`，传进去的就是 region 索引
（`lysh scan --rx0/--rx1` 那套口径）；拿方块坐标当边界会只扫到 1/16 的范围，现象是“明明 C 能找到，
Java 找不到”。② **阶段 1→2 的 density 预筛在 C 阶段 2 下默认关闭** —— 它当初是为“Java 阶段 2
≈1 s/候选”设计的近似过滤器（`mean > maxHeight + 8`）；阶段 2 换成 C 之后它只剩漏检风险、没有
速度收益，所以 `nativePhase2` 生效时自动跳过。

## 6. 陷阱日志

“纯阅读字节码/源码会看错”或“看着显然其实不等价”的地方。**共 52 条，每条一行（+续行）；
只留结论、规格常数与“必须怎么写”**（取证过程与实测差值已压缩掉）。一条都没丢。

**6.1 噪声派生 · 6.2 Java 数学 / float 边界**

1. `pow(2, firstOctave)` 的 boolean 必须是 `true`（`legacy = TRUE`）：走**按名字派生**（每 octave 独立流），**不是**顺序消费 + `SKIP_COUNT`。
2. octave 名字带前缀：`"octave_<idx>"`（如 `"octave_-9"`），**不是** `"-9"`；`javap -c` 看不到，要 `javap -v`。
3. `amplitude = 0.16666666666666666 / (0.1 * (1.0 + 1.0/(span+1.0)))`，`span = maxIdx - minIdx`（**不是** `+1`）；含 `CONTINENTALNESS` 的是 `1.4999999999999998`。
4. Perlin 构造消耗 259 次 RNG（3×nextDouble + 256×nextInt），MC 的 `SKIP_COUNT` 是 **262**。
5. `Math.abs(-0.0)` 是 `+0.0`，`v < 0 ? -v : v` 不是 ⇒ 必须用 `fabs`。
6. `Math.max`/`Math.min` 有 ±0.0 特判：`max`: `if(a!=a)return a; if(a==0&&b==0&&bits(a)==-0.0)return b; return (a>=b)?a:b;`；`min`: `if(a!=a)return a; if(a==0&&b==0&&bits(b)==-0.0)return b; return (a<=b)?a:b;` —— `max` 查 **a** 的符号位、`min` 查 **b** 的。
7. `MathHelper.clamp` 不抛异常也不规范化 NaN：`v<lo?lo : v>hi?hi : v`，NaN 返回 NaN 本身。
8. Java 里 `y * 8`、`x * 2` 是 **int 乘法**（先溢出再拓宽）；`x * 0.75`、`y * 0.6666666666666666` 才是 double 乘法 ⇒ C 端必须显式转换。

**6.3 洞穴梯子 / 阶段 1 · 6.4 密度场 / 含水层**

9. `NoiseColumnSampler.sample(n,x,y,z,s)` 是**除法**：`n.sample(x/s, y/s, z/s)`；`s ∈ {0.75, 1.0, 1.5, 2.0}`。
10. `scaleTunnels` 是**阶梯函数**不是插值：`<-0.5→0.75`、`<0→1.0`、`<0.5→1.5`、否则 `2.0`（NaN 走最后）；`scaleCaves` 另一组：`-0.75/-0.5/0.5/0.75 → 0.5/0.75/1.0/2.0/3.0`。
11. `lerpFromProgress` 是“先采样再映射”：`a + ((v-(-1.0))/(1.0-(-1.0)))*(b-a)`，不是拿坐标 lerp。
12. `ladderFloor = Math.max(-50, Math.min(-40, maxHeight))` —— 内外顺序不能反（`maxHeight = 100` 应得 -40，`-54` 应得 -50）。
13. 梯子 `y<=0` 段有 `if (maxHeight < y)` 守卫：`maxHeight = 100` 时整段不执行，`-50` 时跳过 `y = -50` 那档。
14. **大陆性门在洞穴梯子之后**（`continentalness` 梯子跑完才采），提前它会改 funnel 数字。
15. floodedness 用 `y * 0.67`（不是 `2/3`），坐标 `(hutX+3, y*0.67, hutZ+3)`。
16. `HeightLimitView.create` 的参数顺序是 `(bottomY, height)`，不是 `(height, bottomY)`。
17. `DimensionType.field_35479` 的哨兵是 `-32512`（不是 -54），语义是“所有真实 y 都是空气”。
18. `spreadNoise.sample(x / 16.0, ...)` 是错的，必须 `Math.floorDiv(x, 16)`。
19. `GenerationShapeConfig.minimumBlockY()` 返回的是 **cell 坐标（-8）**，不是方块坐标（-64）——当方块坐标用会让扫描少 56 格、**静默漏掉全部低 Y 小屋**；测试集必须含深列（y < -8）。
19b. 同一类错误在 CLI 上：`--rx0/--rz0` 的“未设置”哨兵必须是 **`INT_MIN`**（`-1` 会把 `--rx0 -58594` 夹成 0）；且 CLI 必须**打印请求范围与实扫格数并自查相等**。

**6.5 密度场 / 地形 · 6.6 `sampleNoiseColumn`**

20. `createTerrainNoisePoint` 的实参顺序**以 MC 字节码为准**：签名是 `createNoisePoint(continentalness, erosion, weirdness)`，而它内部做了换位 `createNoisePoint(f3, f5, f4)` ⇒ 照 noise-sampler 的 `createNoiseInfo` 抄会传反。
21. 噪声的 Java 字段名 ≠ 数据 identifier：`shiftNoise` 在 JSON 里叫 **`minecraft:offset`**、`weirdnessNoise` 叫 **`minecraft:ridge`**。
22. `sampleShiftNoise` 两次调用的实参顺序**不对称**：`(x,0,z)` 与 `(z,x,0)` —— 第二次的“y”是 x；写成 `(z,0,x)` 不报错但静默算错。
23. 样条求值**全程 `float`**（`TerrainShaper`/`Spline` 是单精度）：用 double 会在最后一位偏离并改变 density 符号；数据按 `%.9g` 存 float。
24. 样条树**不要手抄**：反射遍历 `Spline$SplineImpl`（`locations`/`values`/`derivatives`）摊平成“节点数组 + 浮点池 + 子节点下标”（→ `src/terrain_table.h`）。`LocationFunction` → `NoisePoint` 的映射**不要按名字猜**：构造四字段互不相同的 `NoisePoint` 求值一次就知道。
25. 两个 boolean 形参顺序：MC 真实顺序是 `(x, y, z, t, baseNoise, noNoiseCaves, useJagged, blender)` —— **local 7 = noNoiseCaves** 走“跳过噪声洞穴”分支，**local 8 = useJagged** 才管 jagged。规格写反 ⇒ 返回值能到 18.65（正确上界 ~4.3）。**参数顺序必须从字节码确认，不能信伪码。**
26. 含水层用的列顶 Y（`method_38383`）实际传 `(noNoiseCaves=1, useJagged=0)` —— **不含噪声洞穴、也不算 jagged**，别按注释反话写。

**6.7 测试方法论 · 6.8 工具链与版本取证**

27. **负向对照要挑“能到达输出”的那一行**：最终判定是一长串 `&&`，放松早期门几乎不改变布尔结果（`decision bad = 0`）；要把末尾 `return 1` 换成 `return 0` 才看得到红。
28. **绿色测试在你看过它变红之前没有意义**：每加一层对拍都要做一次负向对照。
29. **性能改动也是“实现改动”**：只有重跑逐位对拍（0 差异）才能证明等价。
30. **分支的影响范围要算清楚**：`clampedLerp(g/512,h/512,t)` 在 `t >= 1` 恒返回 `h/512`、`t <= 0` 恒返回 `g/512` ⇒ `skip_lower` 在 `t>=1`、`skip_upper` 在 `t<=0` 时**必然**无影响 —— “改坏了还是绿的”可能是测试无效，也可能是分支数学上无关。
31. **别猜 RNG 配方 —— 把真值抓出来当标签。** 仿射/区域级 `nextInt(4)` 是错的；`setCarverSeed` 这个函数在 26.1.2 **不存在**，也没有任何 `|1L`。**9/11 这种分数最危险**（高到让你以为只差一个边界）。正确配方见 §2.5。
32. **“两种形状”不是“两个方向”，而是“两个轴”**：`axisZ = (dir==0||dir==2)` ⇒ **命中集合呈现结构性配对（`{N,S}`/`{E,W}`）说明参数空间维度选错了。**
33. 负向对照脚本的“恢复”必须 `finally` 且无条件：每个用例**先无条件恢复再改**，`finally` 再恢复，最后用**哈希**对账；否则一次中断污染后续全部基线。
34. PS 5.1 读**无 BOM** 的 `.ps1` 会把中文注释后的行首命令整行吃掉 ⇒ 带中文注释的 UTF-8 脚本**必须带 BOM** 或纯 ASCII（现只剩 `build-dist.ps1`）。
35. seed-checker 的 `FakeSaveProperties` 是本地打过补丁的：补丁 class 在 `_archive/dev_scratch_20260921.zip` 的 `projclasses/`，必须放在 classpath **最前面**。
36. **浮点比对一律比原始位**：`printf("%.17g")` 对 `-0.0` 打印 `-0`，对拍用 `%016llx`。
37. **换版本取证先确认“类还在不在、叫什么”，再谈“逻辑变没变”**：26.1.2 把 1.18 类名全改了（§2.5），按旧名 grep 会一无所获。`javap` 必须用 **JDK 25**（jar 是 class file version 69）。

**6.9 `base_3d_noise` / `interpolated`**

38. 1.18.1 与 1.21.1 的 overworld sampling 不是一回事：1.18.1 是 `xz_scale = 1.0`、**`y_scale = 1.0`**、`xz_factor = 80.0`、`y_factor = 160.0`；1.21.1 的 `final_density` 是 `y_scale = 0.5` ⇒ **照 1.21 的 JSON 抄 1.18 的 L4 会全错**（量级看着正常）。另：`noise-sampler` 1.20.0 把 factor 硬编码 80/160，而 **1.18.1 是读 `getXZFactor()/getYFactor()`**。
39. 26.1.2 的 `BlendedNoise` 必须按新代数写：**`xz_scale = 0.25` / `y_scale = 0.125` / `smear_scale_multiplier = 8.0`**；`(blockX * xzMultiplier) / xzFactor` **求值时**才除，且 **y 的比例参数要乘 `smear_scale_multiplier`**。量纲分析会以为 cell 角点逐位等价（4×0.25=1、0.125×8=1）——**那是错的**：跨版本“看起来等价”的代数必须用 oracle 的一个具体值钉死。
40. `ridges_folded` 的常数在 JSON 里是 **double**：26.1.2 是 `-3.0 * (-0.3333333333333333 + |(-0.6666666666666666 + |ridges|)|)`，**全程 double，最后才 `(float)` 进样条**；1.18.1 的 `getNormalizedWeirdness` 是**全程 float**。两版都保留：`lysh_terrain_set_semantics_261()`。
41. `interpolated` 的“内层函数”**不能用普通 context 直接求值**：`cache_once`/`cache_2d`/`flat_cache` 的状态由 `NoiseChunk` 的 `arrayIndex`/`interpolationCounter` 驱动 ⇒ **唯一可靠口径是 `getInterpolatedDensity()` / `getInterpolatedState()`**；registry 里的 `overworld/sloped_cheese` 等是**未重新播种的模板**，不能当基线。
42. `BlockPos.getY/getZ` 的解包移位正确是 **`(v<<52)>>52` / `(v<<26)>>38`**（写成 `(v<<26)>>52`/`(v<<12)>>38` 会让锚点 y 恒为 0 → 到处判成水）。

**6.10 erosion 分层提前退出 / 阶段 2 接线**

43. **零 amplitude 的槽位也必须推进 `persistence`/`lacunarity`**：`minecraft:erosion` 的 amplitudes 是 `{1,1,0,1,1}`，槽位 **2 没有 Perlin 调用**但循环仍对**每个槽位**做 `lacunarity *= 2.0; persistence /= 2.0;`。正确权重（×persistence，初始 8/15）：`slot0=1, slot1=0.5, slot2=0(跳过), slot3=0.125, slot4=0.0625` ⇒ `amp*persistence` = `0.516129, 0.516129, 0.258065, 0.258065, 0.0645161, 0.0645161, 0.0322581, 0.0322581`。
44. **阈值必须乘 `dn->amplitude` 之后再比**：`erosion` 的 `amplitude = 1.3888888888888888`；拿未归一化的部分和比 0.55，末档就**不再等于**原门 `erosion < 0.55`。
45. **交错累加的顺序不能随便排**：阈值全依赖“累计和单调递增”，顺序一旦不是按贡献降序，前几档可能误触发、假阴性率爆掉。判据：**假阳性必须恒为 0**。
46. **「每线程一个 ctx」≠「每线程一份可写状态」**：并发 `counter++`/`cursor++` 就是数据竞争，症状极隐蔽（**统计 `accepted = 0` 而明细里有超门槛候选**）。正确形态（`src/eval.c`）：worker 只写 `ctx->p2_slot` 的**每线程私有 slot**，扫完**单线程**回放决定并填共享明细 —— **“统计”和“明细”必须由同一次单线程过程产生。**
47. **明细缓冲容量不能按“线程数分段”切**：`hits_cap / nthreads` 在 `cap = 1` 时 `seg = 0` ⇒ **每条明细被静默丢弃**而计数器仍说“通过 1 个” ⇒ 容量与线程数必须彻底解耦。
48. **输出格式的退路已取消 —— 现在只有一种格式**：`--list` 是六元组（含 `y`/`flooded`，契约见 §4.3），`--no-phase2` 的四元组退路已删除 ⇒ 改格式必须一次性说清“旧格式作废”。
49. `ceil(sum/63 + 1)` 与 `sum/63` **描述同一个量**（§2.5.4）：负 Y 段 `ceil` 与截断同值 ⇒ **没有 off-by-one**。**不要**给 C 的 `avg_y` 加 1，也不要改 Java 的取整。

**6.11 列顶 Y：两个把实现引偏的细节**

50. **`quarter_negative` 除的是「负」分支 —— 语义容易写反**：正确的展开是 **`v > 0 ? 4v : v`**（被包在 `mul(4.0, quarter_negative(...))` 里，“四分之一”落在**非正**那侧）。写成 `v > 0 ? v*0.25 : v` 症状是列顶 Y 系统性偏移。
51. **密度函数路径喂进去的必须是「biome 坐标」`block >> 2`**（`BiomeCoords.fromBlock(x)`，地板除 4、丢低 2 位），不是方块坐标；喂方块坐标不报错，只会让整列 density 与列顶 Y 都错。

**6.12 MSVC + 代码页 936 会静默吞掉换行（环境陷阱）**

52. MSVC 默认按系统 ANSI（本机 936/GBK）解码 UTF-8 中文注释，**某些序列会把紧随其后的换行一起吃掉** ⇒ 后面几行代码整段消失、只在远处报“未声明的标识符”，而**同一个文件 gcc 编译完全正常**。**修法**：MSVC 分支必须有 **`/source-charset:utf-8`**（只改 source charset）。“gcc 编得过”不能作为“源码没问题”的证据。

## 7. 性能

阶段 1 原来每格 8 次 Perlin 求值（`erosion` 有 4 个非空 octave × 2 个 sub-sampler）。
这是**算术**，不是语言。

### 7.1 ⭐ erosion 门的「分层提前退出」（**默认路径**）

**动机**：`erosion < 0.55` 一门就淘汰 ~95% 的格子（实测 95.0647%），却要为它付满 8 次 Perlin 求值。
**做法**：把 8 次求值按**贡献从大到小交错**（`0A,0B,1A,1B,…`），每算完一次就比一次下界，低于阈值
立刻返回。**阈值** —— 累计和**先乘
`dn->amplitude`**（erosion = `1.3888888888888888`）再比，与最终值同单位：

| 档 | 检查点 | 累计和 < 此值 → 淘汰 |
|---|---|---|
| 0 | 算完 `0A+0B` | **0.05** |
| 1 | 算完 `0A+0B+1A` | **0.22** |
| 2 | 算完 `0A+0B+1A+1B` | **0.42** |
| 3 | 算完 `…+2A` | **0.45** |
| 4 | 算完 `…+2A+2B` | **0.49** |
| 5 | 算完 `…+3A` | **0.51** |
| 6 | 算完全部 8 次 | **0.55** ← 就是原门，所以“没有提前退出”与原判定等价 |

（A = `sub[0]`，B = `sub[1]`；“0/1/2/3” 是**非空槽位** `0,1,3,4` —— 槽位 2 的 amplitude 是 0、
没有 Perlin 调用，但**仍必须推进 `persistence`/`lacunarity`**，否则权重全错，见 §6.10 陷阱 43。）

**速度**（`--regions 4000000`，`-O3 -mavx2 -ffp-contract=off -flto`，多遍取最好值）：

| 路径 | 单线程 ns/格（M 格/s） | 6 线程 ns/格（M 格/s） | 加速 |
|---|---|---|---|
| 精确表达式（当年 `--exact-erosion` 选的、也是 tier 预计算失败时的自动回退） | 226.8（4.41） | 56.2（17.78） | 1.00× |
| **分层提前退出（现在唯一可选的路径）** | **113.2（8.83）** | **27.3（36.60）** | **2.004× / 2.06×** |

> ⚠️ `--exact-erosion` 开关**已删除**：现在只有分层提前退出这一条路，精确表达式只作为
> `erosion_tier_ready == 0` 的自动兜底。上表“精确”一列因此**不再能从产品里重测**；当年测它的
> `erosion_tier_test.c` 只存在于 `_archive/verify_tools_20260921.zip` 里，且**已不能编译**（见 §3）。

**假阴性率**（`精确门通过 && 近似门淘汰` 的占比）：实测两个口径都是 **1.2e-05 ~ 1.3e-05**。
**假阳性恒为 0**（结构性）：提前退出只会更严，不可能放行一个精确门本该淘汰的格子（`sum` 单调递增
+ 末档阈值 = 0.55）。平均 **3.01 次 Perlin 求值/格**（原路径 8 次）。**⚠️ 两个边界**：① **末尾档
用的是交错累加的和**（原路径是分组求和），两者只差**结合顺序**（1–2 ulp，~1e-16），所以快路径直接
拿它当 erosion 的值、**不做任何对齐** —— 实践中 ~46/4e6 个边界格子会翻转。② **档位表只对“两个
sub-sampler 的非空槽位一一对应”的噪声成立**（erosion 满足：两边都是 `{0,1,3,4}`）；
`lysh_tier_seq_init` 不满足时**返回非 0 且绝不静默降级**（`lysh_climate_init` 往 stderr 报一行，
快路径自动回退到精确路径）。**召回**：两条路径下 11 个已知小屋都必须 **11/11**（§4.2 必跑项）。

**为什么用 C**：真正的收益是工程性的 —— **版本独立**（唯一能支持 1.21 / 26.2 的路径）← **首要
理由**；启动不再需要 10–15 s 的 SeedChecker 初始化；内存不再需要 ~4 GB 堆；多种子模式下每个线程
自己派生噪声、无共享状态。瓶颈是 Perlin perm 查表的**依赖链延迟**：提前退出有效正是因为它**跳过
整次求值**，而不是让单次求值更快 —— 换语言 / 换 SIMD 都拿不到这部分。**已试过并确认无效**：
`-flto`、AVX2 gather 做 perm 查表；**有效**：y=0 特化 `maintainPrecision`、erosion 分层提前退出。

### 7.3 全流水线（阶段 1 + 阶段 2）—— **整节是外推**

阶段 2 单候选（63 列 footprint）：**约 5~8 ms**。其中**雕刻层约 2~3 ms**（含灌水短路时不付），
其余是 63 列 `lysh_phase2_height` + 含水层。`lysh scan` 内 8 线程实测约 7~9 ms/candidate。

> ⚠️ **下表整张都是外推，不是全图实测。** 唯一直接测过的“全图时间”是**精确路径的 20.8 min**
> （6 线程，1.373e10 格，4,047 幸存）。分层提前退出上线后**没有重跑过全图**：阶段 1 是“实测吞吐
> × 全图格数”的折算，阶段 2 是“实测单候选成本 × 幸存数 ÷ 线程数”，并行按 6 线程线性折算（实际
> 达不到线性）⇒ 阶段 1 是**下界**。**结论**：**阶段 1 独占全流水线**，正确量级是 **≈ 6.3 min**。

| 阶段 | 依据 | 时间 |
|---|---|---|
| 阶段 1（全图 1.373e10 格） | 6 线程实测吞吐 36.60 M 格/s ⇒ 1.373e10 / 36.60e6 | ≈ **375 s ≈ 6.25 min** |
| 阶段 2（4,047 幸存，实测幸存数） | 4047 × 7 ms ÷ 6 线程 | ≈ **4.7 s**（占 ~1.2%） |
| **全流水线（按实测幸存数）** | 375 s + 4.7 s | ≈ **6.3 min** |

### 7.4 ⭐ Java 路径的重复阶段 1（**已修**，P2 的根因）

**症状**：同一窗口 Java 产品全图 **948 s**，C CLI 的 `elapsed` 却是 29.2 ns/格（外推 ≈ 400 s），
差 **2.4×**。**根因**：`jni_bridge.c` 的 `gradeScanNative` 里每个 Z 带**跑了两遍阶段 1**
（① 只为拿统计的 `lysh_scan_rect`；② 真正带阶段 2 的 `lysh_grade_scan`）—— 阶段 1 占全流水线 >95%，
多扫一遍就是几乎整整多一倍；而且 C CLI 的 `elapsed` 只统计**第一遍**，拿它当基线本身就把 ~2× 藏在
度量口径里。**修法**：单遍 —— 统计全部由 `lysh_hut_grade` 回填（新增 `scanned` 字段），明细直接写进
调用方的 `out_hits`（容量不够才返回 `capacityNeeded = -1` 让 Java 放大**那一带**重试）；`long[20]`
布局没变，Java 侧一行都不用改。**实测**（同一窗口 = **351,564,000 格 / 256 个 Z 带**，同一 jar、
同一台机器、`-Dlowyswamphut.nativeLib=` 钉住 DLL）：双遍 **21.0–21.5 s** → 单遍 **11.1–11.2 s**
（⇒ **≈1.9×**；剩下的 = 8.8 s 单遍 + ~2.3 s 的 256 次 JNI/线程/ctx 开销）。**Java 路径现在比 C CLI
快**，因为 CLI 仍然每带扫两遍（`--list` 需要与 funnel 自洽的统计）。

> ⚠️ **`NativePhase1.loadLibrary()` 的候选列表里，`<cwd>\lysh-c\build\cmake\lysh.dll` 会赢过
> jar 旁边的 `lysh.dll`**（完整顺序见仓库根目录的 `README.md`）。**一律用
> `-Dlowyswamphut.nativeLib=<绝对路径>` 钉死**；`dist\README.txt` 也写了这条。

## 8. 现状与维护要点

**已完成**：噪声派生（0a/0b/0c）· 结构放置（1b）· **阶段 1 完整 `check()`（含真实群系门）** ·
阶段 1 全图扫描（1.373e10 格 / 4,047 幸存）· JNI 桥 · **阶段 2 接进 C 核心**（`lysh scan` 默认
全流水线 + `lysh hut` + `lysh_eval_hut` + JNI `NativePhase2`）—— 26.1.2 的 L4 `BlendedNoise` /
L5 含水层 / L6 `interpolated` 的 footprint 平均高度与 oracle 相等（11/11 + 1681/1681）·
**雕刻层**（`carver.c`，见 §2.5.5）。
**未做**：**M4 精确生成校验**（真游戏 / 无头服务端；Java 侧老路径的 `findGeneratedHutFloorY`
已删除，C 里没有对应实现）· **M5 1.21.1 的 JSON 解释器**（现在参数是硬编码/传入的）·
**M6 GUI 打包**。

**⚠️ 正确性的边界（必须知道）**：产品不再产出 `×`（“无法生成”），也**没有任何产品内交叉校验**
（老 Java 路径的真生成标记随回退路径一起删除）。因此**正确性完全押在“各道门是完备的”之上** ——
阶段 1 阈值 + 精确 footprint 高度 + 含水层灌水判定 + 精确群系门 + 雕刻层。若仍有某个放置条件
未被建模，症状会是**一个假阳性，且没有任何预警**。**雕刻层有两处已知近似**：`topMaterial`
（`SurfaceSystem` 的草/菌丝）未建模；SURFACE 之后的方块**类型**按“阻挡移动”近似（依据是这些
深度上地表规则的产出都在 `#overworld_carver_replaceables` 里，仅经逐列对拍间接验证）。

**⚠️ R1（最要紧的一条，不许删）**：`phase1.c` `climate_path()` 里的

```c
} else {
    /* 兜底：精确路径（= 原 `--exact-erosion` 的那个表达式）。**必须保留**。 */
    e = sample_climate(&p->climate.erosion, hut_x, hut_z);
}
```

**必须保留**。它同时是 `lysh_tier_seq_init()` 失败（`erosion_tier_ready == 0`，`climate.c` 会往
stderr 报 `"falling back to the exact path"`）时的**唯一兜底**：**删掉它 = tier 预计算一旦失败，
erosion 门就静默全通过。** 已用离线测试验证：把 `erosion_tier_ready` 强置 0 后，erosion 门仍然
淘汰 20 格里的 11 格，且与 tier 路径**逐格判定完全一致**。

**改内核时的必跑项**（没有更重的验证手段）：① `[engine]` / `[funnel]` 诊断数字不漂；
② §4.2 表里 11 个小屋的 **Y 与 dir 一字不变**（Y 对不上是行为变，dir 对不上是播种变）；
③ Java 产品端到端的 `[funnel]` 行。**本期的回归结果**：`cmake --build` **exit 0 / 零警告**；
11 个已知小屋全部 ACCEPT 且 Y 与 dir 相符；`scan` 100M 格的档位直方图与 erosion 门计数不变。

**一个容易记错的事实**： Java 产品的 `ceil(sum/63 + 1)` **没有
off-by-one**：Java 每列的值是**方块 Y**（= `getBaseHeight` 返回值 − 1），与 C 的
`trunc(sum/63)` 描述同一个量（§2.5.4）。