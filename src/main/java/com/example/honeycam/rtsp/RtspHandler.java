package com.example.honeycam.rtsp;

import com.example.honeycam.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles a single RTSP client connection.
 * Parses incoming RTSP requests and returns realistic camera-like responses.
 * All requests are logged for attacker behavior analysis.
 */
public class RtspHandler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RtspHandler.class);

    private static final Pattern REQUEST_LINE = Pattern.compile(
            "^(\\S+)\\s+(\\S+)\\s+RTSP/(\\d\\.\\d)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CSEQ = Pattern.compile("^CSeq:\\s*(\\d+)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern SESSION = Pattern.compile("^Session:\\s*(\\S+)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern TRANSPORT = Pattern.compile("^Transport:\\s*(.+)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final String SERVER_HEADER = "HoneyCam-RTSP/1.0";

    // --- Fake SDP (realistic H.264 IP camera) ---
    private static final String FAKE_SDP =
            "v=0\r\n" +
            "o=- %d 1 IN IP4 0.0.0.0\r\n" +
            "s=IP Camera Live Stream\r\n" +
            "i=HoneyCam NC-360\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "t=0 0\r\n" +
            "a=tool:HoneyCam RTSP Server\r\n" +
            "a=type:broadcast\r\n" +
            "a=control:*\r\n" +
            "a=range:npt=0-\r\n" +
            "m=video 0 RTP/AVP 96\r\n" +
            "b=AS:2048\r\n" +
            "a=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1;" +
                "profile-level-id=4D0029;" +
                "sprop-parameter-sets=Z00AH5Y1QVAoAeSAN6BwEBA=,aO48gA==\r\n" +
            "a=framerate:30.0\r\n" +
            "a=control:track1\r\n";

    private final Socket socket;
    private final LogService logService;
    private String sessionId;

    public RtspHandler(Socket socket, LogService logService) {
        this.socket = socket;
        this.logService = logService;
    }

    @Override
    public void run() {
        String clientIp = socket.getInetAddress().getHostAddress();
        logger.debug("RTSP connection from {}", clientIp);

        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream()))) {

            socket.setSoTimeout(30_000); // 30 s timeout

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.equals("\r")) continue;

                Matcher reqMatch = REQUEST_LINE.matcher(line);
                if (!reqMatch.find()) {
                    // Not an RTSP request line — skip or respond with error
                    sendError(writer, 400, "Bad Request", 0);
                    break;
                }

                String method = reqMatch.group(1).toUpperCase();
                String url = reqMatch.group(2);
                String version = reqMatch.group(3);

                // Read headers (terminated by blank line)
                StringBuilder headers = new StringBuilder();
                String headerLine;
                while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                    headers.append(headerLine).append("\r\n");
                }

                String headersStr = headers.toString();
                int cseq = parseCSeq(headersStr);

                logger.debug("RTSP {} {} from {} (CSeq={})", method, url, clientIp, cseq);

                // Log the RTSP request
                logService.logRtspRequest(clientIp, method, url, headersStr);

                switch (method) {
                    case "OPTIONS"   -> handleOptions(writer, cseq);
                    case "DESCRIBE"  -> handleDescribe(writer, cseq, url);
                    case "SETUP"     -> handleSetup(writer, cseq, headersStr);
                    case "PLAY"      -> handlePlay(writer, cseq, headersStr);
                    case "TEARDOWN"  -> handleTeardown(writer, cseq, headersStr);
                    case "PAUSE"     -> handlePause(writer, cseq, headersStr);
                    case "GET_PARAMETER",
                         "SET_PARAMETER" -> handleOk(writer, cseq);
                    default         -> sendError(writer, 501, "Not Implemented", cseq);
                }
                writer.flush();
            }
        } catch (java.net.SocketTimeoutException e) {
            logger.debug("RTSP timeout from {}", clientIp);
        } catch (IOException e) {
            if (!socket.isClosed()) {
                logger.debug("RTSP I/O error from {}: {}", clientIp, e.getMessage());
            }
        }
        logger.debug("RTSP connection closed from {}", clientIp);
    }

    // ---- RTSP method handlers -----------------------------------------

    private void handleOptions(BufferedWriter w, int cseq) throws IOException {
        w.write("RTSP/1.0 200 OK\r\n");
        w.write("CSeq: " + cseq + "\r\n");
        w.write("Public: OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, PAUSE\r\n");
        w.write("Server: " + SERVER_HEADER + "\r\n");
        w.write("\r\n");
    }

    private void handleDescribe(BufferedWriter w, int cseq, String url) throws IOException {
        String sdp = String.format(FAKE_SDP, Instant.now().getEpochSecond());
        byte[] sdpBytes = sdp.getBytes();

        w.write("RTSP/1.0 200 OK\r\n");
        w.write("CSeq: " + cseq + "\r\n");
        w.write("Content-Type: application/sdp\r\n");
        w.write("Content-Length: " + sdpBytes.length + "\r\n");
        w.write("Server: " + SERVER_HEADER + "\r\n");
        w.write("\r\n");
        w.write(sdp);
    }

    private void handleSetup(BufferedWriter w, int cseq, String headers) throws IOException {
        // Extract client ports for realistic response
        String clientPorts = parseClientPorts(headers);
        String serverPorts = "6970-6971";
        if (sessionId == null) {
            sessionId = Integer.toHexString((int) (System.currentTimeMillis() % 0xFFFFFFFFL));
        }

        w.write("RTSP/1.0 200 OK\r\n");
        w.write("CSeq: " + cseq + "\r\n");
        w.write("Transport: RTP/AVP;unicast;client_port=" + clientPorts
                + ";server_port=" + serverPorts + "\r\n");
        w.write("Session: " + sessionId + ";timeout=60\r\n");
        w.write("Server: " + SERVER_HEADER + "\r\n");
        w.write("\r\n");
    }

    private void handlePlay(BufferedWriter w, int cseq, String headers) throws IOException {
        String sesId = parseSession(headers);
        if (sesId == null) sesId = (sessionId != null) ? sessionId : "00000000";

        w.write("RTSP/1.0 200 OK\r\n");
        w.write("CSeq: " + cseq + "\r\n");
        w.write("Session: " + sesId + "\r\n");
        w.write("Range: npt=0.000-\r\n");
        w.write("RTP-Info: url=track1;seq=0;rtptime=0\r\n");
        w.write("Server: " + SERVER_HEADER + "\r\n");
        w.write("\r\n");
    }

    private void handleTeardown(BufferedWriter w, int cseq, String headers) throws IOException {
        String sesId = parseSession(headers);
        if (sesId == null) sesId = (sessionId != null) ? sessionId : "00000000";

        w.write("RTSP/1.0 200 OK\r\n");
        w.write("CSeq: " + cseq + "\r\n");
        w.write("Session: " + sesId + "\r\n");
        w.write("Server: " + SERVER_HEADER + "\r\n");
        w.write("\r\n");

        sessionId = null; // clear session
    }

    private void handlePause(BufferedWriter w, int cseq, String headers) throws IOException {
        String sesId = parseSession(headers);
        if (sesId == null) sesId = "00000000";

        w.write("RTSP/1.0 200 OK\r\n");
        w.write("CSeq: " + cseq + "\r\n");
        w.write("Session: " + sesId + "\r\n");
        w.write("Server: " + SERVER_HEADER + "\r\n");
        w.write("\r\n");
    }

    /** Generic OK for methods like GET_PARAMETER / SET_PARAMETER */
    private void handleOk(BufferedWriter w, int cseq) throws IOException {
        w.write("RTSP/1.0 200 OK\r\n");
        w.write("CSeq: " + cseq + "\r\n");
        w.write("Server: " + SERVER_HEADER + "\r\n");
        w.write("\r\n");
    }

    private void sendError(BufferedWriter w, int code, String reason, int cseq) throws IOException {
        w.write("RTSP/1.0 " + code + " " + reason + "\r\n");
        w.write("CSeq: " + cseq + "\r\n");
        w.write("Server: " + SERVER_HEADER + "\r\n");
        w.write("\r\n");
    }

    // ---- Parsing helpers ----------------------------------------------

    private int parseCSeq(String headers) {
        Matcher m = CSEQ.matcher(headers);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String parseSession(String headers) {
        Matcher m = SESSION.matcher(headers);
        if (m.find()) {
            String s = m.group(1).trim();
            int semi = s.indexOf(';');
            return (semi >= 0) ? s.substring(0, semi) : s;
        }
        return null;
    }

    private String parseClientPorts(String headers) {
        Matcher m = TRANSPORT.matcher(headers);
        if (m.find()) {
            String transport = m.group(1);
            Matcher portMatch = Pattern.compile("client_port=(\\d+)-(\\d+)").matcher(transport);
            if (portMatch.find()) {
                return portMatch.group(1) + "-" + portMatch.group(2);
            }
        }
        return "1234-1235"; // fallback
    }
}
