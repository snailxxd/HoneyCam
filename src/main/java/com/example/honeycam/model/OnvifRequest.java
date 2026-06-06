package com.example.honeycam.model;

import java.time.Instant;

/**
 * Records an ONVIF protocol request from an attacker or scanner.
 */
public class OnvifRequest {

    private String id;
    private String ipAddress;
    private String method;       // HTTP method: GET, POST
    private String path;         // URL path: /onvif/device_service, etc.
    private String soapAction;   // SOAPAction header value
    private String requestBody;  // First 2000 chars of the SOAP body
    private Instant timestamp;

    public OnvifRequest() {
        this.timestamp = Instant.now();
    }

    public OnvifRequest(String ipAddress, String method, String path,
                        String soapAction, String requestBody) {
        this();
        this.ipAddress = ipAddress;
        this.method = method;
        this.path = path;
        this.soapAction = soapAction;
        this.requestBody = requestBody;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getSoapAction() { return soapAction; }
    public void setSoapAction(String soapAction) { this.soapAction = soapAction; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "OnvifRequest{" +
                "ipAddress='" + ipAddress + '\'' +
                ", method='" + method + '\'' +
                ", path='" + path + '\'' +
                ", soapAction='" + soapAction + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
