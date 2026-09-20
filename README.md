# CCTV Collector (视频流采集守护服务)

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.8-red.svg)](https://quarkus.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

针对家庭局域网智能监控摄像机（TP-LINK TL-IPC44AW 2.5K 全彩云台机型）的 7×24 小时高可靠视频流采集守护服务，基于 **Quarkus 3.8 + Java 21** 亚原子轻量级架构构建。

---

## 🎯 设计理念：两段式解耦流水线

整个体系采用 **采集缓冲（Collector）** 与 **网盘上传（Uploader）** 两段式彻底解耦的云原生流媒体架构：

1. **第一阶段（采集端 `cctv-collector-service`）**：
   - 跑在 Radxa K3s 节点（`10.0.1.105`），通过 RTSP TCP 抓取摄像机视频流；
   - 零 CPU 转码消耗（Stream Copy），每 15 分钟无损切片暂存于挂载的外接固态缓冲卷；
   - 内置看门狗与 K3s Liveness Probe，支持摄像机掉线自动重试与优雅退出保护。
2. **第二阶段（上传端 `cctv-uploader-service`）**：
   - 同样编排部署在 Radxa K3s 节点，通过挂载同一共享缓冲卷定期扫描已闭合切片；
   - 异步批量流式上传至 Alist 挂载的云端网盘（阿里云盘/百度网盘/夸克），国内专线免代理直连加速；
   - **本地滑动窗口缓存（Rolling Window Cache）**：本地 SSD 始终保留最新的 10 个切片（~2.5 小时内网秒开回看），仅对已入库的更早历史切片执行 FIFO 自动淘汰。

---

## 🏗️ Collector 与 Uploader 协同运作架构 (Collaboration Architecture)

两个微服务通过 **K3s HostPath 共享存储卷（`/home/gateman/cctv-buffer`）** 实现物理存储介质上的完全解耦与异步生产-消费协作：

```mermaid
flowchart TD
    subgraph LAN["家庭局域网 (10.0.1.0/24)"]
        IPC["TP-LINK TL-IPC44AW<br/>(10.0.1.20:554)<br/>2.5K H.265 / AAC"]
        
        subgraph RADXA["Radxa 单板机 (10.0.1.105 · K3s Node)"]
            subgraph COLLECTOR_POD["cctv-collector-service (Pod :8081)"]
                WATCHDOG["看门狗 Supervisor<br/>(Virtual Threads)"]
                FFMPEG["FFmpeg 进程<br/>(-c copy -f segment)"]
                WATCHDOG -->|进程树生命周期管控| FFMPEG
            end

            subgraph BUFFER["共享外接 SSD 缓冲池 (/home/gateman/cctv-buffer)"]
                ACTIVE["cctv_*.mp4 (写入中 · mtime 毫秒刷新)"]
                READY["cctv_*.mp4 (已闭合 · mtime 凝固 >= 60s)"]
                WINDOW["[滑动窗口保留区: 最新 10 个切片 (~2.5h) 零延迟回看]"]
            end

            subgraph UPLOADER_POD["cctv-uploader-service (Pod :8082)"]
                SCHED["Quarkus Cron 调度器<br/>(默认每 5 分钟扫描)"]
                SCANNER["SegmentScanner<br/>(60s 冷却与非零字节审计)"]
                QUEUE["TaskDao 内存队列<br/>(按时间自然排序 FIFO)"]
                EXEC["UploadExecutorService<br/>(非重入 CAS 互斥流式上传)"]
                
                SCHED --> SCANNER
                SCANNER -->|审计合格入队| QUEUE
                QUEUE --> EXEC
            end
        end

        subgraph STARFIVE["StarFive RISC-V 节点 (10.0.1.227)"]
            ALIST["Alist 网关服务 (:5244/dav)<br/>[已配置 NO_PROXY 国内直连]"]
        end
    end

    subgraph CLOUD["云端网盘 (外部存储)"]
        QUARK["夸克网盘 / 阿里云盘<br/>/Quark/CCTV_Records/锦绣世家_客厅/YYYY-MM-DD/"]
    end

    %% 核心数据流
    IPC -->|"1. RTSP TCP 视频流 (1.5 Mbps)"| FFMPEG
    FFMPEG -->|"2. 实时无损追加切片"| ACTIVE
    ACTIVE -.->|"3. 15分钟满 close 句柄"| READY
    READY --> WINDOW
    
    SCANNER -.->|"4. 检查 mtime 凝固 >= 60s (绝对防半成品)"| READY
    EXEC -->|"5. HTTP WebDAV PUT 极速直传 (6MB/s+)"| ALIST
    ALIST -->|"6. 专线持久化入库"| QUARK
    QUARK -->>|"7. HTTP 201 Created 确认"| EXEC
    EXEC -.->|"8. 本地切片 > 10 ? 淘汰最旧已入库切片 : 保留"| WINDOW
```

### 🔄 协同运作关键工作机制

1. **时区物理对齐（Timezone Alignment）**：
   - 容器统一注入 `TZ: "Asia/Shanghai"`，Collector 生成的切片文件名（`cctv_YYYYMMDD_HHmmss.mp4`）与摄像头画面右上角 OSD 水印时钟分秒无缝对齐；
   - Uploader 按文件名日期精准将切片归档至当天自然日目录（如 `/2026-09-21/`），杜绝跨天错位。
2. **读写防碰撞安全防线（File Age Cooldown Window）**：
   - Collector 正在录制的切片，操作系统 `mtime` 会以每秒 15 帧的频率实时更新；
   - Uploader 的 `SegmentScanner` 强制执行 `now - lastModified >= 60s` 冷却时间与大小非零校验，**绝对不读、不传正在写入中的半成品文件**。
3. **先进先出本地滑动窗口（FIFO Rolling Window Cache）**：
   - 切片录制完成后第一时间直推夸克网盘完成异地容灾备份；
   - 上传成功后探测本地 `.mp4` 文件总数：
     - 若 `<= 10`：跳过删除，保留文件，维持本地最新 2.5 小时的极速局域网回看缓存；
     - 若 `> 10`：按文件名时间戳自旧向新检索，**仅物理删除最旧且已确认入库的切片**，正在录制与待上传的切片受状态机绝对保护。
4. **控制面内外网隔离（Public Readonly vs Internal Mutation）**：
   - 公网 Kong 网关仅开放只读接口（`/api/uploader/status` 与 `/tasks`）；
   - 写操作接口（`POST /api/uploader/trigger`）仅限 K8s 集群内网调用，彻底免除公网恶意刷接口的风险。

---

## 📚 项目技术文档

- 📋 **[需求规格说明书 (Requirements Specification)](docs/REQUIREMENTS.md)**
- 🏗️ **[整体架构设计文档 (Architecture Design Document)](docs/ARCHITECTURE.md)**
- 🏛️ **[采集服务详细类设计 (Collector Class Design)](docs/CLASS_DESIGN.md)**
- 🏛️ **[上传服务详细类设计 (Uploader Class Design)](docs/UPLOADER_CLASS_DESIGN.md)**

---

## 🚀 快速开始

### 依赖环境
- Linux (Debian/Ubuntu/NixOS 等)
- OpenJDK 21+
- FFmpeg 5.0+

### 配置说明
支持通过系统环境变量或 ConfigMap 覆盖配置参数：

#### 通用环境配置
- `TZ`: 容器运行与切片命名时区（强制推荐 `Asia/Shanghai`，使切片文件名与视频画面 OSD 水印分秒一致，且云端网盘按中国自然日归档）

#### 采集端 (`cctv-collector`)
- `CCTV_RTSP_URL`: 摄像机 RTSP 地址（默认 `rtsp://admin:your_password@10.0.1.20:554/stream1`）
- `CCTV_BUFFER_DIR`: 视频切片暂存目录（默认 `/tmp/cctv_buffer` 或 Radxa 外接固态）
- `CCTV_SEGMENT_SECONDS`: 切片分段时长（默认 `900` 秒 / 15 分钟）
- `CCTV_MIN_FREE_DISK_GB`: 磁盘安全熔断阈值（默认 `5` GB）

#### 上传端 (`cctv-uploader`)
- `CCTV_UPLOADER_BUFFER_DIR`: 共享切片缓冲目录（默认 `/mnt/buffer/cctv`）
- `CCTV_UPLOADER_ALIST_ENDPOINT`: Alist WebDAV 地址（默认 `http://10.0.1.227:5244/dav`）
- `CCTV_UPLOADER_REMOTE_BASE_DIR`: 远端网盘基础根目录（默认 `/Quark/CCTV_Records`）
- `CCTV_UPLOADER_LOCATION_NAME`: 监控机位名称（默认 `锦绣世家_客厅`）
- `CCTV_UPLOADER_MIN_RETAINED_FILES`: 本地滑动窗口最少留存切片数（默认 `10`，覆盖约 2.5 小时）
- `CCTV_UPLOADER_CLEANUP_POLICY`: 清理策略（默认 `DELETE`）
- `CCTV_UPLOADER_SCAN_CRON_EXPRESSION`: 定时轮询表达式（默认 `0 */5 * * * ?`，即每 5 分钟自动扫描并批处理上传）

### ⏰ 定时调度与自动化触发机制 (Scheduler Architecture)
Uploader 服务采用 **Quarkus 进程内原生 Cron 调度器（`io.quarkus.scheduler.Scheduled`）**，结合非重入互斥锁与双模触发设计：
1. **自动定时调度（In-Process Cron）**：
   - 由 `UploaderScheduler` 基于 `cctv.uploader.scan-cron-expression` 周期性唤醒（默认每 5 分钟，逢整点 00, 05, 10, 15... 分钟触发）；
   - 配置 `@Scheduled(concurrentExecution = ConcurrentExecution.SKIP)` 并配合 CAS 原子状态锁（`uploadInProgress`），若上一批大文件正在直传，自动跳过本次并发，避免 WebDAV 锁冲突；
2. **安全边界保护（Restricted Public Ingress）**：
   - 触发接口（`POST /api/uploader/trigger`）**仅向集群内网开放**，公网网关（Kong）实施严格白名单路由，仅放行只读监控接口（`GET /api/uploader/status` 与 `/tasks`），彻底封堵公网被恶意触发攻击的风险；
3. **按需手动触发（On-Demand API）**：
   - 支持 K8s 集群内网其他组件、运维脚本或轻量 Pod 直接调用 `POST http://cctv-uploader:8082/api/uploader/trigger` 立即执行一次上传。

---

## 🔄 CI/CD 与 GitOps 部署 (GitHub Actions + ArgoCD)

本项目原生接入自动化持续集成与 GitOps 交付流水线：

1. **GitHub Actions (`.github/workflows/ci-cd.yaml`)**：
   - 代码 Push 触发自动化 Maven 构建与测试；
   - Docker Buildx 构建 ARM64 架构镜像并推送至 GHCR (`ghcr.io`)；
   - 自动更新 `k8s/` 部署清单中的镜像版本标签。
2. **ArgoCD 持续交付**：
   - 由主人的阿里云控制面 (`aliyun-k3s`) 统一纳管 Application 声明；
   - 自动检测并同步至目标内网 Radxa K3s 节点，实现滚动更新与探针自愈。

---

## 📄 License
MIT License.
