package com.example.honeycam.controller;

import com.example.honeycam.model.InteractionEvent;
import com.example.honeycam.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a real camera vendor login page.
 * Records all login attempts (credentials) from attackers.
 */
@Controller
public class AuthController {

    private final LogService logService;
    private final String brand;
    private final String modelName;
    private final double fakeSuccessRate;
    private final boolean ptzEnabled;
    private final String panoramaMode;
    private final String panoramaVideoUrl;
    private final String panoramaImageUrl;
    private final int previewLatencyMinMs;
    private final int previewLatencyMaxMs;
    private final boolean autoPatrolEnabled;
    private final double autoPatrolPanDegrees;
    private final double autoPatrolTiltDegrees;
    private final double autoPatrolCycleSeconds;
    private final Set<String> likelyCameraUsers = Set.of("admin", "administrator", "root", "user");

    public AuthController(
            LogService logService,
            @Value("${honeycam.camera.brand:Hikvision}") String brand,
            @Value("${honeycam.camera.model:DS-2CD2043G2-I}") String modelName,
            @Value("${honeycam.auth.fake-success-rate:0.35}") double fakeSuccessRate,
            @Value("${honeycam.interaction.ptz-enabled:true}") boolean ptzEnabled,
            @Value("${honeycam.panorama.mode:auto}") String panoramaMode,
            @Value("${honeycam.panorama.video-url:/media/360-demo.mp4}") String panoramaVideoUrl,
            @Value("${honeycam.panorama.image-url:https://threejs.org/examples/textures/2294472375_24a3b8ef46_o.jpg}") String panoramaImageUrl,
            @Value("${honeycam.deception.preview-latency-ms-min:300}") int previewLatencyMinMs,
            @Value("${honeycam.deception.preview-latency-ms-max:1400}") int previewLatencyMaxMs,
            @Value("${honeycam.interaction.auto-patrol-enabled:false}") boolean autoPatrolEnabled,
            @Value("${honeycam.interaction.auto-patrol.pan-degrees:35}") double autoPatrolPanDegrees,
            @Value("${honeycam.interaction.auto-patrol.tilt-degrees:8}") double autoPatrolTiltDegrees,
            @Value("${honeycam.interaction.auto-patrol.cycle-seconds:18}") double autoPatrolCycleSeconds) {
        this.logService = logService;
        this.brand = brand;
        this.modelName = modelName;
        this.fakeSuccessRate = fakeSuccessRate;
        this.ptzEnabled = ptzEnabled;
        this.panoramaMode = panoramaMode;
        this.panoramaVideoUrl = panoramaVideoUrl;
        this.panoramaImageUrl = panoramaImageUrl;
        this.previewLatencyMinMs = previewLatencyMinMs;
        this.previewLatencyMaxMs = previewLatencyMaxMs;
        this.autoPatrolEnabled = autoPatrolEnabled;
        this.autoPatrolPanDegrees = autoPatrolPanDegrees;
        this.autoPatrolTiltDegrees = autoPatrolTiltDegrees;
        this.autoPatrolCycleSeconds = autoPatrolCycleSeconds;
    }

    /**
     * Login page — mimics a real camera's web authentication UI.
     */
    @GetMapping("/login")
    public String loginPage(Model model, HttpServletRequest request, HttpSession session) {
        model.addAttribute("brand", brand);
        model.addAttribute("modelName", modelName);

        InteractionEvent event = new InteractionEvent();
        event.setActionType(InteractionEvent.ActionType.LOGIN_PAGE_LOAD);
        event.setTimestamp(Instant.now());
        event.setSessionId(session.getId());
        event.setIpAddress(getClientIp(request));
        logService.logInteraction(event);
        return "login";
    }

    /**
     * Handle login submission. Always fails (honeypot), but logs the credentials.
     */
    @PostMapping("/login")
    public String processLogin(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            Model model) {

        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        logService.logLoginAttempt(ip, userAgent, username, password);

        InteractionEvent attemptEvent = new InteractionEvent();
        attemptEvent.setActionType(InteractionEvent.ActionType.LOGIN_ATTEMPT);
        attemptEvent.setTimestamp(Instant.now());
        attemptEvent.setSessionId(session.getId());
        attemptEvent.setIpAddress(ip);
        logService.logInteraction(attemptEvent);

        boolean fakePass = shouldAllowFakePass(username, password);
        if (fakePass) {
            redirectAttributes.addFlashAttribute("notice", "Login accepted. Redirecting to live view...");
            return "redirect:/camera";
        }

        // Always return "login failed" — this is a honeypot
        redirectAttributes.addFlashAttribute("error", "Invalid username or password.");
        return "redirect:/login";
    }

    /**
     * Root path — redirect to login page (like a real camera).
     */
    @GetMapping("/")
    public String index() {
        return "redirect:/login";
    }

    /**
     * Camera view page (after "successful" login — accessible without real auth in honeypot mode).
     */
    @GetMapping("/camera")
    public String cameraPage(Model model) {
        model.addAttribute("brand", brand);
        model.addAttribute("modelName", modelName);
        model.addAttribute("ptzEnabled", ptzEnabled);
        model.addAttribute("panoramaMode", panoramaMode);
        model.addAttribute("panoramaVideoUrl", panoramaVideoUrl);
        model.addAttribute("panoramaImageUrl", panoramaImageUrl);
        model.addAttribute("previewLatencyMinMs", previewLatencyMinMs);
        model.addAttribute("previewLatencyMaxMs", previewLatencyMaxMs);
        model.addAttribute("autoPatrolEnabled", autoPatrolEnabled);
        model.addAttribute("autoPatrolPanDegrees", autoPatrolPanDegrees);
        model.addAttribute("autoPatrolTiltDegrees", autoPatrolTiltDegrees);
        model.addAttribute("autoPatrolCycleSeconds", autoPatrolCycleSeconds);
        return "camera";
    }

    private boolean shouldAllowFakePass(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }

        String normalized = username.trim().toLowerCase();
        double dynamicRate = likelyCameraUsers.contains(normalized) ? fakeSuccessRate : fakeSuccessRate * 0.4;
        return ThreadLocalRandom.current().nextDouble() < dynamicRate;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
