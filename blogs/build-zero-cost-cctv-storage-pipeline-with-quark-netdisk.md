# 不花一分钱云存储与内存卡：用一台乞丐版摄像头 + 闲置单板机 + 夸克网盘，自建 24×7 监控存储与回放系统

家里装修或者租房，装个摄像头看家防盗本来是件挺舒心的事。但只要你买过市面上的家用监控（小米、TP-LINK、萤石、华为等），十有八九被厂家的套路恶心过：

1. **机身便宜，增值服务死贵**：裸机一百出头甚至几十块，但买回来才发现，不买他们一年一两百块钱的云存储套餐，只能看实时画面，根本没法回看。
2. **插内存卡？寿命堪忧**：24 小时不间断录 2.5K/4K 视频，普通的 MicroSD (TF) 卡几个月到半年就直接被写入寿命磨损报废；一旦遇上坏人入室，顺手把摄像头或内存卡拔走拔掉，证据瞬间灰飞灭灭。
3. **NAS 存监控？大炮打蚊子**：开着一套几十上百瓦的大 NAS 24 小时跑监控，噪音、发热加上硬盘电费，成本早够买好几年云服务了。

手头刚好有一台别人送的、甚至不用插卡的最基础款 **TP-LINK TL-IPC44AW（2.5K 全彩云台机型，不到一百块）**，桌角又有一块常年通电跑 Linux 的闲置 ARM 开发板（Radxa Cubie A7A，类似树莓派）。

经过两个晚上的折腾，用一套纯自研的微服务流水线，彻底实现了：
- **不买一分钱的官方云套餐**；
- **机身不插任何内存卡**；
- **全天候 2.5K 无损高清录制（H.265 + AAC 音频）**；
- **本地保留最新 2.5 小时录像极速秒开回看**；
- **历史视频全部全自动直传存入不限容量的夸克网盘（按日期分目录归档）**；
- **手机打开夸克网盘 App，随时随地免费在线倍速拖动回看全天的视频！**

这套架构已经在内网连续稳定运行了几个月，零丢帧、零维护。本文把整套落地方案、架构设计中的各种巨坑以及核心源码实现完整复盘出来，供有同样需求的技术人参考。

---

## 1. 整体架构思考：为什么不能“直接推流到网盘”？

最开始想得很简单：能不能用开发板抓摄像头的流，直接通过网盘客户端上传？

实际跑起来发现，在边缘网络环境下这完全行不通：
- **家用宽带上行抖动与断网**：家用公网偶尔断连几秒，推流进程立刻阻塞崩溃，导致这段时间的监控录像直接丢帧破损；
- **网盘限流与防刷**：各大网盘对高频写入都有并发或频次限制，长时间维持 TCP 上传连接极易被对端主动 RST；
- **I 帧与切片完整性**：MP4 封装需要最终写入 `moov atom` 元数据头。如果网络一断导致文件没封口，存到网盘里的就是一个无法播放的损坏废文件。

为了解决这个问题，必须采用**工业级“两段式解耦”架构（Two-Stage Pipeline）**：

```mermaid
flowchart LR
    A[TP-LINK 摄像机] -->|RTSP TCP 无损拉流| B(第一阶段: Collector 采集服务)
    B -->|每 15 分钟无损切片| C[(开发板本地外接 SSD 缓冲池)]
    C -->|滑动窗口保留最新 10 个切片| D(第二阶段: Uploader 上传服务)
    D -->|WebDAV HTTP PUT 直传| E[Alist 网关]
    E -->|国内阿里 OSS 专线极速直传| F[云端 夸克网盘]
```

整个系统分为两个职责单一、互不干涉的子服务：
1. **阶段一：采集守护者（`cctv-collector`）**：
   - 唯一的任务就是在局域网内用极低开销抓取摄像头的 RTSP 流，按 15 分钟一段写入本地外接 SSD 缓冲；
   - 哪怕外网彻底拔掉网线，本地录制也绝不中断。
2. **阶段二：智能上传与轮转引擎（`cctv-uploader`）**：
   - 定期扫描本地已经写完并封口的切片，按自然顺序（FIFO）推送到夸克网盘；
   - 维持**“本地滑动窗口”**：本地始终保留最新的 10 个切片（覆盖最近 2.5 小时），上传成功一个、且本地总数超过 10 个时，才淘汰删除最老的一个已入库切片。

---

## 2. 第一阶段实战：零 CPU 转码采集与进程看门狗

现在的智能摄像头大多支持 ONVIF 或 RTSP 协议。以这台 TP-LINK 摄像机为例，在路由器 DHCP 绑定静态 IP（`10.0.1.20`），只要在 TP-LINK App 里开启“局域网 PC 客户端访问”并设置密码，就能拿到标准 RTSP 地址：

```text
rtsp://admin:password@10.0.1.20:554/stream1
```

码流规格非常扎实：**2560x1440 (2.5K), 15fps, H.265 (HEVC), 音频 AAC 16kHz**，码率大概在 1.5 Mbps（每秒约 190 KB）。

### 2.1 拒绝转码：Stream Copy 实现 0.5% CPU 占用
单板机 CPU 资源宝贵，如果对 2.5K H.265 进行解码重新编码，4 核 CPU 瞬间拉满且狂发热。
因为摄像头吐出来的本身就是极其高效的 H.265/AAC 编码，我们只需要借用 FFmpeg 执行容器封装解构，做 **Stream Copy（`-c copy`）**：

```bash
ffmpeg -hide_banner -loglevel info \
  -rtsp_transport tcp \
  -i "rtsp://admin:password@10.0.1.20:554/stream1" \
  -c copy \
  -f segment \
  -segment_time 900 \
  -segment_format mp4 \
  -reset_timestamps 1 \
  -strftime 1 \
  "/mnt/buffer/cctv/cctv_%Y%m%d_%H%M%S.mp4"
```

几个关键参数必须注意：
- `-rtsp_transport tcp`：安防监控强制走 TCP。UDP 只要局域网一有干扰就会丢包，产生大面积绿屏或马赛克；
- `-f segment -segment_time 900`：每 900 秒（15 分钟）精准自动切一段；
- `-strftime 1`：以切片启动那一刻的年月日时分秒动态命名；
- **效果**：纯内存数据包流转与磁盘顺序写，在开发板上的 CPU 占用率稳定在 **0.3% ~ 0.5%**，几乎完全不耗电。

### 2.2 生产级看门狗与优雅停机（Virtual Threads）
在真实的运行环境中，摄像头可能因为停电、Wi-Fi 抖动断连，或者 FFmpeg 遭遇坏帧导致进程僵死。必须有一层严密的守护进程（Supervisor）。

我们用 **Java 21 虚拟线程 + 响应式 Quarkus** 实现看门狗循环。当进程异常退出时，执行指数退避重连（5s ➔ 10s ➔ 20s... 上限 60s）；退出时向 FFmpeg 标准输入注入字符 `'q'`，等待其自愿刷新 MP4 文件尾退出，防止暴力 `SIGKILL` 损坏最后一段切片：

```java
public synchronized boolean stopGracefully(long timeoutSeconds) {
    if (currentProcess == null || !currentProcess.isAlive()) {
        return true;
    }
    try {
        // 1. 发送 'q' 触发 FFmpeg 写入 moov atom 并安全收口
        OutputStream stdin = currentProcess.getOutputStream();
        stdin.write("q\n".getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        
        // 2. 超时未自愿退出则强制销毁
        if (currentProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            return true;
        }
    } catch (Exception e) {
        LOG.warn("Failed to stop ffmpeg gracefully", e);
    }
    currentProcess.destroyForcibly();
    return false;
}
```

---

## 3. 第二阶段实战：跨过网盘上传的那些“深坑”

有了连续落盘的 MP4 切片，接下来就是把它们传上夸克网盘。这一步是坑最多的地方。

我们采用 **Alist** 作为网盘的驱动网关（跑在内网另一台机器上，提供统一的标准 WebDAV 协议 `http://10.0.1.227:5244/dav/Quark/`）。

### 坑 1：Alist 走境外代理导致国内网盘大面积报错
这是排查最久的一个巨坑。
部署在开发板上的 Alist 往往为了拉取海外资源（如 OneDrive、Google Drive）配置了全局代理 `HTTP_PROXY=http://10.0.1.105:7890`。
结果导致：**传往国内夸克网盘（阿里云 OSS 节点 `ul-sz.pds.quark.cn`）的数据流，全部莫名其妙绕到了境外代理节点再传回国内！**

- 上传带宽从正常的千兆宽带被死死压制在 **300 KB/s**，传一个 150MB 的 15 分钟切片耗时 10 分钟以上；
- 境外链路稍一波动发生重试，Alist 会误发一个 0 字节分块，阿里云 OSS 抛出 `EntityTooSmall` 错误直接拒收。

**解法**：在 Alist 的 systemd 服务中严格注入国内直连白名单：
```ini
Environment="NO_PROXY=localhost,127.0.0.1,10.0.0.0/8,192.168.0.0/16,quark.cn,.quark.cn,pds.quark.cn,.pds.quark.cn,aliyuncs.com,.aliyuncs.com"
```
放行后，上传速度瞬间飙到 **6.8 MB/s**！一个原本需要传十分钟的切片，在 **30 秒内秒传**完毕。

---

### 坑 2：并发读写碰撞 —— Uploader 怎么知道切片写完了没有？
Uploader 是一个定时扫描缓冲目录的独立程序。当它看到目录下有一个 `cctv_20260921_010822.mp4` 时，怎么确定这是上一段已经写完的文件，还是 Collector 正在写、只录了 2 分钟的半成品？

传统做法用文件锁或重命名 `.tmp`，但在高频跨进程场景下极易发生竞争或死锁。我们采用操作系统级的**“文件修改时间凝固窗口算法（File Age Cooldown Window）”**：

```java
public boolean isFileStable(Path path, long minAgeSeconds) {
    long size = Files.size(path);
    if (size <= 0) return false;

    // 关键：获取系统真实最后修改时间戳
    long lastModifiedSeconds = Files.getLastModifiedTime(path).toInstant().getEpochSecond();
    long nowSeconds = Instant.now().getEpochSecond();
    
    // 只有修改时间永久定格且冷却超过 60 秒，才确认 FFmpeg 已经彻底 close 句柄
    return (nowSeconds - lastModifiedSeconds) >= minAgeSeconds; // 默认 60s
}
```
- 正在录制时，FFmpeg 每秒都在向文件追加帧，操作系统的 `mtime` 实时刷新（与当前时间差只有几毫秒），Uploader 绝对拒收；
- 15 分钟满，FFmpeg 关闭句柄并切换到下一段，该文件的 `mtime` 永久定格；
- 60 秒冷却期一过，Uploader 立即建单推进队列。无锁、安全、零碰撞。

---

### 坑 3：时区差异导致的 8 小时倒流与跨天混乱
测试时发现一个诡异现象：明明是北京时间凌晨 `00:38` 录的视频，生成的切片名字居然叫 `cctv_20260920_163822.mp4`，慢了整整 8 个小时，还倒退回了前一天！

**根因**：容器化运行时默认采用零时区 UTC。FFmpeg 在做 `-strftime 1` 格式化时调用了 Linux glibc 的 `localtime_r`，读取不到时区直接回退到 UTC。
**解法**：在 K8s / 容器环境统一注入环境变量 `TZ=Asia/Shanghai`。
生效后，文件名 `cctv_20260921_003822.mp4` 与视频右上角水印时间秒级咬合，网盘日期分层目录（`2026-09-21/`）也不会再跨天错乱。

---

## 4. 极致体验：本地滑动窗口缓存（Rolling Window Cache）

网盘上传完后，本地磁盘的文件该不该删？
- **如果传完就删**：本地空空如也。如果家里突然有响动想看一下刚刚 5 分钟前发生了什么，必须打开网盘去外网拉流缓冲，又慢又耗外网流量；
- **如果不删**：164G 的外接固态几天就被打满崩溃。

我们最终落地了**“本地滑动窗口淘汰算法”**：
- 设定本地保底文件数：`minRetainedFiles = 10`；
- 10 个切片 × 15 分钟 = **始终保持本地拥有最近 2.5 小时的最新监控**；
- 淘汰规则遵循严格的 **FIFO + 云端落盘确认双重铁律**：

```java
void cleanLocalFiles() {
    List<Path> allSegments = listLocalMp4FilesSorted(); // 按时间戳升序排序
    int totalCount = allSegments.size();
    int minRetained = config.minRetainedFiles(); // 10

    if (totalCount <= minRetained) {
        return; // 文件数不足 10 个，一律保留在本地
    }

    int excess = totalCount - minRetained; // 超出几个就淘汰几个
    for (Path file : allSegments) {
        if (excess <= 0) break;

        // 核心铁律：只有被远端网盘确认写入（SUCCESS/SKIPPED）的最旧切片才允许物理删除
        if (isConfirmedUploadedToCloud(file)) {
            Files.deleteIfExists(file);
            excess--;
            LOG.infof("Evicted oldest local segment (retaining %d newest): %s", 
                minRetained, file.getFileName());
        }
    }
}
```

**运行效果**：
- 刚生成的视频在本地随时可以极速秒开回看；
- 随着新视频不断写入，系统在后台默默把 2.5 小时之前的旧视频上传并清理掉；
- 本地 SSD 永远稳稳当当停留在 10 个文件（约 1.5GB 占用），再也不会爆盘。

---

## 5. 云端与移动端回看体验

当整套流水线跑通后，日常的使用体验就完全颠覆了：

1. **零成本、零心智负担**：
   - 摄像头不用插卡，不用担心内存卡写坏；
   - 不用给云厂商交月租，不用担心订阅过期；
2. **手机端体验极其丝滑**：
   - 打开手机上的 **夸克网盘 App**；
   - 导航到 `/CCTV_Records/锦绣世家_客厅/`，里面清清楚楚按每天一个文件夹排列（`2026-09-20`、`2026-09-21`...）；
   - 点击任意一段 15 分钟的 MP4，**直接调用夸克云端转码，支持 1.5x / 2.0x / 3.0x 倍速播放、AI 智能字幕、画面高清预览**！

---

## 6. 总结与开源架构启发

通过把简单的底层工具（FFmpeg Stream Copy）与现代云原生微服务设计结合，我们用不到一百块钱的乞丐版监控硬件，搭建出了一套媲美商业级云存储的稳定安防系统。

**核心经验总结**：
1. **网络与安全**：永远不要把写操作 API（如上传触发）无防护裸露在公网，外网网关只留只读大屏，写操作锁在 K8s 内部；国内网盘节点必须配置 `NO_PROXY` 直连；
2. **读写解耦**：利用文件系统的修改时间凝固窗口（`mtime cooldown`），可以做到进程间零死锁、零锁竞争的安全衔接；
3. **分层存储**：热数据（最新 2.5 小时）靠本地滑动窗口保证低延迟，冷数据（全天历史）靠免费网盘保证安全容灾与异地持久化。

折腾的乐趣不仅在于“省钱”，更在于将整个系统的控制权和所有数据完完全全握在自己手中的踏实感。如果你手头也有闲置的树莓派或开发板，不妨也来试试这套极简架构！
