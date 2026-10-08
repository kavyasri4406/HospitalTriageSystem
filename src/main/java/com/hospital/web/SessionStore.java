package com.hospital.web;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory browser sessions keyed by a random cookie value; each holds the logged-in staff id and a CSRF token. */
final class SessionStore {

    static final String COOKIE = "ERSESSION";
    private static final Duration IDLE_TIMEOUT = Duration.ofHours(8);
    private static final SecureRandom RANDOM = new SecureRandom();

    static final class Session {
        final String id;
        final String csrf;
        Integer staffId;
        String flash;
        boolean flashOk;
        Instant lastSeen = Instant.now();

        Session(String id, String csrf) {
            this.id = id;
            this.csrf = csrf;
        }

        void flash(boolean ok, String message) {
            this.flashOk = ok;
            this.flash = message;
        }
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Existing live session for the cookie, or a brand-new anonymous one. */
    Session get(String cookieValue) {
        Session s = cookieValue == null ? null : sessions.get(cookieValue);
        if (s != null && s.lastSeen.plus(IDLE_TIMEOUT).isBefore(Instant.now())) {
            sessions.remove(s.id);
            s = null;
        }
        if (s == null) {
            s = new Session(token(), token());
            sessions.put(s.id, s);
        }
        s.lastSeen = Instant.now();
        sessions.values().removeIf(x -> x.lastSeen.plus(IDLE_TIMEOUT).isBefore(Instant.now()));
        return s;
    }

    /** New session id after login (prevents session fixation). */
    Session rotate(Session old) {
        sessions.remove(old.id);
        Session s = new Session(token(), token());
        s.flash = old.flash;
        s.flashOk = old.flashOk;
        sessions.put(s.id, s);
        return s;
    }

    void remove(Session s) {
        sessions.remove(s.id);
    }

    private static String token() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
