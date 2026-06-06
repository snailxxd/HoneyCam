package com.example.honeycam.service;

import com.example.honeycam.config.HoneyCamProperties;
import com.example.honeycam.model.InteractionEvent;
import com.example.honeycam.model.LoginAttempt;
import com.example.honeycam.model.OnvifRequest;
import com.example.honeycam.model.RtspRequest;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Central logging service that persists all attacker activity to JSON log files.
 * Logs are stored under the configured log directory with one file per day.
 */
@Service
public class LogService {

    private static final Logger logger = LoggerFactory.getLogger(LogService.class);

    private final ObjectMapper objectMapper;
    private final Path logDir;

    public LogService(HoneyCamProperties props) {
        this.objectMapper = new ObjectMapper();
        // Jackson 3.x has Java time support built-in — no need for separate module
        this.logDir = Paths.get(props.getLog().getDir());
        try {
            Files.createDirectories(this.logDir);
            logger.info("Log directory initialized: {}", this.logDir.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to create log directory: {}", logDir, e);
        }
    }

    /**
     * Log a login attempt to the daily login-attempts JSON file.
     *
     * @param ipAddress attacker's IP address
     * @param userAgent attacker's User-Agent header
     * @param username  attempted username
     * @param password  attempted password
     * @return the recorded LoginAttempt
     */
    public LoginAttempt logLoginAttempt(String ipAddress, String userAgent, String username, String password) {
        LoginAttempt attempt = new LoginAttempt(ipAddress, userAgent, username, password);
        attempt.setId(UUID.randomUUID().toString());

        logger.info("[LOGIN] IP={}, username={}, password={}, UA={}",
                ipAddress, username, password, userAgent);

        appendToLog("login-attempts", attempt);
        return attempt;
    }

    /**
     * Log a PTZ interaction event to the daily interactions JSON file.
     *
     * @param event the interaction event to log
     * @return the recorded InteractionEvent
     */
    public InteractionEvent logInteraction(InteractionEvent event) {
        if (event.getId() == null || event.getId().isEmpty()) {
            event.setId(UUID.randomUUID().toString());
        }

        logger.info("[INTERACTION] IP={}, action={}, pan={}, tilt={}, zoom={}",
                event.getIpAddress(), event.getActionType(),
                event.getPanAngle(), event.getTiltAngle(), event.getZoomLevel());

        appendToLog("interactions", event);
        return event;
    }

    /**
     * Log an RTSP protocol request to the daily rtsp-requests JSON file.
     *
     * @param ipAddress attacker's IP address
     * @param method    RTSP method (OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, etc.)
     * @param url       the requested RTSP URL path
     * @param headers   raw RTSP headers from the request
     * @return the recorded RtspRequest
     */
    public RtspRequest logRtspRequest(String ipAddress, String method, String url, String headers) {
        RtspRequest request = new RtspRequest(ipAddress, method, url, headers);
        request.setId(UUID.randomUUID().toString());

        logger.info("[RTSP] IP={}, method={}, url={}", ipAddress, method, url);

        appendToLog("rtsp-requests", request);
        return request;
    }

    /**
     * Log an ONVIF protocol request to the daily onvif-requests JSON file.
     *
     * @param ipAddress  attacker's or scanner's IP address
     * @param method     HTTP method (usually POST for SOAP)
     * @param path       URL path (/onvif/device_service, etc.)
     * @param soapAction the SOAPAction header value
     * @param requestBody first 2000 chars of the SOAP request body
     * @return the recorded OnvifRequest
     */
    public OnvifRequest logOnvifRequest(String ipAddress, String method, String path,
                                         String soapAction, String requestBody) {
        OnvifRequest request = new OnvifRequest(ipAddress, method, path, soapAction, requestBody);
        request.setId(UUID.randomUUID().toString());

        logger.info("[ONVIF] IP={}, method={}, path={}, action={}",
                ipAddress, method, path, soapAction);

        appendToLog("onvif-requests", request);
        return request;
    }

    /**
     * Append an object as a JSON line to the daily log file.
     */
    private void appendToLog(String logType, Object entry) {
        try {
            String today = java.time.LocalDate.now().toString();
            Path logFile = logDir.resolve(logType + "-" + today + ".jsonl");
            String jsonLine = objectMapper.writeValueAsString(entry);

            try (BufferedWriter writer = Files.newBufferedWriter(
                    logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(jsonLine);
                writer.newLine();
            }
        } catch (IOException e) {
            logger.error("Failed to write {} log entry", logType, e);
        }
    }

    /**
     * @return the absolute path to the log directory
     */
    public Path getLogDir() {
        return logDir.toAbsolutePath();
    }
}
