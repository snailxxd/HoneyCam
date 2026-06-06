package com.example.honeycam.onvif;

import com.example.honeycam.service.LogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the ONVIF protocol handler.
 * Starts a real TCP server on a random port, sends ONVIF SOAP/HTTP requests,
 * and validates the XML responses.
 */
class OnvifHandlerTest {

    private ServerSocket serverSocket;
    private Thread serverThread;
    private Thread currentHandlerThread;
    private CountDownLatch handlerReady;
    private LogService logService;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        com.example.honeycam.config.HoneyCamProperties props =
                new com.example.honeycam.config.HoneyCamProperties();
        props.getLog().setDir("build/test-logs");
        logService = new LogService(props);

        serverSocket = new ServerSocket(0);
        port = serverSocket.getLocalPort();

        // Accept loop: spawns a new handler for each connection
        serverThread = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket serverSide = serverSocket.accept();
                    if (handlerReady != null) handlerReady.countDown();
                    currentHandlerThread = new Thread(() -> {
                        new OnvifHandler(serverSide, logService).run();
                    });
                    currentHandlerThread.setDaemon(true);
                    currentHandlerThread.start();
                } catch (IOException e) {
                    break; // server socket closed
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        try { serverSocket.close(); } catch (Exception ignored) {}
        serverThread.interrupt();
    }

    // ── Helpers ────────────────────────────────────

    /** Build a SOAP request body for a given action. */
    private String soapRequest(String action, String bodyXml) {
        String ns;
        if (action.contains("/device/wsdl") || action.contains("device_service")) {
            ns = "tds";
        } else if (action.contains("/media/wsdl") || action.contains("media_service")) {
            ns = "trt";
        } else if (action.contains("/ptz/wsdl") || action.contains("ptz_service")) {
            ns = "tptz";
        } else {
            ns = "ns";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("POST /onvif/device_service HTTP/1.1\r\n");
        sb.append("Host: localhost\r\n");
        sb.append("Content-Type: application/soap+xml; charset=utf-8\r\n");
        sb.append("SOAPAction: \"").append(action).append("\"\r\n");

        String body = "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\">" +
                "<env:Body>" + bodyXml + "</env:Body></env:Envelope>";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        sb.append(body);
        return sb.toString();
    }

    /** Build a SOAP request targeted at the media service path. */
    private String soapRequestTo(String path, String action, String bodyXml) {
        StringBuilder sb = new StringBuilder();
        sb.append("POST ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: localhost\r\n");
        sb.append("Content-Type: application/soap+xml; charset=utf-8\r\n");
        sb.append("SOAPAction: \"").append(action).append("\"\r\n");

        String body = "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\">" +
                "<env:Body>" + bodyXml + "</env:Body></env:Envelope>";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        sb.append(body);
        return sb.toString();
    }

    /** Send a raw HTTP request over a fresh connection and return the full response. */
    private String sendRequest(String request) throws Exception {
        handlerReady = new CountDownLatch(1);
        try (Socket sock = new Socket("localhost", port)) {
            handlerReady.await(2, TimeUnit.SECONDS);
            sock.setSoTimeout(5000);
            OutputStream os = sock.getOutputStream();
            os.write(request.getBytes(StandardCharsets.UTF_8));
            os.flush();

            BufferedInputStream is = new BufferedInputStream(sock.getInputStream());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                int available = is.available();
                if (available > 0) {
                    int read = is.read(buf, 0, Math.min(buf.length, available));
                    if (read < 0) break;
                    baos.write(buf, 0, read);
                } else {
                    // Poll briefly then check again, or break if response seems complete
                    if (baos.size() > 0) {
                        String soFar = baos.toString(StandardCharsets.UTF_8);
                        int headerEnd = soFar.indexOf("\r\n\r\n");
                        if (headerEnd > 0) {
                            String headers = soFar.substring(0, headerEnd);
                            int cl = -1;
                            for (String line : headers.split("\r\n")) {
                                if (line.toLowerCase().startsWith("content-length:")) {
                                    cl = Integer.parseInt(line.split(":")[1].trim());
                                }
                            }
                            if (cl > 0 && soFar.length() >= headerEnd + 4 + cl) break;
                            if (cl == 0) break;
                        }
                    }
                    Thread.sleep(50);
                }
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private void assertContains(String response, String... fragments) {
        for (String frag : fragments) {
            assertTrue(response.contains(frag),
                    "Response should contain: " + frag + "\nActual response:\n" + response);
        }
    }

    // ═══════════════════════════════════════════════
    //  Device Service Tests
    // ═══════════════════════════════════════════════

    @Test
    void testGetDeviceInformation() throws Exception {
        String response = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetDeviceInformation",
                "<tds:GetDeviceInformation xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));

        System.out.println("=== GetDeviceInformation Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "Hikvision",
                "DS-2CD2043G2-I",
                "FirmwareVersion",
                "SerialNumber",
                "HardwareId");
        System.out.println("✅ GetDeviceInformation test passed");
    }

    @Test
    void testGetCapabilities() throws Exception {
        String response = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
                "<tds:GetCapabilities xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));

        System.out.println("=== GetCapabilities Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetCapabilitiesResponse",
                "/onvif/device_service",
                "/onvif/media_service",
                "/onvif/ptz_service",
                "RTP_USCORERTSP_USCORETCP");
        System.out.println("✅ GetCapabilities test passed");
    }

    @Test
    void testGetSystemDateAndTime() throws Exception {
        String response = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetSystemDateAndTime",
                "<tds:GetSystemDateAndTime xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));

        System.out.println("=== GetSystemDateAndTime Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetSystemDateAndTimeResponse",
                "UTCDateTime",
                "TimeZone",
                "CST-8");
        System.out.println("✅ GetSystemDateAndTime test passed");
    }

    @Test
    void testGetServices() throws Exception {
        String response = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetServices",
                "<tds:GetServices xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));

        System.out.println("=== GetServices Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetServicesResponse",
                "/onvif/device_service",
                "/onvif/media_service",
                "/onvif/ptz_service");
        System.out.println("✅ GetServices test passed");
    }

    @Test
    void testGetScopes() throws Exception {
        String response = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetScopes",
                "<tds:GetScopes xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));

        System.out.println("=== GetScopes Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetScopesResponse",
                "video_encoder",
                "ptz",
                "DS-2CD2043G2-I",
                "IP%20Camera");
        System.out.println("✅ GetScopes test passed");
    }

    @Test
    void testGetNetworkInterfaces() throws Exception {
        String response = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetNetworkInterfaces",
                "<tds:GetNetworkInterfaces xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));

        System.out.println("=== GetNetworkInterfaces Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetNetworkInterfacesResponse",
                "eth0",
                "IPv4");
        System.out.println("✅ GetNetworkInterfaces test passed");
    }

    @Test
    void testGetHostname() throws Exception {
        String response = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetHostname",
                "<tds:GetHostname xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));

        System.out.println("=== GetHostname Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetHostnameResponse",
                "IPC-");
        System.out.println("✅ GetHostname test passed");
    }

    @Test
    void testGetDeviceState() throws Exception {
        String response = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetDeviceState",
                "<tds:GetDeviceState xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));

        System.out.println("=== GetDeviceState Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetDeviceStateResponse",
                "Operational");
        System.out.println("✅ GetDeviceState test passed");
    }

    // ═══════════════════════════════════════════════
    //  Media Service Tests
    // ═══════════════════════════════════════════════

    @Test
    void testGetProfiles() throws Exception {
        String response = sendRequest(soapRequestTo(
                "/onvif/media_service",
                "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
                "<trt:GetProfiles xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>"
        ));

        System.out.println("=== GetProfiles Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetProfilesResponse",
                "MainStream",
                "H264",
                "1920",
                "1080",
                "PTZConfiguration",
                "VideoSourceConfiguration",
                "VideoSource_1");
        System.out.println("✅ GetProfiles test passed");
    }

    @Test
    void testGetStreamUri() throws Exception {
        String response = sendRequest(soapRequestTo(
                "/onvif/media_service",
                "http://www.onvif.org/ver10/media/wsdl/GetStreamUri",
                "<trt:GetStreamUri xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\">" +
                "<trt:StreamSetup><tt:Stream xmlns:tt=\"http://www.onvif.org/ver10/schema\">RTP-Unicast</tt:Stream>" +
                "<tt:Transport xmlns:tt=\"http://www.onvif.org/ver10/schema\"><tt:Protocol>RTSP</tt:Protocol>" +
                "</tt:Transport></trt:StreamSetup>" +
                "<trt:ProfileToken>prof_01</trt:ProfileToken></trt:GetStreamUri>"
        ));

        System.out.println("=== GetStreamUri Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetStreamUriResponse",
                "rtsp://",
                "554",
                "Streaming/Channels/101");
        System.out.println("✅ GetStreamUri test passed");
    }

    @Test
    void testGetVideoSources() throws Exception {
        String response = sendRequest(soapRequestTo(
                "/onvif/media_service",
                "http://www.onvif.org/ver10/media/wsdl/GetVideoSources",
                "<trt:GetVideoSources xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>"
        ));

        System.out.println("=== GetVideoSources Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetVideoSourcesResponse",
                "1920",
                "1080",
                "30");  // framerate
        System.out.println("✅ GetVideoSources test passed");
    }

    @Test
    void testGetVideoEncoderConfigurations() throws Exception {
        String response = sendRequest(soapRequestTo(
                "/onvif/media_service",
                "http://www.onvif.org/ver10/media/wsdl/GetVideoEncoderConfigurations",
                "<trt:GetVideoEncoderConfigurations " +
                "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>"
        ));

        System.out.println("=== GetVideoEncoderConfigurations Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetVideoEncoderConfigurationsResponse",
                "H264",
                "VideoEncoder_1",
                "BitrateLimit",
                "FrameRateLimit");
        System.out.println("✅ GetVideoEncoderConfigurations test passed");
    }

    @Test
    void testGetSnapshotUri() throws Exception {
        String response = sendRequest(soapRequestTo(
                "/onvif/media_service",
                "http://www.onvif.org/ver10/media/wsdl/GetSnapshotUri",
                "<trt:GetSnapshotUri xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\">" +
                "<trt:ProfileToken>prof_01</trt:ProfileToken></trt:GetSnapshotUri>"
        ));

        System.out.println("=== GetSnapshotUri Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetSnapshotUriResponse",
                "snapshot");
        System.out.println("✅ GetSnapshotUri test passed");
    }

    // ═══════════════════════════════════════════════
    //  PTZ Service Tests
    // ═══════════════════════════════════════════════

    @Test
    void testGetPtzConfigurations() throws Exception {
        String response = sendRequest(soapRequestTo(
                "/onvif/ptz_service",
                "http://www.onvif.org/ver20/ptz/wsdl/GetConfigurations",
                "<tptz:GetConfigurations xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\"/>"
        ));

        System.out.println("=== GetConfigurations Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetConfigurationsResponse",
                "PTZConfiguration",
                "NodeToken",
                "PTZ_1");
        System.out.println("✅ GetConfigurations test passed");
    }

    @Test
    void testGetPtzConfigurationOptions() throws Exception {
        String response = sendRequest(soapRequestTo(
                "/onvif/ptz_service",
                "http://www.onvif.org/ver20/ptz/wsdl/GetConfigurationOptions",
                "<tptz:GetConfigurationOptions xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\">" +
                "<tptz:ConfigurationToken>ptz_conf_01</tptz:ConfigurationToken>" +
                "</tptz:GetConfigurationOptions>"
        ));

        System.out.println("=== GetConfigurationOptions Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetConfigurationOptionsResponse",
                "ContinuousPanTiltVelocitySpace",
                "ContinuousZoomVelocitySpace",
                "PanTiltSpeedSpace",
                "ZoomSpeedSpace");
        System.out.println("✅ GetConfigurationOptions test passed");
    }

    @Test
    void testGetPtzNodes() throws Exception {
        String response = sendRequest(soapRequestTo(
                "/onvif/ptz_service",
                "http://www.onvif.org/ver20/ptz/wsdl/GetNodes",
                "<tptz:GetNodes xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\"/>"
        ));

        System.out.println("=== GetNodes Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "GetNodesResponse",
                "PTZNode",
                "PTZ_Node_1",
                "HomeSupported");
        System.out.println("✅ GetNodes test passed");
    }

    // ═══════════════════════════════════════════════
    //  End-to-End Tests
    // ═══════════════════════════════════════════════

    @Test
    void testFullDeviceDiscoveryHandshake() throws Exception {
        // Simulate what ONVIF Device Manager / Nmap / Shodan does

        // 1. GetDeviceInformation — identify the device
        String devInfoResp = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetDeviceInformation",
                "<tds:GetDeviceInformation xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));
        assertTrue(devInfoResp.contains("Hikvision"), "Should identify as Hikvision");
        assertTrue(devInfoResp.contains("DS-2CD2043G2-I"), "Should report model");

        // 2. GetCapabilities — discover available services
        String capsResp = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
                "<tds:GetCapabilities xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));
        assertTrue(capsResp.contains("device_service"), "Should expose device service");
        assertTrue(capsResp.contains("media_service"), "Should expose media service");
        assertTrue(capsResp.contains("ptz_service"), "Should expose PTZ service");

        // 3. GetServices — get service addresses
        String svcResp = sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetServices",
                "<tds:GetServices xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));
        assertTrue(svcResp.contains("/onvif/device_service"), "Should list device service URL");
        assertTrue(svcResp.contains("/onvif/media_service"), "Should list media service URL");
        assertTrue(svcResp.contains("/onvif/ptz_service"), "Should list PTZ service URL");

        // 4. GetProfiles — get media profile
        String profResp = sendRequest(soapRequestTo(
                "/onvif/media_service",
                "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
                "<trt:GetProfiles xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>"
        ));
        assertTrue(profResp.contains("MainStream"), "Should have MainStream profile");
        assertTrue(profResp.contains("H264"), "Should advertise H.264");

        // 5. GetStreamUri — get the actual stream URL
        String uriResp = sendRequest(soapRequestTo(
                "/onvif/media_service",
                "http://www.onvif.org/ver10/media/wsdl/GetStreamUri",
                "<trt:GetStreamUri xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\">" +
                "<trt:StreamSetup><tt:Stream xmlns:tt=\"http://www.onvif.org/ver10/schema\">RTP-Unicast</tt:Stream>" +
                "<tt:Transport xmlns:tt=\"http://www.onvif.org/ver10/schema\"><tt:Protocol>RTSP</tt:Protocol>" +
                "</tt:Transport></trt:StreamSetup>" +
                "<trt:ProfileToken>prof_01</trt:ProfileToken></trt:GetStreamUri>"
        ));
        assertTrue(uriResp.contains("rtsp://"), "Stream URI should be RTSP");
        assertTrue(uriResp.contains("554"), "Stream URI should point to port 554");

        System.out.println("=== Full ONVIF Device Discovery Handshake ===");
        System.out.println("GetDeviceInformation → " + (devInfoResp.contains("200 OK") ? "✅" : "❌"));
        System.out.println("GetCapabilities      → " + (capsResp.contains("200 OK") ? "✅" : "❌"));
        System.out.println("GetServices          → " + (svcResp.contains("200 OK") ? "✅" : "❌"));
        System.out.println("GetProfiles          → " + (profResp.contains("200 OK") ? "✅" : "❌"));
        System.out.println("GetStreamUri         → " + (uriResp.contains("200 OK") ? "✅" : "❌"));
    }

    @Test
    void testGetRequest() throws Exception {
        // Plain HTTP GET — ONVIF service endpoint should return an HTML index
        String request = "GET /onvif/device_service HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        String response = sendRequest(request);

        System.out.println("=== GET /onvif/device_service Response ===");
        System.out.println(response);

        assertContains(response,
                "HTTP/1.1 200 OK",
                "text/html",
                "ONVIF Device Service",
                "/onvif/device_service");
        System.out.println("✅ GET request test passed");
    }

    @Test
    void testMissingSoapAction() throws Exception {
        // POST without SOAPAction header should be treated gracefully
        String body = "<env:Envelope xmlns:env=\"http://www.w3.org/2003/05/soap-envelope\">" +
                "<env:Body><Unknown/></env:Body></env:Envelope>";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String request = "POST /onvif/device_service HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/soap+xml\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" + body;

        String response = sendRequest(request);

        System.out.println("=== Missing SOAPAction Response ===");
        System.out.println(response);

        // Defaults to GetDeviceInformation
        assertContains(response, "200 OK", "Hikvision", "DS-2CD2043G2-I");
        System.out.println("✅ Missing SOAPAction test passed");
    }

    @Test
    void testLogging() throws Exception {
        sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetDeviceInformation",
                "<tds:GetDeviceInformation xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));
        sendRequest(soapRequest(
                "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
                "<tds:GetCapabilities xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>"
        ));

        assertTrue(java.nio.file.Files.exists(logService.getLogDir()),
                "Log directory should exist");

        // Verify onvif-requests log file exists
        String today = java.time.LocalDate.now().toString();
        java.nio.file.Path logFile = logService.getLogDir()
                .resolve("onvif-requests-" + today + ".jsonl");
        assertTrue(java.nio.file.Files.exists(logFile),
                "ONVIF log file should exist: " + logFile);

        System.out.println("✅ Logging test passed");
        System.out.println("Log directory: " + logService.getLogDir());
        System.out.println("Log file: " + logFile);
    }
}
