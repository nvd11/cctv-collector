# 🏗️ CCTV Collector 架构设计文档 (Architecture Design Document)

## 1. 架构总览与分层解耦

整个家用监控云端备份体系采用“生产者-缓冲存储-消费者”模式：

```mermaid
graph TD
    subgraph LAN_HOME["家庭千兆内网 (10.0.1.0/24)"]
        IPC["TP-LINK TL-IPC44AW<br/>(10.0.1.20:554)"]
        
        subgraph RADXA_NODE["采集节点: Radxa (10.0.1.105)"]
            COLLECTOR["CCTV Collector Service<br/>(Java 21 / Watchdog)"]
            FFMPEG["FFmpeg Subprocess<br/>(-c copy -f segment)"]
            COLLECTOR -->|管理生命周期| FFMPEG
        end

        subgraph STARFIVE_NODE["缓冲存储节点: StarFive (10.0.1.227)"]
            BUFFER["/mnt/buffer/cctv/<br/>15分钟切片 (*.mp4)"]
            ALIST["Alist 服务<br/>(:5244/dav)"]
        end
    end

    subgraph CLOUD["云端网盘 (外部公网)"]
        NETDISK["阿里云盘 / 百度网盘 / 夸克<br/>(/CCTV_Records/YYYY-MM-DD/)"]
    end

    IPC -->|"RTSP TCP (1.5 Mbps)"| FFMPEG
    FFMPEG -->|"无损写分段切片"| BUFFER
    
    subgraph BATCH_SYSTEM["解耦的第二阶段"]
        UPLOADER["CCTV Batch Uploader<br/>(Java Batch / Cron)"]
    end

    BUFFER -.->|"扫描已闭合文件"| UPLOADER
    UPLOADER -->|"WebDAV / rclone 上传"| ALIST
    ALIST -->|"安全异步推流"| NETDISK
    UPLOADER -.->|"上传确认后清理"| BUFFER
```

---

## 2. 整体工程结构：Maven 多模块 Monorepo

为了统一版本管理、共享公共代码、简化 CI/CD 流水线，本项目采用 **Maven 多模块（Multi-Module）** 架构，将原本计划拆分为两个独立仓库的 `cctv-collector` 与 `cctv-batch-uploader` 合并为单一 Monorepo。

### 2.1 模块划分与职责

所有动态运行参数（如 RTSP 地址、存储路径、切片时长等）统一由 **K3s ConfigMap / Secret** 声明并注入环境变量，两个服务均直接读取环境变量，各司其职、彻底解耦，拒绝过度工程与多余抽象。

| 模块 | 职责 | 核心配置来源 | 部署节点 |
| :--- | :--- | :--- | :--- |
| **`cctv-collector-service`** | 视频流采集守护服务：RTSP 拉流、FFmpeg 进程管理、切片缓冲、看门狗重连 | K3s ConfigMap (`CCTV_RTSP_URL`, `CCTV_BUFFER_DIR` 等) | Radxa K3s 节点 (`10.0.1.105`) |
| **`cctv-uploader-service`** | 批量上传服务：扫描缓冲目录、WebDAV 上传至 Alist、云端网盘归档、过期清理 | K3s ConfigMap / Secret (`ALIST_URL`, `ALIST_TOKEN` 等) | Radxa K3s 节点 (`10.0.1.105`) |

### 2.2 根目录结构

```text
cctv-collector/                          # 根项目（Parent POM）
├── pom.xml                              # 父 POM：聚合两个微服务子模块、统一依赖与插件版本
├── README.md
├── docs/
│   ├── REQUIREMENTS.md
│   └── ARCHITECTURE.md
├── k8s/                                 # K3s GitOps 配置目录
│   ├── configmap.yaml                   # 统一运行配置（路径、时长、URL）
│   ├── secret.yaml                      # 敏感信息（Alist Token 等）
│   ├── pv-buffer.yaml                   # 共享持久卷
│   ├── cctv-collector-deployment.yaml   # 采集端编排
│   └── cctv-uploader-deployment.yaml    # 上传端编排
│
├── cctv-collector-service/              # 【Svc1】视频流采集守护服务
│   ├── pom.xml
│   └── src/main/java/com/gateman/cctv/collector/
│       ├── CctvCollectorApp.java        # 服务入口
│       ├── config/                      # 自身直接读取 ConfigMap 环境变量
│       ├── supervisor/                  # FFmpeg 进程看门狗与磁盘熔断
│       └── hook/                        # 优雅停机钩子
│
└── cctv-uploader-service/               # 【Svc2】批量上传网盘服务
    ├── pom.xml
    └── src/main/java/com/gateman/cctv/uploader/
        ├── CctvUploaderApp.java         # 服务入口
        ├── config/                      # 自身直接读取 ConfigMap/Secret 环境变量
        ├── scanner/                     # 缓冲目录扫描器（识别已闭合切片）
        ├── client/                      # Alist WebDAV 客户端封装
        └── cleaner/                     # 过期文件清理与磁盘轮转
```

### 2.3 极简双微服务优势

1. **零冗余依赖**：彻底移除空泛的 `common` 模块，两服务各自独立，构建仅需 0.9 秒。
2. **云原生纯粹解耦**：所有共用契约（目录路径、切片分段）统一由 **K3s ConfigMap** 作为唯一真实来源（Single Source of Truth），修改无需重新编译代码。
3. **独立镜像打包**：每个子服务各自拥有 Shade 插件，编译输出干净可执行 FatJar 并构建容器。
4. **统一 GitOps 维护**：单仓库单流水线，与主人的 ArgoCD 架构完美吻合。

---

## 3. 采集服务内部核心架构

`cctv-collector-service` 内部划分为四大核心管理器：

```text
┌──────────────────────────────────────────────────────────────┐
│                  CctvCollectorService (Main)                 │
├──────────────────────────────┬───────────────────────────────┤
│  1. StreamConfig             │ 环境变量与属性加载器          │
│  2. DiskHealthChecker        │ 缓冲目录磁盘空间熔断控制器    │
│  3. FFmpegProcessSupervisor  │ FFmpeg 进程生命周期与日志泵  │
│  4. FileCommitterWatcher     │ 分段切片状态追踪与提交器      │
│  5. GracefulShutdownHook     │ 信号捕获与安全停机钩子        │
└──────────────────────────────┴───────────────────────────────┘
```

### 3.1 组件交互时序 (Sequence Diagram)

```mermaid
sequenceDiagram
    autonumber
    participant App as CctvCollectorService
    participant Checker as DiskHealthChecker
    participant Super as ProcessSupervisor
    participant FFmpeg as FFmpeg Process
    participant FS as Buffer FileSystem

    App->>Checker: 检查缓冲目录挂载与剩余空间
    alt 剩余磁盘空间 < 5GB
        Checker-->>App: 空间不足告警，熔断暂停
    else 空间充足
        Checker-->>App: 检查通过
    end

    App->>Super: 启动流拉取任务 (spawn)
    Super->>FFmpeg: 执行 ProcessBuilder (RTSP TCP -> segment copy)
    
    loop 持续推流切片
        FFmpeg->>FS: 写入分段切片 (cctv_20260915_230000.mp4)
        FFmpeg-->>Super: 输出 stderr/stdout 进度流
        Super->>Super: 心跳保活与状态解析
    end

    alt 异常断流 (Wi-Fi 抖动 / 摄像机重启)
        FFmpeg-->>Super: 进程非正常退出 (ExitCode != 0)
        Super->>Super: 触发指数退避重试 (Backoff 5s...10s)
        Super->>FFmpeg: 重新拉起采集会话
    end

    alt 收到系统停机信号 (SIGTERM/SIGINT)
        App->>Super: 触发 Shutdown Hook
        Super->>FFmpeg: 发送 'q' 字符或 SIGINT
        FFmpeg->>FS: 闭合当前正在写的末尾切片
        FFmpeg-->>Super: 进程安全退出
    end
```

---

---

## 4. 核心设计细节与执行机理

### 4.1 进程编排架构：外部进程独立托管 (Out-of-Process Supervision)

在音视频流媒体工程中，Java 应用程序通常面临两种与底层多媒体引擎交互的架构路线选择：

1. **进程内 JNI 绑定模式 (In-Process via JNI / JavaCV)**：
   - 依赖 JavaCV / JNI 将 FFmpeg 的 C 动态库（`libavcodec`, `libavformat` 等）绑定至 JVM 堆内运行。
   - **致命缺陷**：家庭无线监控摄像头的 RTSP 视频流极易因 Wi-Fi 抖动、微波干扰产生畸变坏帧。一旦底层的 C 代码解析损坏的数据包发生内存访问越界或段错误（Segmentation Fault），**将直接引发整个 Java 虚拟机崩溃宕机**，且任何 Java 层的 `try-catch` 均无法拦截。此外，JNI 复杂的 C 指针绑定与 Quarkus Native (GraalVM SubstrateVM) 编译体系存在严重兼容性壁垒。
2. **外部独立子进程托管模式 (Out-of-Process Supervision - 本项目采用)**：
   - **清晰解耦**：容器基础镜像预装官方原生编译的 `ffmpeg` 二进制程序（`debian:12-slim` + `apt-get install ffmpeg`，实测版本为 FFmpeg 5.1.9）；
   - **职责分离**：Java 服务作为**控制大脑与工业级看门狗（Master Watchdog）**，通过 `java.lang.ProcessBuilder` 调起并托管底层的 `ffmpeg` 命令；
   - **容错隔离与自愈**：底层 FFmpeg 即使遭遇不可恢复的异常坏流崩溃退出（ExitCode != 0），受影响的仅是操作系统子进程，Java 守护主服务依然安然无恙，能在 0.1 秒内感知退出事件并触发指数退避机制重新拉起拉流会话。

```mermaid
flowchart TD
    subgraph Container ["K3s Pod 容器内部 (cctv-collector 镜像)"]
        subgraph JavaSide ["Java 控制面 (Quarkus 3.8 Native / 12MB RAM)"]
            Service["CollectorService (业务门面)"]
            Supervisor["FFmpegProcessSupervisor (看门狗事件循环)"]
            Checker["DiskHealthChecker (磁盘熔断)"]
            LogPump["FFmpegLogPump (管道日志抽取 & 心跳检测)"]
            Service --> Supervisor
            Supervisor --> Checker
            Supervisor --> LogPump
        end

        subgraph OSSide ["Linux 操作系统层 (Debian 12 Slim 内核)"]
            FFMPEG_CMD["/usr/bin/ffmpeg 5.1.9 原生二进制"]
            PIPE_ERR["stderr / stdout 管道缓冲区 (64KB)"]
        end

        Supervisor -->|"1. ProcessBuilder 调度执行"| FFMPEG_CMD
        Supervisor -->|"2. stdin 注入 'q' 触发优雅收尾"| FFMPEG_CMD
        FFMPEG_CMD -->|"输出帧进度流"| PIPE_ERR
        PIPE_ERR -->|"循环非阻塞抽取"| LogPump
    end

    subgraph External ["内网硬件与存储卷"]
        IPC["TP-LINK 摄像机 (10.0.1.20:554)"]
        DISK["/mnt/buffer/cctv (MP4 物理切片文件)"]
    end

    IPC -->|"RTSP TCP (H.265+AAC)"| FFMPEG_CMD
    FFMPEG_CMD -->|"无损 Stream Copy 写入"| DISK
```

### 4.2 零损耗流拷贝 (Stream Copy) 参数矩阵
底层 FFmpeg 命令行构造遵循：
```bash
ffmpeg -hide_banner -loglevel info \
  -rtsp_transport tcp \
  -i "rtsp://admin:your_password@10.0.1.20:554/stream1" \
  -c copy \
  -f segment \
  -segment_time 900 \
  -segment_format mp4 \
  -reset_timestamps 1 \
  -strftime 1 \
  "/mnt/buffer/cctv/cctv_%Y%m%d_%H%M%S.mp4"
```

- `-rtsp_transport tcp`：强制走 TCP，解决无线监控摄像头丢包造成的绿屏和花屏。
- `-c copy`：音视频均不进行任何解码与重编码，只做 MP4 容器封装，极度节约 CPU（单板占用 < 0.01 核）。
- `-reset_timestamps 1`：确保每个 15 分钟的切片都从 PTS=0 开始，避免播放器拖动时间轴错位。
- `-strftime 1`：按切片生成的具体系统时间自动命名。

### 4.3 文件并发访问保护（避免下游 Batch 读半成品）
由于下阶段的 `Batch Uploader` 会异步扫描该目录，必须防止批处理读取到“正在写入中”的文件：
1. **方案 A（大小检测）**：Batch 脚本仅拾取最后修改时间早于 60 秒前、且文件大小已稳定的切片；
2. **方案 B（临时后缀）**：配合 segment 命名模式与移动重命名。

---

## 5. 部署与运维架构 (K3s 集群化与容器编排)

### 5.1 Radxa K3s 节点部署拓扑

采集服务与上传服务均打包为轻量容器镜像，统一部署在 **Radxa K3s 节点**（`radxa-cubie-a7a` · `10.0.1.105`）。二者通过共享的本地持久化卷（HostPath / Local PV）或者内网 StarFive NFS/SMB 共享目录进行无缝解耦衔接：

```mermaid
graph TD
    subgraph LAN_HOME["家庭千兆内网 (10.0.1.0/24)"]
        IPC["TP-LINK TL-IPC44AW<br/>(10.0.1.20:554)"]

        subgraph RADXA_K3S["Radxa K3s 宿主节点 (10.0.1.105)"]
            subgraph K3S_POD_COLLECTOR["Pod: cctv-collector"]
                COLLECTOR_CONTAINER["cctv-collector-service<br/>(Quarkus 3.8 + Java 21 + FFmpeg)"]
            end

            subgraph K3S_POD_UPLOADER["Pod: cctv-uploader (CronJob/Deployment)"]
                UPLOADER_CONTAINER["cctv-uploader-service<br/>(Quarkus 3.8 + Java 21 WebDAV Client)"]
            end

            SHARED_VOL[("缓冲数据卷 Buffer Volume<br/>HostPath / NFS 挂载")]
        end

        subgraph STARFIVE_NODE["存储/网关节点: StarFive (10.0.1.227)"]
            ALIST["Alist 服务<br/>(:5244/dav)"]
        end
    end

    subgraph CLOUD["云端网盘"]
        NETDISK["阿里云盘 / 百度网盘 / 夸克"]
    end

    IPC -->|"RTSP TCP (1.5 Mbps)"| COLLECTOR_CONTAINER
    COLLECTOR_CONTAINER -->|"写入切片"| SHARED_VOL
    SHARED_VOL -.->|"扫描闭合切片"| UPLOADER_CONTAINER
    UPLOADER_CONTAINER -->|"HTTP/WebDAV"| ALIST
    ALIST -->|"API 推送"| NETDISK
    UPLOADER_CONTAINER -.->|"上传完成清理"| SHARED_VOL
```

### 5.2 K3s 工作负载与资源调度规范

两个微服务均采用 **Quarkus 3.8 (Java 21)** 云原生架构，内存与启动损耗仅为传统 Spring Boot 的 1/5：

| 服务/工作负载 | K3s 资源类型 | 基础镜像环境 | 资源配额限制 (Limits/Requests) | 存储卷挂载 |
| :--- | :--- | :--- | :--- | :--- |
| **`cctv-collector`** | `Deployment` (Replicas: 1) | `eclipse-temurin:21-jre` + `ffmpeg` | CPU: 0.1~0.5 Core / Mem: 64MB~128MB | 挂载 Buffer 卷至 `/mnt/buffer/cctv` |
| **`cctv-uploader`** | `Deployment` (常驻监听/定时扫描) | `eclipse-temurin:21-jre-alpine` | CPU: 0.1~0.2 Core / Mem: 64MB~128MB | 挂载相同 Buffer 卷至 `/mnt/buffer/cctv` |

- **原生云原生探针 (Quarkus SmallRye Health)**：
  - 存活探针 (Liveness)：`GET http://localhost:8080/q/health/live`（探测 FFmpeg 守护线程是否僵死）
  - 就绪探针 (Readiness)：`GET http://localhost:8080/q/health/ready`（探测 RTSP 连接与缓冲挂载点是否就绪）
- **优雅停机 (Graceful Shutdown)**：利用 Quarkus `@PreDestroy` CDI 生命周期拦截器或容器 `SIGTERM`，确保 FFmpeg 封装写完最后一个 MP4 moov atom 头，避免损坏最后一个分段。

### 5.3 备选 Systemd 传统部署托管 (非容器场景)

若需要在 Radxa 上脱离 K3s 直接裸跑守护进程，依然完整保留 Systemd unit 支持：

| 服务 | Systemd Unit | 运行用户 | 内存限制 | 重启策略 |
| :--- | :--- | :--- | :--- | :--- |
| 采集服务 | `cctv-collector.service` | `gateman` | `128M` | `always` (看门狗兜底) |
| 上传服务 | `cctv-uploader.service` | `gateman` | `256M` | `on-failure` |

### 5.4 GitOps 持续集成与发布流 (GitHub Actions + ArgoCD)

本项目与主人的既有 GitOps 体系（`aliyun-k3s` ArgoCD 控制面 + `my-argocd-manifests` 根配置库）完全整合，实现全自动化交付流水线：

```mermaid
graph LR
    subgraph DEV["开发与源码"]
        CODE["Git Push<br/>(cctv-collector)"]
    end

    subgraph CI["GitHub Actions CI"]
        BUILD["Maven Compile & Test<br/>(Java 21)"]
        DOCKER["Docker Buildx (ARM64)<br/>collector & uploader"]
        GHCR["Push to GHCR / Docker Registry<br/>(ghcr.io/nvd11/cctv-*:tag)"]
        UPDATE_GIT["自动更新 Manifest 镜像版本"]
    end

    subgraph GITOPS["GitOps 仓库体系"]
        ARGOCD_REPO["Git: nvd11/my-argocd-manifests<br/>(或本仓库 k8s/ 目录)"]
    end

    subgraph CD["ArgoCD (aliyun-k3s: 8.148.149.80)"]
        ARGO_APP["ArgoCD Application<br/>cctv-system"]
    end

    subgraph CLUSTER["目标 K3s 集群"]
        RADXA_PODS["Radxa K3s 节点<br/>cctv-collector & cctv-uploader Pods"]
    end

    CODE --> BUILD
    BUILD --> DOCKER
    DOCKER --> GHCR
    GHCR --> UPDATE_GIT
    UPDATE_GIT --> ARGOCD_REPO
    ARGOCD_REPO -.->|"ArgoCD 轮询 / Webhook 触发"| ARGO_APP
    ARGO_APP -->|"GitOps 自动同步/滚动升级"| RADXA_PODS
```

#### 流水线闭环细节：
1. **CI 阶段（GitHub Actions）**：
   - 监听 `main` 分支代码提交或 Release Tag。
   - 执行 `mvn clean package` 多模块并行打包。
   - 使用 Docker Buildx 构建兼容 Radxa 宿主机的容器镜像（ARM64 架构），推送至 GHCR（GitHub Container Registry）或阿里云/DockerHub。
   - 自动提交更新 K8s 部署清单中的 `image.tag`。
2. **CD 阶段（ArgoCD 控制面）**：
   - 在主人的阿里云 ArgoCD 控制面注册 `cctv-collector` 与 `cctv-uploader` 的 Application（或统一 ApplicationSet）。
   - ArgoCD 自动检测 Git 仓库中清单版本变更，向目标集群下发声明式更新。
   - 采用 RollingUpdate 策略完成平滑重启，并在出现拉流或初始化异常时自动告警与回滚。

---

## 6. 目录结构规范（最终落地版）

```text
cctv-collector/                          # 根项目（Parent POM）
├── pom.xml                              # 父 POM：聚合两个微服务子模块、统一依赖版本
├── README.md
├── docs/
│   ├── REQUIREMENTS.md
│   └── ARCHITECTURE.md
│
├── .github/
│   └── workflows/                       # CI/CD 自动化流水线
│       └── ci-cd.yaml                   # Maven 打包 + Docker Buildx (ARM64) + 镜像推送
│
├── k8s/                                 # ArgoCD 纳管的 GitOps 清单目录
│   ├── configmap.yaml                   # 业务配置（RTSP/缓冲路径/切片秒数）
│   ├── secret.yaml                      # 敏感信息（Alist Token/网盘凭据）
│   ├── namespace.yaml
│   ├── pv-buffer.yaml                   # 共享缓冲持久化卷
│   ├── cctv-collector-deployment.yaml   # 采集端 Deployment
│   └── cctv-uploader-deployment.yaml    # 上传端 Deployment
│
├── cctv-collector-service/              # 【Svc1】采集服务模块
│   ├── pom.xml
│   └── src/main/java/com/gateman/cctv/collector/
│       ├── CctvCollectorApp.java
│       ├── config/
│       │   └── CollectorConfig.java     # 直接读取 ConfigMap 环境变量
│       ├── supervisor/
│       │   ├── FFmpegProcessSupervisor.java
│       │   ├── FFmpegCommandBuilder.java
│       │   ├── FFmpegLogPump.java
│       │   └── DiskHealthChecker.java
│       └── hook/
│           └── GracefulShutdownHook.java
│
├── cctv-uploader-service/               # 【Svc2】上传服务模块
│   ├── pom.xml
│   └── src/main/java/com/gateman/cctv/uploader/
│       ├── CctvUploaderApp.java
│       ├── config/
│       │   └── UploaderConfig.java      # 直接读取 ConfigMap/Secret 环境变量
│       ├── scanner/
│       │   └── BufferFileScanner.java
│       ├── client/
│       │   └── AlistWebDavClient.java
│       └── cleaner/
│           └── ExpiredFileCleaner.java
│
└── scripts/                             # 运维脚本（本地调试与兼容裸机启动）
    ├── start-collector.sh
    ├── start-uploader.sh
    ├── cctv-collector.service
    └── cctv-uploader.service
```

---

## 6. 时区一致性与 OSD 物理时钟对齐设计 (Timezone & Clock Alignment)

家庭安防监控体系中，切片文件名所携带的物理时间戳必须与视频画面右上角摄像机固件生成的 OSD 水印时钟保持分秒级严格一致。

### 6.1 8 小时时区偏差与跨天错位根因
- **现象**：在北京时间午夜 00:38 录制的视频切片，生成文件名却为 `cctv_20260920_163822.mp4`。文件名时间比视频画面时间落后整整 8 个小时，且跨天停留在昨天（20日），导致下游网盘按日归档的目录跨天错乱；
- **根因**：FFmpeg 在切片参数 `-strftime 1` 激活时，调用 Linux C 标准库（glibc）的 `localtime_r(&ti, &tm)` 与 `strftime()`。容器内部若未配置 `TZ` 环境变量，操作系统内核默认回退至世界协调时 `/etc/localtime -> Etc/UTC`（UTC+0），而 TP-LINK 摄像机固件印在视频画面上的时钟为中国标准时间（CST，UTC+8）。

### 6.2 零编译改动方案：POSIX 环境变量注入
由于 Java 业务逻辑与底层 FFmpeg 原生二进制文件本身严格遵循 POSIX 时区规范，无需重新构建或修改代码，统一由 GitOps 仓库（`my-argocd-manifests`）在 ConfigMap 中注入 `TZ: "Asia/Shanghai"`：
1. **FFmpeg 子进程**：通过 `ProcessBuilder` 继承 Pod 容器级环境变量 `TZ=Asia/Shanghai`，glibc `strftime` 准确输出当前北京时间（如 `cctv_20260921_003822.mp4`），与视频画面右上角的水印时间分秒咬合；
2. **Java 虚拟线程与应用日志**：Quarkus / Java 21 自动将默认时区校准为 CST，标准输出日志、状态探针与调度 Cron 表达式全面按照东八区自然时间运作；
3. **网盘云端分层归档**：Uploader 基于切片文件名解析出的日期（`YYYY-MM-DD`）精准匹配本地现实生活中的自然日（00:00~24:00），将切片规整归入 `/Quark/CCTV_Records/锦绣世家_客厅/2026-09-21/`。
