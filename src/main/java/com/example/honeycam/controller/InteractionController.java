package com.example.honeycam.controller;

import com.example.honeycam.model.InteractionEvent;
import com.example.honeycam.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles PTZ (Pan-Tilt-Zoom) interaction recording from the frontend.
 * Attackers interacting with the 360° camera view trigger these endpoints.
 */
@RestController
@RequestMapping("/api")
public class InteractionController {

    private final LogService logService;
    private final Map<String, InteractionSession> sessions = new ConcurrentHashMap<>();

    public InteractionController(LogService logService) {
        this.logService = logService;
    }

    /**
     * Record a PTZ interaction event from the attacker.
     */
    @PostMapping("/interaction")
    public ResponseEntity<Map<String, Object>> recordInteraction(
            @RequestBody InteractionEvent event,
            HttpServletRequest request) {

        // Fill in server-side information
        event.setIpAddress(getClientIp(request));
        event.setTimestamp(Instant.now());

        logService.logInteraction(event);

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "interaction recorded"
        ));
    }

    /**
     * Retrieve current camera state for a session.
     */
    @GetMapping("/camera-state")
    public ResponseEntity<Map<String, Object>> getCameraState(HttpSession session) {
        InteractionSession camSession = sessions.get(session.getId());
        if (camSession == null) {
            camSession = new InteractionSession();
            sessions.put(session.getId(), camSession);
        }

        return ResponseEntity.ok(Map.of(
                "pan", camSession.pan,
                "tilt", camSession.tilt,
                "fov", camSession.fov,
                "sessionId", session.getId()
        ));
    }

    /**
     * Update camera state (used during continuous drag/zoom operations).
     */
    @PostMapping("/camera-state")
    public ResponseEntity<Map<String, Object>> updateCameraState(
            @RequestBody Map<String, Double> state,
            HttpServletRequest request,
            HttpSession session) {

        InteractionSession camSession = sessions.computeIfAbsent(session.getId(), k -> new InteractionSession());

        if (state.containsKey("pan")) camSession.pan = state.get("pan");
        if (state.containsKey("tilt")) camSession.tilt = state.get("tilt");
        if (state.containsKey("fov")) camSession.fov = state.get("fov");

        // Also log as interaction
        InteractionEvent event = new InteractionEvent();
        event.setIpAddress(getClientIp(request));
        event.setSessionId(session.getId());
        event.setActionType(determineActionType(state));
        event.setPanAngle(camSession.pan);
        event.setTiltAngle(camSession.tilt);
        event.setFov(camSession.fov);
        event.setTimestamp(Instant.now());

        logService.logInteraction(event);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /**
     * Dummy RTSP stream metadata endpoint — mimics what a real camera might expose.
     */
    @GetMapping(value = "/stream/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getStreamInfo() {
        return ResponseEntity.ok(Map.of(
                "protocol", "RTSP",
                "resolution", "1920x1080",
                "codec", "H.264",
                "framerate", 30,
                "bitrate", "2048 kbps",
                "streamUrl", "/api/stream/live"
        ));
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    private InteractionEvent.ActionType determineActionType(Map<String, Double> state) {
        if (state.containsKey("fov") && (!state.containsKey("pan") && !state.containsKey("tilt"))) {
            return InteractionEvent.ActionType.ZOOM;
        }
        return InteractionEvent.ActionType.DRAG;
    }

    /**
     * Internal class to track per-session camera state.
     */
    private static class InteractionSession {
        double pan = 0.0;
        double tilt = 0.0;
        double fov = 75.0; // default FOV
    }
}
