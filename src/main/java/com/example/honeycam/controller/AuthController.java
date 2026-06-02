package com.example.honeycam.controller;

import com.example.honeycam.service.LogService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Simulates a real camera vendor login page.
 * Records all login attempts (credentials) from attackers.
 */
@Controller
public class AuthController {

    private final LogService logService;

    public AuthController(LogService logService) {
        this.logService = logService;
    }

    /**
     * Login page — mimics a real camera's web authentication UI.
     */
    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("brand", "IP Camera");
        model.addAttribute("modelName", "NC-360");
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
            RedirectAttributes redirectAttributes,
            Model model) {

        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");

        logService.logLoginAttempt(ip, userAgent, username, password);

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
        model.addAttribute("brand", "IP Camera");
        model.addAttribute("modelName", "NC-360");
        return "camera";
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
