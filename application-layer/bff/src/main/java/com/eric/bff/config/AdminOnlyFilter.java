package com.eric.bff.config;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.eric.bff.service.UserService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

//Exige role ROLE_ADMIN pra mutações em /users/**
// GET permanece aberto pra qualquer autenticado (GroupsView precisa listar).
@Component
public class AdminOnlyFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AdminOnlyFilter.class);

    private final UserService userService;

    public AdminOnlyFilter(UserService userService) {
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (isProtected(request)) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (!(auth instanceof OAuth2AuthenticationToken token)) {
                deny(response, 401, "não autenticado");
                return;
            }
            boolean isAdmin = userService.checkUserHasRole(
                    List.of("ROLE_ADMIN"), token, request, response);
            if (!isAdmin) {
                logger.warn("Bloqueado acesso não-admin a {} {}", request.getMethod(), request.getRequestURI());
                deny(response, 403, "somente administradores podem gerenciar usuários");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isProtected(HttpServletRequest req) {
        String path = req.getRequestURI();
        String method = req.getMethod();
        if (!path.startsWith("/users")) return false;
        return method.equals("POST") || method.equals("PUT") || method.equals("DELETE");
    }

    private void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
            "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"" + message + "\"}}"
        );
    }
}
