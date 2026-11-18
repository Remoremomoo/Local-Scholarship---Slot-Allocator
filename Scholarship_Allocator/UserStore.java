package Scholarship_Allocator;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * File-based account storage (users.txt) -- tugma sa proposal na
 * text-file storage lang, walang DBMS at walang external library.
 *
 * Format ng bawat linya (9 na field):
 *   username|role|iterations|saltBase64|hashBase64|fullName|barangay|email|phone
 *
 * Ang lumang 7-field na linya ay binabasa pa rin (walang email/phone).
 *
 * Paano naka-hash ang password:
 *   PBKDF2WithHmacSHA256, 120,000 iterations, 16-byte random salt,
 *   256-bit derived key. Built-in lang ito sa JDK (javax.crypto),
 *   kaya walang kailangang dependency. Hindi naka-plain text ang
 *   password kahit buksan mo ang users.txt.
 */
public class UserStore {

    public static final String STUDENT = "student";
    public static final String ADMIN   = "admin";

    private static final int ITERATIONS  = 120_000;
    private static final int KEY_BITS    = 256;
    private static final int SALT_BYTES  = 16;
    private static final String SEP      = "|";
    private static final String EMAIL_PATTERN = "^[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}$";

    /** Simpleng user record na ipinapadala sa front end/API. */
    public static class User {
        private final String username;
        private final String role;
        private final String fullName;
        private final String barangay;
        private final String email;
        private final String phone;

        User(String username, String role, String fullName, String barangay,
             String email, String phone){
            this.username = username;
            this.role = role;
            this.fullName = fullName;
            this.barangay = barangay;
            this.email = email;
            this.phone = phone;
        }

        public String getUsername(){ return username; }
        public String getRole(){ return role; }
        public String getFullName(){ return fullName; }
        public String getBarangay(){ return barangay; }
        public String getEmail(){ return email; }
        public String getPhone(){ return phone; }
        public boolean isAdmin(){ return ADMIN.equals(role); }
    }

    /** Row sa users.txt (kasama ang hash material). */
    private static class Entry {
        final String username, role, salt, hash, fullName, barangay, email, phone;
        final int iterations;

        Entry(String username, String role, int iterations, String salt, String hash,
              String fullName, String barangay, String email, String phone){
            this.username = username;
            this.role = role;
            this.iterations = iterations;
            this.salt = salt;
            this.hash = hash;
            this.fullName = fullName;
            this.barangay = barangay;
            this.email = email == null ? "" : email;
            this.phone = phone == null ? "" : phone;
        }

        String line(){
            return String.join(SEP, username, role, String.valueOf(iterations),
                    salt, hash, fullName, barangay, email, phone);
        }

        User toUser(){
            return new User(username, role, fullName, barangay, email, phone);
        }
    }

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public UserStore(Path file) throws IOException {
        this.file = file;
        load();
    }

    /* ------------------------------------------------------------------ */
    /*  Public API                                                         */
    /* ------------------------------------------------------------------ */

    public synchronized int size(){
        return entries.size();
    }

    public synchronized User find(String username){
        Entry entry = entries.get(key(username));
        return entry == null ? null : entry.toUser();
    }

    /** Hanapin gamit ang username O email. */
    public synchronized User findByIdentifier(String identifier){
        Entry entry = entryFor(identifier);
        return entry == null ? null : entry.toUser();
    }

    /** Meron na bang admin account? Ginagamit para sa first-run seeding. */
    public synchronized boolean hasAdmin(){
        for (Entry entry : entries.values()){
            if (ADMIN.equals(entry.role)) return true;
        }
        return false;
    }

    /**
     * Gumagawa ng admin account kung wala pa.
     * @return true kung bagong gawa (para mai-print ang credentials sa console)
     */
    public synchronized boolean ensureAdmin(String username, String password) throws IOException {
        if (hasAdmin()) return false;
        String user = validateUsername(username);
        String pass = validatePassword(password);
        if (entries.containsKey(user)){
            throw new IllegalStateException("Username '" + user + "' is already taken.");
        }
        entries.put(user, newEntry(user, pass, ADMIN,
                "Scholarship Administrator", "City Scholarship Office", "", ""));
        save();
        return true;
    }

    /**
     * Student registration.
     * @throws IllegalArgumentException kapag invalid o taken ang username/email/password
     */
    public synchronized User register(String username, String password, String fullName,
                                      String barangay, String email, String phone) throws IOException {
        String user = validateUsername(username);
        String pass = validatePassword(password);
        String name = clean(fullName);
        String brgy = clean(barangay);
        String mail = validateEmail(email);
        String tel  = validatePhone(phone);

        if (name.isEmpty()) throw new IllegalArgumentException("Please enter your full name.");
        if (brgy.isEmpty()) throw new IllegalArgumentException("Please enter your barangay.");
        if (entries.containsKey(user)) {
            throw new IllegalArgumentException("That username is already taken. Please choose another one.");
        }
        if (entryByEmail(mail) != null) {
            throw new IllegalArgumentException("That email is already used by another account.");
        }

        entries.put(user, newEntry(user, pass, STUDENT, name, brgy, mail, tel));
        save();
        return new User(user, STUDENT, name, brgy, mail, tel);
    }

    /**
     * Login gamit ang username O email.
     * @return ang User kapag tama ang identifier at password, null kung hindi.
     */
    public synchronized User authenticate(String identifier, String password){
        if (identifier == null || password == null) return null;
        Entry entry = entryFor(identifier);
        if (entry == null) return null;

        byte[] salt = Base64.getDecoder().decode(entry.salt);
        String candidate = hash(password, salt, entry.iterations);
        // Constant-time comparison para hindi malaman sa timing kung
        // tumama ang password.
        boolean match = MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                entry.hash.getBytes(StandardCharsets.UTF_8));
        return match ? entry.toUser() : null;
    }

    /**
     * Bagong password (ginagamit ng forgot-password flow pagkatapos ma-verify
     * ang OTP). Bagong salt at hash ang gagawin.
     */
    public synchronized void changePassword(String username, String newPassword) throws IOException {
        String pass = validatePassword(newPassword);
        Entry old = entries.get(key(username));
        if (old == null) throw new IllegalArgumentException("Account not found.");
        entries.put(key(username), newEntry(old.username, pass, old.role,
                old.fullName, old.barangay, old.email, old.phone));
        save();
    }

    /* ------------------------------------------------------------------ */
    /*  Internals                                                          */
    /* ------------------------------------------------------------------ */

    private Entry entryFor(String identifier){
        if (identifier == null) return null;
        Entry byUsername = entries.get(key(identifier));
        return byUsername != null ? byUsername : entryByEmail(identifier);
    }

    private Entry entryByEmail(String email){
        String target = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) return null;
        for (Entry entry : entries.values()){
            if (entry.email != null && entry.email.equalsIgnoreCase(target)) return entry;
        }
        return null;
    }

    private Entry newEntry(String username, String password, String role, String fullName,
                           String barangay, String email, String phone){
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        String encodedSalt = Base64.getEncoder().encodeToString(salt);
        String hash = hash(password, salt, ITERATIONS);
        return new Entry(username, role, ITERATIONS, encodedSalt, hash,
                fullName, barangay, email, phone);
    }

    private static String hash(String password, byte[] salt, int iterations){
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return Base64.getEncoder().encodeToString(factory.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash the password: " + e.getMessage(), e);
        }
    }

    private static String key(String username){
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String clean(String value){
        return value == null ? "" : value.replace(SEP, " ").trim();
    }

    private static String validateUsername(String username){
        String user = key(username);
        if (!user.matches("[a-z0-9._-]{3,24}")) {
            throw new IllegalArgumentException(
                "Username must be 3-24 characters: letters, numbers, dot, dash or underscore.");
        }
        return user;
    }

    private static String validatePassword(String password){
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters.");
        }
        return password;
    }

    private static String validateEmail(String email){
        String mail = clean(email).toLowerCase(Locale.ROOT);
        if (!mail.matches(EMAIL_PATTERN)) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }
        return mail;
    }

    private static String validatePhone(String phone){
        String tel = clean(phone);
        String digits = tel.replaceAll("[^0-9]", "");
        if (digits.length() < 7 || digits.length() > 15 || !tel.matches("[0-9+()\\-\\s]+")) {
            throw new IllegalArgumentException("Please enter a valid phone number.");
        }
        return tel;
    }

    private synchronized void load() throws IOException {
        entries.clear();
        if (!Files.isRegularFile(file)) return;

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int lineNo = 0;
        for (String line : lines){
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            String[] parts = trimmed.split("\\|", -1);
            if (parts.length < 7){
                System.out.println("Skipping malformed line " + lineNo + " in " + file.getFileName());
                continue;
            }

            int iterations;
            try {
                iterations = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                System.out.println("Skipping malformed line " + lineNo + " in " + file.getFileName());
                continue;
            }

            // 7-field = lumang account (walang email/phone).
            String email = parts.length > 7 ? parts[7] : "";
            String phone = parts.length > 8 ? parts[8] : "";

            Entry entry = new Entry(parts[0], parts[1], iterations, parts[3], parts[4],
                    parts[5], parts[6], email, phone);
            entries.put(key(entry.username), entry);
        }
    }

    private synchronized void save() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Local Scholarship accounts -- huwag i-commit sa git.");
        lines.add("# username|role|iterations|salt|hash|fullName|barangay|email|phone");
        for (Entry entry : entries.values()) lines.add(entry.line());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
