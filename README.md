# HoneyCam — 360° 摄像头蜜罐原型

基于 **Java 23 + Spring Boot 4.x + Three.js** 构建的高交互 IoT 摄像头蜜罐系统，用于研究"低成本 360° 全景视频能否达到接近真实设备的欺骗效果"。

## 项目进度概览

| 模块 | 状态 | 说明 |
|------|------|------|
| 伪装登录页 (Hikvision UI) | ✅ 完成 | 高仿海康威视 UI，支持 Enter 键提交 |
| 360° 全景渲染 (Three.js) | ✅ 完成 | 等距柱状投影 + 球体映射 |
| PTZ 交互控制 | ✅ 完成 | 方向键/按钮控制，3 档速度，惯性模拟 |
| 登录日志 | ✅ 完成 | 记录 IP/UA/用户名/密码 → JSONL |
| 交互日志 | ✅ 完成 | PAN/TILT/ZOOM/SESSION 事件 → JSONL |
| Fake RTSP (554) | ✅ 完成 | OPTIONS/DESCRIBE/SETUP/PLAY/PAUSE/TEARDOWN |
| Fake ONVIF (8000) | ✅ 完成 | Device/Media/PTZ 服务，SOAP XML 响应 |
| 可配置欺骗策略 | ✅ 完成 | 概率性登录成功、白名单凭据、延迟预览 |
| Group B/C 实验配置 | ✅ 完成 | 低交互自动巡航 / 高交互 PTZ |
| 分析脚本 | ✅ 完成 | 转化率 + 停留时长 CDF |
| 抓包脚本 | ✅ 完成 | Linux shell + Windows PowerShell |
| RTMP (1935) | ❌ 跳过 | 已过时，缺失不降低欺骗度 |
| 云端部署实验 | ❌ 未开始 | |
| 最终报告 | ❌ 未开始 | |

## 系统架构

```
[攻击者]
    ↓
[公网 IP]
    ↓
[Spring Boot 后端]
    ├── AuthController     ← 伪装登录（概率性放行 + 记录凭据）
    ├── PageController     ← 注入蜜罐配置到页面
    ├── InteractionController ← PTZ 交互记录 API
    ├── RtspServer         ← 554 端口 RTSP 握手仿真
    ├── OnvifServer        ← 8000 端口 ONVIF SOAP 仿真
    └── LogService         ← JSONL 日志（按日切分）
    ↓
[前端 (Thymeleaf + Three.js)]
    ├── 360° 等距柱状投影渲染
    ├── PTZ 按钮 + 键盘控制
    ├── OSD 时间戳叠加层
    └── 预览延迟遮罩（模拟流加载）
    ↓
[日志系统]
    ├── login-attempts-YYYY-MM-DD.jsonl
    ├── interactions-YYYY-MM-DD.jsonl
    ├── rtsp-requests-YYYY-MM-DD.jsonl
    └── onvif-requests-YYYY-MM-DD.jsonl
```

## 快速启动

### 环境要求
- Java 23+
- Gradle 9.5.1 (wrapper included)

### 运行

```bash
./gradlew bootRun
```

默认端口：
- HTTP: `8081`（环境变量 `HONEYCAM_HTTP_PORT` 覆盖）
- RTSP: `554`（环境变量 `HONEYCAM_RTSP_PORT` 覆盖）
- ONVIF: `8000`（环境变量 `HONEYCAM_ONVIF_PORT` 覆盖）

访问：`http://localhost:8081/` → 自动跳转到 `/login`

## 实验分组配置

### Group B（低交互对照 — 无 PTZ，自动巡航）

```bash
./gradlew bootRun --args='--spring.profiles.active=group-b'
```

特点：画面自动左右往返巡航，用户无法控制 PTZ。模拟静态循环播放视频。

### Group C（高交互实验组 — 完整 PTZ）

```bash
./gradlew bootRun --args='--spring.profiles.active=group-c'
```

特点：攻击者可自由控制 Pan/Tilt/Zoom，默认使用 360° 全景图片。

### 实验部署（端口 80/554，需 root）

```bash
./gradlew bootRun --args='--spring.profiles.active=experiment'
```

## 全景素材

- 将 360° 全景视频放入 `src/main/resources/static/media/360-demo.mp4`
- 推荐格式：equirectangular 2:1（如 3840×1920），H.264 mp4，15~60 秒
- 视频加载失败时自动回退到全景图片（`honeycam.panorama.image-url`）

## 关键配置 (`application.properties`)

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `honeycam.camera.brand` | Hikvision | 摄像头品牌 |
| `honeycam.camera.model` | DS-2CD2043G2-I | 摄像头型号 |
| `honeycam.auth.fake-success-rate` | 0.35 | 登录成功概率 |
| `honeycam.auth.weak-credentials` | admin:12345,... | 白名单凭据（必成功） |
| `honeycam.panorama.mode` | auto | video/image/auto |
| `honeycam.deception.preview-latency-ms-min` | 300 | 预览加载最小延迟 |
| `honeycam.deception.preview-latency-ms-max` | 1400 | 预览加载最大延迟 |
| `honeycam.interaction.ptz-enabled` | true | PTZ 是否启用 |
| `honeycam.interaction.auto-patrol-enabled` | false | 自动巡航是否启用 |
| `honeycam.rtsp.port` | 554 | RTSP 监听端口 |
| `honeycam.onvif.port` | 8000 | ONVIF 监听端口 |

## 抓包

```bash
# Linux
./scripts/start_capture.sh eth0

# Windows PowerShell（需安装 Wireshark）
.\scripts\start_capture.ps1 -Interface "Ethernet"
```

## 指标分析

```bash
python scripts/analyze_logs.py --log-dir logs --out-dir analysis-output
```

输出：
- `analysis-output/metrics-summary.json` — 转化率 + 停留时长统计
- `analysis-output/dwell-time-cdf.csv` — 停留时长经验 CDF

## 待完成工作

详见 [PROGRESS.md](PROGRESS.md)