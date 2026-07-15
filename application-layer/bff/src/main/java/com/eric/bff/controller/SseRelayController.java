package com.eric.bff.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;


@RestController
@RequestMapping("/realtime")
@Slf4j
public class SseRelayController {

    private final RestClient sseRestClient;
    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public SseRelayController(@Qualifier("sseRestClient") RestClient sseRestClient,
                              OAuth2AuthorizedClientManager authorizedClientManager) {
        this.sseRestClient = sseRestClient;
        this.authorizedClientManager = authorizedClientManager;
    }

    @GetMapping("/stream")
    public void stream(@RequestParam(required = false, defaultValue = "map") String scope,
                       HttpServletRequest request,
                       HttpServletResponse response) throws IOException {

        String accessToken = resolveAccessToken(request, response);
        if (accessToken == null) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        OutputStream out = response.getOutputStream();
        try {
            
            out.write(": relay aberto\n\n".getBytes(StandardCharsets.UTF_8));
            response.flushBuffer();

            log.info("SSE relay: conectando no upstream scope={}", scope);
            sseRestClient.get()
                    .uri(uri -> uri.path("/realtime/stream").queryParam("scope", scope).build())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .exchange((req, res) -> {
                        log.info("SSE relay: upstream status={}", res.getStatusCode());
                        if (!res.getStatusCode().is2xxSuccessful()) {
                            writeEvent(out, response, "relay-error",
                                    "{\"status\":" + res.getStatusCode().value() + "}");
                            return null;
                        }
                        pipe(res.getBody(), out, response);
                        return null;
                    });
        } catch (Exception e) {
            log.info("SSE relay encerrado: {}", e.toString());
        }
    }

    private void pipe(InputStream in, OutputStream out, HttpServletResponse response) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            response.flushBuffer();
        }
    }

    private void writeEvent(OutputStream out, HttpServletResponse response, String event, String data) {
        try {
            out.write(("event: " + event + "\ndata: " + data + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            response.flushBuffer();
        } catch (IOException ignored) {
            // cliente já desconectou
        }
    }

    private String resolveAccessToken(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            return null;
        }

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(oauthToken.getAuthorizedClientRegistrationId())
                .principal(authentication)
                .attribute(HttpServletRequest.class.getName(), request)
                .attribute(HttpServletResponse.class.getName(), response)
                .build();

        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            return null;
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
