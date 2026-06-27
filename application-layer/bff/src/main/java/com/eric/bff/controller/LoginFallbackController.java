package com.eric.bff.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginFallbackController {

    private static final Logger logger = LoggerFactory.getLogger(LoginFallbackController.class);

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @GetMapping("/login")
    public String redirecionarLoginPerdido() {
        logger.info("Requisição no /login interceptada — redirecionando para o frontend");
        return "redirect:" + frontendUrl + "/";
    }
}
