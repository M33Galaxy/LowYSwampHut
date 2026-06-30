# 性能与压力测试

Benchmark runner 会执行真实的 `SearchCoords` 搜索路径，不会使用合成任务替代
seed 判断、结构判断或 feature 检查。

## 测试配置

| Profile | 线程数 | Iterations | Chunk cache | Worldgen 并发 | 用途 |
|---|---:|---:|---:|---:|---|
| A | 2 | 1,000,000 | 1,024 | 2 | 低负载基线测试 |
| B | 4 | 10,000,000 | 1,024 | 2 | 标准吞吐量与结构生成测试 |
| C | 4 | 500,000,000 | 512 | 2 | 10 分钟以上的 plateau 与内存泄漏测试 |

使用 JDK 17 或 JDK 21 运行：

```powershell
./gradlew benchmarkA
./gradlew benchmarkB
./gradlew benchmarkC
```

每次运行都会在 `benchmark-results/` 下生成带时间戳的 CSV 文件和
`-summary.json` 汇总文件。默认采样间隔为 5 秒。Benchmark runner 支持以下参数：

```text
--profile=A|B|C
--output=benchmark-results/custom.csv
--sample-seconds=5
```

如需获得有效的稳定性结论，应完整运行 Profile C，不要提前终止测试。

## 指标说明

- `memory plateau`：10 分钟内的 heap 回归斜率不高于 8 MB/min。
- `hidden memory leak`：10 分钟内的 heap 回归斜率高于 8 MB/min。
- `throughput stable`：chunks/sec 的下降幅度不超过 20%。
- `gc_pause_delta_ms`：当前采样区间内所有垃圾收集器的累计暂停时间。
- `worker_cpu_percent`：按照配置的 RegionChecker 容量归一化后的 CPU 使用率；
  在逻辑处理器较多的主机上，应使用该指标，而不是整机 CPU 使用率。
- `seedchecker_estimated_mb`：SeedChecker 内存占用估算值，可通过
  `lowyswamphut.metrics.seedCheckerBaseMb` 和
  `lowyswamphut.metrics.protoChunkMb` 调整估算参数。
- `cache_single_peak`：单个 SeedChecker 的 cache 峰值，是判断 cache 上限的主要指标；
  `cache_peak` 是所有并发 SeedChecker cache 的合计峰值。
- `chunks_total`：SeedChecker chunk Map 的正向增长量。该值是低开销的
  ProtoChunk 创建数量估算，而不是基于 bytecode 的精确对象分配计数。

## 自动报警

出现以下情况时，采集器会输出报警：

- ForkJoin queue backlog 持续增长；
- 多个采样区间出现超过 500 ms 的 GC pause；
- chunks/sec 下降超过 20%；
- RegionChecker worker 数量超过 4；
- heap 持续增长 10 分钟仍未进入 plateau；
- iteration 停滞，但 chunk generation 仍在继续。
