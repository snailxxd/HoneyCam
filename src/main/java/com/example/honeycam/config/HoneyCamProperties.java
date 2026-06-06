package com.example.honeycam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Centralized configuration for the HoneyCam honeypot.
 * Groups related settings into nested classes so controllers
 * can inject just the group they need rather than a dozen
 * individual @Value parameters.
 */
@Component
@ConfigurationProperties(prefix = "honeycam")
public class HoneyCamProperties {

    /** Camera identity (brand, model name shown on login/camera pages). */
    private Camera camera = new Camera();

    /** Authentication / login deception settings. */
    private Auth auth = new Auth();

    /** Panorama rendering mode and source URLs. */
    private Panorama panorama = new Panorama();

    /** Deception realism settings (delays, overlays). */
    private Deception deception = new Deception();

    /** PTZ interaction settings. */
    private Interaction interaction = new Interaction();

    /** RTSP settings. */
    private Rtsp rtsp = new Rtsp();

    /** ONVIF settings. */
    private Onvif onvif = new Onvif();

    /** Log directory path. */
    private Log log = new Log();

    // ---- nested classes ----

    public static class Camera {
        private String brand = "Hikvision";
        private String model = "DS-2CD2043G2-I";
        /** Unix epoch seconds when the camera "went online".
         *  The frontend OSD clock starts from this fixed timestamp
         *  so the date never resets across attacker page refreshes. */
        private long epochStart = 0L;

        public String getBrand() { return brand; }
        public void setBrand(String brand) { this.brand = brand; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public long getEpochStart() { return epochStart; }
        public void setEpochStart(long epochStart) { this.epochStart = epochStart; }
    }

    public static class Auth {
        /** Probability (0–1) that a login attempt is "accepted" to let the attacker in. */
        private double fakeSuccessRate = 0.35;

        /** Weak credential pairs that always succeed, format "username:password". */
        private java.util.List<String> weakCredentials = java.util.List.of(
            "admin:12345",
            "admin:123456",
            "admin:admin",
            "root:root"
        );

        public double getFakeSuccessRate() { return fakeSuccessRate; }
        public void setFakeSuccessRate(double fakeSuccessRate) { this.fakeSuccessRate = fakeSuccessRate; }
        public java.util.List<String> getWeakCredentials() { return weakCredentials; }
        public void setWeakCredentials(java.util.List<String> weakCredentials) { this.weakCredentials = weakCredentials; }
    }

    public static class Panorama {
        /** Rendering mode: "auto" (video if available, image fallback), "video", "image". */
        private String mode = "auto";
        private String videoUrl = "/media/360-demo.mp4";
        private String imageUrl = "https://threejs.org/examples/textures/2294472375_24a3b8ef46_o.jpg";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getVideoUrl() { return videoUrl; }
        public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
        public String getImageUrl() { return imageUrl; }
        public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    }

    public static class Deception {
        /** Min random delay (ms) before the camera "preview" stream appears. */
        private int previewLatencyMinMs = 300;
        /** Max random delay (ms) before the camera "preview" stream appears. */
        private int previewLatencyMaxMs = 1400;

        public int getPreviewLatencyMinMs() { return previewLatencyMinMs; }
        public void setPreviewLatencyMinMs(int previewLatencyMinMs) { this.previewLatencyMinMs = previewLatencyMinMs; }
        public int getPreviewLatencyMaxMs() { return previewLatencyMaxMs; }
        public void setPreviewLatencyMaxMs(int previewLatencyMaxMs) { this.previewLatencyMaxMs = previewLatencyMaxMs; }
    }

    public static class Interaction {
        /** Whether PTZ controls are enabled (false for Group B low-interaction). */
        private boolean ptzEnabled = true;
        /** Whether the camera auto-pans periodically (simulates patrol). */
        private boolean autoPatrolEnabled = false;
        /** Pan degrees for auto-patrol sweep. */
        private double autoPatrolPanDegrees = 35;
        /** Tilt degrees for auto-patrol sweep. */
        private double autoPatrolTiltDegrees = 8;
        /** Seconds for one complete auto-patrol cycle. */
        private double autoPatrolCycleSeconds = 18;
        /** Max sessions tracked per IP. */
        private int maxPerIp = 50;

        public boolean isPtzEnabled() { return ptzEnabled; }
        public void setPtzEnabled(boolean ptzEnabled) { this.ptzEnabled = ptzEnabled; }
        public boolean isAutoPatrolEnabled() { return autoPatrolEnabled; }
        public void setAutoPatrolEnabled(boolean autoPatrolEnabled) { this.autoPatrolEnabled = autoPatrolEnabled; }
        public double getAutoPatrolPanDegrees() { return autoPatrolPanDegrees; }
        public void setAutoPatrolPanDegrees(double autoPatrolPanDegrees) { this.autoPatrolPanDegrees = autoPatrolPanDegrees; }
        public double getAutoPatrolTiltDegrees() { return autoPatrolTiltDegrees; }
        public void setAutoPatrolTiltDegrees(double autoPatrolTiltDegrees) { this.autoPatrolTiltDegrees = autoPatrolTiltDegrees; }
        public double getAutoPatrolCycleSeconds() { return autoPatrolCycleSeconds; }
        public void setAutoPatrolCycleSeconds(double autoPatrolCycleSeconds) { this.autoPatrolCycleSeconds = autoPatrolCycleSeconds; }
        public int getMaxPerIp() { return maxPerIp; }
        public void setMaxPerIp(int maxPerIp) { this.maxPerIp = maxPerIp; }
    }

    public static class Rtsp {
        private int port = 554;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class Onvif {
        private int port = 8000;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class Log {
        private String dir = "logs";

        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }

    // ---- top-level getters ----

    public Camera getCamera() { return camera; }
    public void setCamera(Camera camera) { this.camera = camera; }

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public Panorama getPanorama() { return panorama; }
    public void setPanorama(Panorama panorama) { this.panorama = panorama; }

    public Deception getDeception() { return deception; }
    public void setDeception(Deception deception) { this.deception = deception; }

    public Interaction getInteraction() { return interaction; }
    public void setInteraction(Interaction interaction) { this.interaction = interaction; }

    public Rtsp getRtsp() { return rtsp; }
    public void setRtsp(Rtsp rtsp) { this.rtsp = rtsp; }

    public Onvif getOnvif() { return onvif; }
    public void setOnvif(Onvif onvif) { this.onvif = onvif; }

    public Log getLog() { return log; }
    public void setLog(Log log) { this.log = log; }
}
