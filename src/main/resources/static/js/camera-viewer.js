/**
 * HoneyCam — 360° Camera Viewer
 * Three.js equirectangular renderer with PTZ simulation.
 *
 * Features:
 *  - Equirectangular 360° panorama on an inverted sphere
 *  - Pan  (drag left/right) → sphere Y rotation
 *  - Tilt (drag up/down)   → camera vertical angle (clamped)
 *  - Zoom (scroll wheel)   → FOV adjustment
 *  - Inertia: velocity tracking with exponential decay
 *  - Touch support for mobile
 *  - All interactions logged to backend API
 *
 * Dependencies: Three.js (CDN)
 */
(function () {
  'use strict';

  // ── Configuration ──────────────────────────────────────────────
  const CONFIG = {
    // Equirectangular 360° image URL
    // Replace with your own 360° photo for deployment:
    //   e.g. '/images/360-scene.jpg'
    textureUrl: 'https://threejs.org/examples/textures/2294472375_24a3b8ef46_o.jpg',

    // Camera settings
    defaultFov: 75,          // default field of view (degrees)
    minFov: 18,              // max zoom in
    maxFov: 110,             // max zoom out
    tiltMin: -65,            // max tilt up   (degrees)
    tiltMax: 65,             // max tilt down (degrees)

    // Sensitivity
    panSensitivity: 0.0035,
    tiltSensitivity: 0.0035,
    zoomSensitivity: 0.08,

    // Inertia
    inertiaDamping: 0.92,    // velocity multiplier per frame (0..1, lower = faster stop)
    inertiaThreshold: 0.0005, // stop when velocity below this

    // Logging debounce (ms)
    logDebounce: 600,

    // Speed levels for button PTZ
    speedLevels: [0.3, 0.8, 1.8],  // degrees per frame at each level
  };

  // ── DOM refs ────────────────────────────────────────────────────
  const viewport = document.getElementById('viewport');
  const osdPan   = document.getElementById('osdPan');
  const osdTilt  = document.getElementById('osdTilt');
  const osdZoom  = document.getElementById('osdZoom');
  const osdTime  = document.getElementById('osdTime');
  const dragHint = document.getElementById('dragHint');
  const speedDots = document.querySelectorAll('.ptz-speed-dot');

  // ── State ───────────────────────────────────────────────────────
  let camera, scene, renderer, sphere;
  let panAngle = 0;         // sphere Y rotation (radians)
  let tiltAngle = 0;        // camera vertical offset (radians)
  let fov = CONFIG.defaultFov;
  let speedLevel = 0;       // 0=slow, 1=medium, 2=fast
  let animationId;

  // ── Velocity / inertia ──────────────────────────────────────────
  let velocityX = 0;        // pan velocity
  let velocityY = 0;        // tilt velocity
  let isDragging = false;
  let lastMouseX = 0, lastMouseY = 0;
  let lastMoveTime = 0;
  let loggedPan = 0, loggedTilt = 0, loggedFov = fov; // for debounce

  // ── Logging ─────────────────────────────────────────────────────
  let logTimer = null;

  function logInteraction(actionType, extra) {
    const body = Object.assign({
      actionType: actionType,
      panAngle: panAngle,
      tiltAngle: tiltAngle,
      fov: fov,
    }, extra || {});
    fetch('/api/interaction', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).catch(() => {}); // fire-and-forget
  }

  /** Debounced log — only fires when PTZ values stabilize */
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
      logInteraction(actionType, {
        deltaX: 0, deltaY: 0,
        panAngle: panAngle,
        tiltAngle: tiltAngle,
        fov: fov,
      });
    }, CONFIG.logDebounce);
  }

  // ── OSD update ──────────────────────────────────────────────────
  function updateOsd() {
    const panDeg = ((panAngle * 180 / Math.PI) % 360 + 360) % 360;
    osdPan.textContent  = panDeg.toFixed(1) + '°';
    osdTilt.textContent = (tiltAngle * 180 / Math.PI).toFixed(1) + '°';
    osdZoom.textContent = fov.toFixed(1) + '°';
    const now = new Date();
    osdTime.textContent = now.toTimeString().slice(0, 8);
  }

  function updateSpeedDots() {
    speedDots.forEach((d, i) => d.classList.toggle('active', i <= speedLevel));
  }

  // ── Three.js setup ──────────────────────────────────────────────
  function initThree() {
    // Renderer
    renderer = new THREE.WebGLRenderer({ antialias: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    renderer.setSize(window.innerWidth, window.innerHeight - 40);
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    viewport.appendChild(renderer.domElement);

    // Camera
    camera = new THREE.PerspectiveCamera(fov, viewport.clientWidth / viewport.clientHeight, 1, 2000);
    camera.position.set(0, 0, 0);

    // Scene
    scene = new THREE.Scene();

    // Inverted sphere for equirectangular projection
    const geometry = new THREE.SphereGeometry(500, 128, 64);
    // Flip normals inward so texture renders on the inside
    geometry.scale(-1, 1, 1);

    // Start with a dark material while texture loads
    const material = new THREE.MeshBasicMaterial({ color: 0x1a1a1a });
    sphere = new THREE.Mesh(geometry, material);
    scene.add(sphere);

    // Load 360° texture
    viewport.classList.add('loading');
    const loader = new THREE.TextureLoader();
    loader.load(
      CONFIG.textureUrl,
      (texture) => {
        texture.colorSpace = THREE.SRGBColorSpace;
        material.map = texture;
        material.color.set(0xffffff);
        material.needsUpdate = true;
        viewport.classList.remove('loading');
        dragHint.style.opacity = '1';
        setTimeout(() => { dragHint.style.opacity = '0'; }, 5000);
      },
      undefined, // onProgress
      () => {
        // On error — still show something (dark sphere)
        viewport.classList.remove('loading');
        console.warn('Failed to load 360° texture. Using dark placeholder.');
      }
    );

    // Lighting (not strictly needed for BasicMaterial, but kept for future)
    // scene.add(new THREE.AmbientLight(0xffffff, 1));

    // Start render loop
    animate();
  }

  // ── Animation loop ──────────────────────────────────────────────
  function animate() {
    animationId = requestAnimationFrame(animate);

    // Apply inertia
    if (!isDragging) {
      if (Math.abs(velocityX) > CONFIG.inertiaThreshold ||
          Math.abs(velocityY) > CONFIG.inertiaThreshold) {
        panAngle  += velocityX;
        tiltAngle += velocityY;

        // Clamp tilt
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

    // Pan: rotate the sphere around Y axis (left/right)
    sphere.rotation.y = panAngle;

    // Tilt: camera looks up/down from center of sphere
    const lookY = Math.sin(tiltAngle);
    const lookZ = Math.cos(tiltAngle);
    camera.lookAt(0, lookY, lookZ);

    // Update FOV
    if (Math.abs(camera.fov - fov) > 0.05) {
      camera.fov += (fov - camera.fov) * 0.3;
      camera.updateProjectionMatrix();
    }

    renderer.render(scene, camera);
  }

  // ── Event handlers ──────────────────────────────────────────────

  function getEventPos(e) {
    if (e.touches && e.touches.length > 0) {
      return { x: e.touches[0].clientX, y: e.touches[0].clientY };
    }
    if (e.changedTouches && e.changedTouches.length > 0) {
      return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
    }
    return { x: e.clientX, y: e.clientY };
  }

  function onPointerDown(e) {
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
    e.preventDefault();
    const pos = getEventPos(e);
    const dx = pos.x - lastMouseX;
    const dy = pos.y - lastMouseY;
    const now = performance.now();
    const dt = Math.max(now - lastMoveTime, 1); // ms, min 1 to avoid div-by-0

    if (isDragging) {
      // Pan: left/right drag (sign flipped for inverted sphere)
      panAngle += dx * CONFIG.panSensitivity;
      // Tilt: up/down drag
      tiltAngle -= dy * CONFIG.tiltSensitivity;

      // Clamp tilt
      const tiltMinRad = CONFIG.tiltMin * Math.PI / 180;
      const tiltMaxRad = CONFIG.tiltMax * Math.PI / 180;
      tiltAngle = Math.max(tiltMinRad, Math.min(tiltMaxRad, tiltAngle));

      // Compute velocity for inertia (degrees per frame at ~60fps)
      if (dt > 0) {
        velocityX = (dx * CONFIG.panSensitivity)  * (16.67 / dt);
        velocityY = -(dy * CONFIG.tiltSensitivity) * (16.67 / dt);
      }

      updateOsd();
    }

    lastMouseX = pos.x;
    lastMouseY = pos.y;
    lastMoveTime = now;
  }

  function onPointerUp(e) {
    if (!isDragging) return;
    isDragging = false;
    viewport.style.cursor = 'grab';

    // Log the final position after drag
    const pos = getEventPos(e);
    logInteraction('DRAG', {
      deltaX: pos.x - lastMouseX || 0,
      deltaY: pos.y - lastMouseY || 0,
      panAngle: panAngle,
      tiltAngle: tiltAngle,
      fov: fov,
    });
  }

  function onWheel(e) {
    e.preventDefault();
    fov += e.deltaY * CONFIG.zoomSensitivity;
    fov = Math.max(CONFIG.minFov, Math.min(CONFIG.maxFov, fov));
    camera.fov = fov;
    camera.updateProjectionMatrix();
    updateOsd();
    debouncedLog('ZOOM');
  }

  // ── Button PTZ ──────────────────────────────────────────────────
  let buttonInterval = null;
  let buttonAction = null;

  function startButtonPTZ(action, getDelta) {
    stopButtonPTZ();
    buttonAction = action;
    // Immediate step
    applyButtonStep(getDelta);
    // Repeat
    buttonInterval = setInterval(() => applyButtonStep(getDelta), 40);
  }

  function applyButtonStep(getDelta) {
    const speed = CONFIG.speedLevels[speedLevel]; // degrees/frame
    const radPerDeg = Math.PI / 180;
    const d = getDelta(speed);

    if (d.pan) {
      panAngle += d.pan * radPerDeg;
      velocityX = d.pan * radPerDeg * 0.5;
    }
    if (d.tilt) {
      tiltAngle += d.tilt * radPerDeg;
      velocityY = d.tilt * radPerDeg * 0.5;
    }
    if (d.fov) {
      fov += d.fov;
      fov = Math.max(CONFIG.minFov, Math.min(CONFIG.maxFov, fov));
    }

    // Clamp tilt
    const tMin = CONFIG.tiltMin * radPerDeg;
    const tMax = CONFIG.tiltMax * radPerDeg;
    tiltAngle = Math.max(tMin, Math.min(tMax, tiltAngle));

    updateOsd();
  }

  function stopButtonPTZ() {
    if (buttonInterval) {
      clearInterval(buttonInterval);
      buttonInterval = null;
      // Log final state
      logInteraction(buttonAction || 'PAN', {
        panAngle, tiltAngle, fov,
      });
      buttonAction = null;
    }
  }

  // ── Keyboard shortcuts ──────────────────────────────────────────
  function onKeyDown(e) {
    const speed = CONFIG.speedLevels[speedLevel];
    const radPerDeg = Math.PI / 180;
    switch (e.key) {
      case 'ArrowLeft':  panAngle -= speed * radPerDeg; break;
      case 'ArrowRight': panAngle += speed * radPerDeg; break;
      case 'ArrowUp':    tiltAngle += speed * radPerDeg; break;
      case 'ArrowDown':  tiltAngle -= speed * radPerDeg; break;
      case '+': case '=': fov = Math.max(CONFIG.minFov, fov - speed * 2); break;
      case '-': case '_': fov = Math.min(CONFIG.maxFov, fov + speed * 2); break;
      case '0': // Reset view
        panAngle = 0; tiltAngle = 0; fov = CONFIG.defaultFov;
        velocityX = 0; velocityY = 0;
        break;
      default: return;
    }
    e.preventDefault();
    // Clamp
    const tMin = CONFIG.tiltMin * radPerDeg;
    const tMax = CONFIG.tiltMax * radPerDeg;
    tiltAngle = Math.max(tMin, Math.min(tMax, tiltAngle));
    updateOsd();
    debouncedLog('PAN');
  }

  // ── Speed toggle ────────────────────────────────────────────────
  function cycleSpeed() {
    speedLevel = (speedLevel + 1) % CONFIG.speedLevels.length;
    updateSpeedDots();
  }

  // ── Resize handler ──────────────────────────────────────────────
  function onResize() {
    const w = window.innerWidth;
    const h = window.innerHeight - 40; // header height
    renderer.setSize(w, h);
    camera.aspect = w / Math.max(h, 1);
    camera.updateProjectionMatrix();
  }

  // ── Bind events ─────────────────────────────────────────────────
  function bindEvents() {
    // Mouse
    viewport.addEventListener('mousedown', onPointerDown);
    window.addEventListener('mousemove', onPointerMove);
    window.addEventListener('mouseup', onPointerUp);

    // Touch
    viewport.addEventListener('touchstart', onPointerDown, { passive: false });
    window.addEventListener('touchmove', onPointerMove, { passive: false });
    window.addEventListener('touchend', onPointerUp);

    // Wheel
    viewport.addEventListener('wheel', onWheel, { passive: false });

    // Keyboard
    window.addEventListener('keydown', onKeyDown);

    // Resize
    window.addEventListener('resize', onResize);

    // PTZ buttons — pointer events for continuous press
    document.getElementById('btnPanLeft') .addEventListener('pointerdown', () => startButtonPTZ('PAN',  (s) => ({ pan: -s, tilt: 0 })));
    document.getElementById('btnPanRight').addEventListener('pointerdown', () => startButtonPTZ('PAN',  (s) => ({ pan:  s, tilt: 0 })));
    document.getElementById('btnTiltUp')  .addEventListener('pointerdown', () => startButtonPTZ('TILT', (s) => ({ pan: 0, tilt:  s })));
    document.getElementById('btnTiltDown').addEventListener('pointerdown', () => startButtonPTZ('TILT', (s) => ({ pan: 0, tilt: -s })));
    document.getElementById('btnZoomIn')  .addEventListener('pointerdown', () => startButtonPTZ('ZOOM', (s) => ({ pan: 0, tilt: 0, fov: -s * 3 })));
    document.getElementById('btnZoomOut') .addEventListener('pointerdown', () => startButtonPTZ('ZOOM', (s) => ({ pan: 0, tilt: 0, fov:  s * 3 })));

    // Stop on release anywhere
    window.addEventListener('pointerup', stopButtonPTZ);
    window.addEventListener('pointerleave', stopButtonPTZ);

    // Speed toggle
    document.getElementById('btnSpeed')?.addEventListener('click', cycleSpeed);
  }

  // ── Start ───────────────────────────────────────────────────────
  function start() {
    initThree();
    bindEvents();
    updateOsd();
    updateSpeedDots();
  }

  // Wait for Three.js to be available
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
