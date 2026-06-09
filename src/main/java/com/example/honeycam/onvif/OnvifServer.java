package com.example.honeycam.onvif;

import com.example.honeycam.config.HoneyCamProperties;
import com.example.honeycam.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fake ONVIF server that listens on TCP port 8000 and responds to
 * ONVIF SOAP/XML requests with realistic Hikvision-camera-like responses.
 * <p>
 * Uses the same threading model as {@link com.example.honeycam.rtsp.RtspServer}
 * — a cached thread pool handles concurrent connections from scanners
 * and attackers.
 * <p>
 * Supported services:
 * <ul>
 *   <li>/onvif/device_service — GetDeviceInformation, GetCapabilities,
 *       GetSystemDateAndTime, GetServices, GetScopes, GetNetworkInterfaces,
 *       GetHostname, GetDeviceState</li>
 *   <li>/onvif/media_service — GetProfiles, GetStreamUri, GetVideoSources,
 *       GetVideoEncoderConfigurations, GetSnapshotUri</li>
 *   <li>/onvif/ptz_service — GetConfigurations, GetConfigurationOptions,
 *       GetStatus, GetNodes</li>
 * </ul>
 */
@Component
public class OnvifServer {

    private static final Logger logger = LoggerFactory.getLogger(OnvifServer.class);

    private final LogService logService;
    private final InetAddress bindAddress;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService threadPool;

    public OnvifServer(LogService logService, HoneyCamProperties props) {
        this.logService = logService;
        this.port = props.getOnvif().getPort();
        InetAddress addr;
        try {
            addr = InetAddress.getByName(props.getOnvif().getAddress());
        } catch (IOException e) {
            logger.warn("Invalid ONVIF bind address '{}', falling back to 0.0.0.0: {}",
                    props.getOnvif().getAddress(), e.getMessage());
            try {
                addr = InetAddress.getByName("0.0.0.0");
            } catch (IOException impossible) {
                // 0.0.0.0 is always valid; this branch never runs
                throw new RuntimeException("Cannot resolve fallback address", impossible);
            }
        }
        this.bindAddress = addr;
    }

    /**
     * Start the ONVIF server automatically after the Spring application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running.compareAndSet(false, true)) {
            threadPool = Executors.newCachedThreadPool(
                    r -> new Thread(r, "onvif-worker"));
            Thread acceptThread = new Thread(this::acceptLoop, "onvif-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            logger.info("ONVIF server started on port {}", port);
        }
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port, 50, bindAddress);
            logger.info("ONVIF server listening on {}:{}", bindAddress.getHostAddress(), port);

            while (running.get() && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    threadPool.submit(new OnvifHandler(client, logService));
                } catch (IOException e) {
                    if (running.get()) {
                        logger.warn("ONVIF accept error: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to start ONVIF server on port {}: {}", port, e.getMessage());
            running.set(false);
        }
    }

    /**
     * Gracefully stop the ONVIF server (called on application shutdown).
     */
    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                logger.warn("Error closing ONVIF server socket: {}", e.getMessage());
            }
            if (threadPool != null) {
                threadPool.shutdownNow();
            }
            logger.info("ONVIF server stopped");
        }
    }
}
