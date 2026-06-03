# HoneyCam

HoneyCam 是一个面向研究实验的 IoT 摄像头蜜罐原型，当前采用 `Java 23 + Spring Boot 4`。

## 当前能力

- 伪装登录页（品牌/型号可配置），记录用户名密码、来源 IP、UA
- 假认证策略（概率性进入监控页），提高攻击者停留时间
- 摄像头页面 + PTZ 交互日志（支持 Group C；可通过配置关闭变为 Group B）
- RTSP 554 握手仿真（OPTIONS/DESCRIBE/SETUP/PLAY/PAUSE/TEARDOWN）
- JSONL 日志输出：`login-attempts` / `interactions` / `rtsp-requests`
- 会话起止事件（`SESSION_START` / `SESSION_END`），可计算停留时长
- 分析脚本：导出转化率与停留时长 CDF 数据

## 运行

```bash
./gradlew bootRun
```

默认端口：

- HTTP: `8081`（可用环境变量 `HONEYCAM_HTTP_PORT` 覆盖）
- RTSP: `554`（可用环境变量 `HONEYCAM_RTSP_PORT` 覆盖）

Windows PowerShell:

```powershell
.\gradlew.bat bootRun
```

访问地址：`http://localhost:8081/login`

## 实验配置

### Group B（低交互，无 PTZ）

```bash
./gradlew bootRun --args='--spring.profiles.active=group-b'
```

PowerShell:

```powershell
.\gradlew.bat bootRun "--args=--spring.profiles.active=group-b"
```

说明：B 组已实现“自动巡航循环”效果（画面自动左右往返），但禁用用户 PTZ 输入，用于模拟低交互的循环播放型摄像头。

### Group C（高交互，带 PTZ）

```bash
./gradlew bootRun --args='--spring.profiles.active=group-c'
```

PowerShell:

```powershell
.\gradlew.bat bootRun "--args=--spring.profiles.active=group-c"
```

说明：当前 `group-c` 默认使用 360 全景图片（论文中该方案已可达到较好欺骗效果）。如需切到视频，可在配置中改 `honeycam.panorama.mode=video` 并提供 `static/media/360-demo.mp4`。

## 实验部署端口（80/554）

使用实验 profile（需 root/管理员权限）：

```bash
./gradlew bootRun --args='--spring.profiles.active=experiment'
```

## 全景素材（视频优先）

- 将 360 全景视频放到：`src/main/resources/static/media/360-demo.mp4`
- 默认会优先加载 `/media/360-demo.mp4`
- 若视频缺失或浏览器加载失败，会自动回退到全景图片模式（不影响运行）

建议素材：

- equirectangular 2:1（例如 3840x1920）
- H.264 编码 mp4
- 15~60 秒循环素材

## 抓包

Linux:

```bash
./scripts/start_capture.sh eth0
```

Windows PowerShell（需安装 Wireshark/tshark）：

```powershell
.\scripts\start_capture.ps1 -Interface "Ethernet"
```

## 指标分析

执行：

```bash
python scripts/analyze_logs.py --log-dir logs --out-dir analysis-output
```

输出：

- `analysis-output/metrics-summary.json`
- `analysis-output/dwell-time-cdf.csv`