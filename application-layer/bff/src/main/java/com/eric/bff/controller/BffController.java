package com.eric.bff.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.eric.bff.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
public class BffController {

    private static final Logger logger = LoggerFactory.getLogger(BffController.class);

    private final UserService userService;

    public BffController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMe(
            @AuthenticationPrincipal OidcUser principal,
            OAuth2AuthenticationToken authToken,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (principal == null || authToken == null) {
            return ResponseEntity.ok(Map.of("authenticated", false));
        }

        Map<String, Object> userInfo = userService.getAuthenticatedUserInfo(authToken, principal, request, response);

        if (userInfo == null) {
            logger.warn("Tokens perdidos no Redis para o usuário {}", authToken.getName());
            return ResponseEntity.ok(Map.of("authenticated", false));
        }

        return ResponseEntity.ok(userInfo);
    }

    @GetMapping("/check-role")
    public ResponseEntity<Map<String, Boolean>> checkRole(
            @RequestParam List<String> roles,
            OAuth2AuthenticationToken authToken,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (authToken == null) {
            return ResponseEntity.ok(Map.of("hasRole", false));
        }

        boolean hasRole = userService.checkUserHasRole(roles, authToken, request, response);
        return ResponseEntity.ok(Map.of("hasRole", hasRole));
    }
}
