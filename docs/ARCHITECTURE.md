# 🏗️ CCTV Collector 架构设计文档 (Architecture Design Document)

## 1. 架构总览与分层解耦

整个家用监控云端备份体系采用“生产者-缓冲存储-消费者”模式：

```mermaid
graph TD
    subgraph LAN_HOME["家庭千兆内网 (10.0.1.0/24)"]
        IPC["TP-LINK TL-IPC44AW<br/>(10.0.1.20:554)"]
        
        subgraph RADXA_NODE["采集节点: Radxa (10.0.1.105)"]
            COLLECTOR["CCTV Collector Service<br/>(Java 17 / Watchdog)"]
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

## 2. 采集服务内部核心架构

`cctv-collector` 内部划分为四大核心管理器：

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

### 2.1 组件交互时序 (Sequence Diagram)

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
    activate FFmpeg
    
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
        deactivate FFmpeg
    end
```

---

## 3. 核心设计细节

### 3.1 零损耗流拷贝 (Stream Copy) 参数矩阵
底层 FFmpeg 命令行构造遵循：
```bash
ffmpeg -hide_banner -loglevel info \
  -rtsp_transport tcp \
  -i "rtsp://admin:ga32565624@10.0.1.20:554/stream1" \
  -c copy \
  -f segment \
  -segment_time 900 \
  -segment_format mp4 \
  -reset_timestamps 1 \
  -strftime 1 \
  "/mnt/buffer/cctv/cctv_%Y%m%d_%H%M%S.mp4"
```

- `-rtsp_transport tcp`：强制走 TCP，解决无线监控摄像头丢包造成的绿屏和花屏。
- `-c copy`：音视频均不进行任何解码与重编码，只做 MP4 容器封装，极度节约 CPU。
- `-reset_timestamps 1`：确保每个 15 分钟的切片都从 PTS=0 开始，避免播放器拖动时间轴错位。
- `-strftime 1`：按切片生成的具体系统时间自动命名。

### 3.2 文件并发访问保护（避免下游 Batch 读半成品）
由于下阶段的 `Batch Uploader` 会异步扫描该目录，必须防止批处理读取到“正在写入中”的文件：
1. **方案 A（大小检测）**：Batch 脚本仅拾取最后修改时间早于 60 秒前、且文件大小已稳定的切片；
2. **方案 B（临时后缀）**：配合 segment 命名模式与移动重命名。

---

## 4. 目录结构规范

```text
cctv-collector/
├── README.md                 # 项目介绍与一键启动指南
├── docs/
│   ├── REQUIREMENTS.md       # 需求规格说明书
│   └── ARCHITECTURE.md       # 架构设计文档
├── src/
│   └── main/
│       └── java/
│           └── com/
│               └── gateman/
│                   └── cctv/
│                       ├── CctvCollectorApp.java   # 主服务入口
│                       ├── config/                 # 配置定义
│                       └── supervisor/             # 进程看门狗与监控
├── pom.xml                   # Maven 构建定义 (支持打包 Jar)
└── scripts/
    ├── start.sh              # 快速运行脚本
    └── cctv-collector.service# Systemd 托管服务定义
```
