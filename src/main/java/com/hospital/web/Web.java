package com.hospital.web;

import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small types shared by the web handlers: the per-request context and the response. */
final class Web {

    private Web() {}

    /** Everything a page handler needs for one request. */
    record Ctx(Request req, SessionStore.Session session, HospitalManager m) {
        String csrf() { return session.csrf; }

        /** Stores the outcome for the next page (post/redirect/get) and redirects. */
        Response done(OperationResult r, String location) {
            session.flash(r.success(), r.errors().isEmpty() ? r.message() : r.message() + " " + String.join(" ", r.errors()));
            return Response.redirect(location);
        }
    }

    record Response(int status, String contentType, byte[] body, Map<String, String> headers) {

        static Response html(String html) {
            return new Response(200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8), new LinkedHashMap<>());
        }

        static Response status(int status, String text) {
            return new Response(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8), new LinkedHashMap<>());
        }

        static Response redirect(String location) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Location", location);
            return new Response(303, "text/plain; charset=utf-8", new byte[0], h);
        }

        static Response download(byte[] body, String contentType, String fileName) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Disposition", "attachment; filename=\"" + fileName.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
            return new Response(200, contentType, body, h);
        }
    }
}
