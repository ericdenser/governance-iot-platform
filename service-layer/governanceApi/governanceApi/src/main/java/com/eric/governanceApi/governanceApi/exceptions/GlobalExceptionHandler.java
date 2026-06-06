package com.eric.governanceApi.governanceApi.exceptions;

import java.io.IOException;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

import com.eric.governanceApi.governanceApi.model.response.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {


        @ExceptionHandler(SecurityException.class)
        public ResponseEntity<ApiResponse<Void>> handleSecurityException(SecurityException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponse.error("AUTH_403", ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponse.error("PARAM_400", ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ApiResponse<Void>> handleValidationException(
                MethodArgumentNotValidException ex,
                HttpServletRequest request) {

                String message = ex.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(FieldError::getDefaultMessage)
                        .findFirst()
                        .orElse("Erro de validação");

                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponse.error("VALIDATION_400", message, request.getRequestURI()));
        }

        @ExceptionHandler(ResourceNotFoundException.class)
        public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponse.error("NOT_FOUND_404", ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(
                HttpMessageNotReadableException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponse.error(
                                "PARSE_400",
                                "Corpo da requisição inválido ou JSON malformado",
                                request.getRequestURI()
                        ));
        }

        @ExceptionHandler(MethodArgumentTypeMismatchException.class)
        public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
                String msg = String.format("O parâmetro '%s' deve ser do tipo '%s'. Valor recebido: '%s'",
                        ex.getName(), ex.getRequiredType().getSimpleName(), ex.getValue());

                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponse.error("PARAM_400", msg, request.getRequestURI()));
        }

        @ExceptionHandler(InfrastructureException.class)
        public ResponseEntity<ApiResponse<Void>> handleInfrastructureException(InfrastructureException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponse.error("INFRA_503", ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex, HttpServletRequest request, HttpServletResponse response) {
                // Remove o atributo de content-negotiation que o ResourceHttpRequestHandler
                // seta como 'application/octet-stream' ao servir .bin; sem isso, o Spring
                // não encontra converter para ApiResponse e lança HttpMessageNotWritableException.
                request.removeAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
                if (!response.isCommitted()) {
                        response.reset();
                }
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponse.error("SYS_500", "Erro interno do servidor: " + ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler(ConflictException.class)
        public ResponseEntity<ApiResponse<Void>> handleConflictException (ConflictException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponse.error("CONFLICT_409", ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler({ClientAbortException.class, IOException.class})
        public void handleClientAbortException(Exception ex) {
            // O cliente (ESP32) abortou o download ou perdeu conexão.
            // O retorno é 'void' para que o Spring não tente escrever nada no Response.
            log.warn("Download de firmware interrompido. Cliente desconectou abruptamente: {}", ex.getMessage());
        }
}
