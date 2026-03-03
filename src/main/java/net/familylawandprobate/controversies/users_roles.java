package net.familylawandprobate.controversies;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jakarta.servlet.http.HttpSession;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * users_roles
 *
 * Per-tenant users + roles + permissions stored in:
 *   data/tenants/{tenantUuid}/users.xml
 *   data/tenants/{tenantUuid}/roles.xml
 *
 * Global pepper for password hashing:
 *   data/sec/random_pepper.bin
 *
 * Per-user per-session binding files (ip + session + cookie):
 *   data/tenants/{tenantUuid}/bindings/user_binding_{userUuid}_session_{sessionId}.xml
 *
 * users.xml
 *   <users>
 *     <user>
 *       <uuid>...</uuid>
 *       <enabled>true</enabled>
 *       <role_uuid>...</role_uuid>
 *       <email_address>...</email_address>
 *       <algo2id_password_hash>...</algo2id_password_hash>
 *     </user>
 *   </users>
 *
 * roles.xml
 *   <roles>
 *     <role>
 *       <uuid>...</uuid>
 *       <enabled>true</enabled>
 *       <label>...</label>
 *       <permissions>
 *         <permission key="tenant_admin">true</permission>
 *         <permission key="cases.view">true</permission>
 *       </permissions>
 *     </role>
 *   </roles>
 *
 * Tenant bootstrap (idempotent; called from ensure()):
 *   - Ensures a role "Tenant Administrator" exists, enabled, with permission tenant_admin=true
 *   - Ensures a user "tenant_admin" exists, enabled, assigned to that role, with default password "password"
 *     (only sets the default password if the user is new OR has a blank hash; does not reset existing hashes)
 */
public final class users_roles {

    // -----------------------------
    // Session keys (per-user auth)
    // -----------------------------
    public static final String S_USER_UUID   = "user.uuid";
    public static final String S_USER_EMAIL  = "user.email";
    public static final String S_ROLE_UUID   = "user.role.uuid";
    public static final String S_ROLE_LABEL  = "user.role.label";
    public static final String S_PERMS_MAP   = "user.perms.map";     // Map<String,String>
    public static final String S_PERMS_PREFX = "perm.";              // perm.<key> = <value>

    private static final SecureRandom RNG = new SecureRandom();

    // One lock per tenantUuid to avoid concurrent write corruption.
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    // -----------------------------
    // Global pepper (password hashing)
    // -----------------------------
    private static final Path PEPPER_PATH = Paths.get("data", "sec", "random_pepper.bin").toAbsolutePath();
    private static volatile byte[] PEPPER_CACHE = null;
    private static volatile long PEPPER_MTIME = -1L;
    private static volatile long PEPPER_SIZE  = -1L;
    private static final Object PEPPER_LOCK = new Object();

    // -----------------------------
    // Tenant bootstrap (admin role/user)
    // -----------------------------
    private static final String BOOTSTRAP_ROLE_LABEL = "Tenant Administrator";
    private static final String BOOTSTRAP_PERM_KEY   = "tenant_admin";
    private static final String BOOTSTRAP_PERM_VALUE = "true";
    private static final String BOOTSTRAP_ADMIN_EMAIL = "tenant_admin"; // stored in <email_address>
    private static final String BOOTSTRAP_DEFAULT_PASSWORD = "password";
    private static final String USER_TWO_FACTOR_ENGINE_INHERIT = "inherit";
    private static final String USER_TWO_FACTOR_ENGINE_EMAIL_PIN = "email_pin";
    private static final String USER_TWO_FACTOR_ENGINE_FLOWROUTE_SMS = "flowroute_sms";

    public static users_roles defaultStore() {
        return new users_roles();
    }

    // --------------------------------
    // Public model objects
    // --------------------------------
    public static final class UserRec {
        public final String uuid;
        public final boolean enabled;
        public final String roleUuid;
        public final String emailAddress;           // stored normalized (lowercase trim)
        public final String algo2idPasswordHash;    // Argon2id encoded string
        public final boolean twoFactorEnabled;
        public final String twoFactorEngine;        // inherit | email_pin | flowroute_sms
        public final String twoFactorPhone;         // optional phone destination (for SMS)

        public UserRec(String uuid, boolean enabled, String roleUuid, String emailAddress, String algo2idPasswordHash) {
            this(uuid, enabled, roleUuid, emailAddress, algo2idPasswordHash, false, USER_TWO_FACTOR_ENGINE_INHERIT, "");
        }

        public UserRec(String uuid,
                       boolean enabled,
                       String roleUuid,
                       String emailAddress,
                       String algo2idPasswordHash,
                       boolean twoFactorEnabled,
                       String twoFactorEngine,
                       String twoFactorPhone) {
            this.uuid = safe(uuid);
            this.enabled = enabled;
            this.roleUuid = safe(roleUuid);
            this.emailAddress = normalizeEmail(emailAddress);
            this.algo2idPasswordHash = safe(algo2idPasswordHash);
            this.twoFactorEnabled = twoFactorEnabled;
            this.twoFactorEngine = normalizeUserTwoFactorEngine(twoFactorEngine);
            this.twoFactorPhone = normalizePhone(twoFactorPhone);
        }
    }

    public static final class RoleRec {
        public final String uuid;
        public final boolean enabled;
        public final String label;
        public final LinkedHashMap<String, String> permissions; // key -> value

        public RoleRec(String uuid, boolean enabled, String label, Map<String, String> permissions) {
            this.uuid = safe(uuid);
            this.enabled = enabled;
            this.label = safe(label);
            this.permissions = new LinkedHashMap<>();
            if (permissions != null) this.permissions.putAll(permissions);
        }
    }

    public static final class AuthResult {
        public final UserRec user;
        public final RoleRec role;
        public final LinkedHashMap<String, String> permissions; // resolved role permissions snapshot

        public AuthResult(UserRec user, RoleRec role) {
            this.user = user;
            this.role = role;
            this.permissions = new LinkedHashMap<>();
            if (role != null && role.permissions != null) this.permissions.putAll(role.permissions);
        }
    }

    // --------------------------------
    // Per-user session binding (ip + session + cookie)
    // --------------------------------
    public static final class UserBinding {
        public final String tenantUuid;
        public final String userUuid;
        public final String email;
        public final String roleUuid;
        public final String ip;
        public final String sessionId;
        public final String cookie;

        public UserBinding(String tenantUuid, String userUuid, String email, String roleUuid, String ip, String sessionId, String cookie) {
            this.tenantUuid = safe(tenantUuid);
            this.userUuid = safe(userUuid);
            this.email = normalizeEmail(email);
            this.roleUuid = safe(roleUuid);
            this.ip = safe(ip);
            this.sessionId = safe(sessionId);
            this.cookie = safe(cookie);
        }
    }

    private static String safeFileToken(String s) {
        s = safe(s).trim();
        if (s.isBlank()) return "unknown";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path userBindingPath(String tenantUuid, String userUuid, String sessionId) {
        String uu = safeFileToken(userUuid);
        String sid = safeFileToken(sessionId);
        return Paths.get("data", "tenants", tenantUuid, "bindings",
                "user_binding_" + uu + "_session_" + sid + ".xml").toAbsolutePath();
    }

    public void writeUserBinding(String tenantUuid,
                                 String userUuid,
                                 String email,
                                 String roleUuid,
                                 String ip,
                                 String sessionId,
                                 String cookieBind) throws Exception {
        String tu = safe(tenantUuid).trim();
        String uu = safe(userUuid).trim();
        String sid = safe(sessionId).trim();
        if (tu.isBlank() || uu.isBlank() || sid.isBlank()) return;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path p = userBindingPath(tu, uu, sid);
            Files.createDirectories(p.getParent());

            UserBinding b = new UserBinding(tu, uu, email, roleUuid, ip, sid, cookieBind);

            String now = Instant.now().toString();
            String xml =
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<userBinding created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\" " +
                    "tenantUuid=\"" + xmlAttr(b.tenantUuid) + "\" userUuid=\"" + xmlAttr(b.userUuid) + "\" " +
                    "email=\"" + xmlAttr(b.email) + "\" roleUuid=\"" + xmlAttr(b.roleUuid) + "\">\n" +
                    "  <ip>" + xmlText(b.ip) + "</ip>\n" +
                    "  <sessionId>" + xmlText(b.sessionId) + "</sessionId>\n" +
                    "  <cookie>" + xmlText(b.cookie) + "</cookie>\n" +
                    "</userBinding>\n";

            writeAtomic(p, xml);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public UserBinding readUserBinding(String tenantUuid, String userUuid, String sessionId) {
        try {
            String tu = safe(tenantUuid).trim();
            String uu = safe(userUuid).trim();
            String sid = safe(sessionId).trim();
            if (tu.isBlank() || uu.isBlank() || sid.isBlank()) return null;

            Path p = userBindingPath(tu, uu, sid);
            if (!Files.exists(p)) return null;

            Document d = parseXml(p);
            Element root = (d == null) ? null : d.getDocumentElement();
            if (root == null) return null;

            String t = safe(root.getAttribute("tenantUuid"));
            String u = safe(root.getAttribute("userUuid"));
            String em = safe(root.getAttribute("email"));
            String ru = safe(root.getAttribute("roleUuid"));

            String ip = "";
            String s = "";
            String cb = "";

            NodeList nl = root.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                String tag = safe(e.getTagName()).trim();
                String val = safe(e.getTextContent()).trim();
                if ("ip".equalsIgnoreCase(tag)) ip = val;
                else if ("sessionId".equalsIgnoreCase(tag)) s = val;
                else if ("cookie".equalsIgnoreCase(tag)) cb = val;
            }
            return new UserBinding(t, u, em, ru, ip, s, cb);
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean userBindingMatches(String tenantUuid,
                                      String userUuid,
                                      String sessionId,
                                      String ip,
                                      String cookieBind) {
        UserBinding b = readUserBinding(tenantUuid, userUuid, sessionId);
        if (b == null) return false;
        String cip = safe(ip);
        String sid = safe(sessionId);
        String cb = safe(cookieBind);
        return cip.equals(b.ip) && sid.equals(b.sessionId) && cb.equals(b.cookie);
    }

    public void deleteUserBinding(String tenantUuid, String userUuid, String sessionId) {
        try {
            String tu = safe(tenantUuid).trim();
            String uu = safe(userUuid).trim();
            String sid = safe(sessionId).trim();
            if (tu.isBlank() || uu.isBlank() || sid.isBlank()) return;

            ReentrantReadWriteLock lock = lockFor(tu);
            lock.writeLock().lock();
            try {
                deleteQuiet(userBindingPath(tu, uu, sid));
            } finally {
                lock.writeLock().unlock();
            }
        } catch (Exception ignored) {}
    }

    // --------------------------------
    // Ensure files exist + bootstrap admin role/user
    // --------------------------------
    public void ensure(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        // ensure pepper exists early (global)
        getPepper();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path users = usersPath(tu);
            Path roles = rolesPath(tu);

            Files.createDirectories(users.getParent());
            Files.createDirectories(roles.getParent());

            if (!Files.exists(users)) writeAtomic(users, emptyUsersXml());
            if (!Files.exists(roles)) writeAtomic(roles, emptyRolesXml());

            // bootstrap Tenant Administrator role + tenant_admin user (idempotent)
            ensureTenantBootstrapLocked(tu);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Idempotent bootstrap; assumes caller holds tenant write lock.
     */
    private void ensureTenantBootstrapLocked(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return;

        byte[] pepper = getPepper();

        // Ensure role exists, enabled, and has tenant_admin=true
        List<RoleRec> roles = readRoles(tu);
        RoleRec adminRole = null;
        for (RoleRec r : roles) {
            if (r != null && BOOTSTRAP_ROLE_LABEL.equalsIgnoreCase(safe(r.label).trim())) {
                adminRole = r;
                break;
            }
        }

        boolean rolesChanged = false;
        if (adminRole == null) {
            LinkedHashMap<String, String> perms = new LinkedHashMap<>();
            perms.put(BOOTSTRAP_PERM_KEY, BOOTSTRAP_PERM_VALUE);

            adminRole = new RoleRec(UUID.randomUUID().toString(), true, BOOTSTRAP_ROLE_LABEL, perms);
            roles.add(adminRole);
            rolesChanged = true;
        } else {
            boolean needUpdate = false;

            boolean enabled = adminRole.enabled;
            if (!enabled) {
                enabled = true;
                needUpdate = true;
            }

            LinkedHashMap<String, String> perms = new LinkedHashMap<>(adminRole.permissions == null ? Map.of() : adminRole.permissions);
            String cur = perms.get(BOOTSTRAP_PERM_KEY);
            if (cur == null || !BOOTSTRAP_PERM_VALUE.equalsIgnoreCase(cur.trim())) {
                perms.put(BOOTSTRAP_PERM_KEY, BOOTSTRAP_PERM_VALUE);
                needUpdate = true;
            }

            if (needUpdate) {
                List<RoleRec> out = new ArrayList<>(roles.size());
                for (RoleRec r : roles) {
                    if (r != null && safe(r.uuid).equals(adminRole.uuid)) {
                        out.add(new RoleRec(adminRole.uuid, enabled, BOOTSTRAP_ROLE_LABEL, perms));
                    } else {
                        out.add(r);
                    }
                }
                roles = out;
                adminRole = findRoleByUuid(roles, adminRole.uuid);
                rolesChanged = true;
            }
        }

        if (rolesChanged) {
            writeRoles(tu, roles);
        }

        if (adminRole == null) return;

        // Ensure tenant_admin user exists, enabled, assigned to adminRole, and has a hash if missing
        List<UserRec> users = readUsers(tu);
        String adminEmail = normalizeEmail(BOOTSTRAP_ADMIN_EMAIL);
        UserRec adminUser = findUserByEmail(users, adminEmail);

        boolean usersChanged = false;

        if (adminUser == null) {
            char[] pw = BOOTSTRAP_DEFAULT_PASSWORD.toCharArray();
            String hash;
            try {
                hash = Argon2id.hash(pw, pepper);
            } finally {
                wipe(pw);
            }

            adminUser = new UserRec(
                    UUID.randomUUID().toString(),
                    true,
                    adminRole.uuid,
                    adminEmail,
                    hash,
                    false,
                    USER_TWO_FACTOR_ENGINE_INHERIT,
                    ""
            );
            users.add(adminUser);
            usersChanged = true;
        } else {
            boolean needUpdate = false;

            boolean enabled = adminUser.enabled;
            if (!enabled) {
                enabled = true;
                needUpdate = true;
            }

            String roleUuid = safe(adminUser.roleUuid).trim();
            if (roleUuid.isBlank() || !roleUuid.equals(adminRole.uuid)) {
                roleUuid = adminRole.uuid;
                needUpdate = true;
            }

            String email = normalizeEmail(adminUser.emailAddress);
            if (!adminEmail.equals(email)) {
                email = adminEmail;
                needUpdate = true;
            }

            String hash = safe(adminUser.algo2idPasswordHash).trim();
            if (hash.isBlank()) {
                // Only set default if missing/blank; does NOT reset existing passwords.
                char[] pw = BOOTSTRAP_DEFAULT_PASSWORD.toCharArray();
                try {
                    hash = Argon2id.hash(pw, pepper);
                } finally {
                    wipe(pw);
                }
                needUpdate = true;
            }

            if (needUpdate) {
                List<UserRec> out = new ArrayList<>(users.size());
                for (UserRec u : users) {
                    if (u != null && safe(u.uuid).equals(adminUser.uuid)) {
                        out.add(new UserRec(
                                adminUser.uuid,
                                enabled,
                                roleUuid,
                                email,
                                hash,
                                adminUser.twoFactorEnabled,
                                adminUser.twoFactorEngine,
                                adminUser.twoFactorPhone
                        ));
                    } else {
                        out.add(u);
                    }
                }
                users = out;
                usersChanged = true;
            }
        }

        if (usersChanged) {
            writeUsers(tu, users);
        }
    }

    // --------------------------------
    // Authentication + session helpers
    // --------------------------------
    /**
     * Authenticate individual user within a tenant.
     * Reject if user missing/disabled, role missing/disabled, or password invalid.
     */
    public AuthResult authenticate(String tenantUuid, String email, char[] password) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return null;

        String em = normalizeEmail(email);
        if (em.isBlank() || password == null || password.length == 0) return null;

        byte[] pepper = getPepper();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<UserRec> users = readUsers(tu);
            UserRec u = findUserByEmail(users, em);
            if (u == null || !u.enabled) return null;

            List<RoleRec> roles = readRoles(tu);
            RoleRec r = findRoleByUuid(roles, u.roleUuid);
            if (r == null || !r.enabled) return null;

            if (!Argon2id.verify(u.algo2idPasswordHash, password, pepper)) return null;

            return new AuthResult(u, r);
        } finally {
            wipe(password);
            lock.readLock().unlock();
        }
    }

    /**
     * Loads authenticated user identity + role + permissions into the session.
     * Stores:
     *   user.uuid, user.email, user.role.uuid, user.role.label
     *   user.perms.map (Map<String,String>)
     *   perm.<key> = <value> (for convenience)
     */
    public void bindToSession(HttpSession session, AuthResult ar) {
        if (session == null || ar == null || ar.user == null || ar.role == null) return;

        session.setAttribute(S_USER_UUID, ar.user.uuid);
        session.setAttribute(S_USER_EMAIL, ar.user.emailAddress);
        session.setAttribute(S_ROLE_UUID, ar.role.uuid);
        session.setAttribute(S_ROLE_LABEL, ar.role.label);

        session.setAttribute(S_PERMS_MAP, new LinkedHashMap<>(ar.permissions));

        for (Map.Entry<String, String> e : ar.permissions.entrySet()) {
            String k = safe(e.getKey()).trim();
            if (k.isBlank()) continue;
            session.setAttribute(S_PERMS_PREFX + k, safe(e.getValue()));
        }
    }

    /** Removes user auth info from session (does not invalidate session). */
    public void clearSessionAuth(HttpSession session) {
        if (session == null) return;

        Object m = session.getAttribute(S_PERMS_MAP);
        if (m instanceof Map<?, ?> map) {
            for (Object kObj : map.keySet()) {
                String k = (kObj == null) ? "" : String.valueOf(kObj);
                if (!k.isBlank()) session.removeAttribute(S_PERMS_PREFX + k);
            }
        }

        session.removeAttribute(S_USER_UUID);
        session.removeAttribute(S_USER_EMAIL);
        session.removeAttribute(S_ROLE_UUID);
        session.removeAttribute(S_ROLE_LABEL);
        session.removeAttribute(S_PERMS_MAP);
    }

    /** Convenience for JSPs: returns permission value or "" if missing. */
    public static String getPermission(HttpSession session, String key) {
        if (session == null) return "";
        String k = safe(key).trim();
        if (k.isBlank()) return "";
        Object v = session.getAttribute(S_PERMS_PREFX + k);
        return (v == null) ? "" : String.valueOf(v);
    }

    /** Convenience: checks permission key equalsIgnoreCase("true"). */
    public static boolean hasPermissionTrue(HttpSession session, String key) {
        return "true".equalsIgnoreCase(getPermission(session, key).trim());
    }

    // --------------------------------
    // Users CRUD
    // --------------------------------
    public List<UserRec> listUsers(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readUsers(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public UserRec getUserByUuid(String tenantUuid, String userUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || uu.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            for (UserRec u : readUsers(tu)) {
                if (uu.equals(u.uuid)) return u;
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public UserRec getUserByEmail(String tenantUuid, String email) throws Exception {
        String tu = safe(tenantUuid).trim();
        String em = normalizeEmail(email);
        if (tu.isBlank() || em.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return findUserByEmail(readUsers(tu), em);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Creates a new user. Enforces unique email (case-insensitive).
     */
    public UserRec createUser(String tenantUuid, String email, String roleUuid, boolean enabled, char[] password) throws Exception {
        return createUser(tenantUuid, email, roleUuid, enabled, password, false);
    }

    /**
     * Creates a new user. Enforces unique email (case-insensitive).
     * When bypassPasswordPolicy is true, password-policy checks are skipped.
     */
    public UserRec createUser(String tenantUuid,
                              String email,
                              String roleUuid,
                              boolean enabled,
                              char[] password,
                              boolean bypassPasswordPolicy) throws Exception {
        String tu = safe(tenantUuid).trim();
        String em = normalizeEmail(email);
        String ru = safe(roleUuid).trim();

        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (em.isBlank()) throw new IllegalArgumentException("email required");
        if (password == null || password.length == 0) throw new IllegalArgumentException("password required");
        if (!bypassPasswordPolicy) enforcePasswordPolicy(tu, password);

        byte[] pepper = getPepper();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);

            List<UserRec> users = readUsers(tu);
            if (findUserByEmail(users, em) != null) {
                throw new IllegalStateException("User email already exists");
            }

            String uuid = UUID.randomUUID().toString();
            String hash = Argon2id.hash(password, pepper);

            UserRec u = new UserRec(uuid, enabled, ru, em, hash, false, USER_TWO_FACTOR_ENGINE_INHERIT, "");
            users.add(u);

            writeUsers(tu, users);
            return u;
        } finally {
            wipe(password);
            lock.writeLock().unlock();
        }
    }

    public boolean updateUserEnabled(String tenantUuid, String userUuid, boolean enabled) throws Exception {
        return updateUserField(tenantUuid, userUuid, "enabled", enabled ? "true" : "false");
    }

    public boolean updateUserRole(String tenantUuid, String userUuid, String roleUuid) throws Exception {
        return updateUserField(tenantUuid, userUuid, "role_uuid", safe(roleUuid).trim());
    }

    public boolean updateUserEmail(String tenantUuid, String userUuid, String newEmail) throws Exception {
        String tu = safe(tenantUuid).trim();
        String uu = safe(userUuid).trim();
        String em = normalizeEmail(newEmail);
        if (tu.isBlank() || uu.isBlank() || em.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<UserRec> users = readUsers(tu);

            UserRec existing = findUserByEmail(users, em);
            if (existing != null && !uu.equals(existing.uuid)) {
                throw new IllegalStateException("User email already exists");
            }

            boolean changed = false;
            List<UserRec> out = new ArrayList<>(users.size());
            for (UserRec u : users) {
                if (uu.equals(u.uuid)) {
                    out.add(new UserRec(
                            u.uuid,
                            u.enabled,
                            u.roleUuid,
                            em,
                            u.algo2idPasswordHash,
                            u.twoFactorEnabled,
                            u.twoFactorEngine,
                            u.twoFactorPhone
                    ));
                    changed = true;
                } else {
                    out.add(u);
                }
            }
            if (changed) writeUsers(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateUserTwoFactorEnabled(String tenantUuid, String userUuid, boolean enabled) throws Exception {
        return updateUserField(tenantUuid, userUuid, "two_factor_enabled", enabled ? "true" : "false");
    }

    public boolean updateUserTwoFactorEngine(String tenantUuid, String userUuid, String engine) throws Exception {
        return updateUserField(tenantUuid, userUuid, "two_factor_engine", normalizeUserTwoFactorEngine(engine));
    }

    public boolean updateUserTwoFactorPhone(String tenantUuid, String userUuid, String phoneNumber) throws Exception {
        return updateUserField(tenantUuid, userUuid, "two_factor_phone", normalizePhone(phoneNumber));
    }

    public boolean updateUserTwoFactorSettings(String tenantUuid,
                                               String userUuid,
                                               boolean enabled,
                                               String engine,
                                               String phoneNumber) throws Exception {
        String tu = safe(tenantUuid).trim();
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || uu.isBlank()) return false;

        String normalizedEngine = normalizeUserTwoFactorEngine(engine);
        String normalizedPhone = normalizePhone(phoneNumber);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<UserRec> users = readUsers(tu);
            boolean changed = false;
            List<UserRec> out = new ArrayList<>(users.size());
            for (UserRec u : users) {
                if (!uu.equals(u.uuid)) {
                    out.add(u);
                    continue;
                }
                if (u.twoFactorEnabled != enabled
                        || !u.twoFactorEngine.equals(normalizedEngine)
                        || !u.twoFactorPhone.equals(normalizedPhone)) {
                    changed = true;
                }
                out.add(new UserRec(
                        u.uuid,
                        u.enabled,
                        u.roleUuid,
                        u.emailAddress,
                        u.algo2idPasswordHash,
                        enabled,
                        normalizedEngine,
                        normalizedPhone
                ));
            }
            if (changed) writeUsers(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateUserPassword(String tenantUuid, String userUuid, char[] newPassword) throws Exception {
        return updateUserPassword(tenantUuid, userUuid, newPassword, false);
    }

    /**
     * Updates a user's password.
     * When bypassPasswordPolicy is true, password-policy checks are skipped.
     */
    public boolean updateUserPassword(String tenantUuid,
                                      String userUuid,
                                      char[] newPassword,
                                      boolean bypassPasswordPolicy) throws Exception {
        String tu = safe(tenantUuid).trim();
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || uu.isBlank()) return false;
        if (newPassword == null || newPassword.length == 0) return false;
        if (!bypassPasswordPolicy) enforcePasswordPolicy(tu, newPassword);

        byte[] pepper = getPepper();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<UserRec> users = readUsers(tu);

            boolean changed = false;
            List<UserRec> out = new ArrayList<>(users.size());
            String newHash = Argon2id.hash(newPassword, pepper);

            for (UserRec u : users) {
                if (uu.equals(u.uuid)) {
                    out.add(new UserRec(
                            u.uuid,
                            u.enabled,
                            u.roleUuid,
                            u.emailAddress,
                            newHash,
                            u.twoFactorEnabled,
                            u.twoFactorEngine,
                            u.twoFactorPhone
                    ));
                    changed = true;
                } else {
                    out.add(u);
                }
            }
            if (changed) writeUsers(tu, out);
            return changed;
        } finally {
            wipe(newPassword);
            lock.writeLock().unlock();
        }
    }

    private void enforcePasswordPolicy(String tenantUuid, char[] password) {
        List<String> issues = tenant_settings.defaultStore().validatePasswordAgainstPolicy(tenantUuid, password);
        if (issues == null || issues.isEmpty()) return;
        throw new IllegalArgumentException("Password policy: " + String.join(" ", issues));
    }

    /** Soft-delete helper: disables the user. */
    public boolean disableUser(String tenantUuid, String userUuid) throws Exception {
        return updateUserEnabled(tenantUuid, userUuid, false);
    }

    // --------------------------------
    // Roles CRUD + permissions
    // --------------------------------
    public List<RoleRec> listRoles(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readRoles(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public RoleRec getRoleByUuid(String tenantUuid, String roleUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        String ru = safe(roleUuid).trim();
        if (tu.isBlank() || ru.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return findRoleByUuid(readRoles(tu), ru);
        } finally {
            lock.readLock().unlock();
        }
    }

    public RoleRec createRole(String tenantUuid, String label, boolean enabled) throws Exception {
        String tu = safe(tenantUuid).trim();
        String lbl = safe(label).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("label required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);

            List<RoleRec> roles = readRoles(tu);
            String uuid = UUID.randomUUID().toString();
            RoleRec r = new RoleRec(uuid, enabled, lbl, Map.of());
            roles.add(r);

            writeRoles(tu, roles);
            return r;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateRoleEnabled(String tenantUuid, String roleUuid, boolean enabled) throws Exception {
        return updateRoleSimple(tenantUuid, roleUuid, enabled, null);
    }

    public boolean updateRoleLabel(String tenantUuid, String roleUuid, String label) throws Exception {
        String lbl = safe(label).trim();
        return updateRoleSimple(tenantUuid, roleUuid, null, lbl);
    }

    /** Set (or overwrite) a role permission key/value. Keys are case-sensitive. */
    public boolean setRolePermission(String tenantUuid, String roleUuid, String key, String value) throws Exception {
        String tu = safe(tenantUuid).trim();
        String ru = safe(roleUuid).trim();
        String k = safe(key).trim();
        String v = safe(value);

        if (tu.isBlank() || ru.isBlank() || k.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<RoleRec> roles = readRoles(tu);

            boolean changed = false;
            List<RoleRec> out = new ArrayList<>(roles.size());
            for (RoleRec r : roles) {
                if (ru.equals(r.uuid)) {
                    LinkedHashMap<String, String> perms = new LinkedHashMap<>(r.permissions);
                    String prev = perms.put(k, v);
                    boolean permChanged = (prev == null) || !prev.equals(v);

                    out.add(new RoleRec(r.uuid, r.enabled, r.label, perms));
                    changed = permChanged;
                } else {
                    out.add(r);
                }
            }
            if (changed) writeRoles(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeRolePermission(String tenantUuid, String roleUuid, String key) throws Exception {
        String tu = safe(tenantUuid).trim();
        String ru = safe(roleUuid).trim();
        String k = safe(key).trim();
        if (tu.isBlank() || ru.isBlank() || k.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<RoleRec> roles = readRoles(tu);

            boolean changed = false;
            List<RoleRec> out = new ArrayList<>(roles.size());
            for (RoleRec r : roles) {
                if (ru.equals(r.uuid)) {
                    LinkedHashMap<String, String> perms = new LinkedHashMap<>(r.permissions);
                    if (perms.remove(k) != null) changed = true;
                    out.add(new RoleRec(r.uuid, r.enabled, r.label, perms));
                } else {
                    out.add(r);
                }
            }
            if (changed) writeRoles(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Replace all permissions for a role (common for “edit role permissions” screens). */
    public boolean replaceRolePermissions(String tenantUuid, String roleUuid, Map<String, String> newPermissions) throws Exception {
        String tu = safe(tenantUuid).trim();
        String ru = safe(roleUuid).trim();
        if (tu.isBlank() || ru.isBlank()) return false;

        LinkedHashMap<String, String> cleaned = new LinkedHashMap<>();
        if (newPermissions != null) {
            for (Map.Entry<String, String> e : newPermissions.entrySet()) {
                String k = safe(e.getKey()).trim();
                if (k.isBlank()) continue;
                cleaned.put(k, safe(e.getValue()));
            }
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<RoleRec> roles = readRoles(tu);

            boolean changed = false;
            List<RoleRec> out = new ArrayList<>(roles.size());
            for (RoleRec r : roles) {
                if (ru.equals(r.uuid)) {
                    if (!r.permissions.equals(cleaned)) changed = true;
                    out.add(new RoleRec(r.uuid, r.enabled, r.label, cleaned));
                } else {
                    out.add(r);
                }
            }
            if (changed) writeRoles(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // --------------------------------
    // Internals: read/write XML
    // --------------------------------
    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, k -> new ReentrantReadWriteLock());
    }

    private static Path tenantDir(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
    }

    private static Path usersPath(String tenantUuid) {
        return tenantDir(tenantUuid).resolve("users.xml");
    }

    private static Path rolesPath(String tenantUuid) {
        return tenantDir(tenantUuid).resolve("roles.xml");
    }

    private static List<UserRec> readUsers(String tenantUuid) throws Exception {
        Path p = usersPath(tenantUuid);
        if (!Files.exists(p)) return new ArrayList<>();

        Document d = parseXml(p);
        Element root = (d == null) ? null : d.getDocumentElement();
        if (root == null) return new ArrayList<>();

        List<UserRec> out = new ArrayList<>();
        NodeList users = root.getElementsByTagName("user");
        for (int i = 0; i < users.getLength(); i++) {
            Node n = users.item(i);
            if (!(n instanceof Element e)) continue;

            String uuid = text(e, "uuid");
            boolean enabled = parseBool(text(e, "enabled"), false);
            String roleUuid = text(e, "role_uuid");
            String email = text(e, "email_address");
            String hash = text(e, "algo2id_password_hash");
            boolean twoFactorEnabled = parseBool(text(e, "two_factor_enabled"), false);
            String twoFactorEngine = normalizeUserTwoFactorEngine(text(e, "two_factor_engine"));
            String twoFactorPhone = normalizePhone(text(e, "two_factor_phone"));

            if (uuid.isBlank() || email.isBlank()) continue;
            out.add(new UserRec(
                    uuid,
                    enabled,
                    roleUuid,
                    email,
                    hash,
                    twoFactorEnabled,
                    twoFactorEngine,
                    twoFactorPhone
            ));
        }
        return out;
    }

    private static List<RoleRec> readRoles(String tenantUuid) throws Exception {
        Path p = rolesPath(tenantUuid);
        if (!Files.exists(p)) return new ArrayList<>();

        Document d = parseXml(p);
        Element root = (d == null) ? null : d.getDocumentElement();
        if (root == null) return new ArrayList<>();

        List<RoleRec> out = new ArrayList<>();
        NodeList roles = root.getElementsByTagName("role");
        for (int i = 0; i < roles.getLength(); i++) {
            Node n = roles.item(i);
            if (!(n instanceof Element e)) continue;

            String uuid = text(e, "uuid");
            boolean enabled = parseBool(text(e, "enabled"), false);
            String label = text(e, "label");

            LinkedHashMap<String, String> perms = new LinkedHashMap<>();
            Element permsEl = firstChildElement(e, "permissions");
            if (permsEl != null) {
                NodeList plist = permsEl.getElementsByTagName("permission");
                for (int j = 0; j < plist.getLength(); j++) {
                    Node pn = plist.item(j);
                    if (!(pn instanceof Element pe)) continue;
                    String k = safe(pe.getAttribute("key")).trim();
                    String v = safe(pe.getTextContent());
                    if (!k.isBlank()) perms.put(k, v);
                }
            }

            if (uuid.isBlank() || label.isBlank()) continue;
            out.add(new RoleRec(uuid, enabled, label, perms));
        }
        return out;
    }

    private static void writeUsers(String tenantUuid, List<UserRec> users) throws Exception {
        Path p = usersPath(tenantUuid);
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<users updated=\"").append(xmlAttr(now)).append("\">\n");

        List<UserRec> list = (users == null) ? List.of() : users;
        for (UserRec u : list) {
            sb.append("  <user>\n");
            sb.append("    <uuid>").append(xmlText(u.uuid)).append("</uuid>\n");
            sb.append("    <enabled>").append(u.enabled ? "true" : "false").append("</enabled>\n");
            sb.append("    <role_uuid>").append(xmlText(u.roleUuid)).append("</role_uuid>\n");
            sb.append("    <email_address>").append(xmlText(u.emailAddress)).append("</email_address>\n");
            sb.append("    <algo2id_password_hash>").append(xmlText(u.algo2idPasswordHash)).append("</algo2id_password_hash>\n");
            sb.append("    <two_factor_enabled>").append(u.twoFactorEnabled ? "true" : "false").append("</two_factor_enabled>\n");
            sb.append("    <two_factor_engine>").append(xmlText(u.twoFactorEngine)).append("</two_factor_engine>\n");
            sb.append("    <two_factor_phone>").append(xmlText(u.twoFactorPhone)).append("</two_factor_phone>\n");
            sb.append("  </user>\n");
        }

        sb.append("</users>\n");
        writeAtomic(p, sb.toString());
    }

    private static void writeRoles(String tenantUuid, List<RoleRec> roles) throws Exception {
        Path p = rolesPath(tenantUuid);
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<roles updated=\"").append(xmlAttr(now)).append("\">\n");

        List<RoleRec> list = (roles == null) ? List.of() : roles;
        for (RoleRec r : list) {
            sb.append("  <role>\n");
            sb.append("    <uuid>").append(xmlText(r.uuid)).append("</uuid>\n");
            sb.append("    <enabled>").append(r.enabled ? "true" : "false").append("</enabled>\n");
            sb.append("    <label>").append(xmlText(r.label)).append("</label>\n");
            sb.append("    <permissions>\n");
            for (Map.Entry<String, String> e : r.permissions.entrySet()) {
                String k = safe(e.getKey()).trim();
                if (k.isBlank()) continue;
                sb.append("      <permission key=\"").append(xmlAttr(k)).append("\">")
                  .append(xmlText(safe(e.getValue())))
                  .append("</permission>\n");
            }
            sb.append("    </permissions>\n");
            sb.append("  </role>\n");
        }

        sb.append("</roles>\n");
        writeAtomic(p, sb.toString());
    }

    // --------------------------------
    // Internals: small update helpers
    // --------------------------------
    private boolean updateUserField(String tenantUuid, String userUuid, String field, String newValue) throws Exception {
        String tu = safe(tenantUuid).trim();
        String uu = safe(userUuid).trim();
        String f = safe(field).trim();
        if (tu.isBlank() || uu.isBlank() || f.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<UserRec> users = readUsers(tu);

            boolean changed = false;
            List<UserRec> out = new ArrayList<>(users.size());
            for (UserRec u : users) {
                if (!uu.equals(u.uuid)) {
                    out.add(u);
                    continue;
                }

                String uuid = u.uuid;
                boolean enabled = u.enabled;
                String roleUuid = u.roleUuid;
                String email = u.emailAddress;
                String hash = u.algo2idPasswordHash;
                boolean twoFactorEnabled = u.twoFactorEnabled;
                String twoFactorEngine = u.twoFactorEngine;
                String twoFactorPhone = u.twoFactorPhone;

                switch (f) {
                    case "enabled" -> enabled = parseBool(newValue, enabled);
                    case "role_uuid" -> roleUuid = safe(newValue).trim();
                    case "email_address" -> email = normalizeEmail(newValue);
                    case "algo2id_password_hash" -> hash = safe(newValue);
                    case "two_factor_enabled" -> twoFactorEnabled = parseBool(newValue, twoFactorEnabled);
                    case "two_factor_engine" -> twoFactorEngine = normalizeUserTwoFactorEngine(newValue);
                    case "two_factor_phone" -> twoFactorPhone = normalizePhone(newValue);
                    default -> { out.add(u); continue; }
                }

                out.add(new UserRec(
                        uuid,
                        enabled,
                        roleUuid,
                        email,
                        hash,
                        twoFactorEnabled,
                        twoFactorEngine,
                        twoFactorPhone
                ));
                changed = true;
            }

            if (changed) writeUsers(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean updateRoleSimple(String tenantUuid, String roleUuid, Boolean enabled, String label) throws Exception {
        String tu = safe(tenantUuid).trim();
        String ru = safe(roleUuid).trim();
        if (tu.isBlank() || ru.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<RoleRec> roles = readRoles(tu);

            boolean changed = false;
            List<RoleRec> out = new ArrayList<>(roles.size());
            for (RoleRec r : roles) {
                if (!ru.equals(r.uuid)) {
                    out.add(r);
                    continue;
                }

                boolean en = (enabled == null) ? r.enabled : enabled.booleanValue();
                String lbl = (label == null) ? r.label : safe(label).trim();
                if (lbl.isBlank()) lbl = r.label;

                out.add(new RoleRec(r.uuid, en, lbl, r.permissions));
                changed = true;
            }

            if (changed) writeRoles(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static UserRec findUserByEmail(List<UserRec> users, String emailLower) {
        if (users == null) return null;
        String em = normalizeEmail(emailLower);
        for (UserRec u : users) {
            if (u != null && em.equals(normalizeEmail(u.emailAddress))) return u;
        }
        return null;
    }

    private static RoleRec findRoleByUuid(List<RoleRec> roles, String roleUuid) {
        if (roles == null) return null;
        String ru = safe(roleUuid).trim();
        if (ru.isBlank()) return null;
        for (RoleRec r : roles) {
            if (r != null && ru.equals(r.uuid)) return r;
        }
        return null;
    }

    // --------------------------------
    // XML parsing utilities (XXE-safe)
    // --------------------------------
    private static Document parseXml(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return null;
        DocumentBuilder b = secureBuilder();
        try (InputStream in = Files.newInputStream(p)) {
            Document d = b.parse(in);
            d.getDocumentElement().normalize();
            return d;
        }
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);

        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return b;
    }

    private static Element firstChildElement(Element parent, String tagName) {
        if (parent == null || tagName == null) return null;
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl == null || nl.getLength() == 0) return null;
        Node n = nl.item(0);
        return (n instanceof Element e) ? e : null;
    }

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl == null || nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return (n == null) ? "" : safe(n.getTextContent()).trim();
    }

    // --------------------------------
    // File write (atomic) + delete
    // --------------------------------
    private static void writeAtomic(Path p, String content) throws Exception {
        if (p == null) return;
        Files.createDirectories(p.getParent());

        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(tmp, content == null ? "" : content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicNotSupported) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuiet(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    // --------------------------------
    // Pepper handling
    // --------------------------------
    private static byte[] getPepper() throws Exception {
        synchronized (PEPPER_LOCK) {
            Files.createDirectories(PEPPER_PATH.getParent());

            if (!Files.exists(PEPPER_PATH)) {
                byte[] p = new byte[32];
                RNG.nextBytes(p);
                try {
                    Files.write(PEPPER_PATH, p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (FileAlreadyExistsException ignored) {
                    // race: created elsewhere
                } finally {
                    wipeBytes(p);
                }
            }

            long mtime = Files.getLastModifiedTime(PEPPER_PATH).toMillis();
            long size = Files.size(PEPPER_PATH);

            if (PEPPER_CACHE != null && PEPPER_MTIME == mtime && PEPPER_SIZE == size && size > 0) {
                return PEPPER_CACHE;
            }

            byte[] b = Files.readAllBytes(PEPPER_PATH);
            if (b == null || b.length < 16) {
                throw new IllegalStateException("Pepper file invalid/too small: " + PEPPER_PATH);
            }

            PEPPER_CACHE = b;
            PEPPER_MTIME = mtime;
            PEPPER_SIZE = size;
            return PEPPER_CACHE;
        }
    }

    // --------------------------------
    // XML escaping + misc
    // --------------------------------
    private static String safe(String s) { return s == null ? "" : s; }

    private static String xmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("\"","&quot;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("'","&apos;");
    }

    private static String xmlText(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&apos;");
    }

    private static boolean parseBool(String s, boolean def) {
        String v = safe(s).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return def;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v);
    }

    private static String normalizeEmail(String email) {
        return safe(email).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeUserTwoFactorEngine(String engine) {
        String e = safe(engine).trim().toLowerCase(Locale.ROOT);
        if (USER_TWO_FACTOR_ENGINE_EMAIL_PIN.equals(e)) return USER_TWO_FACTOR_ENGINE_EMAIL_PIN;
        if (USER_TWO_FACTOR_ENGINE_FLOWROUTE_SMS.equals(e)) return USER_TWO_FACTOR_ENGINE_FLOWROUTE_SMS;
        return USER_TWO_FACTOR_ENGINE_INHERIT;
    }

    private static String normalizePhone(String phone) {
        String p = safe(phone).trim();
        if (p.isBlank()) return "";
        boolean plus = p.startsWith("+");
        String digits = p.replaceAll("[^0-9]", "");
        if (digits.length() < 10) return "";
        if (digits.length() > 15) digits = digits.substring(digits.length() - 15);
        return plus ? ("+" + digits) : digits;
    }

    private static String emptyUsersXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<users created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\"></users>\n";
    }

    private static String emptyRolesXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<roles created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\"></roles>\n";
    }

    private static void wipe(char[] c) {
        if (c == null) return;
        Arrays.fill(c, '\0');
    }

    private static void wipeBytes(byte[] b) {
        if (b == null) return;
        Arrays.fill(b, (byte) 0);
    }

    // --------------------------------
    // Argon2id support (reflection-based) + PEPPER
    // --------------------------------
    /**
     * Stores/verifies encoded Argon2id hashes in the standard "$argon2id$..." format.
     * Uses PEPPER as Argon2 "secret" when supported by BouncyCastle; otherwise mixes pepper into password bytes.
     *
     * Runtime dependency (recommended):
     *   org.bouncycastle:bcprov-jdk15on (or similar)
     */
    private static final class Argon2id {
        private static final int DEFAULT_ITERATIONS = 3;
        private static final int DEFAULT_MEMORY_KB = 65536; // 64 MB
        private static final int DEFAULT_PARALLELISM = 1;
        private static final int DEFAULT_SALT_BYTES = 16;
        private static final int DEFAULT_HASH_BYTES = 32;
        private static final int DEFAULT_VERSION = 19;

        static String hash(char[] password, byte[] pepper) {
            if (password == null || password.length == 0) throw new IllegalArgumentException("password required");
            if (pepper == null || pepper.length < 16) throw new IllegalArgumentException("pepper required");

            byte[] salt = new byte[DEFAULT_SALT_BYTES];
            RNG.nextBytes(salt);

            byte[] out = new byte[DEFAULT_HASH_BYTES];
            byte[] pw = toUtf8(password);

            try {
                bcArgon2Generate(out, pw, salt, pepper, DEFAULT_ITERATIONS, DEFAULT_MEMORY_KB, DEFAULT_PARALLELISM, DEFAULT_VERSION);
            } finally {
                wipeBytes(pw);
            }

            String saltB64 = b64NoPad(salt);
            String hashB64 = b64NoPad(out);

            return "$argon2id$v=" + DEFAULT_VERSION
                    + "$m=" + DEFAULT_MEMORY_KB + ",t=" + DEFAULT_ITERATIONS + ",p=" + DEFAULT_PARALLELISM
                    + "$" + saltB64 + "$" + hashB64;
        }

        static boolean verify(String encoded, char[] password, byte[] pepper) {
            String enc = safe(encoded).trim();
            if (enc.isBlank() || password == null || password.length == 0) return false;
            if (pepper == null || pepper.length < 16) return false;

            Parsed p = parseEncoded(enc);
            if (p == null) return false;

            byte[] out = new byte[p.hash.length];
            byte[] pw = toUtf8(password);
            try {
                bcArgon2Generate(out, pw, p.salt, pepper, p.iterations, p.memoryKb, p.parallelism, p.version);
            } finally {
                wipeBytes(pw);
            }

            return MessageDigest.isEqual(out, p.hash);
        }

        private static final class Parsed {
            final int version;
            final int memoryKb;
            final int iterations;
            final int parallelism;
            final byte[] salt;
            final byte[] hash;

            Parsed(int version, int memoryKb, int iterations, int parallelism, byte[] salt, byte[] hash) {
                this.version = version;
                this.memoryKb = memoryKb;
                this.iterations = iterations;
                this.parallelism = parallelism;
                this.salt = salt;
                this.hash = hash;
            }
        }

        private static Parsed parseEncoded(String encoded) {
            try {
                if (!encoded.startsWith("$argon2id$")) return null;
                String[] parts = encoded.split("\\$");
                if (parts.length < 6) return null;

                int version = parseIntAfter(parts[2], "v=", DEFAULT_VERSION);

                int mem = DEFAULT_MEMORY_KB;
                int t = DEFAULT_ITERATIONS;
                int p = DEFAULT_PARALLELISM;

                String[] params = parts[3].split(",");
                for (String param : params) {
                    String x = safe(param).trim();
                    if (x.startsWith("m=")) mem = Integer.parseInt(x.substring(2));
                    else if (x.startsWith("t=")) t = Integer.parseInt(x.substring(2));
                    else if (x.startsWith("p=")) p = Integer.parseInt(x.substring(2));
                }

                byte[] salt = b64DecodeNoPad(parts[4]);
                byte[] hash = b64DecodeNoPad(parts[5]);
                if (salt == null || salt.length == 0 || hash == null || hash.length == 0) return null;

                return new Parsed(version, mem, t, p, salt, hash);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static int parseIntAfter(String s, String prefix, int def) {
            String v = safe(s).trim();
            if (!v.startsWith(prefix)) return def;
            try { return Integer.parseInt(v.substring(prefix.length())); }
            catch (Exception ignored) { return def; }
        }

        private static String b64NoPad(byte[] b) {
            return Base64.getEncoder().withoutPadding().encodeToString(b);
        }

        private static byte[] b64DecodeNoPad(String s) {
            if (s == null) return null;
            String t = s.trim();
            int mod = t.length() % 4;
            if (mod != 0) t = t + "====".substring(mod);
            return Base64.getDecoder().decode(t);
        }

        private static byte[] toUtf8(char[] password) {
            // Best-effort; avoids making additional long-lived objects beyond this conversion.
            return new String(password).getBytes(StandardCharsets.UTF_8);
        }

        /**
         * Uses BouncyCastle's Argon2 generator via reflection:
         *   org.bouncycastle.crypto.params.Argon2Parameters
         *   org.bouncycastle.crypto.params.Argon2Parameters$Builder
         *   org.bouncycastle.crypto.generators.Argon2BytesGenerator
         *
         * Tries Builder.withSecret(byte[]) for pepper.
         * If not available, falls back to mixing pepper into password bytes (pw || 0x00 || pepper).
         */
        private static void bcArgon2Generate(
                byte[] out,
                byte[] passwordBytes,
                byte[] salt,
                byte[] pepper,
                int iterations,
                int memoryKb,
                int parallelism,
                int version
        ) {
            try {
                Class<?> paramsCls = Class.forName("org.bouncycastle.crypto.params.Argon2Parameters");
                Class<?> builderCls = Class.forName("org.bouncycastle.crypto.params.Argon2Parameters$Builder");
                Class<?> genCls = Class.forName("org.bouncycastle.crypto.generators.Argon2BytesGenerator");

                int ARGON2_id = paramsCls.getField("ARGON2_id").getInt(null);
                Object builder = builderCls.getConstructor(int.class).newInstance(ARGON2_id);

                builderCls.getMethod("withSalt", byte[].class).invoke(builder, (Object) salt);
                builderCls.getMethod("withParallelism", int.class).invoke(builder, parallelism);
                builderCls.getMethod("withMemoryAsKB", int.class).invoke(builder, memoryKb);
                builderCls.getMethod("withIterations", int.class).invoke(builder, iterations);
                builderCls.getMethod("withVersion", int.class).invoke(builder, version);

                boolean usedSecret = false;
                try {
                    builderCls.getMethod("withSecret", byte[].class).invoke(builder, (Object) pepper);
                    usedSecret = true;
                } catch (NoSuchMethodException ignored) {
                    usedSecret = false;
                }

                Object params = builderCls.getMethod("build").invoke(builder);

                Object gen = genCls.getConstructor().newInstance();
                genCls.getMethod("init", paramsCls).invoke(gen, params);

                byte[] pwEffective = passwordBytes;
                byte[] mixed = null;
                if (!usedSecret) {
                    mixed = new byte[passwordBytes.length + 1 + pepper.length];
                    System.arraycopy(passwordBytes, 0, mixed, 0, passwordBytes.length);
                    mixed[passwordBytes.length] = 0;
                    System.arraycopy(pepper, 0, mixed, passwordBytes.length + 1, pepper.length);
                    pwEffective = mixed;
                }

                try {
                    genCls.getMethod("generateBytes", byte[].class, byte[].class).invoke(gen, pwEffective, out);
                } finally {
                    if (mixed != null) wipeBytes(mixed);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Argon2id runtime support not found. Add BouncyCastle (bcprov) or another Argon2 implementation to the classpath.",
                        e
                );
            } catch (Exception e) {
                throw new IllegalStateException("Argon2id hash/verify failed: " + e.getMessage(), e);
            }
        }
    }
}
