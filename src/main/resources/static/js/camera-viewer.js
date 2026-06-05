/**
 * HoneyCam — Hikvision-style 360° Camera Viewer
 * PTZ via buttons + keyboard only (no drag-to-pan).
 */
(function () {
  'use strict';

  /* ── DOM refs ─────────────────────────────────── */
  const viewport = document.getElementById('viewport');
  const hikOsd = document.getElementById('hikOsd');
  const modeBadge = document.getElementById('modeBadge');
  const previewOverlay = document.getElementById('previewOverlay');
  const speedDots = document.querySelectorAll('.speed-dot');

  /* ── Config from data attributes ──────────────── */
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
  const cameraEpochStartSec = Number(document.body.dataset.cameraEpochStart || 0);

  /* ── Constants ────────────────────────────────── */
  const CONFIG = {
    defaultFov: 75,
    minFov: 18,
    maxFov: 110,
    tiltMin: -65,
    tiltMax: 65,
    tiltMinRad: -65 * Math.PI / 180,
    tiltMaxRad: 65 * Math.PI / 180,
    inertiaDamping: 0.88,
    inertiaThreshold: 0.0003,
    logDebounceMs: 600,
    speedLevels: [0.06, 0.08, 0.1],
  };

  /* ── State ────────────────────────────────────── */
  let camera, scene, renderer, sphere;
  let liveVideoEl = null;
  let panAngle = 0;
  let tiltAngle = 0;
  let fov = CONFIG.defaultFov;
  let speedLevel = 0;
  let velocityX = 0;
  let velocityY = 0;
  let loggedPan = 0;
  let loggedTilt = 0;
  let loggedFov = fov;
  let logTimer = null;
  let buttonInterval = null;
  let buttonAction = null;
  let autoPatrolStartMs = null;
  let osdTimer = null;

  /* ── Helpers ──────────────────────────────────── */
  function clamp(num, min, max) {
    return Math.max(min, Math.min(max, num));
  }

  function randomBetween(min, max) {
    return Math.floor(Math.random() * (Math.max(min, max) - Math.min(min, max) + 1)) + Math.min(min, max);
  }

  /* ── Preview overlay ──────────────────────────── */
  function showPreviewOverlay(durationMs) {
    if (!previewOverlay) return Promise.resolve();
    previewOverlay.classList.remove('hidden');
    return new Promise((resolve) => setTimeout(resolve, Math.max(durationMs, 1)));
  }

  function hidePreviewOverlay() {
    if (previewOverlay) previewOverlay.classList.add('hidden');
  }

  /* ── Interaction logging ──────────────────────── */
  function logInteraction(actionType, extra) {
    const body = Object.assign({ actionType, panAngle, tiltAngle, fov }, extra || {});
    fetch('/api/interaction', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).catch(() => {});
  }

  function debouncedLog(actionType) {
    if (Math.abs(panAngle - loggedPan) < 0.008 &&
        Math.abs(tiltAngle - loggedTilt) < 0.008 &&
        Math.abs(fov - loggedFov) < 0.5) return;
    if (logTimer) clearTimeout(logTimer);
    logTimer = setTimeout(() => {
      loggedPan = panAngle;
      loggedTilt = tiltAngle;
      loggedFov = fov;
      logInteraction(actionType, { deltaX: 0, deltaY: 0, panAngle, tiltAngle, fov });
    }, CONFIG.logDebounceMs);
  }

  /* ── OSD pixel-canvas rendering ─────────────────── */
  // Renders text on low-res offscreen canvas, thresholds alpha to strip anti-aliasing,
  // then scales up 3× with nearest-neighbour for crisp dot-matrix look (no halo).
  const OSD_FONT_SIZE = 18;
  const OSD_CANVAS_W = 240;
  const OSD_CANVAS_H = 22;
  const OSD_SCALE = 3;
  let osdOffscreen = null;
  let osdOffCtx = null;
  let osdVisCtx = null;

  function initOsdCanvas() {
    if (!hikOsd) return;
    osdOffscreen = document.createElement('canvas');
    osdOffscreen.width = OSD_CANVAS_W;
    osdOffscreen.height = OSD_CANVAS_H;
    osdOffCtx = osdOffscreen.getContext('2d', { willReadFrequently: true });
    osdOffCtx.textBaseline = 'top';
    osdOffCtx.font = OSD_FONT_SIZE + 'px Arial, Helvetica, sans-serif';
    hikOsd.width = OSD_CANVAS_W * OSD_SCALE;
    hikOsd.height = OSD_CANVAS_H * OSD_SCALE;
    osdVisCtx = hikOsd.getContext('2d');
    osdVisCtx.imageSmoothingEnabled = false;
  }

  /** Threshold alpha channel: every pixel becomes fully opaque white or fully transparent. */
  function thresholdAlpha(imageData) {
    const data = imageData.data;
    for (let i = 0; i < data.length; i += 4) {
      // r, g, b all set to 255 (white); alpha thresholded at 50%
      if (data[i + 3] >= 128) {
        data[i] = 255;
        data[i + 1] = 255;
        data[i + 2] = 255;
        data[i + 3] = 255;
      } else {
        data[i + 3] = 0;
      }
    }
    return imageData;
  }

  function updateOsd() {
    if (!osdOffCtx || !osdVisCtx) return;
    // Camera clock: elapsed real seconds since the camera went online
    const nowSec = Date.now() / 1000;
    const cameraSec = Math.floor(nowSec - cameraEpochStartSec);
    const d = new Date(cameraSec * 1000);

    const yyyy = d.getUTCFullYear();
    const mm = String(d.getUTCMonth() + 1).padStart(2, '0');
    const dd = String(d.getUTCDate()).padStart(2, '0');
    const hh = String(d.getUTCHours()).padStart(2, '0');
    const mi = String(d.getUTCMinutes()).padStart(2, '0');
    const ss = String(d.getUTCSeconds()).padStart(2, '0');
    const text = yyyy + '-' + mm + '-' + dd + '  ' + hh + ':' + mi + ':' + ss;

    // 1. Draw text on offscreen canvas (browser anti-aliases)
    osdOffCtx.clearRect(0, 0, OSD_CANVAS_W, OSD_CANVAS_H);
    osdOffCtx.fillStyle = '#FFFFFF';
    osdOffCtx.fillText(text, 4, 4);

    // 2. Threshold alpha to strip all anti-aliasing (no gray pixels → no halo)
    const raw = osdOffCtx.getImageData(0, 0, OSD_CANVAS_W, OSD_CANVAS_H);
    osdOffCtx.putImageData(thresholdAlpha(raw), 0, 0);

    // 3. Scale up 3× with nearest-neighbour
    const dw = OSD_CANVAS_W * OSD_SCALE;
    const dh = OSD_CANVAS_H * OSD_SCALE;
    osdVisCtx.clearRect(0, 0, dw, dh);
    osdVisCtx.drawImage(osdOffscreen, 0, 0, dw, dh);
  }

  function showOsd() {
    if (hikOsd) hikOsd.classList.remove('hidden');
  }

  function updateSpeedDots() {
    speedDots.forEach((dot, i) => dot.classList.toggle('active', i <= speedLevel));
  }

  /* ── Panorama loading ─────────────────────────── */
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

      function onErr() { cleanup(); reject(new Error('failed to load video panorama')); }

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

  /* ── Three.js init ────────────────────────────── */
  async function initThree() {
    renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    const el = viewport;
    renderer.setSize(el.clientWidth, el.clientHeight);
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    el.appendChild(renderer.domElement);

    camera = new THREE.PerspectiveCamera(fov, el.clientWidth / el.clientHeight, 1, 2000);
    camera.position.set(0, 0, 0);

    scene = new THREE.Scene();
    const geometry = new THREE.SphereGeometry(500, 128, 64);
    geometry.scale(-1, 1, 1);
    const material = new THREE.MeshBasicMaterial({ color: 0x1a1a1a });
    sphere = new THREE.Mesh(geometry, material);
    scene.add(sphere);

    let loaded = false;
    const preferVideo = panoramaMode === 'video' || panoramaMode === 'auto';
    if (preferVideo) {
      try { await loadPanoramaVideoTexture(material); loaded = true; }
      catch { console.warn('Video panorama unavailable, fallback to image.'); }
    }
    if (!loaded) {
      try { await loadPanoramaImageTexture(material); loaded = true; }
      catch { console.warn('Image panorama unavailable, using dark placeholder.'); }
    }

    hidePreviewOverlay();
    // OSD appears only after the camera "stream" is live
    initOsdCanvas();
    showOsd();
    animate();
    osdTimer = setInterval(updateOsd, 1000);
    updateOsd();
  }

  /* ── Animation loop ───────────────────────────── */
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
    } else {
      if (Math.abs(velocityX) > CONFIG.inertiaThreshold || Math.abs(velocityY) > CONFIG.inertiaThreshold) {
        panAngle += velocityX;
        tiltAngle += velocityY;
        if (tiltAngle < CONFIG.tiltMinRad) { tiltAngle = CONFIG.tiltMinRad; velocityY = 0; }
        if (tiltAngle > CONFIG.tiltMaxRad) { tiltAngle = CONFIG.tiltMaxRad; velocityY = 0; }
        velocityX *= CONFIG.inertiaDamping;
        velocityY *= CONFIG.inertiaDamping;
      } else {
        velocityX = 0;
        velocityY = 0;
      }
    }

    sphere.rotation.y = panAngle;
    camera.lookAt(0, Math.sin(tiltAngle), Math.cos(tiltAngle));

    if (Math.abs(camera.fov - fov) > 0.05) {
      camera.fov += (fov - camera.fov) * 0.3;
      camera.updateProjectionMatrix();
    }
    renderer.render(scene, camera);
  }

  /* ── Button PTZ ───────────────────────────────── */
  function applyStep(getDelta) {
    const speed = CONFIG.speedLevels[speedLevel];
    const rad = Math.PI / 180;
    const d = getDelta(speed);
    if (d.pan) { panAngle += d.pan * rad; velocityX = d.pan * rad * 0.5; }
    if (d.tilt) { tiltAngle += d.tilt * rad; velocityY = d.tilt * rad * 0.5; }
    if (d.fov) { fov = clamp(fov + d.fov, CONFIG.minFov, CONFIG.maxFov); }
    tiltAngle = clamp(tiltAngle, CONFIG.tiltMinRad, CONFIG.tiltMaxRad);
    updateOsd();
  }

  function startPTZ(action, getDelta) {
    if (!ptzEnabled) return;
    stopPTZ();
    buttonAction = action;
    applyStep(getDelta);
    buttonInterval = setInterval(() => applyStep(getDelta), 40);
  }

  function stopPTZ() {
    if (!buttonInterval) return;
    clearInterval(buttonInterval);
    buttonInterval = null;
    if (buttonAction) logInteraction(buttonAction, { panAngle, tiltAngle, fov });
    buttonAction = null;
  }

  /* ── Keyboard controls ────────────────────────── */
  function onKeyDown(e) {
    if (!ptzEnabled) return;
    const s = CONFIG.speedLevels[speedLevel];
    const r = Math.PI / 180;
    switch (e.key) {
      case 'ArrowLeft':  panAngle -= s * r; break;
      case 'ArrowRight': panAngle += s * r; break;
      case 'ArrowUp':    tiltAngle += s * r; break;
      case 'ArrowDown':  tiltAngle -= s * r; break;
      case '+': case '=': fov = Math.max(CONFIG.minFov, fov - s * 2); break;
      case '-': case '_': fov = Math.min(CONFIG.maxFov, fov + s * 2); break;
      case '0': panAngle = 0; tiltAngle = 0; fov = CONFIG.defaultFov; velocityX = 0; velocityY = 0; break;
      default: return;
    }
    e.preventDefault();
    tiltAngle = clamp(tiltAngle, CONFIG.tiltMinRad, CONFIG.tiltMaxRad);
    updateOsd();
    debouncedLog('PAN');
  }

  function cycleSpeed() {
    speedLevel = (speedLevel + 1) % CONFIG.speedLevels.length;
    updateSpeedDots();
  }

  /* ── Wheel zoom ───────────────────────────────── */
  function onWheel(e) {
    if (!ptzEnabled) return;
    e.preventDefault();
    fov = clamp(fov + e.deltaY * 0.02, CONFIG.minFov, CONFIG.maxFov);
    camera.fov = fov;
    camera.updateProjectionMatrix();
    updateOsd();
    debouncedLog('ZOOM');
  }

  /* ── Resize ───────────────────────────────────── */
  function onResize() {
    if (!renderer || !camera || !viewport) return;
    const w = viewport.clientWidth;
    const h = viewport.clientHeight;
    renderer.setSize(w, h);
    camera.aspect = w / Math.max(h, 1);
    camera.updateProjectionMatrix();
  }

  /* ── Full-screen ──────────────────────────────── */
  function toggleFullScreen() {
    const el = viewport;
    if (!document.fullscreenElement) { el.requestFullscreen().catch(() => {}); }
    else { document.exitFullscreen(); }
  }

  /* ── Bind events ──────────────────────────────── */
  function bindEvents() {
    viewport.addEventListener('wheel', onWheel, { passive: false });
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('resize', onResize);
    window.addEventListener('fullscreenchange', onResize);

    // Direction pad: bind pairs to startPTZ/getDelta
    function bindPTZ(id, action, getDelta) {
      const el = document.getElementById(id);
      if (el) {
        el.addEventListener('pointerdown', () => startPTZ(action, getDelta));
      }
    }
    bindPTZ('btnTiltUp',       'TILT', (s) => ({ pan: 0, tilt: s }));
    bindPTZ('btnTiltDown',     'TILT', (s) => ({ pan: 0, tilt: -s }));
    bindPTZ('btnPanLeft',      'PAN',  (s) => ({ pan: -s, tilt: 0 }));
    bindPTZ('btnPanRight',     'PAN',  (s) => ({ pan: s, tilt: 0 }));
    bindPTZ('btnPanLeftUp',    'PAN',  (s) => ({ pan: -s, tilt: s }));
    bindPTZ('btnPanRightUp',   'PAN',  (s) => ({ pan: s, tilt: s }));
    bindPTZ('btnPanLeftDown',  'PAN',  (s) => ({ pan: -s, tilt: -s }));
    bindPTZ('btnPanRightDown', 'PAN',  (s) => ({ pan: s, tilt: -s }));
    bindPTZ('btnZoomIn',       'ZOOM', (s) => ({ pan: 0, tilt: 0, fov: -s * 3 }));
    bindPTZ('btnZoomOut',      'ZOOM', (s) => ({ pan: 0, tilt: 0, fov: s * 3 }));

    window.addEventListener('pointerup', stopPTZ);
    window.addEventListener('pointerleave', stopPTZ);

    const speedBtn = document.getElementById('btnSpeed');
    if (speedBtn) speedBtn.addEventListener('click', cycleSpeed);

    const fsBtn = document.getElementById('btnFullScreen');
    if (fsBtn) fsBtn.addEventListener('click', toggleFullScreen);

    // Logout
    const logoutBtn = document.getElementById('btnLogout');
    if (logoutBtn) logoutBtn.addEventListener('click', () => { window.location.href = '/login'; });
  }

  /* ── Startup ──────────────────────────────────── */
  function start() {
    const delay = randomBetween(previewLatencyMinMs, previewLatencyMaxMs);
    showPreviewOverlay(delay).then(() => initThree());
    bindEvents();
    updateOsd();
    updateSpeedDots();
    fetch('/api/session/start', { method: 'POST' }).catch(() => {});

    if (!ptzEnabled) {
      const panel = document.getElementById('ptzPanel');
      if (panel) panel.style.display = 'none';
      if (modeBadge) modeBadge.style.display = '';
    }
  }

  /* ── Cleanup ──────────────────────────────────── */
  window.addEventListener('beforeunload', () => {
    if (liveVideoEl) { liveVideoEl.pause(); liveVideoEl.src = ''; liveVideoEl.load(); liveVideoEl = null; }
    if (osdTimer) clearInterval(osdTimer);
    if (navigator.sendBeacon) { navigator.sendBeacon('/api/session/end'); }
    else { fetch('/api/session/end', { method: 'POST', keepalive: true }).catch(() => {}); }
  });

  /* ── Wait for Three.js ────────────────────────── */
  function waitForThree() {
    if (typeof THREE !== 'undefined') { start(); }
    else { setTimeout(waitForThree, 100); }
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', waitForThree); }
  else { waitForThree(); }
})();
