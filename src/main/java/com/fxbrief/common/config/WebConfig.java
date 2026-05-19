package com.fxbrief.common.config;

import com.fxbrief.auth.security.FrontendProperties;
import com.fxbrief.common.security.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private static final String LOCAL_DEV_ORIGIN = "http://localhost:4200";

    private final RateLimitInterceptor rateLimitInterceptor;
    private final FrontendProperties frontendProperties;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/auth/login",
                        "/auth/register",
                        "/auth/forgot-password");
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(buildAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private List<String> buildAllowedOrigins() {
        List<String> origins = new ArrayList<>();
        origins.add(LOCAL_DEV_ORIGIN);
        String frontend = frontendProperties.baseUrl();
        if (frontend != null && !frontend.isBlank() && !LOCAL_DEV_ORIGIN.equals(frontend)) {
            origins.add(frontend.endsWith("/") ? frontend.substring(0, frontend.length() - 1) : frontend);
        }
        return origins;
    }
}
