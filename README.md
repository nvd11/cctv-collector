# CCTV Collector (视频流采集守护服务)

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.8-red.svg)](https://quarkus.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

针对家庭局域网智能监控摄像机（TP-LINK TL-IPC44AW 2.5K 全彩云台机型）的 7×24 小时高可靠视频流采集守护服务，基于 **Quarkus 3.8 + Java 21** 亚原子轻量级架构构建。

---

## 🎯 设计理念：两段式解耦流水线

整个体系采用 **采集缓冲** 与 **网盘上传** 两段式彻底解耦的架构：

1. **第一阶段（采集服务 `cctv-collector-service`）**：
   - 跑在 Radxa K3s 节点（`10.0.1.105`），通过 RTSP TCP 抓取摄像机视频流；
   - 零 CPU 转码消耗（Stream Copy），每 15 分钟无损切片暂存于挂载的缓冲卷；
   - 内置看门狗与 K3s Liveness Probe，支持摄像机掉线自动重试与优雅退出保护。
2. **第二阶段（上传服务 `cctv-uploader-service`）**：
   - 同样编排部署在 Radxa K3s 节点，通过挂载同一共享缓冲卷定期扫描已闭合切片；
   - 异步批量流式上传至 Alist 挂载的云端网盘（阿里云盘/百度网盘/夸克），国内专线免代理直连加速；
   - **本地滑动窗口缓存（Rolling Window Cache）**：本地 SSD 始终保留最新的 10 个切片（~2.5 小时内网秒开回看），仅对已入库的更早历史切片执行 FIFO 自动淘汰。

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
