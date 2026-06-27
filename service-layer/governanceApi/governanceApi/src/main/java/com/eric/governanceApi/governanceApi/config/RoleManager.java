package com.eric.governanceApi.governanceApi.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Component
public class RoleManager implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Logger logger = LoggerFactory.getLogger(RoleManager.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private Map<String, Map<String, List<String>>> rules;
    private final ObjectMapper objectMapper;

    public RoleManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadRules() {
        try {
            InputStream is = new ClassPathResource("regras-acesso.json").getInputStream();
            rules = objectMapper.readValue(is, new TypeReference<Map<String, Map<String, List<String>>>>() {});
            logger.info("Regras de acesso carregadas: {} rotas configuradas", rules.size());
        } catch (IOException e) {
            throw new RuntimeException("Falha crítica ao carregar regras-acesso.json", e);
        }
    }

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authSupplier,
                                           RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        String uri    = request.getRequestURI();
        String method = request.getMethod();

        // Busca o padrão mais específico que casa com a URI.
        // Prioridade: padrões sem wildcard > padrões com wildcard; mais longo > mais curto.
        Optional<Map<String, List<String>>> matched = rules.entrySet().stream()
            .filter(e -> PATH_MATCHER.match(e.getKey(), uri))
            .min(Comparator
                .<Map.Entry<String, Map<String, List<String>>>, Integer>comparing(
                    e -> e.getKey().contains("*") ? 1 : 0)
                .thenComparingInt(e -> -e.getKey().length()))
            .map(Map.Entry::getValue);

        if (matched.isEmpty() || !matched.get().containsKey(method)) {
            logger.info("Bloqueado [rota não mapeada]: {} {}", method, uri);
            return new AuthorizationDecision(false);
        }

        Authentication auth = authSupplier.get();
        if (auth == null || !auth.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        List<String> required = matched.get().get(method);
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (required.contains(authority.getAuthority())) {
                logger.debug("Autorizado: {} {} — role: {}", method, uri, authority.getAuthority());
                return new AuthorizationDecision(true);
            }
        }

        logger.info("Bloqueado [sem role]: {} {} — authorities: {}", method, uri, auth.getAuthorities());
        return new AuthorizationDecision(false);
    }
}
