package com.eric.governanceApi.governanceApi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Callbacks do Mosquitto (plugin JWT em modo remote) ficam fora do resource
    // server: o BearerTokenAuthenticationFilter autentica qualquer request com
    // header Authorization mesmo em rota permitAll, então JWT de device expirado
    // viraria 401 antes de chegar no controller — e o plugin trata status != 200
    // como erro opaco ("error code: 401"). Nesta chain o header é ignorado e o
    // MqttAuthController valida o token, respondendo Ok=false com o motivo real.
    // Expostos apenas internamente (Mosquitto na rede docker); nginx externo
    // NÃO deve fazer proxy destes paths.
    @Bean
    @Order(1)
    public SecurityFilterChain mqttAuthFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/auth/mqtt-verify", "/auth/mqtt-acl")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
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
