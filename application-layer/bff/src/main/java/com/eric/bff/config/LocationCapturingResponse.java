package com.eric.bff.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Wrapper que intercepta chamadas de redirect (sendRedirect e setHeader("Location", ...))
 * sem propagá-las pro response real. Usado pra capturar a URL de end_session gerada pelo
 * OidcClientInitiatedLogoutSuccessHandler e devolvê-la como JSON pra XHRs.
 */
public class LocationCapturingResponse extends HttpServletResponseWrapper {

    private String capturedLocation;

    public LocationCapturingResponse(HttpServletResponse response) {
        super(response);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        this.capturedLocation = location;
    }

    @Override
    public void setHeader(String name, String value) {
        if ("Location".equalsIgnoreCase(name)) {
            this.capturedLocation = value;
            return;
        }
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if ("Location".equalsIgnoreCase(name)) {
            this.capturedLocation = value;
            return;
        }
        super.addHeader(name, value);
    }

    public String getCapturedLocation() {
        return capturedLocation;
    }
}
