# CCTV Collector (视频流采集守护服务)

[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

针对家庭局域网智能监控摄像机（TP-LINK TL-IPC44AW 2.5K 全彩云台机型）的 7×24 小时高可靠视频流采集守护服务。

---

## 🎯 设计理念：两段式解耦流水线

整个体系采用 **采集缓冲** 与 **网盘上传** 两段式彻底解耦的架构：

1. **第一阶段（本项目 `cctv-collector`）**：
   - 跑在家庭内网主机（如 Radxa），通过 RTSP TCP 抓取摄像机视频流；
   - 零 CPU 转码消耗（Stream Copy），每 15 分钟无损切片暂存于内网存储（如 StarFive 缓冲盘或本地目录）；
   - 内置进程看门狗（Watchdog），支持摄像机掉线自动重试与优雅退出保护。
2. **第二阶段（关联项目 `cctv-batch-uploader`）**：
   - 独立批处理任务，定时从缓冲目录扫描已闭合切片；
   - 异步批量上传至 Alist 挂载的云端网盘（阿里云盘/百度网盘/夸克），并自动轮转清理旧文件。

---

## 📚 项目技术文档

- 📋 **[需求规格说明书 (Requirements Specification)](docs/REQUIREMENTS.md)**
- 🏗️ **[架构设计文档 (Architecture Design Document)](docs/ARCHITECTURE.md)**

---

## 🚀 快速开始

### 依赖环境
- Linux (Debian/Ubuntu/NixOS 等)
- OpenJDK 17+
- FFmpeg 5.0+

### 配置说明
支持通过系统环境变量或 `.env` 覆盖配置参数：
- `CCTV_RTSP_URL`: 摄像机 RTSP 地址（默认 `rtsp://admin:ga32565624@10.0.1.20:554/stream1`）
- `CCTV_BUFFER_DIR`: 视频切片暂存目录（默认 `/tmp/cctv_buffer` 或指定 StarFive 挂载点）
- `CCTV_SEGMENT_SECONDS`: 切片分段时长（默认 `900` 秒 / 15 分钟）
- `CCTV_MIN_FREE_DISK_GB`: 磁盘安全熔断阈值（默认 `5` GB）

---

## 📄 License
MIT License.
