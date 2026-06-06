package com.example.honeycam.onvif;

import com.example.honeycam.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles a single ONVIF HTTP/SOAP client connection.
 * Parses incoming SOAP requests and returns realistic Hikvision-camera-like
 * XML responses for device discovery, media, and PTZ services.
 * <p>
 * All requests are logged via LogService for attacker behaviour analysis.
 */
public class OnvifHandler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(OnvifHandler.class);

    // ── SOAP parsing ──────────────────────────────
    private static final Pattern REQUEST_LINE =
            Pattern.compile("^(GET|POST)\\s+(\\S+)\\s+HTTP/(\\d\\.\\d)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER =
            Pattern.compile("^([\\w-]+):\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOAP_ACTION =
            Pattern.compile("(?:SOAPAction|soapaction):\\s*\"?([^\"]+?)\"?\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern BODY_ACTION =
            Pattern.compile("<(\\w+):(\\w+)\\s", Pattern.CASE_INSENSITIVE);

    // ── Common XML fragments ──────────────────────
    private static final String SOAP_ENV =
            "http://www.w3.org/2003/05/soap-envelope";
    private static final String WS_ADDR =
            "http://www.w3.org/2005/08/addressing";
    private static final String ONVIF_DEVICE =
            "http://www.onvif.org/ver10/device/wsdl";
    private static final String ONVIF_MEDIA =
            "http://www.onvif.org/ver10/media/wsdl";
    private static final String ONVIF_PTZ =
            "http://www.onvif.org/ver20/ptz/wsdl";
    private static final String ONVIF_SCHEMA =
            "http://www.onvif.org/ver10/schema";

    private static final String DEVICE_SERVICE_PATH = "/onvif/device_service";
    private static final String MEDIA_SERVICE_PATH  = "/onvif/media_service";
    private static final String PTZ_SERVICE_PATH    = "/onvif/ptz_service";

    // Pre-generated UUIDs for consistent device identity per run
    private static final String DEVICE_ADDRESS = "urn:uuid:" + randomUUID();
    private static final String DEVICE_METADATA_TOKEN = "meta_" + randomUUID().substring(0, 8);
    private static final String DEVICE_PROFILE_TOKEN  = "prof_" + randomUUID().substring(0, 8);
    private static final String DEVICE_VIDEO_SRC_TOKEN = "vsrc_" + randomUUID().substring(0, 8);
    private static final String DEVICE_VIDEO_ENC_TOKEN = "venc_" + randomUUID().substring(0, 8);

    private final Socket socket;
    private final LogService logService;

    public OnvifHandler(Socket socket, LogService logService) {
        this.socket = socket;
        this.logService = logService;
    }

    @Override
    public void run() {
        String clientIp = socket.getInetAddress().getHostAddress();
        logger.debug("ONVIF connection from {}", clientIp);

        try (socket;
             BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
             OutputStream os = socket.getOutputStream()) {

            socket.setSoTimeout(15_000); // 15 s timeout

            // Read HTTP request
            byte[] buf = new byte[8192];
            int total = 0;
            int read;
            long deadline = System.currentTimeMillis() + 15_000;
            while (total < buf.length && (read = bis.read(buf, total, buf.length - total)) > 0) {
                total += read;
                // Try to see if we have a complete request
                String soFar = new String(buf, 0, total, StandardCharsets.UTF_8);
                if (soFar.contains("\r\n\r\n")) {
                    // Check Content-Length
                    int cl = parseContentLength(soFar);
                    if (cl <= 0) break;
                    int headerEnd = soFar.indexOf("\r\n\r\n") + 4;
                    if (total >= headerEnd + cl) break;
                }
                if (System.currentTimeMillis() > deadline) break;
            }

            String raw = new String(buf, 0, total, StandardCharsets.UTF_8);
            String[] parts = raw.split("\r\n\r\n", 2);
            String headerBlock = parts.length > 0 ? parts[0] : raw;
            String body = parts.length > 1 ? parts[1] : "";

            // Parse method and path
            String method = "POST";
            String path = "/";
            Matcher rm = REQUEST_LINE.matcher(headerBlock);
            if (rm.find()) {
                method = rm.group(1).toUpperCase();
                path = rm.group(2);
            }

            // Determine SOAP action
            String soapAction = extractSoapAction(headerBlock, body);
            if (soapAction.isEmpty()) {
                soapAction = extractActionFromBody(body);
            }

            logger.info("[ONVIF] {} {} {} SOAP={}", clientIp, method, path, soapAction);

            // Log the request
            logService.logOnvifRequest(clientIp, method, path, soapAction,
                    body.length() > 2000 ? body.substring(0, 2000) : body);

            // Route to the right handler
            String response;
            if ("GET".equals(method)) {
                response = buildGetResponse(path);
            } else if (soapAction.contains("/device/wsdl") || path.contains("device")) {
                response = buildDeviceResponse(body, soapAction);
            } else if (soapAction.contains("/media/wsdl") || path.contains("media")) {
                response = buildMediaResponse(body, soapAction);
            } else if (soapAction.contains("/ptz/wsdl") || path.contains("ptz")) {
                response = buildPtzResponse(body, soapAction);
            } else if (!soapAction.isEmpty()) {
                // Unknown action — return generic ONVIF fault
                response = buildFault(soapAction, "Action not supported by this device");
            } else {
                // No SOAP action — could be a plain HTTP scan
                response = buildServiceIndex();
            }

            byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
            os.write(respBytes);
            os.flush();

        } catch (java.net.SocketTimeoutException e) {
            logger.debug("ONVIF timeout from {}", clientIp);
        } catch (IOException e) {
            if (!socket.isClosed()) {
                logger.debug("ONVIF I/O from {}: {}", clientIp, e.getMessage());
            }
        }
        logger.debug("ONVIF connection closed from {}", clientIp);
    }

    // ── Device service responses ──────────────────

    private String buildDeviceResponse(String body, String action) {
        if (action.contains("GetDeviceInformation"))
            return soapResponse(getDeviceInformationXml());
        if (action.contains("GetSystemDateAndTime"))
            return soapResponse(getSystemDateAndTimeXml());
        if (action.contains("GetCapabilities"))
            return soapResponse(getCapabilitiesXml());
        if (action.contains("GetServices"))
            return soapResponse(getServicesXml());
        if (action.contains("GetScopes"))
            return soapResponse(getScopesXml());
        if (action.contains("GetNetworkInterfaces"))
            return soapResponse(getNetworkInterfacesXml());
        if (action.contains("GetHostname"))
            return soapResponse(getHostnameXml());
        if (action.contains("GetDeviceState"))
            return soapResponse(getDeviceStateXml());
        return soapResponse(getDeviceInformationXml()); // default
    }

    // ── Media service responses ───────────────────

    private String buildMediaResponse(String body, String action) {
        if (action.contains("GetProfiles"))
            return soapResponse(getProfilesXml());
        if (action.contains("GetStreamUri"))
            return soapResponse(getStreamUriXml());
        if (action.contains("GetVideoSources"))
            return soapResponse(getVideoSourcesXml());
        if (action.contains("GetVideoEncoderConfigurations"))
            return soapResponse(getVideoEncoderConfigsXml());
        if (action.contains("GetSnapshotUri"))
            return soapResponse(getSnapshotUriXml());
        return soapResponse(getProfilesXml()); // default
    }

    // ── PTZ service responses ─────────────────────
    private String buildPtzResponse(String body, String action) {
        if (action.contains("GetConfigurations"))
            return soapResponse(getPtzConfigurationsXml());
        if (action.contains("GetConfigurationOptions"))
            return soapResponse(getPtzConfigurationOptionsXml());
        if (action.contains("GetStatus"))
            return soapResponse(getPtzStatusXml());
        if (action.contains("GetNodes"))
            return soapResponse(getPtzNodesXml());
        return soapResponse(getPtzConfigurationsXml()); // default
    }

    // ── GET handler ───────────────────────────────
    private String buildGetResponse(String path) {
        String body = "<html><body><h1>ONVIF Device Service</h1>" +
                "<p>Available at:</p><ul>" +
                "<li>" + DEVICE_SERVICE_PATH + "</li>" +
                "<li>" + MEDIA_SERVICE_PATH + "</li>" +
                "<li>" + PTZ_SERVICE_PATH + "</li>" +
                "</ul></body></html>";
        return httpResponse(200, "text/html", body);
    }

    // ── Service index (no SOAP action, plain HTTP) ─
    private String buildServiceIndex() {
        String body = "<env:Envelope xmlns:env=\"" + SOAP_ENV + "\">" +
                "<env:Body>" +
                "<env:Fault><env:Code><env:Value>env:Sender</env:Value>" +
                "</env:Code><env:Reason><env:Text xml:lang=\"en\">" +
                "Missing SOAPAction header. Use POST with SOAPAction.</env:Text>" +
                "</env:Reason></env:Fault></env:Body></env:Envelope>";
        return httpResponse(500, "application/soap+xml", body);
    }

    private String buildFault(String action, String reason) {
        String body = "<env:Envelope xmlns:env=\"" + SOAP_ENV + "\">" +
                "<env:Body>" +
                "<env:Fault><env:Code><env:Value>env:Sender</env:Value>" +
                "</env:Code><env:Reason><env:Text xml:lang=\"en\">" +
                escapeXml(reason) + "</env:Text></env:Reason>" +
                "<env:Detail><tds:ActionNotSupported xmlns:tds=\"" + ONVIF_DEVICE + "\"/>" +
                "</env:Detail></env:Fault></env:Body></env:Envelope>";
        return httpResponse(500, "application/soap+xml", body);
    }

    // ═══════════════════════════════════════════════
    //  XML builders — realistic Hikvision-like data
    // ═══════════════════════════════════════════════

    private String getDeviceInformationXml() {
        return "<tds:GetDeviceInformationResponse xmlns:tds=\"" + ONVIF_DEVICE + "\">" +
                "<tds:Manufacturer>Hikvision</tds:Manufacturer>" +
                "<tds:Model>DS-2CD2043G2-I</tds:Model>" +
                "<tds:FirmwareVersion>V5.7.15 build 240605</tds:FirmwareVersion>" +
                "<tds:SerialNumber>DS-2CD2043G2-I20240101AAW" + DEVICE_METADATA_TOKEN + "</tds:SerialNumber>" +
                "<tds:HardwareId>IPC-" + DEVICE_METADATA_TOKEN + "</tds:HardwareId>" +
                "</tds:GetDeviceInformationResponse>";
    }

    private String getSystemDateAndTimeXml() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String utc = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        // Local time: fake UTC+8 (China time for Hikvision)
        ZonedDateTime local = now.withZoneSameInstant(ZoneOffset.ofHours(8));
        String localStr = local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        return "<tds:GetSystemDateAndTimeResponse xmlns:tds=\"" + ONVIF_DEVICE + "\">" +
                "<tds:SystemDateAndTime>" +
                "<tt:DateTimeType xmlns:tt=\"" + ONVIF_SCHEMA + "\">Manual</tt:DateTimeType>" +
                "<tt:DaylightSavings xmlns:tt=\"" + ONVIF_SCHEMA + "\">false</tt:DaylightSavings>" +
                "<tt:TimeZone xmlns:tt=\"" + ONVIF_SCHEMA + "\"><tt:TZ>CST-8</tt:TZ></tt:TimeZone>" +
                "<tt:UTCDateTime xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:Time><tt:Hour>" + now.getHour() + "</tt:Hour>" +
                "<tt:Minute>" + now.getMinute() + "</tt:Minute>" +
                "<tt:Second>" + now.getSecond() + "</tt:Second></tt:Time>" +
                "<tt:Date><tt:Year>" + now.getYear() + "</tt:Year>" +
                "<tt:Month>" + now.getMonthValue() + "</tt:Month>" +
                "<tt:Day>" + now.getDayOfMonth() + "</tt:Day></tt:Date>" +
                "</tt:UTCDateTime>" +
                "<tt:LocalDateTime xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:Time><tt:Hour>" + local.getHour() + "</tt:Hour>" +
                "<tt:Minute>" + local.getMinute() + "</tt:Minute>" +
                "<tt:Second>" + local.getSecond() + "</tt:Second></tt:Time>" +
                "<tt:Date><tt:Year>" + local.getYear() + "</tt:Year>" +
                "<tt:Month>" + local.getMonthValue() + "</tt:Month>" +
                "<tt:Day>" + local.getDayOfMonth() + "</tt:Day></tt:Date>" +
                "</tt:LocalDateTime>" +
                "</tds:SystemDateAndTime>" +
                "</tds:GetSystemDateAndTimeResponse>";
    }

    private String getCapabilitiesXml() {
        return "<tds:GetCapabilitiesResponse xmlns:tds=\"" + ONVIF_DEVICE + "\">" +
                "<tds:Capabilities>" +
                "<tt:Device xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:XAddr>http://0.0.0.0/onvif/device_service</tt:XAddr>" +
                "</tt:Device>" +
                "<tt:Media xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:XAddr>http://0.0.0.0/onvif/media_service</tt:XAddr>" +
                "<tt:StreamingCapabilities>" +
                "<tt:RTPMulticast>true</tt:RTPMulticast>" +
                "<tt:RTP_USCORETCP>true</tt:RTP_USCORETCP>" +
                "<tt:RTP_USCORERTSP_USCORETCP>true</tt:RTP_USCORERTSP_USCORETCP>" +
                "</tt:StreamingCapabilities>" +
                "</tt:Media>" +
                "<tt:PTZ xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:XAddr>http://0.0.0.0/onvif/ptz_service</tt:XAddr>" +
                "</tt:PTZ>" +
                "<tt:Analytics xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:XAddr>http://0.0.0.0/onvif/device_service</tt:XAddr>" +
                "</tt:Analytics>" +
                "</tds:Capabilities>" +
                "</tds:GetCapabilitiesResponse>";
    }

    private String getServicesXml() {
        return "<tds:GetServicesResponse xmlns:tds=\"" + ONVIF_DEVICE + "\">" +
                "<tds:Service>" +
                "<tds:Namespace>" + ONVIF_DEVICE + "</tds:Namespace>" +
                "<tds:XAddr>http://0.0.0.0/onvif/device_service</tds:XAddr>" +
                "<tds:Version><tt:Major xmlns:tt=\"" + ONVIF_SCHEMA + "\">2</tt:Major>" +
                "<tt:Minor xmlns:tt=\"" + ONVIF_SCHEMA + "\">60</tt:Minor></tds:Version>" +
                "</tds:Service>" +
                "<tds:Service>" +
                "<tds:Namespace>" + ONVIF_MEDIA + "</tds:Namespace>" +
                "<tds:XAddr>http://0.0.0.0/onvif/media_service</tds:XAddr>" +
                "<tds:Version><tt:Major xmlns:tt=\"" + ONVIF_SCHEMA + "\">2</tt:Major>" +
                "<tt:Minor xmlns:tt=\"" + ONVIF_SCHEMA + "\">50</tt:Minor></tds:Version>" +
                "</tds:Service>" +
                "<tds:Service>" +
                "<tds:Namespace>" + ONVIF_PTZ + "</tds:Namespace>" +
                "<tds:XAddr>http://0.0.0.0/onvif/ptz_service</tds:XAddr>" +
                "<tds:Version><tt:Major xmlns:tt=\"" + ONVIF_SCHEMA + "\">2</tt:Major>" +
                "<tt:Minor xmlns:tt=\"" + ONVIF_SCHEMA + "\">20</tt:Minor></tds:Version>" +
                "</tds:Service>" +
                "</tds:GetServicesResponse>";
    }

    private String getScopesXml() {
        return "<tds:GetScopesResponse xmlns:tds=\"" + ONVIF_DEVICE + "\">" +
                "<tds:Scopes>" +
                "<tt:ScopeDef xmlns:tt=\"" + ONVIF_SCHEMA + "\">Fixed</tt:ScopeDef>" +
                "<tt:ScopeItem xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "onvif://www.onvif.org/type/video_encoder</tt:ScopeItem>" +
                "<tt:ScopeDef xmlns:tt=\"" + ONVIF_SCHEMA + "\">Fixed</tt:ScopeDef>" +
                "<tt:ScopeItem xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "onvif://www.onvif.org/type/ptz</tt:ScopeItem>" +
                "<tt:ScopeDef xmlns:tt=\"" + ONVIF_SCHEMA + "\">Fixed</tt:ScopeDef>" +
                "<tt:ScopeItem xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "onvif://www.onvif.org/hardware/DS-2CD2043G2-I</tt:ScopeItem>" +
                "<tt:ScopeDef xmlns:tt=\"" + ONVIF_SCHEMA + "\">Configurable</tt:ScopeDef>" +
                "<tt:ScopeItem xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "onvif://www.onvif.org/name/IP%20Camera</tt:ScopeItem>" +
                "</tds:Scopes>" +
                "</tds:GetScopesResponse>";
    }

    private String getNetworkInterfacesXml() {
        return "<tds:GetNetworkInterfacesResponse xmlns:tds=\"" + ONVIF_DEVICE + "\">" +
                "<tds:NetworkInterfaces token=\"eth0\">" +
                "<tt:Enabled xmlns:tt=\"" + ONVIF_SCHEMA + "\">true</tt:Enabled>" +
                "<tt:Info xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:Name>eth0</tt:Name><tt:HwAddress>00:50:C2:00:00:01</tt:HwAddress>" +
                "</tt:Info>" +
                "<tt:IPv4 xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:Enabled>true</tt:Enabled>" +
                "<tt:Config><tt:Manual><tt:Address>192.168.1.64</tt:Address>" +
                "<tt:PrefixLength>24</tt:PrefixLength></tt:Manual>" +
                "<tt:DHCP>false</tt:DHCP></tt:Config>" +
                "</tt:IPv4>" +
                "</tds:NetworkInterfaces>" +
                "</tds:GetNetworkInterfacesResponse>";
    }

    private String getHostnameXml() {
        return "<tds:GetHostnameResponse xmlns:tds=\"" + ONVIF_DEVICE + "\">" +
                "<tds:HostnameInformation>" +
                "<tt:FromDHCP xmlns:tt=\"" + ONVIF_SCHEMA + "\">false</tt:FromDHCP>" +
                "<tt:Name xmlns:tt=\"" + ONVIF_SCHEMA + "\">IPC-" +
                DEVICE_METADATA_TOKEN + "</tt:Name>" +
                "</tds:HostnameInformation>" +
                "</tds:GetHostnameResponse>";
    }

    private String getDeviceStateXml() {
        return "<tds:GetDeviceStateResponse xmlns:tds=\"" + ONVIF_DEVICE + "\">" +
                "<tds:State>Operational</tds:State>" +
                "</tds:GetDeviceStateResponse>";
    }

    // ── Media ──────────────────────────────────────

    private String getProfilesXml() {
        return "<trt:GetProfilesResponse xmlns:trt=\"" + ONVIF_MEDIA + "\">" +
                "<trt:Profiles token=\"" + DEVICE_PROFILE_TOKEN + "\" fixed=\"true\">" +
                "<tt:Name xmlns:tt=\"" + ONVIF_SCHEMA + "\">MainStream</tt:Name>" +
                "<tt:VideoSourceConfiguration token=\"" + DEVICE_VIDEO_SRC_TOKEN + "\" " +
                "xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:Name>VideoSource_1</tt:Name>" +
                "<tt:UseCount>1</tt:UseCount>" +
                "<tt:SourceToken>" + DEVICE_VIDEO_SRC_TOKEN + "</tt:SourceToken>" +
                "<tt:Bounds x=\"0\" y=\"0\" width=\"1920\" height=\"1080\"/>" +
                "</tt:VideoSourceConfiguration>" +
                "<tt:VideoEncoderConfiguration token=\"" + DEVICE_VIDEO_ENC_TOKEN + "\" " +
                "xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:Name>VideoEncoder_1</tt:Name>" +
                "<tt:UseCount>1</tt:UseCount>" +
                "<tt:Encoding>H264</tt:Encoding>" +
                "<tt:Resolution><tt:Width>1920</tt:Width>" +
                "<tt:Height>1080</tt:Height></tt:Resolution>" +
                "<tt:Quality>4.0</tt:Quality>" +
                "<tt:RateControl><tt:FrameRateLimit>30</tt:FrameRateLimit>" +
                "<tt:EncodingInterval>1</tt:EncodingInterval>" +
                "<tt:BitrateLimit>2048</tt:BitrateLimit></tt:RateControl>" +
                "<tt:H264><tt:GovLength>60</tt:GovLength>" +
                "<tt:H264Profile>Main</tt:H264Profile></tt:H264>" +
                "<tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type>" +
                "<tt:IPv4Address>239.255.255.250</tt:IPv4Address>" +
                "</tt:Address><tt:Port>37020</tt:Port><tt:TTL>64</tt:TTL>" +
                "<tt:AutoStart>false</tt:AutoStart></tt:Multicast>" +
                "<tt:SessionTimeout>PT10S</tt:SessionTimeout>" +
                "</tt:VideoEncoderConfiguration>" +
                "<tt:PTZConfiguration token=\"ptz_" + DEVICE_PROFILE_TOKEN + "\" " +
                "xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:Name>PTZ_1</tt:Name>" +
                "<tt:UseCount>1</tt:UseCount>" +
                "<tt:NodeToken>ptz_node_" + DEVICE_METADATA_TOKEN + "</tt:NodeToken>" +
                "<tt:DefaultContinuousPanTiltVelocitySpace>" +
                "<tt:PanTilt x=\"0.0\" y=\"0.0\" space=\"http://www.onvif.org/ver10/tptz/" +
                "PanTiltSpaces/VelocityGenericSpace\"/>" +
                "</tt:DefaultContinuousPanTiltVelocitySpace>" +
                "<tt:DefaultContinuousZoomVelocitySpace>" +
                "<tt:Zoom x=\"0.0\" space=\"http://www.onvif.org/ver10/tptz/" +
                "ZoomSpaces/VelocityGenericSpace\"/>" +
                "</tt:DefaultContinuousZoomVelocitySpace>" +
                "<tt:DefaultPTZTimeout>PT10S</tt:DefaultPTZTimeout>" +
                "</tt:PTZConfiguration>" +
                "</trt:Profiles>" +
                "</trt:GetProfilesResponse>";
    }

    private String getStreamUriXml() {
        return "<trt:GetStreamUriResponse xmlns:trt=\"" + ONVIF_MEDIA + "\">" +
                "<trt:MediaUri>" +
                "<tt:Uri xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "rtsp://0.0.0.0:554/Streaming/Channels/101</tt:Uri>" +
                "<tt:InvalidAfterConnect xmlns:tt=\"" + ONVIF_SCHEMA + "\">false" +
                "</tt:InvalidAfterConnect>" +
                "<tt:InvalidAfterReboot xmlns:tt=\"" + ONVIF_SCHEMA + "\">false" +
                "</tt:InvalidAfterReboot>" +
                "<tt:Timeout xmlns:tt=\"" + ONVIF_SCHEMA + "\">PT60S</tt:Timeout>" +
                "</trt:MediaUri>" +
                "</trt:GetStreamUriResponse>";
    }

    private String getVideoSourcesXml() {
        return "<trt:GetVideoSourcesResponse xmlns:trt=\"" + ONVIF_MEDIA + "\">" +
                "<trt:VideoSources token=\"" + DEVICE_VIDEO_SRC_TOKEN + "\">" +
                "<tt:Framerate xmlns:tt=\"" + ONVIF_SCHEMA + "\">30</tt:Framerate>" +
                "<tt:Resolution xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:Width>1920</tt:Width><tt:Height>1080</tt:Height></tt:Resolution>" +
                "</trt:VideoSources>" +
                "</trt:GetVideoSourcesResponse>";
    }

    private String getVideoEncoderConfigsXml() {
        return "<trt:GetVideoEncoderConfigurationsResponse xmlns:trt=\"" + ONVIF_MEDIA + "\">" +
                "<trt:Configurations token=\"" + DEVICE_VIDEO_ENC_TOKEN + "\">" +
                "<tt:Name xmlns:tt=\"" + ONVIF_SCHEMA + "\">VideoEncoder_1</tt:Name>" +
                "<tt:UseCount xmlns:tt=\"" + ONVIF_SCHEMA + "\">1</tt:UseCount>" +
                "<tt:Encoding xmlns:tt=\"" + ONVIF_SCHEMA + "\">H264</tt:Encoding>" +
                "<tt:Resolution xmlns:tt=\"" + ONVIF_SCHEMA + "\"><tt:Width>1920</tt:Width>" +
                "<tt:Height>1080</tt:Height></tt:Resolution>" +
                "<tt:Quality xmlns:tt=\"" + ONVIF_SCHEMA + "\">4.0</tt:Quality>" +
                "<tt:RateControl xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:FrameRateLimit>30</tt:FrameRateLimit>" +
                "<tt:EncodingInterval>1</tt:EncodingInterval>" +
                "<tt:BitrateLimit>2048</tt:BitrateLimit></tt:RateControl>" +
                "<tt:H264 xmlns:tt=\"" + ONVIF_SCHEMA + "\"><tt:GovLength>60</tt:GovLength>" +
                "<tt:H264Profile>Main</tt:H264Profile></tt:H264>" +
                "</trt:Configurations>" +
                "</trt:GetVideoEncoderConfigurationsResponse>";
    }

    private String getSnapshotUriXml() {
        return "<trt:GetSnapshotUriResponse xmlns:trt=\"" + ONVIF_MEDIA + "\">" +
                "<trt:MediaUri>" +
                "<tt:Uri xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "http://0.0.0.0/onvif/snapshot/" + DEVICE_PROFILE_TOKEN + "</tt:Uri>" +
                "<tt:InvalidAfterConnect xmlns:tt=\"" + ONVIF_SCHEMA + "\">false" +
                "</tt:InvalidAfterConnect>" +
                "<tt:InvalidAfterReboot xmlns:tt=\"" + ONVIF_SCHEMA + "\">false" +
                "</tt:InvalidAfterReboot>" +
                "<tt:Timeout xmlns:tt=\"" + ONVIF_SCHEMA + "\">PT10S</tt:Timeout>" +
                "</trt:MediaUri>" +
                "</trt:GetSnapshotUriResponse>";
    }

    // ── PTZ ────────────────────────────────────────
    private String getPtzConfigurationsXml() {
        return "<tptz:GetConfigurationsResponse xmlns:tptz=\"" + ONVIF_PTZ + "\">" +
                "<tptz:PTZConfiguration token=\"ptz_" + DEVICE_PROFILE_TOKEN + "\">" +
                "<tt:Name xmlns:tt=\"" + ONVIF_SCHEMA + "\">PTZ_1</tt:Name>" +
                "<tt:UseCount xmlns:tt=\"" + ONVIF_SCHEMA + "\">1</tt:UseCount>" +
                "<tt:NodeToken xmlns:tt=\"" + ONVIF_SCHEMA + "\">ptz_node_" +
                DEVICE_METADATA_TOKEN + "</tt:NodeToken>" +
                "<tt:DefaultContinuousPanTiltVelocitySpace xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:PanTilt x=\"0.0\" y=\"0.0\" space=\"http://www.onvif.org/ver10/tptz/" +
                "PanTiltSpaces/VelocityGenericSpace\"/>" +
                "</tt:DefaultContinuousPanTiltVelocitySpace>" +
                "</tptz:PTZConfiguration>" +
                "</tptz:GetConfigurationsResponse>";
    }

    private String getPtzConfigurationOptionsXml() {
        return "<tptz:GetConfigurationOptionsResponse xmlns:tptz=\"" + ONVIF_PTZ + "\">" +
                "<tptz:PTZConfigurationOptions>" +
                "<tt:Spaces xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:ContinuousPanTiltVelocitySpace>" +
                "<tt:URI>http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace" +
                "</tt:URI>" +
                "<tt:XRange><tt:Min>-1.0</tt:Min><tt:Max>1.0</tt:Max></tt:XRange>" +
                "<tt:YRange><tt:Min>-1.0</tt:Min><tt:Max>1.0</tt:Max></tt:YRange>" +
                "</tt:ContinuousPanTiltVelocitySpace>" +
                "<tt:ContinuousZoomVelocitySpace>" +
                "<tt:URI>http://www.onvif.org/ver10/tptz/ZoomSpaces/VelocityGenericSpace" +
                "</tt:URI>" +
                "<tt:XRange><tt:Min>-1.0</tt:Min><tt:Max>1.0</tt:Max></tt:XRange>" +
                "</tt:ContinuousZoomVelocitySpace>" +
                "<tt:PanTiltSpeedSpace>" +
                "<tt:URI>http://www.onvif.org/ver10/tptz/PanTiltSpaces/PositionGenericSpace" +
                "</tt:URI>" +
                "<tt:XRange><tt:Min>-170.0</tt:Min><tt:Max>170.0</tt:Max></tt:XRange>" +
                "<tt:YRange><tt:Min>-65.0</tt:Min><tt:Max>65.0</tt:Max></tt:YRange>" +
                "</tt:PanTiltSpeedSpace>" +
                "<tt:ZoomSpeedSpace>" +
                "<tt:URI>http://www.onvif.org/ver10/tptz/ZoomSpaces/PositionGenericSpace" +
                "</tt:URI>" +
                "<tt:XRange><tt:Min>1.0</tt:Min><tt:Max>4.0</tt:Max></tt:XRange>" +
                "</tt:ZoomSpeedSpace>" +
                "</tt:Spaces>" +
                "<tt:PTZTimeout>" +
                "<tt:Min>PT1S</tt:Min><tt:Max>PT300S</tt:Max>" +
                "</tt:PTZTimeout>" +
                "</tptz:PTZConfigurationOptions>" +
                "</tptz:GetConfigurationOptionsResponse>";
    }

    private String getPtzStatusXml() {
        return "<tptz:GetStatusResponse xmlns:tptz=\"" + ONVIF_PTZ + "\">" +
                "<tptz:PTZStatus>" +
                "<tt:Position xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:PanTilt x=\"0.0\" y=\"0.0\" " +
                "space=\"http://www.onvif.org/ver10/tptz/PanTiltSpaces/PositionGenericSpace\"/>" +
                "<tt:Zoom x=\"1.0\" " +
                "space=\"http://www.onvif.org/ver10/tptz/ZoomSpaces/PositionGenericSpace\"/>" +
                "</tt:Position>" +
                "<tt:MoveStatus xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:PanTilt>IDLE</tt:PanTilt><tt:Zoom>IDLE</tt:Zoom>" +
                "</tt:MoveStatus>" +
                "<tt:UtcTime xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                Instant.now().toString() + "</tt:UtcTime>" +
                "</tptz:PTZStatus>" +
                "</tptz:GetStatusResponse>";
    }

    private String getPtzNodesXml() {
        return "<tptz:GetNodesResponse xmlns:tptz=\"" + ONVIF_PTZ + "\">" +
                "<tptz:PTZNode token=\"ptz_node_" + DEVICE_METADATA_TOKEN + "\">" +
                "<tt:Name xmlns:tt=\"" + ONVIF_SCHEMA + "\">PTZ_Node_1</tt:Name>" +
                "<tt:SupportedPTZSpaces xmlns:tt=\"" + ONVIF_SCHEMA + "\">" +
                "<tt:ContinuousPanTiltVelocitySpace>" +
                "<tt:URI>http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace" +
                "</tt:URI></tt:ContinuousPanTiltVelocitySpace>" +
                "<tt:ContinuousZoomVelocitySpace>" +
                "<tt:URI>http://www.onvif.org/ver10/tptz/ZoomSpaces/VelocityGenericSpace" +
                "</tt:URI></tt:ContinuousZoomVelocitySpace>" +
                "<tt:PanTiltSpeedSpace>" +
                "<tt:URI>http://www.onvif.org/ver10/tptz/PanTiltSpaces/PositionGenericSpace" +
                "</tt:URI></tt:PanTiltSpeedSpace>" +
                "<tt:ZoomSpeedSpace>" +
                "<tt:URI>http://www.onvif.org/ver10/tptz/ZoomSpaces/PositionGenericSpace" +
                "</tt:URI></tt:ZoomSpeedSpace>" +
                "</tt:SupportedPTZSpaces>" +
                "<tt:MaximumNumberOfPresets xmlns:tt=\"" + ONVIF_SCHEMA + "\">256" +
                "</tt:MaximumNumberOfPresets>" +
                "<tt:HomeSupported xmlns:tt=\"" + ONVIF_SCHEMA + "\">true" +
                "</tt:HomeSupported>" +
                "</tptz:PTZNode>" +
                "</tptz:GetNodesResponse>";
    }

    // ═══════════════════════════════════════════════
    //  HTTP response helpers
    // ═══════════════════════════════════════════════

    private String soapResponse(String bodyXml) {
        return httpResponse(200, "application/soap+xml; charset=utf-8",
                "<env:Envelope xmlns:env=\"" + SOAP_ENV + "\"" +
                        " xmlns:wsdl=\"" + ONVIF_DEVICE + "\">" +
                        "<env:Header/>" +
                        "<env:Body>" + bodyXml + "</env:Body>" +
                        "</env:Envelope>");
    }

    private String httpResponse(int code, String contentType, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ");
        sb.append(code == 200 ? "OK" : "Internal Server Error").append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
        sb.append("Server: gSOAP/2.8\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Date: ").append(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n");
        sb.append("\r\n");
        sb.append(body);
        return sb.toString();
    }

    // ── Parsing helpers ────────────────────────────

    private String extractSoapAction(String headerBlock, String body) {
        // Search header block first
        Matcher hm = SOAP_ACTION.matcher(headerBlock);
        if (hm.find()) return hm.group(1).trim();
        // Try body
        Matcher bm = SOAP_ACTION.matcher(body);
        if (bm.find()) return bm.group(1).trim();
        return "";
    }

    private String extractActionFromBody(String body) {
        Matcher m = BODY_ACTION.matcher(body);
        if (m.find()) return m.group(2);
        return "";
    }

    private int parseContentLength(String raw) {
        Matcher m = Pattern.compile("(?i)Content-Length:\\s*(\\d+)").matcher(raw);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String randomUUID() {
        return UUID.randomUUID().toString();
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
