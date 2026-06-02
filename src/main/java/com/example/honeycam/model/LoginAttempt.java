package com.example.honeycam.model;

import java.time.Instant;

/**
 * Records each login attempt made by an attacker against the fake camera login page.
 */
public class LoginAttempt {

    private String id;
    private String ipAddress;
    private String userAgent;
    private String username;
    private String password;
    private Instant timestamp;
    private boolean success;

    public LoginAttempt() {
        this.timestamp = Instant.now();
        this.success = false;
    }

    public LoginAttempt(String ipAddress, String userAgent, String username, String password) {
        this();
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.username = username;
        this.password = password;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    @Override
    public String toString() {
        return "LoginAttempt{" +
                "ipAddress='" + ipAddress + '\'' +
                ", username='" + username + '\'' +
                ", password='" + password + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
