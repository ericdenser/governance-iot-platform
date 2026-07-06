package com.eric.bff.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;

@EnableWebSecurity
@Configuration
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .headers(headers -> headers
                // CSP — última linha de defesa contra XSS. Cada diretiva justificada:
                //  script-src 'self'         : só JS do próprio origin (sem eval, sem inline)
                //  style-src googleapis      : import de fonts (design-system.css); 'unsafe-inline'
                //                              necessário pro Vue <style scoped> e :style bindings
                //  font-src gstatic          : Google Fonts serve .woff2 desse host
                //  img-src data:             : SVG icons inline via data: URI no AppLayout
                //  connect-src 'self'        : SPA só fala com o BFF (que proxya govApi)
                //  object-src 'none'         : sem plugins (<object>, <embed>)
                //  base-uri 'self'           : bloqueia injection de <base>
                //  form-action 'self'        : forms não submetem cross-origin
                //  frame-ancestors 'none'    : anti-clickjacking
                // TODO Obj 2: adicionar 'https://*.tile.openstreetmap.org' em img-src
                //             quando Leaflet for integrado no MapView.
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self'; " +
                    "style-src 'self' https://fonts.googleapis.com 'unsafe-inline'; " +
                    "font-src 'self' https://fonts.gstatic.com; " +
                    "img-src 'self' data:; " +
                    "connect-src 'self'; " +
                    "object-src 'none'; " +
                    "base-uri 'self'; " +
                    "form-action 'self'; " +
                    "frame-ancestors 'none'"
                ))
                // Referrer-Policy — não vaza URL completa em navegações cross-origin.
                .referrerPolicy(ref -> ref.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN
                ))
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest")
                )
            )
            .authorizeHttpRequests(req -> req
                .requestMatchers("/", "/public/**", "/login", "/favicon.ico", "/error", "/me").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(auth -> auth
                .loginPage("/login")
                .defaultSuccessUrl(frontendUrl + "/home", true)
                .failureHandler((request, response, exception) -> {
                    logger.info("OAuth2 flow failure — cleaning up session and redirecting to frontend");
                    if (request.getSession(false) != null) {
                        request.getSession(false).invalidate();
                    }
                    response.sendRedirect(frontendUrl + "/");
                })
            )
            .logout(log -> log
                .logoutSuccessHandler(oidcLogoutSuccessHandler())
                .invalidateHttpSession(true)
                .deleteCookies("SESSION")
            )
            .build();
    }

    private LogoutSuccessHandler oidcLogoutSuccessHandler() {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(this.clientRegistrationRepository);
        handler.setPostLogoutRedirectUri(frontendUrl + "/");
        handler.setDefaultTargetUrl(frontendUrl + "/");
        return handler;
    }
}
