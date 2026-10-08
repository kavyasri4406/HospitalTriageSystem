package com.hospital.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Parsed HTTP request: method, path, query/form parameters and cookies. */
final class Request {

    private static final int MAX_BODY_BYTES = 64 * 1024;

    final HttpExchange exchange;
    final String method;
    final String path;
    private final Map<String, String> query;
    private final Map<String, String> form;
    private final Map<String, String> cookies;

    private Request(HttpExchange exchange, Map<String, String> query, Map<String, String> form, Map<String, String> cookies) {
        this.exchange = exchange;
        this.method = exchange.getRequestMethod().toUpperCase();
        this.path = exchange.getRequestURI().getPath();
        this.query = query;
        this.form = form;
        this.cookies = cookies;
    }

    static Request parse(HttpExchange ex) throws IOException {
        Map<String, String> query = parseUrlEncoded(ex.getRequestURI().getRawQuery());
        Map<String, String> form = Map.of();
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            try (InputStream in = ex.getRequestBody()) {
                byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) throw new IOException("Request body too large");
                form = parseUrlEncoded(new String(body, StandardCharsets.UTF_8));
            }
        }
        Map<String, String> cookies = new HashMap<>();
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header != null) {
            for (String part : header.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) cookies.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return new Request(ex, query, form, cookies);
    }

    private static Map<String, String> parseUrlEncoded(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null || raw.isEmpty()) return values;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                values.putIfAbsent(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // malformed %-escape: skip the parameter
            }
        }
        return values;
    }

    /** Form field first, then query parameter; never null. */
    String param(String name) {
        String v = form.get(name);
        if (v == null) v = query.get(name);
        return v == null ? "" : v.trim();
    }

    boolean has(String name) {
        return form.containsKey(name) || query.containsKey(name);
    }

    int intParam(String name, int fallback) {
        try {
            return Integer.parseInt(param(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    double doubleParam(String name, double fallback) {
        try {
            return Double.parseDouble(param(name));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    String cookie(String name) {
        return cookies.get(name);
    }

    boolean isSecure() {
        return "https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
    }
}
