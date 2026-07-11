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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@EnableWebSecurity
@Configuration
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    private AdminOnlyFilter adminOnlyFilter;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));
        return repo;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .headers(headers -> headers
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
                .referrerPolicy(ref -> ref.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN
                ))
            )
            .csrf(csrf -> csrf
                .spa()
                .csrfTokenRepository(csrfTokenRepository())
            )
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .addFilterAfter(adminOnlyFilter, UsernamePasswordAuthenticationFilter.class)
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
        OidcClientInitiatedLogoutSuccessHandler oidcHandler =
                new OidcClientInitiatedLogoutSuccessHandler(this.clientRegistrationRepository);
        oidcHandler.setPostLogoutRedirectUri(frontendUrl + "/");
        oidcHandler.setDefaultTargetUrl(frontendUrl + "/");

        // XHR (SPA) → 200 JSON com logoutUrl; navegação normal → 302 tradicional
        return (request, response, authentication) -> {
            String requestedWith = request.getHeader("X-Requested-With");
            boolean isXhr = "XMLHttpRequest".equals(requestedWith);

            if (!isXhr) {
                oidcHandler.onLogoutSuccess(request, response, authentication);
                return;
            }

            // Reusa a lógica do OidcClientInitiatedLogoutSuccessHandler pra montar a URL
            // sem redirecionar: wrap num response fake que só coleta o Location.
            LocationCapturingResponse capture = new LocationCapturingResponse(response);
            oidcHandler.onLogoutSuccess(request, capture, authentication);

            String logoutUrl = capture.getCapturedLocation();
            if (logoutUrl == null) logoutUrl = frontendUrl + "/";

            response.setStatus(HttpStatus.OK.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"logoutUrl\":\"" + logoutUrl.replace("\"", "\\\"") + "\"}");
        };
    }
}
