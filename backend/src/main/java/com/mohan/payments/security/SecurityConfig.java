package com.mohan.payments.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless bearer-token security.
 * <ol>
 *   <li>{@code POST /api/v1/auth/login} checks the password and issues an HS256-signed JWT ({@code sub} = merchant id).</li>
 *   <li>Every other API request must send {@code Authorization: Bearer <token>}. The resource-server filter verifies
 *       the signature, issuer and {@code exp}; nothing is stored server-side.</li>
 *   <li>After {@code exp} the token is rejected with 401 and the client must log in again.</li>
 * </ol>
 * CSRF protection is disabled because the API uses no cookies: browsers never attach the token automatically.
 * <p>
 * {@code /actuator/**} has its own chain, checked first, and does not accept merchant tokens at all. See
 * {@link #actuatorSecurity}.
 */
@Configuration
public class SecurityConfig {

    static final String OPERATOR_ROLE = "OPERATOR";

    /**
     * Operator endpoints. Merchant JWTs are deliberately not understood here: a merchant is a tenant, and
     * {@code /actuator/outbox} shows every tenant's dead letters and can requeue them.
     * <ul>
     *   <li>Probes and the Prometheus scrape stay public: orchestrators and scrapers do not log in, and they
     *       expose only health and aggregate counts, never tenant data.</li>
     *   <li>Everything else needs the operator account over HTTP Basic. With no operator password configured,
     *       nobody gets in.</li>
     * </ul>
     * In production the whole of {@code /actuator} would also sit on a management port that is not exposed to the
     * internet ({@code MANAGEMENT_PORT}); this chain makes it safe even when that is forgotten.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurity(HttpSecurity http, SecurityProblemHandler problemHandler,
            OperatorProperties operator, PasswordEncoder passwordEncoder) throws Exception {
        http
                .securityMatcher("/actuator/**")
                // Operators call these with curl; there is no browser session to forge requests from.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationManager(operatorAuthentication(operator, passwordEncoder))
                .httpBasic(basic -> basic.authenticationEntryPoint(problemHandler.challenging("Basic realm=\"operator\"")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .anyRequest().hasRole(OPERATOR_ROLE))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandler.challenging("Basic realm=\"operator\""))
                        .accessDeniedHandler(problemHandler));
        return http.build();
    }

    /** Kept local to the actuator chain so it cannot be used to log in to the merchant API. */
    private static AuthenticationManager operatorAuthentication(OperatorProperties operator,
            PasswordEncoder passwordEncoder) {
        InMemoryUserDetailsManager users = new InMemoryUserDetailsManager();
        if (operator.enabled()) {
            users.createUser(User.withUsername(operator.username())
                    .password(passwordEncoder.encode(operator.password()))
                    .roles(OPERATOR_ROLE)
                    .build());
        }
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProblemHandler problemHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // The schema and Swagger UI are public so the API can be explored without a
                        // token. In production these would be disabled (springdoc.api-docs.enabled=false)
                        // or moved behind the management port alongside /actuator.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .addFilterAfter(new MerchantMdcFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public SecretKey jwtSigningKey(AppSecurityProperties properties) {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("app.security.jwt.secret must be at least 32 bytes (256 bits) for HS256");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, AppSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Default validators check exp/nbf (with 60s clock skew); we add the issuer check.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppSecurityProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        // Patterns accept exact origins (https://dashboard.example.com) as well as wildcards such as
        // http://localhost:* so a dev server that moved to another port still works.
        cors.setAllowedOriginPatterns(properties.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id"));
        cors.setExposedHeaders(List.of("X-Request-Id", "Idempotent-Replayed", "Location"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
