package com.eric.bff.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class InvalidTokenExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(InvalidTokenExceptionHandler.class);

    @ExceptionHandler(ClientAuthorizationException.class)
    public ResponseEntity<Map<String, String>> handleTokenRefreshError(
            ClientAuthorizationException ex, HttpServletRequest request) {

        if ("invalid_grant".equals(ex.getError().getErrorCode())) {
            logger.warn("Refresh token rejeitado pelo Keycloak — limpando sessão do Redis");
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("erro", "Sessão expirada. Faça login novamente."));
        }

        logger.error("Erro de autorização OAuth2 inesperado: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("erro", "Erro interno de autenticação: " + ex.getMessage()));
    }
}
