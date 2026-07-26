package com.eric.governanceApi.governanceApi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtDecoder jwtDecoder,
                                           JwtAuthenticationConverter jwtAuthConverter,
                                           RoleManager roleManager) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                // Docker/Kubernetes healthcheck — sem auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                // ESP32 envia provisioning token no body (qualquer outra requisição é 404)
                .requestMatchers("/provisioning/activate").permitAll()

                // Alimentação interna via docker network — bloquear externamente no nginx-frontend
                .requestMatchers("/events/ingest").permitAll()
                .requestMatchers("/error/ingest").permitAll()

                // Todo o resto é validado pelo RoleManager via regras-acesso.json
                .anyRequest().access(roleManager)
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthConverter)
                )
            );

        return http.build();
    }
}
