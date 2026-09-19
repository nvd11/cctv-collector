# 云原生架构下的配置管理实战：Quarkus @ConfigMapping 与 .env 的本地与 K3s 双模协同

## 1. 业务背景与配置管理痛点

在边缘流媒体采集系统（`cctv-collector`）中，服务运行于部署在家庭局域网内的 Radxa ARM64 边缘计算节点（加入自建 K3s 集群）。该系统负责 24x7 接入 TP-LINK TL-IPC44AW 摄像机的 RTSP 视频流，执行零损耗切片并写入缓冲存储。

在这个工程实践中，配置管理面临两个针锋相对的核心诉求：

1. **本地开发与离线调试的高灵活性**：
   - 开发者在本地机器上运行单元测试或调试 `ffmpeg` 进程时，必须接入真实的摄像机 RTSP 流（包含敏感的设备账号密码 `admin:ga32565624`）；
   - 传统 Java 偏向于在 `application.properties` 里配置参数，但若不慎提交真实凭证到 GitHub 公开仓库，将造成严重的网络安全泄漏；
   - 开发者希望本地配置具备类似脚本语言（如 Python `python-dotenv`）的体验：随时在一个独立文件中修改变量，且该文件天然被版本控制忽略，甚至能在 Linux 终端直接执行 `source .env` 供命令行工具调用。

2. **生产环境与 GitOps 交付的严格一致性（Twelve-Factor App）**：
   - 云原生十二要素法则第 3 条明确要求：**代码与配置严格分离，同一份镜像制品在所有环境中保持完全一致**；
   - 容器镜像由 GitHub Actions 构建并推送到 GitHub Container Registry（GHCR），由 ArgoCD 自动拉取并部署到 K3s 集群。镜像内部绝不能包含任何环境特异性配置；
   - 生产环境的账号密码必须由 K3s Secret 管理，目录与分段时长等非敏感项由 K3s ConfigMap 注入为容器的 Linux 环境变量（POSIX 规范）。

为了同时满足本地研发的敏捷性与生产集群的严密性，我们基于 **Quarkus 3.8 原生配置体系（SmallRye Config / @ConfigMapping）** 与 **.env 模式**，搭建了一套分层穿透、强弱结合的统一配置管理体系。

---

## 2. 统一配置架构设计与覆盖链

整个系统的配置加载严格遵循层级覆盖规则，优先级从低到高如下：

```text
┌────────────────────────────────────────────────────────────────────────┐
│  4. K3s / Linux 操作系统环境变量 (Priority 300)                        │
│     - 由 K3s ConfigMap / Secret 通过 envFrom 注入 (生产最高权威)       │
│     - 格式: CCTV_RTSP_URL, CCTV_BUFFER_DIR                             │
├────────────────────────────────────────────────────────────────────────┤
│  3. 本地脱敏配置文件 .env (Priority 290)                               │
│     - 仅存在于开发者本地, 被 .gitignore 严格忽略                       │
│     - 格式: CCTV_RTSP_URL=..., CCTV_BUFFER_DIR=...                     │
├────────────────────────────────────────────────────────────────────────┤
│  2. 镜像内部默认配置 application.properties (Priority 250)             │
│     - 打包进容器 JAR/Native 二进制内部的通用基础参数                   │
│     - 格式: cctv.buffer-dir=/mnt/buffer/cctv                           │
├────────────────────────────────────────────────────────────────────────┤
│  1. Java 强类型契约默认值 @WithDefault (Priority 100)                  │
│     - 固化在 Java 接口方法签名上的代码级保底值                         │
│     - 格式: @WithDefault("900") int segmentSeconds();                  │
└────────────────────────────────────────────────────────────────────────┘
```

该架构确保了：
- **容器环境无需挂载文件**：K3s 注入的环境变量具有最高优先级（300），无缝覆盖下层的一切默认值；
- **本地开发无需改动代码**：只要本地存在 `.env`，其优先级高于代码内的默认配置；
- **零配置开箱即用**：即使不提供任何外部环境变量和 `.env` 文件，依靠 `@WithDefault` 和 `application.properties`，全套基础测试依然能闭环运行。

---

## 3. 服务端落地：K3s ConfigMap / Secret 与 POSIX 规范

在生产环境的 GitOps 清单（ArgoCD Application）中，配置完全交由 Kubernetes 原生资源进行管理。

### 3.1 ArgoCD Helm 编排配置

在应用的 Helm Values 中，我们通过 `envFrom` 将配置整体暴露为容器的环境变量：

```yaml
# cctv-collector-app.yaml (ArgoCD Application 资源)
spec:
  source:
    helm:
      values: |
        envFrom:
          - configMapRef:
              name: cctv-collector-config

        configMap:
          enabled: true
          name: cctv-collector-config
          data:
            CCTV_RTSP_URL: "rtsp://admin:ga32565624@10.0.1.20:554/stream1"
            CCTV_BUFFER_DIR: "/mnt/buffer/cctv"
            CCTV_SEGMENT_SECONDS: "900"
            CCTV_MIN_FREE_DISK_GB: "5"
```

### 3.2 环境变量的 POSIX 命名约束

根据 IEEE POSIX 1003.1 标准，Linux 操作系统与容器运行时的环境变量名称必须满足以下正规式：
`[a-zA-Z_][a-zA-Z0-9_]*`。这意味着：
- **严禁包含点号（`.`）**：传统的 `cctv.rtsp-url` 无法直接作为合法的 Shell 环境变量名；
- **严禁包含横杠（`-`）**：连字符在很多 Shell 解释器中被解析为减法运算符。

因此，所有进入 K3s 容器的变量名称统一遵循**全大写下划线（UPPER_UNDERSCORE）**格式：`CCTV_RTSP_URL`、`CCTV_BUFFER_DIR`。

---

## 4. 框架粘合剂：Quarkus @ConfigMapping 自动映射推导算法

为了让 Java 代码能够优雅地消费来自操作系统或 `.env` 的大写下划线变量，我们采用了 Quarkus 官方推荐的 `@ConfigMapping` 接口。

### 4.1 强类型接口定义

```java
package com.gateman.cctv.collector.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "cctv")
public interface CollectorConfig {

    @WithDefault("rtsp://localhost:554/stream1")
    String rtspUrl();

    @WithDefault("/mnt/buffer/cctv")
    String bufferDir();

    @WithDefault("900")
    int segmentSeconds();

    @WithDefault("5")
    long minFreeDiskGb();

    @WithDefault("5")
    int reconnectDelaySeconds();

    @WithDefault("60")
    int maxReconnectDelaySeconds();
}
```

### 4.2 确定性映射推导算法剖析

开发者在编写接口时，仅需指定 `prefix = "cctv"` 与普通驼峰方法名 `rtspUrl()`，Quarkus 底层的 SmallRye Config 引擎便会在构建期与启动期通过确定性算法完成多端双向映射：

1. **第一步：驼峰转烤肉串（CamelCase to kebab-case）**：
   扫描方法名称 `rtspUrl`，在大写字母前插入连字符并转小写，推导出 `rtsp-url`；同理 `segmentSeconds` 推导出 `segment-seconds`。
2. **第二步：前缀拼装（Canonical Property Resolution）**：
   将接口指定的 `prefix` 与上述推导结果以点号拼接，得出 `application.properties` 中的标准键名：`cctv.rtsp-url`。
3. **第三步：POSIX 环境变量映射规则（MicroProfile 规范转换）**：
   按照 MicroProfile Config 规范要求，将上述键名中的点号 `.` 与连字符 `-` 全部替换为下划线 `_`，并全量转为大写字母：
   `cctv.rtsp-url` $\longrightarrow$ `CCTV_RTSP_URL`。

这一套算法从根本上免除了繁琐的映射字典维护工作。开发者在 Java 代码中直接面向强类型方法 `config.rtspUrl()` 编程，而底层的容器环境变量 `CCTV_RTSP_URL` 会被全自动拦截并填充至该方法。

### 4.3 编译期与启动期双重防呆（Fail-Fast）

使用 `@ConfigMapping` 相比于直接调用 `System.getenv("CCTV_RTSP_URL")` 具有压倒性的安全优势：

- **代码期静态检查**：IDE 具备完整的代码补全能力，打错任何一个字母（如敲成 `config.rtspUri()`），`javac` 编译器在毫秒内直接报错拦截，杜绝了散落各处的硬编码魔法字符串（Magic Strings）。
- **类型强校验**：如果运维人员在 K3s ConfigMap 中不慎填入了非数字的切片时长（如 `CCTV_SEGMENT_SECONDS="900s"`），Quarkus 会在应用初始化的第一毫秒抛出类型转换异常中断启动，绝不把脏数据隐患留到运行时。
- **必填项熔断机制**：对于未标注 `@WithDefault` 的核心方法，若外部环境（ConfigMap、`.env` 或属性文件）均未提供对应配置，Quarkus 启动时会立即抛出 `ConfigValidationException`，明确告知哪一项配置缺失，实现真正的云原生启动失败熔断。

---

## 5. 本地开发与安全防线：.env 与 .env-template 闭环

为了兼顾本地极速调试与安全防范，工程在本地端引入了 `.env` 与模板机制。

### 5.1 目录安全隔离设计

在 Git 根目录的 `.gitignore` 中，明确排除了 `.env` 文件：

```gitignore
# 排除敏感凭据文件
.env
target/
*.class
```

同时，在仓库中签入一份完全脱敏的模板文件 **`.env-template`**：

```bash
# ==============================================================================
# CCTV Stream Collector - 本地环境变量配置模板 (.env-template)
# ==============================================================================

# [必填] 摄像头真实 RTSP 流地址
CCTV_RTSP_URL=rtsp://admin:your_camera_password@10.0.1.20:554/stream1

# [可选] 切片暂存物理目录
CCTV_BUFFER_DIR=/mnt/buffer/cctv

# [可选] 单个 MP4 切片分段时长（秒）
CCTV_SEGMENT_SECONDS=900

# [可选] 磁盘熔断阈值（GB）
CCTV_MIN_FREE_DISK_GB=5
```

开发者在检出仓库后，只需在本地执行：
```bash
cp .env-template .env
```
随后填入真实的局域网账号密码即可开始本地开发。

### 5.2 动态字典加载器：DotEnv 工具类

虽然 `@ConfigMapping` 满足了大部分核心配置需求，但在某些动态调试、临时排错场景中，开发者往往需要像 Python 一样随意读取未在 Java 接口中预定义的任意变量（例如临时加一个 `DEBUG_RECORD_LIMIT=10`）。

为此，我们在主代码库中实现了一个轻量级、无任何第三方依赖的 `DotEnv` 工具类（位于 `com.gateman.cctv.collector.util.DotEnv`）：

```java
public final class DotEnv {

    private DotEnv() {}

    public static Map<String, String> load() {
        Map<String, String> envMap = new HashMap<>();

        // 1. 扫描当前工作目录或父目录下的 .env
        Path[] candidatePaths = new Path[] {
                Path.of(".env"),
                Path.of("../.env"),
                Path.of("cctv-collector-service/.env")
        };

        for (Path candidate : candidatePaths) {
            if (Files.exists(candidate)) {
                envMap.putAll(loadFile(candidate));
                break;
            }
        }

        // 2. 操作系统真实环境变量覆盖 (高优先级)
        envMap.putAll(System.getenv());

        return Collections.unmodifiableMap(envMap);
    }

    public static Map<String, String> loadFile(Path path) {
        if (!Files.exists(path)) return Collections.emptyMap();

        Map<String, String> result = new HashMap<>();
        try {
            for (String line : Files.readAllLines(path)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String value = line.substring(eqIdx + 1).trim();
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    result.put(key, value);
                }
            }
        } catch (IOException ignored) {}
        return Collections.unmodifiableMap(result);
    }
}
```

该工具类返回标准的不可变 `Map<String, String>`，使得 Java 在需要动态扩展变量时，获得了与 Python `dotenv_values()` 完全同等的灵活性。

---

## 6. 单元测试与端到端验证

为了确保在不同环境下配置均能准确映射，测试框架实现了轻量化分层测试。

### 6.1 Java 21 Record 测试替身（TestCollectorConfig）

在单元测试中，如果每个测试用例都要依赖外部 `.env` 或启动完整的 Quarkus 容器，会导致测试执行变慢且存在环境污染。

我们利用 Java 21 `record` 原生实现 `CollectorConfig` 接口，构建了零样板代码的测试替身：

```java
public record TestCollectorConfig(
        String rtspUrl,
        String bufferDir,
        int segmentSeconds,
        long minFreeDiskGb,
        int reconnectDelaySeconds,
        int maxReconnectDelaySeconds
) implements CollectorConfig {

    public static TestCollectorConfig createDefault() {
        return new TestCollectorConfig(
                "rtsp://localhost:554/stream1",
                "/mnt/buffer/cctv",
                900,
                5L,
                5,
                60
        );
    }

    public TestCollectorConfig withRtspUrl(String rtspUrl) {
        return new TestCollectorConfig(rtspUrl, bufferDir, segmentSeconds, minFreeDiskGb, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }

    public TestCollectorConfig withBufferDir(String bufferDir) {
        return new TestCollectorConfig(rtspUrl, bufferDir, segmentSeconds, minFreeDiskGb, reconnectDelaySeconds, maxReconnectDelaySeconds);
    }
}
```

这使得 `DiskHealthCheckerTest` 和 `FFmpegCommandBuilderTest` 可以直接通过纯内存调用完成毫秒级验证：

```java
CollectorConfig config = TestCollectorConfig.createDefault()
        .withBufferDir(tempDir.toString())
        .withMinFreeDiskGb(10_000_000L); // 构造极端熔断边界
DiskHealthChecker checker = new DiskHealthChecker(config);
assertThat(checker.isDiskHealthy()).isFalse();
```

### 6.2 真实环境变量动态装配验证（EnvVariableTest）

编写专门的集成测试验证动态 `.env` 读取与底层命令装配的连贯性：

```java
@Test
void testBuildFFmpegArgsFromDynamicEnv() {
    Map<String, String> env = DotEnv.load();

    String realRtsp = env.getOrDefault("CCTV_RTSP_URL", "rtsp://localhost:554/stream1");
    String bufferDir = env.getOrDefault("CCTV_BUFFER_DIR", "/mnt/buffer/cctv");
    int segmentSeconds = Integer.parseInt(env.getOrDefault("CCTV_SEGMENT_SECONDS", "900"));

    CollectorConfig config = TestCollectorConfig.createDefault()
            .withRtspUrl(realRtsp)
            .withBufferDir(bufferDir)
            .withSegmentSeconds(segmentSeconds);

    List<String> commandArgs = FFmpegCommandBuilder.buildArgs(config);

    // 验证命令行参数完全由动态环境变量正确驱动
    assertThat(commandArgs).contains("-i", realRtsp);
    assertThat(commandArgs).contains("-segment_time", String.valueOf(segmentSeconds));
}
```

运行 Maven 测试套件：
```text
[INFO] Running com.gateman.cctv.collector.config.EnvVariableTest
2026-09-19 23:49:15 INFO Dynamic USER from OS: gateman
2026-09-19 23:49:15 INFO Retrieved CCTV_RTSP_URL dynamically: rtsp://admin:ga32565624@10.0.1.20:554/stream1
2026-09-19 23:49:15 INFO Final assembled FFmpeg command from dynamic env:
2026-09-19 23:49:15 INFO ffmpeg -hide_banner -loglevel info -rtsp_transport tcp -i rtsp://admin:ga32565624@10.0.1.20:554/stream1 -c copy -f segment -segment_time 900 -segment_format mp4 -reset_timestamps 1 -strftime 1 /mnt/buffer/cctv/cctv_%Y%m%d_%H%M%S.mp4
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.149 s
```

---

## 7. 实践总结

在基于 Java 21 与 Quarkus 的现代云原生微服务实践中，配置管理的演进呈现出两大清晰特征：

1. **契约强类型化**：生产级代码必须依赖 `@ConfigMapping`，借助 Java 编译器与框架的 Fail-Fast 机制，将拼写错误、类型转换崩溃完全消灭在系统启动之前；
2. **输入管道解耦化**：通过引入 `.env` 与模板机制，兼顾了本地开发时无需切换配置文件的轻快体验，同时在 K3s 集群中天然贴合 GitOps 和 Kubernetes POSIX 环境变量规范，实现了“同一套代码在本地与云端无缝自愈运行”的工程目标。
