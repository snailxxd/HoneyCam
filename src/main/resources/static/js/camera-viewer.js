/**
 * HoneyCam — 360° Camera Viewer
 * Supports panorama video (preferred) and image fallback.
 * Includes startup deception delay and PTZ interaction logging.
 */
(function () {
  'use strict';

  const viewport = document.getElementById('viewport');
  const osdPan = document.getElementById('osdPan');
  const osdTilt = document.getElementById('osdTilt');
  const osdZoom = document.getElementById('osdZoom');
  const osdTime = document.getElementById('osdTime');
  const dragHint = document.getElementById('dragHint');
  const previewOverlay = document.getElementById('previewOverlay');
  const previewProgressBar = document.getElementById('previewProgressBar');
  const speedDots = document.querySelectorAll('.ptz-speed-dot');

  const ptzEnabled = (document.body.dataset.ptzEnabled || 'true') === 'true';
  const panoramaMode = (document.body.dataset.panoramaMode || 'auto').toLowerCase();
  const panoramaVideoUrl = document.body.dataset.panoramaVideoUrl || '/media/360-demo.mp4';
  const panoramaImageUrl = document.body.dataset.panoramaImageUrl || 'https://threejs.org/examples/textures/2294472375_24a3b8ef46_o.jpg';
  const previewLatencyMinMs = Number(document.body.dataset.previewLatencyMinMs || 300);
  const previewLatencyMaxMs = Number(document.body.dataset.previewLatencyMaxMs || 1400);
  const autoPatrolEnabled = (document.body.dataset.autoPatrolEnabled || 'false') === 'true';
  const autoPatrolPanDegrees = Number(document.body.dataset.autoPatrolPanDegrees || 35);
  const autoPatrolTiltDegrees = Number(document.body.dataset.autoPatrolTiltDegrees || 8);
  const autoPatrolCycleSeconds = Number(document.body.dataset.autoPatrolCycleSeconds || 18);

  const CONFIG = {
    defaultFov: 75,
    minFov: 18,
    maxFov: 110,
    tiltMin: -65,
    tiltMax: 65,
    panSensitivity: 0.0035,
    tiltSensitivity: 0.0035,
    zoomSensitivity: 0.08,
    inertiaDamping: 0.92,
    inertiaThreshold: 0.0005,
    logDebounce: 600,
    speedLevels: [0.3, 0.8, 1.8],
  };

  let camera;
  let scene;
  let renderer;
  let sphere;
  let liveVideoEl = null;
  let panAngle = 0;
  let tiltAngle = 0;
  let fov = CONFIG.defaultFov;
  let speedLevel = 0;
  let velocityX = 0;
  let velocityY = 0;
  let isDragging = false;
  let lastMouseX = 0;
  let lastMouseY = 0;
  let lastMoveTime = 0;
  let loggedPan = 0;
  let loggedTilt = 0;
  let loggedFov = fov;
  let logTimer = null;
  let buttonInterval = null;
  let buttonAction = null;
  let autoPatrolStartMs = null;

  function clamp(num, min, max) {
    return Math.max(min, Math.min(max, num));
  }

  function randomBetween(min, max) {
    const safeMin = Math.min(min, max);
    const safeMax = Math.max(min, max);
    return Math.floor(Math.random() * (safeMax - safeMin + 1)) + safeMin;
  }

  function showPreviewOverlay(durationMs) {
    if (!previewOverlay || !previewProgressBar) return Promise.resolve();
    previewOverlay.classList.remove('hidden');
    previewProgressBar.style.width = '0%';
    const start = performance.now();
    return new Promise((resolve) => {
      function step(now) {
        const progress = clamp((now - start) / Math.max(durationMs, 1), 0, 1);
        previewProgressBar.style.width = (progress * 100).toFixed(0) + '%';
        if (progress < 1) {
          requestAnimationFrame(step);
          return;
        }
        resolve();
      }
      requestAnimationFrame(step);
    });
  }

  function hidePreviewOverlay() {
    if (previewOverlay) previewOverlay.classList.add('hidden');
  }

  function logInteraction(actionType, extra) {
    const body = Object.assign({
      actionType,
      panAngle,
      tiltAngle,
      fov,
    }, extra || {});
    fetch('/api/interaction', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).catch(() => {});
  }

  function debouncedLog(actionType) {
    const panChanged = Math.abs(panAngle - loggedPan) > 0.008;
    const tiltChanged = Math.abs(tiltAngle - loggedTilt) > 0.008;
    const fovChanged = Math.abs(fov - loggedFov) > 0.5;
    if (!panChanged && !tiltChanged && !fovChanged) return;
    if (logTimer) clearTimeout(logTimer);
    logTimer = setTimeout(() => {
      loggedPan = panAngle;
      loggedTilt = tiltAngle;
      loggedFov = fov;
      logInteraction(actionType, { deltaX: 0, deltaY: 0, panAngle, tiltAngle, fov });
    }, CONFIG.logDebounce);
  }

  function updateOsd() {
    const panDeg = ((panAngle * 180 / Math.PI) % 360 + 360) % 360;
    osdPan.textContent = panDeg.toFixed(1) + '°';
    osdTilt.textContent = (tiltAngle * 180 / Math.PI).toFixed(1) + '°';
    osdZoom.textContent = fov.toFixed(1) + '°';
    osdTime.textContent = new Date().toTimeString().slice(0, 8);
  }

  function updateSpeedDots() {
    speedDots.forEach((dot, i) => dot.classList.toggle('active', i <= speedLevel));
  }

  function loadPanoramaImageTexture(material) {
    return new Promise((resolve, reject) => {
      const loader = new THREE.TextureLoader();
      loader.load(
        panoramaImageUrl,
        (texture) => {
          texture.colorSpace = THREE.SRGBColorSpace;
          material.map = texture;
          material.color.set(0xffffff);
          material.needsUpdate = true;
          resolve('image');
        },
        undefined,
        () => reject(new Error('failed to load image panorama'))
      );
    });
  }

  function loadPanoramaVideoTexture(material) {
    return new Promise((resolve, reject) => {
      const video = document.createElement('video');
      video.src = panoramaVideoUrl;
      video.crossOrigin = 'anonymous';
      video.loop = true;
      video.muted = true;
      video.playsInline = true;
      video.preload = 'auto';

      const failTimer = setTimeout(() => {
        cleanup();
        reject(new Error('video load timeout'));
      }, 5500);

      function cleanup() {
        clearTimeout(failTimer);
        video.removeEventListener('loadeddata', onReady);
        video.removeEventListener('error', onErr);
      }

      function onErr() {
        cleanup();
        reject(new Error('failed to load video panorama'));
      }

      function onReady() {
        cleanup();
        const videoTexture = new THREE.VideoTexture(video);
        videoTexture.colorSpace = THREE.SRGBColorSpace;
        videoTexture.minFilter = THREE.LinearFilter;
        videoTexture.magFilter = THREE.LinearFilter;
        material.map = videoTexture;
        material.color.set(0xffffff);
        material.needsUpdate = true;
        liveVideoEl = video;
        video.play().catch(() => {});
        resolve('video');
      }

      video.addEventListener('loadeddata', onReady, { once: true });
      video.addEventListener('error', onErr, { once: true });
      video.load();
    });
  }

  async function initThree() {
    renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(window.innerWidth, window.innerHeight - 40);
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    viewport.appendChild(renderer.domElement);

    camera = new THREE.PerspectiveCamera(fov, viewport.clientWidth / viewport.clientHeight, 1, 2000);
    camera.position.set(0, 0, 0);

    scene = new THREE.Scene();
    const geometry = new THREE.SphereGeometry(500, 128, 64);
    geometry.scale(-1, 1, 1);
    const material = new THREE.MeshBasicMaterial({ color: 0x1a1a1a });
    sphere = new THREE.Mesh(geometry, material);
    scene.add(sphere);

    viewport.classList.add('loading');
    let loaded = false;
    const preferVideo = panoramaMode === 'video' || panoramaMode === 'auto';

    if (preferVideo) {
      try {
        await loadPanoramaVideoTexture(material);
        loaded = true;
      } catch {
        console.warn('Video panorama unavailable, fallback to image.');
      }
    }

    if (!loaded) {
      try {
        await loadPanoramaImageTexture(material);
        loaded = true;
      } catch {
        console.warn('Image panorama unavailable, using dark placeholder.');
      }
    }

    viewport.classList.remove('loading');
    hidePreviewOverlay();
    dragHint.style.opacity = '1';
    if (!loaded) {
      dragHint.textContent = 'Panorama media load failed, using placeholder.';
    }
    setTimeout(() => { dragHint.style.opacity = '0'; }, 5000);

    animate();
  }

  function animate() {
    requestAnimationFrame(animate);

    if (!ptzEnabled && autoPatrolEnabled) {
      if (autoPatrolStartMs === null) autoPatrolStartMs = performance.now();
      const elapsedSec = (performance.now() - autoPatrolStartMs) / 1000;
      const cycle = Math.max(autoPatrolCycleSeconds, 2);
      const phase = (2 * Math.PI * elapsedSec) / cycle;
      panAngle = (autoPatrolPanDegrees * Math.sin(phase)) * Math.PI / 180;
      tiltAngle = (autoPatrolTiltDegrees * Math.sin(phase / 2)) * Math.PI / 180;
      fov = CONFIG.defaultFov;
      updateOsd();
    } else if (!isDragging) {
      if (Math.abs(velocityX) > CONFIG.inertiaThreshold || Math.abs(velocityY) > CONFIG.inertiaThreshold) {
        panAngle += velocityX;
        tiltAngle += velocityY;
        const tiltMinRad = CONFIG.tiltMin * Math.PI / 180;
        const tiltMaxRad = CONFIG.tiltMax * Math.PI / 180;
        if (tiltAngle < tiltMinRad) { tiltAngle = tiltMinRad; velocityY = 0; }
        if (tiltAngle > tiltMaxRad) { tiltAngle = tiltMaxRad; velocityY = 0; }
        velocityX *= CONFIG.inertiaDamping;
        velocityY *= CONFIG.inertiaDamping;
        updateOsd();
        debouncedLog('DRAG');
      } else {
        velocityX = 0;
        velocityY = 0;
      }
    }

    sphere.rotation.y = panAngle;
    const lookY = Math.sin(tiltAngle);
    const lookZ = Math.cos(tiltAngle);
    camera.lookAt(0, lookY, lookZ);

    if (Math.abs(camera.fov - fov) > 0.05) {
      camera.fov += (fov - camera.fov) * 0.3;
      camera.updateProjectionMatrix();
    }
    renderer.render(scene, camera);
  }

  function getEventPos(e) {
    if (e.touches && e.touches.length > 0) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
    if (e.changedTouches && e.changedTouches.length > 0) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
    return { x: e.clientX, y: e.clientY };
  }

  function onPointerDown(e) {
    if (!ptzEnabled) return;
    e.preventDefault();
    isDragging = true;
    velocityX = 0;
    velocityY = 0;
    const pos = getEventPos(e);
    lastMouseX = pos.x;
    lastMouseY = pos.y;
    lastMoveTime = performance.now();
    viewport.style.cursor = 'grabbing';
    dragHint.style.opacity = '0';
  }

  function onPointerMove(e) {
    if (!ptzEnabled) return;
    e.preventDefault();
    const pos = getEventPos(e);
    const dx = pos.x - lastMouseX;
    const dy = pos.y - lastMouseY;
    const now = performance.now();
    const dt = Math.max(now - lastMoveTime, 1);

    if (isDragging) {
      panAngle += dx * CONFIG.panSensitivity;
      tiltAngle -= dy * CONFIG.tiltSensitivity;
      const tiltMinRad = CONFIG.tiltMin * Math.PI / 180;
      const tiltMaxRad = CONFIG.tiltMax * Math.PI / 180;
      tiltAngle = clamp(tiltAngle, tiltMinRad, tiltMaxRad);
      velocityX = (dx * CONFIG.panSensitivity) * (16.67 / dt);
      velocityY = -(dy * CONFIG.tiltSensitivity) * (16.67 / dt);
      updateOsd();
    }

    lastMouseX = pos.x;
    lastMouseY = pos.y;
    lastMoveTime = now;
  }

  function onPointerUp(e) {
    if (!ptzEnabled || !isDragging) return;
    isDragging = false;
    viewport.style.cursor = 'grab';
    const pos = getEventPos(e);
    logInteraction('DRAG', {
      deltaX: pos.x - lastMouseX || 0,
      deltaY: pos.y - lastMouseY || 0,
      panAngle,
      tiltAngle,
      fov,
    });
  }

  function onWheel(e) {
    if (!ptzEnabled) return;
    e.preventDefault();
    fov = clamp(fov + e.deltaY * CONFIG.zoomSensitivity, CONFIG.minFov, CONFIG.maxFov);
    camera.fov = fov;
    camera.updateProjectionMatrix();
    updateOsd();
    debouncedLog('ZOOM');
  }

  function applyButtonStep(getDelta) {
    const speed = CONFIG.speedLevels[speedLevel];
    const radPerDeg = Math.PI / 180;
    const delta = getDelta(speed);
    if (delta.pan) {
      panAngle += delta.pan * radPerDeg;
      velocityX = delta.pan * radPerDeg * 0.5;
    }
    if (delta.tilt) {
      tiltAngle += delta.tilt * radPerDeg;
      velocityY = delta.tilt * radPerDeg * 0.5;
    }
    if (delta.fov) {
      fov = clamp(fov + delta.fov, CONFIG.minFov, CONFIG.maxFov);
    }
    const tMin = CONFIG.tiltMin * radPerDeg;
    const tMax = CONFIG.tiltMax * radPerDeg;
    tiltAngle = clamp(tiltAngle, tMin, tMax);
    updateOsd();
  }

  function startButtonPTZ(action, getDelta) {
    if (!ptzEnabled) return;
    stopButtonPTZ();
    buttonAction = action;
    applyButtonStep(getDelta);
    buttonInterval = setInterval(() => applyButtonStep(getDelta), 40);
  }

  function stopButtonPTZ() {
    if (!buttonInterval) return;
    clearInterval(buttonInterval);
    buttonInterval = null;
    logInteraction(buttonAction || 'PAN', { panAngle, tiltAngle, fov });
    buttonAction = null;
  }

  function onKeyDown(e) {
    if (!ptzEnabled) return;
    const speed = CONFIG.speedLevels[speedLevel];
    const radPerDeg = Math.PI / 180;
    switch (e.key) {
      case 'ArrowLeft': panAngle -= speed * radPerDeg; break;
      case 'ArrowRight': panAngle += speed * radPerDeg; break;
      case 'ArrowUp': tiltAngle += speed * radPerDeg; break;
      case 'ArrowDown': tiltAngle -= speed * radPerDeg; break;
      case '+':
      case '=': fov = Math.max(CONFIG.minFov, fov - speed * 2); break;
      case '-':
      case '_': fov = Math.min(CONFIG.maxFov, fov + speed * 2); break;
      case '0':
        panAngle = 0;
        tiltAngle = 0;
        fov = CONFIG.defaultFov;
        velocityX = 0;
        velocityY = 0;
        break;
      default: return;
    }
    e.preventDefault();
    const tMin = CONFIG.tiltMin * radPerDeg;
    const tMax = CONFIG.tiltMax * radPerDeg;
    tiltAngle = clamp(tiltAngle, tMin, tMax);
    updateOsd();
    debouncedLog('PAN');
  }

  function cycleSpeed() {
    speedLevel = (speedLevel + 1) % CONFIG.speedLevels.length;
    updateSpeedDots();
  }

  function onResize() {
    if (!renderer || !camera) return;
    const w = window.innerWidth;
    const h = window.innerHeight - 40;
    renderer.setSize(w, h);
    camera.aspect = w / Math.max(h, 1);
    camera.updateProjectionMatrix();
  }

  function bindEvents() {
    viewport.addEventListener('mousedown', onPointerDown);
    window.addEventListener('mousemove', onPointerMove);
    window.addEventListener('mouseup', onPointerUp);
    viewport.addEventListener('touchstart', onPointerDown, { passive: false });
    window.addEventListener('touchmove', onPointerMove, { passive: false });
    window.addEventListener('touchend', onPointerUp);
    viewport.addEventListener('wheel', onWheel, { passive: false });
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('resize', onResize);

    document.getElementById('btnPanLeft').addEventListener('pointerdown', () => startButtonPTZ('PAN', (s) => ({ pan: -s, tilt: 0 })));
    document.getElementById('btnPanRight').addEventListener('pointerdown', () => startButtonPTZ('PAN', (s) => ({ pan: s, tilt: 0 })));
    document.getElementById('btnTiltUp').addEventListener('pointerdown', () => startButtonPTZ('TILT', (s) => ({ pan: 0, tilt: s })));
    document.getElementById('btnTiltDown').addEventListener('pointerdown', () => startButtonPTZ('TILT', (s) => ({ pan: 0, tilt: -s })));
    document.getElementById('btnZoomIn').addEventListener('pointerdown', () => startButtonPTZ('ZOOM', (s) => ({ pan: 0, tilt: 0, fov: -s * 3 })));
    document.getElementById('btnZoomOut').addEventListener('pointerdown', () => startButtonPTZ('ZOOM', (s) => ({ pan: 0, tilt: 0, fov: s * 3 })));
    window.addEventListener('pointerup', stopButtonPTZ);
    window.addEventListener('pointerleave', stopButtonPTZ);
    document.getElementById('btnSpeed')?.addEventListener('click', cycleSpeed);
  }

  function start() {
    const bootDelay = randomBetween(previewLatencyMinMs, previewLatencyMaxMs);
    showPreviewOverlay(bootDelay).then(() => initThree());
    bindEvents();
    updateOsd();
    updateSpeedDots();
    fetch('/api/session/start', { method: 'POST' }).catch(() => {});
    if (!ptzEnabled) {
      const panel = document.getElementById('ptzPanel');
      if (panel) panel.style.display = 'none';
      dragHint.textContent = autoPatrolEnabled
        ? 'Low-interaction mode: auto patrol loop running'
        : 'Low-interaction mode: PTZ disabled';
      dragHint.style.opacity = '0.95';
    }
  }

  window.addEventListener('beforeunload', () => {
    if (liveVideoEl) {
      liveVideoEl.pause();
      liveVideoEl.src = '';
      liveVideoEl.load();
      liveVideoEl = null;
    }
    if (navigator.sendBeacon) {
      navigator.sendBeacon('/api/session/end');
    } else {
      fetch('/api/session/end', { method: 'POST', keepalive: true }).catch(() => {});
    }
  });

  function waitForThree() {
    if (typeof THREE !== 'undefined') {
      start();
    } else {
      setTimeout(waitForThree, 100);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', waitForThree);
  } else {
    waitForThree();
  }
})();
