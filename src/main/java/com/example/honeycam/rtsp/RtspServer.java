package com.example.honeycam.rtsp;

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
 * Fake RTSP server that listens on TCP port 554 and simulates
 * a real IP camera's RTSP handshake. All interaction attempts
 * are logged via LogService for attacker analysis.
 * <p>
 * Supported RTSP methods: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN
 * <p>
 * The server uses a small thread pool to handle concurrent connections
 * and responds with realistic SDP / transport headers mimicking a
 * Hikvision-style H.264 camera stream.
 */
@Component
public class RtspServer {

    private static final Logger logger = LoggerFactory.getLogger(RtspServer.class);

    private final LogService logService;
    private final InetAddress bindAddress;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService threadPool;

    public RtspServer(LogService logService, HoneyCamProperties props) {
        this.logService = logService;
        this.port = props.getRtsp().getPort();
        InetAddress addr;
        try {
            addr = InetAddress.getByName(props.getRtsp().getAddress());
        } catch (IOException e) {
            logger.warn("Invalid RTSP bind address '{}', falling back to 0.0.0.0: {}",
                    props.getRtsp().getAddress(), e.getMessage());
            addr = InetAddress.getByName("0.0.0.0");
        }
        this.bindAddress = addr;
    }

    /**
     * Start the RTSP server automatically after the Spring application is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running.compareAndSet(false, true)) {
            threadPool = Executors.newCachedThreadPool(
                    r -> new Thread(r, "rtsp-worker"));
            Thread acceptThread = new Thread(this::acceptLoop, "rtsp-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            logger.info("RTSP server started on port {}", port);
        }
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(port, 50, bindAddress);
            logger.info("RTSP server listening on {}:{}", bindAddress.getHostAddress(), port);

            while (running.get() && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    threadPool.submit(new RtspHandler(client, logService));
                } catch (IOException e) {
                    if (running.get()) {
                        logger.warn("RTSP accept error: {}", e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to start RTSP server on port {}: {}", port, e.getMessage());
            running.set(false);
        }
    }

    /**
     * Gracefully stop the RTSP server (called on application shutdown).
     */
    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                logger.warn("Error closing RTSP server socket: {}", e.getMessage());
            }
            if (threadPool != null) {
                threadPool.shutdownNow();
            }
            logger.info("RTSP server stopped");
        }
    }
}
