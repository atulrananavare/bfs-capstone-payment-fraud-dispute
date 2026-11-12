package com.example.fraudcase.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Web security for the Phase 7 analyst UI.
 *
 * <p>Spring Security is used for one thing: <b>CSRF protection</b> on the
 * state-changing Thymeleaf forms under {@code /ui/**}. The stateless JSON
 * API under {@code /api/**} is exempt — it is consumed programmatically and
 * already validated/authorized by the application layer.
 *
 * <p>Authorisation is deliberately unchanged: every request stays
 * permitted at the HTTP layer because the training deployment has no
 * authenticated sessions. The Phase 6 {@code ReviewerIdentityProvider}
 * seam remains the authoritative reviewer boundary — UI hiding is never
 * treated as security.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // No login flow in the synthetic training deployment — the
                // identity seam supplies analyst/reviewer principals.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());
        return http.build();
    }
}
