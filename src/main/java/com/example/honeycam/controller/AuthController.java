package com.example.honeycam.controller;

import com.example.honeycam.config.HoneyCamProperties;
import com.example.honeycam.model.InteractionEvent;
import com.example.honeycam.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
 * Records all login attempts and, with a configurable fake success rate,
 * occasionally lets attackers through to the camera view.
 */
@Controller
public class AuthController {

    private final LogService logService;
    private final HoneyCamProperties props;
    private final Set<String> likelyCameraUsers = Set.of("admin", "administrator", "root", "user");

    public AuthController(LogService logService, HoneyCamProperties props) {
        this.logService = logService;
        this.props = props;
    }

    /**
     * Login page — mimics a real camera's web authentication UI.
     */
    @GetMapping("/login")
    public String loginPage(Model model, HttpServletRequest request, HttpSession session) {
        HoneyCamProperties.Camera cam = props.getCamera();
        model.addAttribute("brand", cam.getBrand());
        model.addAttribute("modelName", cam.getModel());

        InteractionEvent event = new InteractionEvent();
        event.setActionType(InteractionEvent.ActionType.LOGIN_PAGE_LOAD);
        event.setTimestamp(Instant.now());
        event.setSessionId(session.getId());
        event.setIpAddress(getClientIp(request));
        logService.logInteraction(event);
        return "login";
    }

    /**
     * Handle login submission. Logs credentials and probabilistically
     * grants access to simulate a realistic authentication flow.
     */
    @PostMapping("/login")
    public String processLogin(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        logService.logLoginAttempt(ip, userAgent, username, password);

        InteractionEvent attemptEvent = new InteractionEvent();
        attemptEvent.setActionType(InteractionEvent.ActionType.LOGIN_ATTEMPT);
        attemptEvent.setTimestamp(Instant.now());
        attemptEvent.setSessionId(session.getId());
        attemptEvent.setIpAddress(ip);
        logService.logInteraction(attemptEvent);

        if (shouldAllowFakePass(username, password)) {
            session.setAttribute("authenticated", true);
            redirectAttributes.addFlashAttribute("notice",
                    "Login accepted. Redirecting to live view...");
            return "redirect:/camera";
        }

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

    // ---- private helpers ----

    private boolean shouldAllowFakePass(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        if (isWeakCredential(username, password)) {
            return true;
        }
        String normalized = username.trim().toLowerCase();
        double rate = props.getAuth().getFakeSuccessRate();
        double dynamicRate = likelyCameraUsers.contains(normalized) ? rate : rate * 0.4;
        return ThreadLocalRandom.current().nextDouble() < dynamicRate;
    }

    private boolean isWeakCredential(String username, String password) {
        String normalizedUser = username.trim().toLowerCase();
        String normalizedPass = password.trim();
        for (String pair : props.getAuth().getWeakCredentials()) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String[] parts = pair.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            String weakUser = parts[0].trim().toLowerCase();
            String weakPass = parts[1].trim();
            if (!weakUser.isEmpty() && normalizedUser.equals(weakUser) && normalizedPass.equals(weakPass)) {
                return true;
            }
        }
        return false;
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
