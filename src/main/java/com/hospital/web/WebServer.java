package com.hospital.web;

import com.hospital.persistence.DatabaseConfig;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.web.Web.Ctx;
import com.hospital.web.Web.Response;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Web version of the triage system, built only on the JDK's {@code com.sun.net.httpserver}.
 * Pages are rendered on the server by Java code (no JavaScript); every change is a POST protected by a
 * per-session CSRF token, followed by a redirect (post/redirect/get).
 *
 * <p>The backend ({@link HospitalManager}) has one "current user" at a time, so requests are handled
 * one after another under a lock: each request resumes its session's user, runs, then logs out again.
 *
 * <pre>
 *   java -cp "out/classes:lib/*" com.hospital.web.WebServer        (PORT env var, default 8080)
 * </pre>
 */
public final class WebServer {

    private final HospitalManager manager;
    private final SessionStore sessions = new SessionStore();
    private final ReentrantLock lock = new ReentrantLock();
    private Instant lastTick = Instant.EPOCH;

    private WebServer(HospitalManager manager) {
        this.manager = manager;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HospitalManager manager = HospitalManager.create(DatabaseConfig.fromEnvironment());
        WebServer app = new WebServer(manager);

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleWithFixedDelay(app::tickIfDue, 2, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            ticker.shutdownNow();
            manager.close();
        }));
        System.out.println("[Web] ER Triage running on http://localhost:" + port);
    }

    /** Wait-time aging, overdue alerts and auto-admit, at the interval set in Settings. */
    private void tickIfDue() {
        lock.lock();
        try {
            int every = Math.max(5, manager.settings().refreshSeconds());
            if (Instant.now().isBefore(lastTick.plusSeconds(every))) return;
            lastTick = Instant.now();
            manager.logout(); // system context: no user
            manager.tick();
        } catch (RuntimeException e) {
            System.err.println("[Web] tick failed: " + e);
        } finally {
            lock.unlock();
        }
    }

    // ---- request handling ---------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        Response response;
        SessionStore.Session session = null;
        try {
            Request req = Request.parse(ex);
            if (req.path.equals("/style.css")) {
                send(ex, new Response(200, "text/css; charset=utf-8", AdminPages.css(), Map.of("Cache-Control", "max-age=300")), null, false);
                return;
            }
            if (req.path.equals("/health")) {
                send(ex, Response.status(200, "ok"), null, false);
                return;
            }
            session = sessions.get(req.cookie(SessionStore.COOKIE));
            if (req.method.equals("POST") && !constantTimeEquals(req.param("csrf"), session.csrf)) {
                response = Response.status(403, "Form expired or invalid. Go back, reload the page and try again.");
            } else {
                lock.lock();
                try {
                    if (session.staffId != null && !manager.resumeSession(session.staffId)) session.staffId = null;
                    if (session.staffId == null) manager.logout();
                    Ctx ctx = new Ctx(req, session, manager);
                    response = route(ctx);
                    session = ctx.session();
                    if (req.path.equals("/login") && req.method.equals("POST") && manager.currentUser() != null) {
                        // successful login: new session id (prevents session fixation)
                        SessionStore.Session fresh = sessions.rotate(session);
                        fresh.staffId = manager.currentUser().getId();
                        session = fresh;
                    }
                } finally {
                    manager.logout();
                    lock.unlock();
                }
            }
            send(ex, response, session, req.isSecure());
        } catch (RuntimeException e) {
            e.printStackTrace();
            send(ex, Response.status(500, "Something went wrong: " + e.getMessage()), session, false);
        } finally {
            ex.close();
        }
    }

    private Response route(Ctx c) {
        String path = c.req().path;
        boolean post = c.req().method.equals("POST");
        boolean loggedIn = manager.currentUser() != null;

        if (path.equals("/login")) {
            if (!post) return loggedIn ? Response.redirect("/") : AdminPages.loginPage(c, null, "");
            OperationResult r = manager.login(c.req().param("username"), c.req().param("password"));
            if (!r.success()) return AdminPages.loginPage(c, r.message(), c.req().param("username"));
            c.session().flash(true, r.message());
            return Response.redirect("/");
        }
        if (!loggedIn) return Response.redirect("/login");
        if (path.equals("/logout") && post) {
            sessions.remove(c.session());
            return Response.redirect("/login");
        }

        if (!post) {
            return switch (path) {
                case "/" -> ClinicalPages.dashboard(c);
                case "/intake" -> ClinicalPages.intake(c);
                case "/queue" -> ClinicalPages.queue(c);
                case "/reassess" -> ClinicalPages.reassessForm(c);
                case "/beds" -> ClinicalPages.beds(c);
                case "/transfer" -> ClinicalPages.transferForm(c);
                case "/doctors" -> ClinicalPages.doctors(c);
                case "/records" -> ClinicalPages.records(c);
                case "/alerts" -> AdminPages.alerts(c);
                case "/reports" -> AdminPages.reports(c);
                case "/export/patients.csv" -> AdminPages.export(c, "patients.csv");
                case "/export/activity.csv" -> AdminPages.export(c, "activity.csv");
                case "/export/report.txt" -> AdminPages.export(c, "report.txt");
                case "/staff" -> AdminPages.staff(c);
                case "/settings" -> AdminPages.settings(c);
                default -> Response.status(404, "Page not found");
            };
        }
        return switch (path) {
            case "/admit-next" -> ClinicalPages.admitNext(c);
            case "/auto-allocate" -> ClinicalPages.autoAllocate(c);
            case "/admit" -> ClinicalPages.admitOne(c);
            case "/lwbs" -> ClinicalPages.lwbs(c);
            case "/simulate" -> ClinicalPages.simulate(c);
            case "/advance" -> ClinicalPages.advance(c);
            case "/reset" -> ClinicalPages.reset(c);
            case "/intake" -> ClinicalPages.registerPatient(c);
            case "/reassess" -> ClinicalPages.reassess(c);
            case "/discharge" -> ClinicalPages.discharge(c);
            case "/clean" -> ClinicalPages.clean(c);
            case "/maintenance" -> ClinicalPages.maintenance(c);
            case "/transfer" -> ClinicalPages.transfer(c);
            case "/doctor-duty" -> ClinicalPages.doctorDuty(c);
            case "/ack" -> AdminPages.ack(c);
            case "/ack-all" -> AdminPages.ackAll(c);
            case "/account/password" -> AdminPages.changeOwnPassword(c);
            case "/staff/create" -> AdminPages.createStaff(c);
            case "/staff/active" -> AdminPages.setActive(c);
            case "/staff/role" -> AdminPages.setRole(c);
            case "/staff/password" -> AdminPages.resetPassword(c);
            case "/settings" -> AdminPages.saveSettings(c);
            default -> Response.status(404, "Page not found");
        };
    }

    private static void send(HttpExchange ex, Response r, SessionStore.Session session, boolean secure) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", r.contentType());
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "same-origin");
        h.set("Content-Security-Policy", "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        if (r.contentType().startsWith("text/html")) h.set("Cache-Control", "no-store");
        r.headers().forEach(h::set);
        if (session != null) {
            h.add("Set-Cookie", SessionStore.COOKIE + "=" + session.id + "; Path=/; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : ""));
        }
        byte[] body = r.body();
        ex.sendResponseHeaders(r.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
