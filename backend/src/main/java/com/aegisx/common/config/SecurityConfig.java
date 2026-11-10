package com.aegisx.common.config;

import com.aegisx.auth.security.CustomUserDetailsService;
import com.aegisx.auth.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Content-Security-Policy applied to responses served by this application.
     *
     * <p>Script, style and font sources are restricted to {@code 'self'}. The policy used to allow
     * {@code https://cdn.jsdelivr.net} as well, a leftover from when Font Awesome and the webfonts
     * were loaded from that CDN: it has allowed an arbitrary third-party payload to run in the page
     * for no benefit since both were self-hosted. Keeping the origin out of the policy is what makes
     * the commodity-XSS defence meaningful.</p>
     *
     * <p>{@code 'unsafe-inline}' remains for now because the HTML pages carry inline bootstrap
     * scripts; removing it requires nonce plumbing on every page and is tracked as remaining work.
     * The {@code connect-src} entries are the provider endpoints used from the browser and are kept
     * deliberately.</p>
     */
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self' 'unsafe-inline' https://checkout.razorpay.com; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; " +
            "connect-src 'self' https://api.openai.com https://api.razorpay.com https://graph.facebook.com; " +
            "frame-src https://checkout.razorpay.com https://api.razorpay.com; " +
            "frame-ancestors 'none'";

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final CustomUserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {})
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000).includeSubDomains(true).preload(true))
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .addHeaderWriter((request, response) -> response.setHeader("Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(), payment=()"))
                .addHeaderWriter((request, response) -> response.setHeader("Server", "Aegis-X")))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authenticationProvider(authenticationProvider())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers("/api/webhooks/**").permitAll()
                // Payment-integrity webhooks are authenticated by HMAC signature over the raw body,
                // not by a bearer token, because the provider cannot present one.
                .requestMatchers("/api/integrity/webhooks/**").permitAll()
                .requestMatchers("/api/health", "/api/health/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
