package com.example.honeycam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for the honeypot.
 * All endpoints are publicly accessible so attackers can freely interact.
 * Login endpoints are logged by AuthController, not by Spring Security.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — honeypot should accept requests from anywhere
            .csrf(csrf -> csrf.disable())

            // Allow all requests without authentication
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/login", "/camera", "/cgi-bin/**", "/ws/**",
                                 "/css/**", "/js/**", "/images/**", "/media/**", "/static/**",
                                 "/error").permitAll()
                .anyRequest().permitAll()
            )

            // Use stateless sessions — we track our own session data
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )

            // Disable Spring Security's default login form
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())

            // Disable frame options to allow embedding (common for camera UIs)
            .headers(headers -> headers
                .frameOptions(frame -> frame.disable())
            );

        return http.build();
    }
}
