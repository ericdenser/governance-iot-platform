package com.eric.governanceApi.governanceApi.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;

public class TokenRelayInterceptor implements ClientHttpRequestInterceptor {

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public TokenRelayInterceptor(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof OAuth2AuthenticationToken oauthToken) {

            OAuth2AuthorizeRequest.Builder requestBuilder = OAuth2AuthorizeRequest
                    .withClientRegistrationId(oauthToken.getAuthorizedClientRegistrationId())
                    .principal(authentication);

            // Passa o contexto HTTP para que o manager possa persistir tokens
            // renovados de volta ao Redis (sem isso, o refresh não é salvo na sessão)
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                requestBuilder
                    .attribute(HttpServletRequest.class.getName(), attrs.getRequest())
                    .attribute(HttpServletResponse.class.getName(), attrs.getResponse());
            }

            OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(requestBuilder.build());

            if (authorizedClient != null && authorizedClient.getAccessToken() != null) {
                request.getHeaders().setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
            }
        }

        return execution.execute(request, body);
    }
}
