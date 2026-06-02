# Camera Honeypot Research Project

## 1. Project Overview

With the widespread deployment of Internet of Things (IoT) cameras, they have become major targets for cyberattacks, including botnet recruitment and privacy intrusion. Honeypots serve as a proactive defense mechanism by simulating vulnerable systems to attract and analyze attackers.

However, existing camera honeypots face a trade-off:

* **Low-cost solutions** (e.g., looping recorded videos):

    * Easy to deploy
    * Lack interactivity → easily detected by attackers

* **High-fidelity solutions** (e.g., real cameras):

    * Realistic interaction
    * Expensive and hard to scale

Recent research (e.g., *HoneyCam*, *SweetCam*) proposes using **360-degree panoramic video** to simulate **PTZ (Pan-Tilt-Zoom)** functionality, aiming to balance realism and cost.

### Core Research Question

Can a **low-cost 360-degree video-based honeypot** achieve deception effectiveness comparable to a real IoT camera?

---

## 2. Technical Stack

### Backend

* Java 23
* Spring Boot 4.x
* Spring Web (REST APIs)
* Spring Security (optional for login simulation logging)

### Frontend

* HTML + CSS + JavaScript
* Three.js (for 360° rendering and interaction)
* WebSocket (optional for real-time interaction logging)

### Networking & System

* HTTP (Port 80)
* RTSP (Port 554)
* Linux server (Ubuntu recommended)
* Packet capture: tcpdump / Wireshark

---

## 3. Project Tasks

---

### 3.1 Literature Review & Preparation

Study and understand:

* HoneyCam
* SweetCam

Focus on:

* 360° video representation (equirectangular projection)
* View transformation techniques
* PTZ simulation via frontend rendering
* Detection evasion strategies

Expected Outcome:

* Understand how to map user interaction → camera movement simulation

---

### 3.2 Build the Honeypot Prototype

#### (1) 360° Camera Simulation

* Use 360° panoramic images or videos
* Render using Three.js
* Support:

    * Pan (left/right)
    * Tilt (up/down)
    * Zoom (FOV adjustment)

#### (2) Fake Login Page

Simulate a real camera vendor UI:

* Example brands:

    * TP-Link
    * Hikvision
    * Reolink

Requirements:

* Realistic UI design
* Login form (username/password)
* Log all credential attempts

#### (3) Backend Design (Spring Boot)

Key modules:

* `AuthController`

    * Handle login requests
    * Log attacker input

* `InteractionController`

    * Record PTZ operations (clicks, drags, zoom)

* `LogService`

    * Store:

        * IP address
        * User-Agent
        * Timestamp
        * Actions

* `Pcap Integration`

    * Capture network-level traffic

#### (4) Port Simulation

Expose:

* Port 80 (HTTP web interface)
* Port 554 (fake RTSP service)

Optional:

* Simulate RTSP handshake behavior
* Return dummy stream metadata

---

### 3.3 Experiment Design & Deployment

Deploy on public cloud (e.g., AWS / Alibaba Cloud / Tencent Cloud):

#### Group A — High-cost Baseline

* Real IoT camera
  OR
* Simulated using real dataset (if hardware unavailable)

#### Group B — Low-interaction Control

* Static looping video
* No PTZ interaction

#### Group C — Experimental System

* 360° interactive honeypot
* Full PTZ simulation

Requirements:

* Same exposure level (ports, IP visibility)
* Run in parallel
* Deployment duration: **7–10 days**

---

### 3.4 Data Collection & Analysis

#### Data Collection

* HTTP logs
* Interaction logs
* PCAP files
* Scan attempts (Nmap, bots, etc.)

---

#### Metrics

### (1) Deception Capability

* **Conversion Rate**

    * Scanning → Interaction (e.g., clicking PTZ buttons)

* **Dwell Time**

    * Time attacker stays on system

* **CDF Plot**

    * Compare dwell time distribution across 3 groups

---

### (2) Detection Resistance

Use tools:

* Shodan
* Nmap

Evaluate:

* Honeypot detection probability
* Service fingerprint similarity

Metric:

* **Honeyscore** (lower = harder to detect)

---

## 4. System Architecture

```
[Attacker]
    ↓
[Public IP]
    ↓
[Spring Boot Backend]
    ├── Login Simulation
    ├── Interaction Logging
    ├── API Layer
    ↓
[Frontend (Three.js)]
    ├── 360° Rendering
    ├── PTZ Simulation
    ↓
[Logging System]
    ├── Database / File
    ├── PCAP Capture
```

---

## 5. Key Technical Challenges

### 1. Video Latency

* Problem: interaction delay reduces realism
* Solution:

    * preload frames
    * reduce resolution
    * use image-based 360° instead of video if needed

---

### 2. PTZ Realism

* Problem: fake movement may look unnatural
* Solution:

    * smooth interpolation
    * inertia simulation
    * realistic zoom (FOV instead of scaling)

---

### 3. Protocol Emulation

* Problem: incomplete RTSP/HTTP behavior exposes honeypot
* Solution:

    * mimic headers of real devices
    * replay real traffic patterns

---

### 4. Detection by Scanners

* Problem: Nmap fingerprint mismatch
* Solution:

    * customize TCP/IP stack behavior (advanced)
    * fake banners consistent with real devices

---

## 6. Final Report Requirements

Your report must answer:

> Can a low-cost 360-degree video honeypot achieve deception performance close to a real device?

Include:

* Experimental setup
* Data analysis (graphs, CDF)
* Comparison across 3 groups
* Detection resistance evaluation
* Limitations
* Future improvements

---

## 7. Deliverables

* Source code (frontend + backend)
* Deployment scripts
* Logs & PCAP data
* Final report

---
