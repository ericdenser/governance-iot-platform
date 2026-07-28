package com.eric.bff.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Controllers do BFF que fazem proxy (RestClient.toEntity) repassam
 * Transfer-Encoding do upstream (govApi). Spring/Tomcat re-adiciona o
 * próprio ao serializar o body, gerando duplicate header line que nginx
 * rejeita com 502 Bad Gateway.
 * <p>
 * Remover ANTES do body ser escrito garante que só o Tomcat emite.
 */
@RestControllerAdvice
public class UpstreamHeaderCleaner implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        response.getHeaders().remove(HttpHeaders.TRANSFER_ENCODING);
        return body;
    }
}
