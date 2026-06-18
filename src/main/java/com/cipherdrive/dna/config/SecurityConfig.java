package com.cipherdrive.dna.config;

import com.cipherdrive.dna.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 6 Configuration for CipherDrive-DNA.
 *
 * Security Architecture:
 *   - Stateless session management (JWT-based, no server-side sessions)
 *   - BCrypt password hashing (strength 12) — compatible with Argon2id migration path
 *   - Role-Based Access Control (RBAC) with ROLE_ADMIN, ROLE_USER, ROLE_AUDITOR
 *   - JWT authentication filter before UsernamePasswordAuthenticationFilter
 *   - CORS configured for SPA frontend (Thymeleaf + Bootstrap 5)
 *   - CSRF disabled (REST API with JWT, not cookie-based)
 *
 * Endpoint Security Matrix:
 *   ┌─────────────────────────────────┬───────────────────┐
 *   │ Endpoint                         │ Required Role     │
 *   ├─────────────────────────────────┼───────────────────┤
 *   │ POST /api/auth/register         │ PUBLIC            │
 *   │ POST /api/auth/login            │ PUBLIC            │
 *   │ POST /api/auth/refresh          │ PUBLIC            │
 *   │ POST /api/auth/logout           │ AUTHENTICATED     │
 *   │ GET  /api/auth/me               │ AUTHENTICATED     │
 *   │ GET  /api/files/**              │ ROLE_USER         │
 *   │ POST /api/files/upload          │ ROLE_USER         │
 *   │ GET  /api/dna/**                │ ROLE_USER         │
 *   │ GET  /api/ics/**                │ ROLE_USER         │
 *   │ GET  /api/tem/**                │ ROLE_USER         │
 *   │ GET  /api/admin/**              │ ROLE_ADMIN        │
 *   │ GET  /api/audit/**              │ ROLE_AUDITOR      │
 *   └─────────────────────────────────┴───────────────────┘
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;

    // ── Security Filter Chain ──

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Disable CSRF — JWT-based auth, not cookie-based
                .csrf(AbstractHttpConfigurer::disable)

                // Configure CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Stateless session — no HTTP sessions, JWT only
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Endpoint authorization
                .authorizeHttpRequests(auth -> auth
                        // ── Public endpoints ──
                        .requestMatchers(
                                "/api/auth/register",
                                "/api/auth/login",
                                "/api/auth/refresh"
                        ).permitAll()

                        // ── Static resources (Thymeleaf) ──
                        .requestMatchers(
                                "/",
                                "/login",
                                "/register",
                                "/css/**",
                                "/js/**",
                                "/webjars/**",
                                "/favicon.ico"
                        ).permitAll()

                        // ── Admin endpoints ──
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // ── Auditor endpoints ──
                        .requestMatchers("/api/audit/**").hasAnyRole("ADMIN", "AUDITOR")

                        // ── User endpoints (DNA, ICS, TEM, Files) ──
                        .requestMatchers("/api/files/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/dna/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/ics/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/tem/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/behavior/**").hasAnyRole("USER", "ADMIN")

                        // ── Authenticated endpoints ──
                        .requestMatchers("/api/auth/**").authenticated()

                        // ── All other requests ──
                        .anyRequest().authenticated()
                )

                // Authentication provider (DaoAuthenticationProvider with BCrypt)
                .authenticationProvider(authenticationProvider())

                // Add JWT filter before Spring Security's default filter
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                // Build
                .build();
    }

    // ── Authentication Provider ──

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    // ── Password Encoder ──

    /**
     * BCrypt with strength 12 (2^12 = 4096 iterations).
     * For production migration to Argon2id, replace with:
     *   new Argon2PasswordEncoder(16, 32, 1, 65536, 3)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    // ── Authentication Manager ──

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // ── CORS Configuration ──

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
                "http://localhost:8080",
                "http://localhost:3000"
        ));
        configuration.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Accept",
                "Origin",
                "X-Client-IP",
                "X-Device-Fingerprint"
        ));
        configuration.setExposedHeaders(List.of(
                "Authorization",
                "X-Total-Count"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
