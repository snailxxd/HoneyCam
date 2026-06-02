package com.example.honeycam.model;

import java.time.Instant;

/**
 * Records an RTSP protocol request from an attacker.
 */
public class RtspRequest {

    private String id;
    private String ipAddress;
    private String method;
    private String url;
    private String headers;
    private Instant timestamp;

    public RtspRequest() {
        this.timestamp = Instant.now();
    }

    public RtspRequest(String ipAddress, String method, String url, String headers) {
        this();
        this.ipAddress = ipAddress;
        this.method = method;
        this.url = url;
        this.headers = headers;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getHeaders() { return headers; }
    public void setHeaders(String headers) { this.headers = headers; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "RtspRequest{" +
                "ipAddress='" + ipAddress + '\'' +
                ", method='" + method + '\'' +
                ", url='" + url + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
