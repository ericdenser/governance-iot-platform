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

                // apenas event-handler (service account com ROLE_EVENT_HANDLER) pode alimentar eventos
                .requestMatchers("/events/ingest").hasRole("EVENT_HANDLER")

                // apenas agent-mqtt (service account com ROLE_AGENT_MQTT) pode alimentar erros
                .requestMatchers("/error/ingest").hasRole("AGENT_MQTT")

                // Download de firmware pelo ESP32 via OTA — sem autenticação
                // TODO: garantir uma prova de identidade do esp antes de permitir o download do .bin
                .requestMatchers("/firmwares/**").permitAll()

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
