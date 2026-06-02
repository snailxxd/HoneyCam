# 🎯 IoT Camera Honeypot Research Project

---

## Project Overview

With the rapid proliferation of IoT cameras, these devices have become frequent targets of cyber attacks, including botnet recruitment and unauthorized surveillance. Honeypots provide a proactive defense strategy by simulating vulnerable devices to attract and analyze attackers.

However, existing camera honeypots suffer from a fundamental trade-off:

* **Low-cost solutions** (e.g., looping recorded videos):
  → Easy to deploy but lack interactivity, making them easily detectable
* **High-fidelity solutions** (real devices):
  → Realistic but expensive and difficult to scale

Recent work (e.g., *HoneyCam*, *SweetCam*) proposes using **360° panoramic media** to simulate PTZ (Pan-Tilt-Zoom) camera behavior.

---

## Research Question

> Can a low-cost 360-degree interactive honeypot achieve deception effectiveness comparable to a real IoT camera?

---

## Project Design

We implement and compare **three types of camera honeypots**:

| Group | Type               | Description                                   |
| ----- | ------------------ | --------------------------------------------- |
| **A** | High-cost baseline | Real IoT camera (or realistic simulation)     |
| **B** | Low-interaction    | Static looping video, no interaction          |
| **C** | Experimental       | 360° interactive honeypot with PTZ simulation |

All systems will be deployed on the public Internet and exposed to real-world attacks.

---