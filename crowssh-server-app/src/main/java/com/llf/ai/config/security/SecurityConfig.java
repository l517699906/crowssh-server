package com.llf.ai.config.security;

import com.llf.ai.domain.auth.service.DeviceIdentityService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * CrowSSH HTTP API 无状态认证配置。
 */
@Configuration
public class SecurityConfig {

    private static final Set<String> TAURI_APP_ORIGINS = Set.of(
            "tauri://localhost",
            "http://tauri.localhost",
            "https://tauri.localhost"
    );

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DeviceIdentityService deviceIdentityService
    ) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(response, 401, "AUTHENTICATION_REQUIRED", "需要有效的设备身份"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(response, 403, "ACCESS_DENIED", "无权访问该资源")))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/device/register").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(
                        new DeviceTokenAuthenticationFilter(deviceIdentityService),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${crowssh.security.allowed-origins:tauri://localhost,http://tauri.localhost}")
            String allowedOrigins,
            Environment environment
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(validateAllowedOrigins(
                allowedOrigins,
                environment.acceptsProfiles(Profiles.of("prod"))));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "X-CrowSSH-Registration-Code"));
        configuration.setExposedHeaders(List.of("Content-Disposition", "Content-Length"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    static List<String> validateAllowedOrigins(String configuredOrigins, boolean production) {
        List<String> origins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("crowssh.security.allowed-origins 不能为空");
        }
        for (String origin : origins) {
            if (origin.contains("*")) {
                throw new IllegalArgumentException("CORS 来源禁止使用通配符: " + origin);
            }
            URI uri;
            try {
                uri = URI.create(origin);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("CORS 来源格式无效: " + origin, exception);
            }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException("CORS 来源必须是无路径的完整 origin: " + origin);
            }
            boolean secureWebOrigin = "https".equalsIgnoreCase(uri.getScheme());
            boolean tauriAppOrigin = TAURI_APP_ORIGINS.contains(origin);
            boolean developmentLoopback = !production
                    && "http".equalsIgnoreCase(uri.getScheme())
                    && isLoopbackHost(uri.getHost());
            if (!secureWebOrigin && !tauriAppOrigin && !developmentLoopback) {
                throw new IllegalArgumentException("CORS 来源不符合当前环境安全策略: " + origin);
            }
        }
        return origins;
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }

    private static void writeError(HttpServletResponse response, int status, String code, String info)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"info\":\"" + info
                + "\",\"data\":null}");
    }
}
