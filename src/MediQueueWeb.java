package mediqueue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import mediqueue.MediQueueData;
import mediqueue.MediQueueData.Account;
import mediqueue.MediQueueData.Appt;
import mediqueue.MediQueueData.Sched;
import mediqueue.MediQueueService.UserError;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static mediqueue.Json.obj;
import static mediqueue.MediQueueData.*;
import static mediqueue.MediQueueService.*;

/**
 * MediQueue Web - the clinic system as a website.
 *
 * This class starts a small web server that comes with the JDK (no Tomcat, Maven or libraries needed):
 *   - Files in the web/ folder (index.html, app.css, app.js) are sent to the browser. They draw the screens.
 *   - Addresses that start with /api/ run the clinic rules in MediQueueService and answer with JSON.
 *   - MediQueueData saves everything to mediqueue-data.txt and mediqueue-users.txt, as in the desktop app.
 *
 * Run:  java -jar MediQueueWeb.jar           then open http://localhost:8080
 *       java -jar MediQueueWeb.jar 9090      use a different port
 *       java -jar MediQueueWeb.jar --lan     also let phones and other computers on your network open it
 */
public class MediQueueWeb {

    static final String COOKIE = "mq_session";
    static final long SESSION_LENGTH_MS = 12L * 60 * 60 * 1000;   // a sign-in lasts 12 hours
    static final int MAX_BODY_BYTES = 16 * 1024;                   // forms are small; refuse anything bigger

    /** Browser security rules for our pages: only load scripts and styles from this server. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8", "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml", "png", "image/png", "ico", "image/x-icon");

    /** Who is signed in on a browser. The browser keeps a random token in a cookie; this remembers whose it is. */
    static final class Session {
        final String username;
        final long started = System.currentTimeMillis();
        Session(String username) { this.username = username; }
        boolean expired() { return System.currentTimeMillis() - started > SESSION_LENGTH_MS; }
    }
    static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    static final SecureRandom RANDOM = new SecureRandom();

// =====================================================================
    //  START THE SERVER
    // =====================================================================
    public static void main(String[] args) throws IOException {
        int port = 8080;
        boolean lan = false;
        for (String a : args) {
            if (a.equals("--lan")) lan = true;
            else if (a.matches("\\d{2,5}") && Integer.parseInt(a) <= 65535) port = Integer.parseInt(a);
            else System.out.println("Ignoring unknown option: " + a);
        }

        loadAccounts();   // the same start-up steps as the desktop app's main()
        loadData();

        InetSocketAddress address = lan ? new InetSocketAddress(port) : new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        HttpServer server;
        try {
            server = HttpServer.create(address, 0);
        } catch (BindException e) {
            System.err.println("Port " + port + " is already in use (is MediQueue Web already running?).");
            System.err.println("Close the other program, or start on another port:  java -jar MediQueueWeb.jar " + (port + 1));
            System.exit(1);
            return;
        }
        server.createContext("/api/", MediQueueWeb::handleApi);
        server.createContext("/", MediQueueWeb::handleFile);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("MediQueue Web is running.");
        System.out.println("  Open in your browser:  http://localhost:" + port);
        if (lan) printNetworkAddresses(port);
        System.out.println("  Web pages from:        " + (Files.isDirectory(Paths.get("web")) ? Paths.get("web").toAbsolutePath() : "the copy inside the .jar file"));
        System.out.println("  Data saved in:         " + Paths.get("").toAbsolutePath());
        System.out.println("  Press Ctrl+C to stop.");
    }

    private static void printNetworkAddresses(int port) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ip : Collections.list(ni.getInetAddresses()))
                    if (ip instanceof Inet4Address) System.out.println("  On other devices:      http://" + ip.getHostAddress() + ":" + port);
            }
        } catch (IOException e) {
            // the extra addresses are only a convenience
        }
        System.out.println("  Note: anyone on this network can open the site, so use made-up data only.");
    }
// =====================================================================
    //  /api/...  (the clinic actions, answered with JSON)
    // =====================================================================
    private static void handleApi(HttpExchange ex) throws IOException {
        try {
            Map<String, String> form = Collections.emptyMap();
            if (ex.getRequestMethod().equals("POST")) {
                // Our own page always sends this header. Other websites cannot add it, so they cannot make a
                // signed-in browser send us requests behind the user's back ("cross-site request forgery").
                if (!"1".equals(ex.getRequestHeaders().getFirst("X-MediQueue"))) throw new UserError(403, "Request blocked.");
                form = readForm(ex);
            }
            String request = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            Object answer;
            synchronized (MediQueueData.class) {   // one request at a time touches the data, like one desktop window
                answer = route(ex, request, form);
            }
            sendJson(ex, 200, answer);
        } catch (UserError e) {
            sendJson(ex, e.status, obj("error", e.getMessage()));
        } catch (RuntimeException e) {
            e.printStackTrace();
            if (ex.getResponseCode() == -1) sendJson(ex, 500, obj("error", "Something went wrong on the server."));
        } finally {
            ex.close();
        }
    }

    /** Decides what to do for each address. Every action is a method in MediQueueService. */
    private static Object route(HttpExchange ex, String request, Map<String, String> form) {
        switch (request) {
            // ---------- asked by the page when it opens: who is signed in on this browser? (null = nobody) ----------
            case "GET /api/state": {
                Account me = signedInUser(ex);
                return obj("ok", true, "state", me == null ? null : stateFor(me));
            }

            // ---------- signing in and out (no account needed) ----------
            case "POST /api/login": {
                String role = field(form, "role").equals("admin") ? "admin" : "patient";
                Account a = login(field(form, "username"), field(form, "password"), role);
                startBrowserSession(ex, a);
                return reply(a, "Welcome, " + firstName(a.name) + "!", "green");
            }
            case "POST /api/register": {
                Account a = register(field(form, "name"), field(form, "contact"), field(form, "username"),
                                     field(form, "password"), field(form, "password2"));
                startBrowserSession(ex, a);
                return reply(a, "Welcome, " + firstName(a.name) + "!", "green");
            }
            
            
case "POST /api/admin/create": {
    Account me = signedInUser(ex);

    if (me == null) {
        throw new UserError(401, "Please sign in first.");
    }

    staffOnly(me);

    Account admin = createAdmin(
        me,
        field(form, "name"),
        field(form, "contact"),
        field(form, "username"),
        field(form, "password"),
        field(form, "password2")
    );

    return reply(me, "Admin account created successfully: " + admin.username, "green");
}

            case "POST /api/reset": {
                Account a = resetPassword(field(form, "username"), field(form, "contact"), field(form, "password"), field(form, "password2"));
                SESSIONS.values().removeIf(s -> s.username.equalsIgnoreCase(a.username));   // sign-ins with the old password end
                return obj("ok", true, "message", "Password updated. You can now sign in.", "tone", "green", "username", a.username);
            }
            case "POST /api/logout":
                endBrowserSession(ex);
                return obj("ok", true, "message", "You have been logged out.", "tone", "green");
            default:
                break;
        }

        Account me = signedInUser(ex);
        if (me == null) throw new UserError(401, "Please sign in first.");

        switch (request) {
            // ---------- patients ----------
            case "POST /api/book": {
                if (isAdmin(me)) throw new UserError(403, "Only patients can book appointments.");
                Appt a = book(me, field(form, "doctor"), field(form, "date"), field(form, "time"));
                return reply(me, "Booked with " + a.doc + " on " + a.date.format(DATE_FMT) + " at " + a.time.format(TIME_FMT), "green");
            }
            case "POST /api/cancel": {   // patients cancel their own appointments; staff can cancel any
                Appt a = cancel(me, field(form, "id"));
                return reply(me, "Appointment " + a.id + " has been cancelled.", "red");
            }

            // ---------- clinic staff ----------
            case "POST /api/confirm":
                staffOnly(me);
                confirm(field(form, "id"));
                return reply(me, "Appointment confirmed.", "green");
            case "POST /api/queue/next":
                staffOnly(me);
                return reply(me, "Now calling " + callNext().name, "green");
            case "POST /api/queue/call":
                staffOnly(me);
                return reply(me, "Now calling " + callPatient(number(form, "index"), field(form, "name")).name, "green");
            case "POST /api/queue/complete":
                staffOnly(me);
                return reply(me, "Consultation completed for " + completePatient(number(form, "index"), field(form, "name")).name + ".", "green");
            case "POST /api/patients/save":
                staffOnly(me);
                savePatient(field(form, "id"), field(form, "name"), field(form, "age"), field(form, "gender"), field(form, "contact"));
                return reply(me, "Patient saved.", "green");
            case "POST /api/patients/delete":
                staffOnly(me);
                deletePatient(field(form, "id"));
                return reply(me, "Patient deleted.", "red");
            case "POST /api/schedules/add":
                staffOnly(me);
                addSchedule(field(form, "doctor"), field(form, "date"), field(form, "slots"));
                return reply(me, "Schedule added.", "green");
            case "POST /api/schedules/toggle": {
                staffOnly(me);
                Sched s = toggleSchedule(number(form, "id"));
                return reply(me, s.ok ? "Schedule marked available." : "Schedule marked not available.", "green");
            }
            case "POST /api/schedules/remove":
                staffOnly(me);
                removeSchedule(number(form, "id"));
                return reply(me, "Schedule removed.", "red");
            default:
                throw new UserError(404, "Not found.");
        }
    }

    /** The usual answer after a change: a message for the pop-up, its colour, and the fresh screen data. */
    private static Map<String, Object> reply(Account me, String message, String tone) {
        return obj("ok", true, "message", message, "tone", tone, "state", stateFor(me));
    }

    private static void staffOnly(Account me) {
        if (!isAdmin(me)) throw new UserError(403, "Only clinic staff can do that.");
    }

    private static String field(Map<String, String> form, String name) {
        String v = form.get(name);
        return v == null ? "" : v;
    }

 // =====================================================================
    //  SIGN-IN SESSIONS (a random token kept in a cookie)
    // =====================================================================
    private static void startBrowserSession(HttpExchange ex, Account a) {
        String old = cookie(ex, COOKIE);
        if (old != null) SESSIONS.remove(old);           // every sign-in gets a brand-new token
        SESSIONS.values().removeIf(Session::expired);     // tidy up sign-ins that ran out
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SESSIONS.put(token, new Session(a.username));
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
    }

    private static void endBrowserSession(HttpExchange ex) {
        String token = cookie(ex, COOKIE);
        if (token != null) SESSIONS.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
    }

    private static Account signedInUser(HttpExchange ex) {
        String token = cookie(ex, COOKIE);
        Session s = token == null ? null : SESSIONS.get(token);
        if (s == null) return null;
        if (s.expired()) {
            SESSIONS.remove(token);
            return null;
        }
        return ACCOUNTS.get(s.username.toLowerCase());
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers)
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        return null;
    }

// =====================================================================
    //  WEB PAGES (index.html, app.css, app.js from the web/ folder)
    // =====================================================================
    private static void handleFile(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendText(ex, 405, "Method not allowed");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String name = path.equals("/") ? "index.html" : path.substring(1);
            // Only plain file names such as app.js are served, so tricks like ../ cannot reach other files.
            byte[] body = name.matches("[A-Za-z0-9_-]+\\.(html|css|js|svg|png|ico)") ? webFile(name) : null;
            if (body == null) {
                sendText(ex, 404, "Not found");
                return;
            }
            Headers h = ex.getResponseHeaders();
            h.set("Content-Type", TYPES.get(name.substring(name.lastIndexOf('.') + 1)));
            h.set("Cache-Control", "no-cache");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("X-Frame-Options", "DENY");
            if (name.endsWith(".html")) h.set("Content-Security-Policy", CSP);
            if (method.equals("HEAD") || body.length == 0) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(body); }
        } finally {
            ex.close();
        }
    }

    /** A file from the web/ folder next to the program, or the copy packed inside the .jar when that folder is missing. */
    private static byte[] webFile(String name) throws IOException {
        Path onDisk = Paths.get("web", name);
        if (Files.isRegularFile(onDisk)) return Files.readAllBytes(onDisk);
        try (InputStream in = MediQueueWeb.class.getResourceAsStream("/web/" + name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

 // =====================================================================
    //  READING REQUESTS AND SENDING ANSWERS
    // =====================================================================

    /** Reads a form the page sent (name=value&name=value) into a map. */
    private static Map<String, String> readForm(HttpExchange ex) throws IOException {
        byte[] body;
        try (InputStream in = ex.getRequestBody()) { body = in.readNBytes(MAX_BODY_BYTES + 1); }
        if (body.length > MAX_BODY_BYTES) throw new UserError(413, "That request is too large.");
        Map<String, String> form = new HashMap<>();
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            try {
                form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            } catch (IllegalArgumentException e) {
                throw new UserError("The form could not be read.");
            }
        }
        return form;
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static void sendText(HttpExchange ex, int status, String text) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

private static int number(Map<String, String> form, String key) {
    try {
        return Integer.parseInt(form.getOrDefault(key, "").trim());
    } catch (NumberFormatException e) {
        return -1;
    }
}
}