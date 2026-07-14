package com.eric.bff.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestParam(required = false, defaultValue = "map") String scope) {

        // O token precisa ser resolvido na thread da request: dentro do
        // StreamingResponseBody não há SecurityContext nem RequestContext.
        String accessToken = resolveAccessToken();
        if (accessToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        StreamingResponseBody body = outputStream -> relay(scope, accessToken, outputStream);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }


    private void relay(String scope, String accessToken, OutputStream out) {
        try {
            sseRestClient.get()
                    .uri(uri -> uri.path("/realtime/stream").queryParam("scope", scope).build())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            log.warn("SSE upstream respondeu {}", response.getStatusCode());
                            writeEvent(out, "relay-error",
                                    "{\"status\":" + response.getStatusCode().value() + "}");
                            return null;
                        }
                        pipe(response.getBody(), out);
                        return null;
                    });
        } catch (Exception e) {
            log.debug("SSE relay encerrado: {}", e.getMessage());
        }
    }

    private void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            out.flush();
        }
    }

    private void writeEvent(OutputStream out, String event, String data) {
        try {
            out.write(("event: " + event + "\ndata: " + data + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // cliente já desconectou
        }
    }

    private String resolveAccessToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            return null;
        }

        OAuth2AuthorizeRequest.Builder requestBuilder = OAuth2AuthorizeRequest
                .withClientRegistrationId(oauthToken.getAuthorizedClientRegistrationId())
                .principal(authentication);

        
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            requestBuilder
                    .attribute(HttpServletRequest.class.getName(), attrs.getRequest())
                    .attribute(HttpServletResponse.class.getName(), attrs.getResponse());
        }

        OAuth2AuthorizedClient authorizedClient =
                authorizedClientManager.authorize(requestBuilder.build());
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            return null;
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
