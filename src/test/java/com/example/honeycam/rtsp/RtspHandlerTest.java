package com.example.honeycam.rtsp;

import com.example.honeycam.service.LogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the RTSP protocol handler.
 * Starts a real TCP server, sends RTSP requests, and validates responses.
 */
class RtspHandlerTest {

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private Thread serverThread;
    private BufferedReader reader;
    private BufferedWriter writer;
    private LogService logService;
    private volatile String serverResponse = "";

    @BeforeEach
    void setUp() throws Exception {
        // Create LogService with temp directory
        logService = new LogService("build/test-logs");

        // Start a test server on a random port
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        CountDownLatch accepted = new CountDownLatch(1);

        serverThread = new Thread(() -> {
            try {
                Socket serverSide = serverSocket.accept();
                accepted.countDown();
                new RtspHandler(serverSide, logService).run();
            } catch (IOException ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Connect to the test server
        clientSocket = new Socket("localhost", port);
        reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        accepted.await(3, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        try { clientSocket.close(); } catch (Exception ignored) {}
        try { serverSocket.close(); } catch (Exception ignored) {}
        serverThread.interrupt();
    }

    /**
     * Send an RTSP request and read the response.
     * Reads headers until blank line, then reads the body if Content-Length present.
     */
    private String sendRequest(String request) throws Exception {
        return sendRequestInternal(request, false);
    }

    /**
     * Like sendRequest but signals EOF after the request.
     * This closes the write side of the socket, causing the handler to close
     * the connection after responding — the response is read until EOF.
     */
    private String sendRequestAndClose(String request) throws Exception {
        return sendRequestInternal(request, true);
    }

    private String sendRequestInternal(String request, boolean closeAfter) throws Exception {
        writer.write(request);
        if (!request.endsWith("\r\n\r\n")) {
            writer.write("\r\n\r\n");
        }
        writer.flush();
        if (closeAfter) {
            clientSocket.shutdownOutput();
        }

        StringBuilder sb = new StringBuilder();
        int contentLength = -1;
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\r\n");
            // Track Content-Length while in headers
            if (contentLength < 0 && line.startsWith("Content-Length:")) {
                contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
            // Blank line = end of headers; body follows
            if (line.isEmpty()) {
                if (contentLength > 0) {
                    char[] body = new char[contentLength];
                    int total = 0;
                    while (total < contentLength) {
                        int n = reader.read(body, total, contentLength - total);
                        if (n < 0) break;
                        total += n;
                    }
                    sb.append(body);
                }
                // If closeAfter, read remaining lines until EOF
                if (closeAfter) continue;
                // Otherwise, we've read the full response — break
                break;
            }
        }
        return sb.toString();
    }

    @Test
    void testOptions() throws Exception {
        String response = sendRequest(
                "OPTIONS rtsp://localhost:554/stream RTSP/1.0\r\n" +
                "CSeq: 1\r\n");

        System.out.println("=== OPTIONS Response ===");
        System.out.println(response);

        assertTrue(response.contains("RTSP/1.0 200 OK"), "Should return 200 OK");
        assertTrue(response.contains("CSeq: 1"), "Should echo CSeq");
        assertTrue(response.contains("Public:"), "Should list supported methods");
        assertTrue(response.contains("OPTIONS"), "Should include OPTIONS");
        assertTrue(response.contains("DESCRIBE"), "Should include DESCRIBE");
        assertTrue(response.contains("SETUP"), "Should include SETUP");
        assertTrue(response.contains("PLAY"), "Should include PLAY");
        assertTrue(response.contains("TEARDOWN"), "Should include TEARDOWN");
        System.out.println("✅ OPTIONS test passed");
    }

    @Test
    void testDescribe() throws Exception {
        String response = sendRequest(
                "DESCRIBE rtsp://localhost:554/stream RTSP/1.0\r\n" +
                "CSeq: 2\r\n" +
                "Accept: application/sdp\r\n");

        System.out.println("=== DESCRIBE Response ===");
        System.out.println(response);

        assertTrue(response.contains("RTSP/1.0 200 OK"), "Should return 200 OK");
        assertTrue(response.contains("Content-Type: application/sdp"), "Should have SDP content type");
        assertTrue(response.contains("v=0"), "SDP should contain version");
        assertTrue(response.contains("H264"), "SDP should advertise H.264");
        assertTrue(response.contains("track1"), "SDP should have a track");
        System.out.println("✅ DESCRIBE test passed");
    }

    @Test
    void testSetup() throws Exception {
        String response = sendRequest(
                "SETUP rtsp://localhost:554/stream/track1 RTSP/1.0\r\n" +
                "CSeq: 3\r\n" +
                "Transport: RTP/AVP;unicast;client_port=1234-1235\r\n");

        System.out.println("=== SETUP Response ===");
        System.out.println(response);

        assertTrue(response.contains("RTSP/1.0 200 OK"), "Should return 200 OK");
        assertTrue(response.contains("Session:"), "Should assign a Session ID");
        assertTrue(response.contains("server_port="), "Should return server ports");
        assertTrue(response.contains("timeout=60"), "Should set session timeout");
        System.out.println("✅ SETUP test passed");
    }

    @Test
    void testFullHandshake() throws Exception {
        // 1. OPTIONS
        String optsResp = sendRequest(
                "OPTIONS rtsp://localhost:554/stream RTSP/1.0\r\n" +
                "CSeq: 1\r\n");
        assertTrue(optsResp.contains("200 OK"), "OPTIONS should succeed");

        // 2. DESCRIBE
        String descResp = sendRequest(
                "DESCRIBE rtsp://localhost:554/stream RTSP/1.0\r\n" +
                "CSeq: 2\r\n" +
                "Accept: application/sdp\r\n");
        assertTrue(descResp.contains("200 OK"), "DESCRIBE should succeed");
        assertTrue(descResp.contains("H264"), "SDP should include H.264");

        // 3. SETUP
        String setupResp = sendRequest(
                "SETUP rtsp://localhost:554/stream/track1 RTSP/1.0\r\n" +
                "CSeq: 3\r\n" +
                "Transport: RTP/AVP;unicast;client_port=1234-1235\r\n");
        assertTrue(setupResp.contains("200 OK"), "SETUP should succeed");

        // Extract session ID for later requests
        String sessionId = null;
        for (String line : setupResp.split("\r\n")) {
            if (line.startsWith("Session:")) {
                sessionId = line.split(":")[1].trim().split(";")[0];
                break;
            }
        }
        assertNotNull(sessionId, "Should have a session ID");

        // 4. PLAY
        String playResp = sendRequest(
                "PLAY rtsp://localhost:554/stream RTSP/1.0\r\n" +
                "CSeq: 4\r\n" +
                "Session: " + sessionId + "\r\n");
        assertTrue(playResp.contains("200 OK"), "PLAY should succeed");
        assertTrue(playResp.contains("RTP-Info:"), "PLAY should include RTP-Info");

        // 5. TEARDOWN
        String teardownResp = sendRequest(
                "TEARDOWN rtsp://localhost:554/stream RTSP/1.0\r\n" +
                "CSeq: 5\r\n" +
                "Session: " + sessionId + "\r\n");
        assertTrue(teardownResp.contains("200 OK"), "TEARDOWN should succeed");

        System.out.println("=== Full RTSP Handshake ===");
        System.out.println("OPTIONS   → " + (optsResp.contains("200 OK") ? "✅" : "❌"));
        System.out.println("DESCRIBE  → " + (descResp.contains("200 OK") ? "✅" : "❌"));
        System.out.println("SETUP     → " + (setupResp.contains("200 OK") ? "✅" : "❌"));
        System.out.println("PLAY      → " + (playResp.contains("200 OK") ? "✅" : "❌"));
        System.out.println("TEARDOWN  → " + (teardownResp.contains("200 OK") ? "✅" : "❌"));
    }

    @Test
    void testUnknownMethod() throws Exception {
        String response = sendRequest(
                "REDIRECT rtsp://localhost:554/stream RTSP/1.0\r\n" +
                "CSeq: 99\r\n");

        System.out.println("=== Unknown Method Response ===");
        System.out.println(response);

        assertTrue(response.contains("RTSP/1.0 501"), "Unknown method should return 501");
        System.out.println("✅ Unknown method test passed");
    }

    @Test
    void testLogging() throws Exception {
        // Send a few RTSP requests to trigger logging
        sendRequest("OPTIONS rtsp://localhost:554/stream RTSP/1.0\r\nCSeq: 1\r\n");
        sendRequest("DESCRIBE rtsp://localhost:554/stream RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n");

        // Check that log directory exists
        assertTrue(java.nio.file.Files.exists(logService.getLogDir()),
                "Log directory should exist");

        System.out.println("✅ Logging test passed");
        System.out.println("Log directory: " + logService.getLogDir());
    }
}
