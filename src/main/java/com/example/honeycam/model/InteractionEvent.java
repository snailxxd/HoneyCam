package com.example.honeycam.model;

import java.time.Instant;

/**
 * Records PTZ (Pan-Tilt-Zoom) interaction events from an attacker.
 */
public class InteractionEvent {

    public enum ActionType {
        PAN,
        TILT,
        ZOOM,
        CLICK,
        DRAG,
        LOGIN_PAGE_LOAD,
        SESSION_START,
        SESSION_END,
        LOGIN_ATTEMPT
    }

    private String id;
    private String ipAddress;
    private String sessionId;
    private ActionType actionType;
    private double deltaX;
    private double deltaY;
    private double zoomLevel;
    private double fov;          // current field-of-view angle
    private double panAngle;     // current horizontal rotation (radians)
    private double tiltAngle;    // current vertical rotation (radians)
    private Instant timestamp;

    public InteractionEvent() {
        this.timestamp = Instant.now();
    }

    public InteractionEvent(String ipAddress, String sessionId, ActionType actionType) {
        this();
        this.ipAddress = ipAddress;
        this.sessionId = sessionId;
        this.actionType = actionType;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }

    public double getDeltaX() { return deltaX; }
    public void setDeltaX(double deltaX) { this.deltaX = deltaX; }

    public double getDeltaY() { return deltaY; }
    public void setDeltaY(double deltaY) { this.deltaY = deltaY; }

    public double getZoomLevel() { return zoomLevel; }
    public void setZoomLevel(double zoomLevel) { this.zoomLevel = zoomLevel; }

    public double getFov() { return fov; }
    public void setFov(double fov) { this.fov = fov; }

    public double getPanAngle() { return panAngle; }
    public void setPanAngle(double panAngle) { this.panAngle = panAngle; }

    public double getTiltAngle() { return tiltAngle; }
    public void setTiltAngle(double tiltAngle) { this.tiltAngle = tiltAngle; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "InteractionEvent{" +
                "ipAddress='" + ipAddress + '\'' +
                ", actionType=" + actionType +
                ", deltaX=" + deltaX +
                ", deltaY=" + deltaY +
                ", zoomLevel=" + zoomLevel +
                ", timestamp=" + timestamp +
                '}';
    }
}
