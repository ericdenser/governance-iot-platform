package com.eric.eventhandler.event_handler.config;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class ProxyHttpClientConfig {

    @Value("${PROXY_HOST:}")
    private String proxyHost;

    @Value("${PROXY_PORT:3128}")
    private int proxyPort;

    @Value("${PROXY_USER:}")
    private String proxyUser;

    @Value("${PROXY_PASSWORD:}")
    private String proxyPassword;

    @Bean
    public RestTemplate proxiedRestTemplate() {
        if (proxyHost == null || proxyHost.isBlank()) {
            return new RestTemplate();
        }

        HttpHost proxy = new HttpHost("http", proxyHost, proxyPort);

        BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
        if (proxyUser != null && !proxyUser.isBlank()) {
            credsProvider.setCredentials(
                new AuthScope(proxy),
                new UsernamePasswordCredentials(proxyUser, proxyPassword.toCharArray())
            );
        }

        CloseableHttpClient httpClient = HttpClientBuilder.create()
            .setProxy(proxy)
            .setDefaultCredentialsProvider(credsProvider)
            .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }
}
