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
   - 异步批量上传至 Alist 挂载的云端网盘（阿里云盘/百度网盘/夸克），并自动轮转清理旧文件。

---

## 📚 项目技术文档

- 📋 **[需求规格说明书 (Requirements Specification)](docs/REQUIREMENTS.md)**
- 🏗️ **[架构设计文档 (Architecture Design Document)](docs/ARCHITECTURE.md)**

---

## 🚀 快速开始

### 依赖环境
- Linux (Debian/Ubuntu/NixOS 等)
- OpenJDK 21+
- FFmpeg 5.0+

### 配置说明
支持通过系统环境变量或 `.env` 覆盖配置参数：
- `CCTV_RTSP_URL`: 摄像机 RTSP 地址（默认 `rtsp://admin:ga32565624@10.0.1.20:554/stream1`）
- `CCTV_BUFFER_DIR`: 视频切片暂存目录（默认 `/tmp/cctv_buffer` 或指定 StarFive 挂载点）
- `CCTV_SEGMENT_SECONDS`: 切片分段时长（默认 `900` 秒 / 15 分钟）
- `CCTV_MIN_FREE_DISK_GB`: 磁盘安全熔断阈值（默认 `5` GB）

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
