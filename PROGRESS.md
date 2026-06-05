# HoneyCam 项目进展记录

> 最后更新：2026-06-05

## 总体进度：原型开发阶段（约 60%）

---

## 1. 已完成 ✅

### 1.1 后端骨架 (2026-05-29 ~ 2026-06-01)

- [x] Spring Boot 4.x + Java 23 项目初始化
- [x] 基础包结构：`controller` / `service` / `model` / `config` / `rtsp`
- [x] 配置中心化：`HoneyCamProperties` — 所有蜜罐参数可外部化
- [x] Spring Security 开放所有端点（蜜罐需要）
- [x] WebSocket STOMP 配置（暂未前端使用）

### 1.2 伪装登录系统

- [x] 高仿海康威视 UI（背景图、图标精灵、品牌色 `#D71920`、布局）
- [x] 登录表单：用户名 + 密码 + 登录按钮
- [x] 概率性登录放行（`fake-success-rate`，默认 0.35）
- [x] 白名单弱密码必成功（如 `admin:12345`）
- [x] 登录页加载事件日志
- [x] IP/UA/凭据 → JSONL 日志
- [x] Enter 键触发登录（2026-06-05）
- [x] 登录错误/成功提示（Flash attribute 回显）

### 1.3 360° 全景查看器 (`camera-viewer.js`)

- [x] Three.js 球体 + 等距柱状投影渲染
- [x] 全景视频优先加载，自动回退图片
- [x] 键盘方向键控制 Pan/Tilt
- [x] +/- 键 Zoom (FOV 调整)
- [x] `0` 键重置视角
- [x] 鼠标滚轮 Zoom
- [x] 屏幕按钮 PTZ（8 方向 + Zoom In/Out）
- [x] 3 档速度切换
- [x] 惯性模拟（`inertiaDamping=0.88`）
- [x] OSD 时间戳叠加层（像素级清晰渲染）
- [x] 预览加载延迟遮罩（模拟真实相机流加载，300-1400ms 随机）
- [x] 自动巡航模式（Group B 用，正弦波 Pan/Tilt）

### 1.4 Fake RTSP 服务器 (端口 554)

- [x] TCP ServerSocket 监听
- [x] 多线程处理并发连接
- [x] 支持 RTSP 方法：OPTIONS / DESCRIBE / SETUP / PLAY / PAUSE / TEARDOWN / GET_PARAMETER / SET_PARAMETER
- [x] 真实 SDP 响应（H.264, profile-level-id, sprop-parameter-sets）
- [x] Session 管理（超时 60s）
- [x] RTSP 请求 → JSONL 日志
- [x] 应用启动自动启动，关闭时优雅停止

### 1.5 日志系统

- [x] 按日切分 JSONL 文件
- [x] 三类日志：`login-attempts` / `interactions` / `rtsp-requests`
- [x] Session 追踪（`SESSION_START` / `SESSION_END`，含 dwell time）
- [x] SLF4J 控制台同步输出

### 1.6 实验配置

- [x] Group B profile：`ptz-enabled=false, auto-patrol=true, panorama=image`
- [x] Group C profile：`ptz-enabled=true, auto-patrol=false, panorama=image`
- [x] Experiment profile：`server.port=80, rtsp.port=554, panorama=video`

### 1.7 分析工具与脚本

- [x] Python 日志分析脚本：转化率 + 停留时长 CDF
- [x] Linux 抓包脚本 (`start_capture.sh`)
- [x] Windows 抓包脚本 (`start_capture.ps1`)

---

## 2. 进行中 🔄

_暂无_

---

## 3. 待完成 ❌

### 3.1 高优先级（实验必须）

- [ ] **ONVIF 仿真（端口 8000）**：模拟 ONVIF 协议握手，让扫描器识别为真实摄像头
- [ ] **RTMP 仿真（端口 1935）**：模拟 RTMP 握手，扩大服务指纹覆盖
- [ ] **云服务器部署**：在 AWS/阿里云/腾讯云上实际部署并暴露到公网
- [ ] **实验运行**：7-10 天并行运行 A/B/C 三组
- [ ] **数据收集与分析**：汇总日志、计算指标、绘制 CDF 对比图
- [ ] **最终报告**：回答研究问题，包含实验设置、分析、结论

### 3.2 中优先级（提升欺骗度）

- [ ] **PTZ 动作建模**：根据真实相机 PTZ 速度/延迟参数调整前端动画
- [ ] **HTTP 响应头伪装**：模拟真实设备的 Server/Header 指纹
- [ ] **RTSP RTP 数据包模拟**：发送虚拟 RTP 包（而非仅仅握手）
- [ ] **多语言登录页**：中文/英文切换
- [ ] **登录验证码**：部分真实相机会有验证码
- [ ] **WebSocket 实时交互同步**：利用已配置的 STOMP 通道

### 3.3 低优先级（改进项）

- [ ] **前后端分离架构**：按 HoneyCam 论文设计，分离 Frontend Honeypot / Backend Server / VPU
- [ ] **MySQL 数据库**：替代 JSONL 文件存储
- [ ] **TCP/IP 栈定制**：对抗 Nmap OS 指纹检测
- [ ] **Shodan 对抗**：定制服务 Banner 以降低 Honeyscore
- [ ] **自动化部署脚本**：一键部署到云服务器
- [ ] **实时监控面板**：管理员查看攻击统计

---

## 4. 关键技术决策记录

| 日期 | 决策 | 理由 |
|------|------|------|
| 2026-05-29 | 选用 Three.js 客户端渲染而非服务端 VPU | 原型阶段降低复杂度，无需视频编码管线 |
| 2026-05-29 | JSONL 文件日志而非 MySQL | 简化部署依赖，便于快速迭代 |
| 2026-05-30 | 高仿 Hikvision UI | Hikvision 是全球最常见摄像头品牌，攻击者最熟悉 |
| 2026-05-31 | HoneyCamProperties 集中配置 | 避免 @Value 散落，按 profile 切换实验组更方便 |
| 2026-06-01 | RTSP 仅做握手仿真不做 RTP 流 | RTP 实时流推送到每个连接者成本太高，且攻击者极少真正拉流 |
| 2026-06-01 | 白名单弱密码 100% 放行 | 使用弱密码的很可能是真实攻击者/扫描器，放行可获取更多交互数据 |
| 2026-06-05 | Enter 键触发登录 | 提升 UI 真实感，真实海康威视页面支持键盘操作 |

---

## 5. 下一步计划

1. **添加 ONVIF 端口 8000 仿真** — 这是扫描器识别摄像头的重要依据
2. **添加 RTMP 端口 1935 仿真** — 完善服务指纹
3. **准备云部署** — 申请云服务器，配置公网 IP 和安全组
4. **启动实验** — 三组并行运行 7-10 天

---

## 6. 参考文档

- [CLAUDE.md](CLAUDE.md) — 项目详细说明和研究设计
- [honeycam.md](honeycam.md) — HoneyCam 论文摘录
- [README.md](README.md) — 运行指南和配置说明
