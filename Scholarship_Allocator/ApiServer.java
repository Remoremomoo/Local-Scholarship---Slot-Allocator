package Scholarship_Allocator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Small web server that connects the Scholarship_Allocator logic to the website.
 *
 * Run from the project folder:
 *     javac -d . *.java
 *     java -cp . Scholarship_Allocator.ApiServer
 *
 * Then open http://localhost:8080
 *
 * If port 8080 is taken:  java -cp . Scholarship_Allocator.ApiServer 9090
 *
 * Accounts:
 *   - Students register at /register.html then sign in at /login.html
 *   - Admin signs in at /login.html (Admin tab) and lands on /admin.html
 *   - Passwords are hashed with PBKDF2 and stored in users.txt
 *   - Sessions are HttpOnly cookies; restarting the server logs everyone out
 */
public class ApiServer {

    // =====================================================================
    //  SETTINGS - ito ang mga pwedeng baguhin
    // =====================================================================

    /** How many scholarships (slots) this cycle. */
    static final int SLOTS = 25;

    /**
     * Weights ng bawat criterion. Dapat total = 1.00.
     * Tugma ito sa nalathalang weights sa website (Figma design).
     */
    static final double GRADE_WEIGHT      = 0.40;
    static final double INCOME_WEIGHT     = 0.30;
    static final double DEPENDENTS_WEIGHT = 0.20;
    static final double NEED_WEIGHT       = 0.10;

    /** Folder ng website (index.html, apply.html, styles.css, app.js). */
    static final Path WEB_ROOT = Paths.get(System.getProperty("user.dir"), "web");

    /** File-based account storage (tugma sa proposal: walang DBMS). */
    static final Path USERS_FILE = Paths.get(System.getProperty("user.dir"), "users.txt");

    /**
     * Admin account na awtomatikong ginagawa sa unang run kung wala pang admin.
     * Bago mag-demo sa labas: palitan ito, tapos i-delete ang users.txt.
     */
    static final String DEFAULT_ADMIN_USER = "admin";
    static final String DEFAULT_ADMIN_PASS = "admin123";

    /** Session cookie. 8 oras bago mag-expire. */
    private static final String SESSION_COOKIE = "sid";
    private static final int SESSION_MAX_AGE = 8 * 60 * 60;

    // =====================================================================

    private static ScholarshipAllocator allocator;
    private static UserStore users;

    /** token -> username. Sa memory lang, kaya logout lahat kapag nag-restart. */
    private static final Map<String, String> sessions = new ConcurrentHashMap<>();
    private static final SecureRandom random = new SecureRandom();

    /**
     * Pending password-reset codes (OTP). Sa memory lang at 5 minuto ang buhay.
     * DEMO MODE: hindi talaga ipinapadala sa email -- ibinabalik sa API para
     * maipakita sa screen at maipakita sa presentation.
     */
    private static final Map<String, Otp> pendingOtps = new ConcurrentHashMap<>();
    private static final long OTP_TTL_MS = 5 * 60 * 1000L;
    private static final int OTP_MAX_ATTEMPTS = 5;

    private static class Otp {
        final String username;
        final String email;
        final String code;
        final long expiresAt;
        int attempts;

        Otp(String username, String email, String code, long expiresAt){
            this.username = username;
            this.email = email;
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }

    public static void main(String[] args) throws IOException {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Invalid port, using 8080.");
            }
        }

        allocator = new ScholarshipAllocator(SLOTS);
        allocator.addCriterion(new GradeCriterion(GRADE_WEIGHT));
        allocator.addCriterion(new IncomeCriterion(INCOME_WEIGHT));
        allocator.addCriterion(new DependentsCriterion(DEPENDENTS_WEIGHT));
        allocator.addCriterion(new NeedCriterion(NEED_WEIGHT));

        users = new UserStore(USERS_FILE);
        boolean adminCreated = users.ensureAdmin(DEFAULT_ADMIN_USER, DEFAULT_ADMIN_PASS);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", ApiServer::handleApi);
        server.createContext("/", ApiServer::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("Scholarship website is running:  http://localhost:" + port);
        System.out.println("Accounts file: " + USERS_FILE.toAbsolutePath()
                + "  (" + users.size() + " account/s)");
        if (adminCreated) {
            System.out.println();
            System.out.println("  Admin account created:");
            System.out.println("      username: " + DEFAULT_ADMIN_USER);
            System.out.println("      password: " + DEFAULT_ADMIN_PASS);
            System.out.println("  Palitan bago i-demo sa labas: i-edit ang DEFAULT_ADMIN_PASS");
            System.out.println("  sa ApiServer.java, tapos i-delete ang users.txt.");
            System.out.println();
        }
        System.out.println("API:  GET /api/config  GET /api/me  GET /api/application");
        System.out.println("      POST /api/register  POST /api/login  POST /api/logout");
        System.out.println("      POST /api/forgot-password  POST /api/reset-password  (OTP demo mode)");
        System.out.println("      POST /api/applicants (student)  GET /api/results (admin)");
        System.out.println("      POST /api/reset (admin)");
        System.out.println("Web root: " + WEB_ROOT.toAbsolutePath());
        System.out.println("Press Ctrl+C to stop.");
    }

    // =====================================================================
    //  API
    // =====================================================================

    private static void handleApi(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            if (path.equals("/api/config") && method.equals("GET"))    { sendJson(ex, 200, configJson()); return; }
            if (path.equals("/api/me") && method.equals("GET"))        { sendJson(ex, 200, meJson(ex)); return; }
            if (path.equals("/api/register") && method.equals("POST")) { handleRegister(ex); return; }
            if (path.equals("/api/login") && method.equals("POST"))    { handleLogin(ex); return; }
            if (path.equals("/api/logout") && method.equals("POST"))   { handleLogout(ex); return; }
            if (path.equals("/api/forgot-password") && method.equals("POST")) { handleForgotPassword(ex); return; }
            if (path.equals("/api/reset-password") && method.equals("POST"))  { handleResetPassword(ex); return; }

            if (path.equals("/api/application") && method.equals("GET"))  { handleMyApplication(ex); return; }
            if (path.equals("/api/applicants") && method.equals("POST"))  { handleAddApplicant(ex); return; }
            if (path.equals("/api/results") && method.equals("GET"))      { handleResults(ex); return; }
            if (path.equals("/api/reset") && method.equals("POST"))       { handleReset(ex); return; }

            sendJson(ex, 404, errorJson("Unknown endpoint: " + path));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, errorJson("Server error: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    /* ---------------------------- accounts ---------------------------- */

    private static void handleRegister(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(readBody(ex));
        try {
            UserStore.User user = users.register(form.get("username"), form.get("password"),
                    form.get("name"), form.get("barangay"), form.get("email"), form.get("phone"));
            startSession(ex, user.getUsername());
            sendJson(ex, 200, "{\"ok\":true,\"user\":" + userJson(user) + "}");
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, errorJson(e.getMessage()));
        }
    }

    private static void handleLogin(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(readBody(ex));
        String as = trim(form.get("as")).toLowerCase(Locale.ROOT);

        UserStore.User user = users.authenticate(form.get("username"), form.get("password"));
        if (user == null) {
            sendJson(ex, 401, errorJson("Wrong username or password."));
            return;
        }
        if (!as.isEmpty() && !as.equals(user.getRole())) {
            String message = as.equals(UserStore.ADMIN)
                    ? "This account is not an admin account."
                    : "This account is not a student account.";
            sendJson(ex, 403, errorJson(message));
            return;
        }

        startSession(ex, user.getUsername());
        sendJson(ex, 200, "{\"ok\":true,\"user\":" + userJson(user) + "}");
    }

    private static void handleLogout(HttpExchange ex) throws IOException {
        endSession(ex);
        sendJson(ex, 200, "{\"ok\":true}");
    }

    /**
     * Step 1 ng forgot-password: gumawa ng 6-digit OTP.
     * DEMO MODE: walang totoong email. Ibinalik ang code sa response
     * ("demoCode") at ipinapakita sa page, para may maipakita sa presentation.
     */
    private static void handleForgotPassword(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(readBody(ex));
        String identifier = trim(form.get("identifier"));

        if (identifier.isEmpty()) {
            sendJson(ex, 400, errorJson("Please enter your username or email."));
            return;
        }

        UserStore.User user = users.findByIdentifier(identifier);
        if (user == null) {
            sendJson(ex, 404, errorJson("No account found with that username or email."));
            return;
        }
        if (user.isAdmin()) {
            sendJson(ex, 403, errorJson("Admin accounts are recovered by the scholarship office."));
            return;
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        pendingOtps.put(user.getUsername(),
                new Otp(user.getUsername(), user.getEmail(), code, System.currentTimeMillis() + OTP_TTL_MS));

        System.out.println("[OTP demo] " + user.getUsername() + " -> " + code
                + "  (ipapadala sana sa " + user.getEmail() + ")");

        String json = "{\"ok\":true"
                + ",\"email\":" + q(maskEmail(user.getEmail()))
                + ",\"demoCode\":" + q(code)
                + ",\"expiresInSeconds\":" + (OTP_TTL_MS / 1000)
                + "}";
        sendJson(ex, 200, json);
    }

    /** Step 2: i-verify ang OTP at palitan ang password. */
    private static void handleResetPassword(HttpExchange ex) throws IOException {
        Map<String, String> form = parseForm(readBody(ex));
        String identifier = trim(form.get("identifier"));
        String otp = trim(form.get("otp"));
        String password = form.get("password");

        UserStore.User user = users.findByIdentifier(identifier);
        if (user == null) {
            sendJson(ex, 404, errorJson("No account found with that username or email."));
            return;
        }

        Otp pending = pendingOtps.get(user.getUsername());
        if (pending == null) {
            sendJson(ex, 400, errorJson("No reset code for this account. Please request a new one."));
            return;
        }
        if (pending.expiresAt < System.currentTimeMillis()) {
            pendingOtps.remove(user.getUsername());
            sendJson(ex, 400, errorJson("That code has expired. Please request a new one."));
            return;
        }
        if (pending.attempts >= OTP_MAX_ATTEMPTS) {
            pendingOtps.remove(user.getUsername());
            sendJson(ex, 429, errorJson("Too many wrong codes. Please request a new one."));
            return;
        }
        if (!pending.code.equals(otp)) {
            pending.attempts++;
            int left = OTP_MAX_ATTEMPTS - pending.attempts;
            sendJson(ex, 400, errorJson("That code is not correct. " + left + " attempt/s left."));
            return;
        }
        if (password == null || password.length() < 8) {
            sendJson(ex, 400, errorJson("Password must be at least 8 characters."));
            return;
        }

        try {
            users.changePassword(user.getUsername(), password);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, errorJson(e.getMessage()));
            return;
        }

        pendingOtps.remove(user.getUsername());
        // Log out everywhere para hindi na valid ang lumang session.
        sessions.entrySet().removeIf(entry -> user.getUsername().equals(entry.getValue()));

        sendJson(ex, 200, "{\"ok\":true}");
    }

    /** ju***@gmail.com para hindi buong email ang lumabas sa screen. */
    private static String maskEmail(String email) {
        if (email == null || email.isEmpty()) return "your email";
        int at = email.indexOf('@');
        if (at <= 0) return email;
        String name = email.substring(0, at);
        String visible = name.length() <= 2 ? name : name.substring(0, 2);
        return visible + "***" + email.substring(at);
    }

    private static String meJson(HttpExchange ex) {
        UserStore.User user = currentUser(ex);
        return "{\"user\":" + (user == null ? "null" : userJson(user)) + "}";
    }

    /* -------------------------- applications -------------------------- */

    /** Sariling application lang ang nakikita ng student. */
    private static void handleMyApplication(HttpExchange ex) throws IOException {
        UserStore.User user = currentUser(ex);
        if (user == null) {
            sendJson(ex, 401, errorJson("Please sign in first."));
            return;
        }
        if (user.isAdmin()) {
            sendJson(ex, 403, errorJson("Admin accounts do not have an application."));
            return;
        }

        Applicant mine;
        List<Applicant> ranked;
        synchronized (allocator) {
            mine = findMine(user.getUsername());
            ranked = ranked();
        }

        if (mine == null) {
            sendJson(ex, 200, "{\"submitted\":false}");
            return;
        }

        int rank = ranked.indexOf(mine) + 1;
        sendJson(ex, 200, applicationJson(mine, rank, ranked.size()));
    }

    private static void handleAddApplicant(HttpExchange ex) throws IOException {
        UserStore.User user = currentUser(ex);
        if (user == null) {
            sendJson(ex, 401, errorJson("Please sign in to submit an application."));
            return;
        }
        if (user.isAdmin()) {
            sendJson(ex, 403, errorJson("Admin accounts cannot submit an application."));
            return;
        }

        Map<String, String> form = parseForm(readBody(ex));

        double grade, income;
        int dependents;
        try {
            grade      = Double.parseDouble(trim(form.get("grade")));
            income     = Double.parseDouble(trim(form.get("income")));
            dependents = (int) Math.round(Double.parseDouble(trim(form.get("dependents"))));
        } catch (NumberFormatException e) {
            sendJson(ex, 400, errorJson("Grade, income and dependents must be numbers."));
            return;
        }

        // Validate bago ipasok, dahil tahimik na binabalewala ng setters ang invalid values.
        if (grade < 0 || grade > 100) {
            sendJson(ex, 400, errorJson("General average must be between 0 and 100."));
            return;
        }
        if (income < 0) {
            sendJson(ex, 400, errorJson("Monthly family income cannot be negative."));
            return;
        }
        if (dependents < 0 || dependents > 30) {
            sendJson(ex, 400, errorJson("Number of dependents must be between 0 and 30."));
            return;
        }

        // Pangalan at barangay ay galing sa account -- hindi mula sa form,
        // para walang makapag-apply sa pangalan ng iba.
        Applicant applicant = new Applicant(user.getFullName(), user.getBarangay(),
                grade, income, dependents);
        applicant.setOwner(user.getUsername());

        int rank;
        int total;
        synchronized (allocator) {
            if (findMine(user.getUsername()) != null) {
                sendJson(ex, 409, errorJson(
                        "You already submitted an application this cycle. Please check your status."));
                return;
            }
            allocator.addApplicant(applicant);
            List<Applicant> ranked = ranked();
            total = ranked.size();
            rank = ranked.indexOf(applicant) + 1;
        }

        String json = "{\"ok\":true,\"applicant\":{"
                + "\"name\":" + q(applicant.getName()) + ","
                + "\"barangay\":" + q(applicant.getBarangay()) + ","
                + "\"score\":" + round2(allocator.computeTotalScore(applicant)) + ","
                + "\"rank\":" + rank + ","
                + "\"total\":" + total + ","
                + "\"status\":" + q(statusOf(rank))
                + "}}";
        sendJson(ex, 200, json);
    }

    /** Buong ranking: admin lang. */
    private static void handleResults(HttpExchange ex) throws IOException {
        UserStore.User user = currentUser(ex);
        if (user == null) {
            sendJson(ex, 401, errorJson("Please sign in first."));
            return;
        }
        if (!user.isAdmin()) {
            sendJson(ex, 403, errorJson("Only the admin can view the full ranking."));
            return;
        }
        sendJson(ex, 200, resultsJson());
    }

    /** Simula ng bagong cycle: admin lang. */
    private static void handleReset(HttpExchange ex) throws IOException {
        UserStore.User user = currentUser(ex);
        if (user == null) {
            sendJson(ex, 401, errorJson("Please sign in first."));
            return;
        }
        if (!user.isAdmin()) {
            sendJson(ex, 403, errorJson("Only the admin can clear the cycle."));
            return;
        }
        synchronized (allocator) {
            allocator.clearApplicants();
        }
        sendJson(ex, 200, "{\"ok\":true}");
    }

    /* ------------------------------ JSON ------------------------------ */

    private static String configJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"slots\":").append(allocator.getSlots()).append(",\"criteria\":[");
        List<Criterion> criteria = allocator.getCriteria();
        for (int i = 0; i < criteria.size(); i++) {
            Criterion c = criteria.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(q(labelOf(c)))
              .append(",\"weight\":").append(c.getWeight())
              .append(",\"percent\":").append(round2(c.getWeight() * 100))
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String resultsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"slots\":").append(allocator.getSlots()).append(",\"criteria\":[");

        List<Criterion> criteria = allocator.getCriteria();
        for (int i = 0; i < criteria.size(); i++) {
            Criterion c = criteria.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(q(labelOf(c)))
              .append(",\"weight\":").append(c.getWeight())
              .append('}');
        }
        sb.append("],\"results\":[");

        List<Applicant> ranked;
        synchronized (allocator) {
            ranked = ranked();
        }

        for (int i = 0; i < ranked.size(); i++) {
            Applicant a = ranked.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"rank\":").append(i + 1)
              .append(",\"name\":").append(q(a.getName()))
              .append(",\"barangay\":").append(q(a.getBarangay()))
              .append(",\"grade\":").append(a.getGrade())
              .append(",\"income\":").append(a.getIncome())
              .append(",\"dependents\":").append(a.getDependents())
              .append(",\"score\":").append(round2(allocator.computeTotalScore(a)))
              .append(",\"status\":").append(q(statusOf(i + 1)))
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String applicationJson(Applicant a, int rank, int total) {
        return "{\"submitted\":true"
                + ",\"name\":" + q(a.getName())
                + ",\"barangay\":" + q(a.getBarangay())
                + ",\"grade\":" + a.getGrade()
                + ",\"income\":" + a.getIncome()
                + ",\"dependents\":" + a.getDependents()
                + ",\"score\":" + round2(allocator.computeTotalScore(a))
                + ",\"rank\":" + rank
                + ",\"total\":" + total
                + ",\"status\":" + q(statusOf(rank))
                + "}";
    }

    private static String userJson(UserStore.User u) {
        return "{\"username\":" + q(u.getUsername())
                + ",\"role\":" + q(u.getRole())
                + ",\"name\":" + q(u.getFullName())
                + ",\"barangay\":" + q(u.getBarangay())
                + "}";
    }

    /* ----------------------------- helpers ---------------------------- */

    /** Same ordering as ScholarshipAllocator.printResults(). */
    private static List<Applicant> ranked() {
        List<Applicant> list = allocator.getApplicants();
        list.sort(allocator::compareApplicants);
        return list;
    }

    private static Applicant findMine(String username) {
        for (Applicant a : allocator.getApplicants()) {
            if (username.equals(a.getOwner())) return a;
        }
        return null;
    }

    private static String statusOf(int rank) {
        return rank <= SLOTS ? "GRANTED" : "WAITLIST";
    }

    private static UserStore.User currentUser(HttpExchange ex) {
        String token = cookieValue(ex, SESSION_COOKIE);
        if (token == null) return null;
        String username = sessions.get(token);
        if (username == null) return null;
        return users.find(username);
    }

    private static void startSession(HttpExchange ex, String username) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, username);
        ex.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + SESSION_MAX_AGE);
    }

    private static void endSession(HttpExchange ex) {
        String token = cookieValue(ex, SESSION_COOKIE);
        if (token != null) sessions.remove(token);
        ex.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    }

    private static String cookieValue(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                if (part.substring(0, eq).trim().equals(name)) {
                    return part.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    private static String labelOf(Criterion c) {
        if (c instanceof GradeCriterion)      return "Academic";
        if (c instanceof IncomeCriterion)     return "Income";
        if (c instanceof DependentsCriterion) return "Dependents";
        if (c instanceof NeedCriterion)       return "Household need";
        return c.getClass().getSimpleName().replace("Criterion", "");
    }

    // =====================================================================
    //  Static website
    // =====================================================================

    private static void handleStatic(HttpExchange ex) throws IOException {
        try {
            String requested = ex.getRequestURI().getPath();
            if (requested.equals("/") || requested.isEmpty()) requested = "/index.html";

            Path file = WEB_ROOT.resolve(requested.substring(1)).normalize();

            if (!file.startsWith(WEB_ROOT) || !Files.isRegularFile(file)) {
                send(ex, 404, "text/plain; charset=utf-8", "404 Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }

            String type = contentType(file.toString());
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            send(ex, 200, type, Files.readAllBytes(file));
        } finally {
            ex.close();
        }
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".html"))  return "text/html; charset=utf-8";
        if (lower.endsWith(".css"))   return "text/css; charset=utf-8";
        if (lower.endsWith(".js"))    return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json"))  return "application/json; charset=utf-8";
        if (lower.endsWith(".svg"))   return "image/svg+xml";
        if (lower.endsWith(".png"))   return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp"))  return "image/webp";
        if (lower.endsWith(".ico"))   return "image/x-icon";
        return "application/octet-stream";
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private static void sendJson(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody(); ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            in.transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            map.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String errorJson(String message) {
        return "{\"error\":" + q(message) + "}";
    }

    /** JSON string escaping. */
    private static String q(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    private static String round2(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }
}
