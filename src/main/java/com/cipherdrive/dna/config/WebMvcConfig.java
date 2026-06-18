package com.cipherdrive.dna.config;

import com.cipherdrive.dna.interceptor.BehaviorLogInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC Configuration — registers behavioral logging interceptor.
 *
 * The interceptor applies to ALL /api/** paths to capture:
 *   - LOGIN, LOGOUT, UPLOAD, DOWNLOAD, DELETE events
 *
 * Non-API paths (static resources, Thymeleaf templates) are excluded.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final BehaviorLogInterceptor behaviorLogInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(behaviorLogInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/refresh",
                        "/api/behavior/**"  // Prevent recursive logging
                );
    }
}
