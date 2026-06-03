# 项目需求文档：基于 360° 全景视频的高交互 IoT 摄像头蜜罐设计与评估

> **项目来源**：Project 4 — Honeycam Tutor（指导教师：孙昊）  
> **核心问题**：低成本的 360° 视频蜜罐能否达到接近真实摄像头的欺骗效果？

---

## 1. 项目背景

IoT 摄像头已成为网络攻击的主要目标（僵尸网络招募、隐私窃取等）。蜜罐（Honeypot）作为主动防御手段，可诱捕攻击者并采集行为数据。现有方案存在两极分化：

| 方案类型 | 优点 | 缺点 |
|---|---|---|
| 低成本：循环播放录制视频 | 部署简单 | 无交互性，易被识别 |
| 高保真：使用真实摄像头 | 欺骗效果好 | 成本高，难以规模化 |
| 新型：360° 全景视频模拟 PTZ | 低成本 + 高交互 | **缺乏大规模实验验证** ← 本项目要解决的 Gap |

参考论文：HoneyCam (IEEE CNS 2022)、SweetCam (CPSIoTSec 2023)、USENIX Security 2024 协同欺骗工作。

---

## 2. 核心任务分解

### Task 1：文献阅读与技术预研

- 精读 HoneyCam 和 SweetCam 两篇核心论文，掌握：
  - 360° 全景视频实现视角变换的原理（等距柱状投影 / equirectangular projection）
  - Three.js 库在 Web 端渲染球形视频的方法
  - PTZ（Pan-Tilt-Zoom）交互的前端模拟思路

### Task 2：蜜罐原型开发

**2.1 伪装登录页面**

- 仿照主流品牌 UI（TP-Link / Hikvision / Reolink 任选其一）制作假登录页
- 参考图示：真实 D-Link 摄像头 UI vs SweetCam UI（见 PPT Figure 1/2）

**2.2 360° 视频 PTZ 交互**

- 在 Web 前端通过 360° 全景素材实现：
  - 水平旋转（Pan Left / Right）
  - 垂直俯仰（Tilt Up / Down）
  - 变焦（Zoom In / Out）
- 推荐技术栈：Three.js 或等距柱状投影变换

**2.3 服务端口模拟**

- 开放真实摄像头常见端口以混淆探测：
  - HTTP：`80`（Web 界面）
  - RTSP：`554`（视频流协议）
  - 可选：ONVIF、Telnet 等额外端口

### Task 3：实验设计与部署

在**公网云服务器**上并行部署三组系统：

| 组别 | 类型 | 说明 |
|---|---|---|
| **Group A** | 高成本基线（真实设备） | 使用真实 IoT 摄像头；若硬件不可用，可使用已发表论文中的公开数据代替 |
| **Group B** | 低交互对照组 | 仅循环播放标准录制视频，**无 PTZ 交互** |
| **Group C** | 实验组（本项目产物） | 基于 360° 视频的高交互蜜罐 |

### Task 4：数据采集与分析

**运行周期**：7～10 天，记录所有日志与 pcap 文件。

**指标 1：欺骗能力（Deception Capability）**

- **转化率**：从扫描发现 → 点击 PTZ 控制按钮的比率
- **平均停留时长（Dwell Time）**
- 输出：三组对比的 **CDF（累积分布函数）图**

**指标 2：抗检测能力（Detection Resistance）**

- 使用 Shodan、Nmap 等工具对三组系统主动探测
- 对比 **Honeyscore**（被识别为蜜罐的概率，越低越好）

### Task 5：报告与答辩

最终报告需回答核心研究问题：

> **"低成本的 360° 视频蜜罐能否达到接近真实设备的欺骗效果？"**

报告内容要求：
- 系统架构说明
- 实验方法论
- 数据分析结果
- 技术挑战（视频延迟、端口仿真不完整等）及解决方案

---

## 3. 预期交付物

| # | 交付物 | 具体内容 |
|---|---|---|
| 1 | **可运行原型**（含源码） | 支持 360° 交互 PTZ 控制的摄像头蜜罐 Web 系统 |
| 2 | **对比数据集** | 三组蜜罐的攻击者 IP、交互时间戳、停留时长、Shodan 指纹 |
| 3 | **最终研究报告** | 系统架构 + 实验方法 + 数据分析 + 结论 |

---

## 4. 技术栈建议

```
前端：HTML/JS + Three.js（360° 球形视频渲染）
后端：Python (Flask/FastAPI) 或 Node.js（伪造登录、日志记录）
网络：公网云服务器（开放 80/554 等端口）
数据分析：Python (pandas, matplotlib) 生成 CDF 图
探测工具：Shodan API、Nmap
抓包：tcpdump / Wireshark（生成 pcap）
```

---

## 5. 参考文献

1. Chongqi Guan et al. **HoneyCam: Scalable High-Interaction Honeypot for IoT Cameras Based on 360-Degree Video**. IEEE CNS, 2022.
2. Zetong Zhao et al. **SweetCam: an IP Camera Honeypot**. CPSIoTSec @ CCS, 2023. https://doi.org/10.1145/3605758.3623495
3. Chongqi Guan and Guohong Cao. **Cyber-Physical Deception Through Coordinated IoT Honeypots**. USENIX Security '24. https://doi.org/10.5281/zenodo.1472979
