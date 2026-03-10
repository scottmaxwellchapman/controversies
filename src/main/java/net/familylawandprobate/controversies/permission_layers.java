package net.familylawandprobate.controversies;

import jakarta.servlet.http.HttpSession;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * permission_layers
 *
 * Layered permission model:
 * - Role-level permissions (legacy users_roles roles.xml)
 * - Tenant-level overrides (permission_layers.xml tenantPermissions)
 * - Group-level permissions + memberships (groups.xml)
 * - User-level overrides (permission_layers.xml userPermissions)
 *
 * Resolution order:
 *   tenant -> role -> group (true wins between groups) -> user
 *
 * If tenant_admin=true at any layer, all checks are allowed.
 */
public final class permission_layers {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    private static final String SESSION_PERMS_LAST_REFRESH_MS = "permission.layers.last_refresh_ms";
    private static final String SESSION_REFRESH_GUARD = "permission.layers.refresh.in_progress";
    private static final long SESSION_REFRESH_THROTTLE_MS = 5000L;

    private static final List<PermissionDef> CATALOG = buildCatalog();
    private static final List<PermissionProfile> PROFILES = buildProfiles();

    public static permission_layers defaultStore() {
        return new permission_layers();
    }

    public static final class PermissionDef {
        public final String key;
        public final String label;
        public final String description;
        public final String category;
        public final boolean adminOnly;

        public PermissionDef(String key, String label, String description, String category, boolean adminOnly) {
            this.key = safe(key).trim();
            this.label = safe(label).trim();
            this.description = safe(description).trim();
            this.category = safe(category).trim();
            this.adminOnly = adminOnly;
        }
    }

    public static final class PermissionProfile {
        public final String key;
        public final String label;
        public final String description;
        public final LinkedHashMap<String, String> permissions;

        public PermissionProfile(String key, String label, String description, Map<String, String> permissions) {
            this.key = safe(key).trim();
            this.label = safe(label).trim();
            this.description = safe(description).trim();
            this.permissions = new LinkedHashMap<String, String>();
            if (permissions != null) {
                for (Map.Entry<String, String> e : permissions.entrySet()) {
                    String k = normalizePermKey(e.getKey());
                    if (k.isBlank()) continue;
                    this.permissions.put(k, normalizePermValue(e.getValue()));
                }
            }
        }
    }

    public static final class GroupRec {
        public final String uuid;
        public final boolean enabled;
        public final String label;
        public final LinkedHashMap<String, String> permissions;
        public final LinkedHashSet<String> memberUserUuids;

        public GroupRec(String uuid,
                        boolean enabled,
                        String label,
                        Map<String, String> permissions,
                        Collection<String> memberUserUuids) {
            this.uuid = safe(uuid).trim();
            this.enabled = enabled;
            this.label = safe(label).trim();
            this.permissions = new LinkedHashMap<String, String>();
            if (permissions != null) {
                for (Map.Entry<String, String> e : permissions.entrySet()) {
                    String k = normalizePermKey(e.getKey());
                    if (k.isBlank()) continue;
                    this.permissions.put(k, normalizePermValue(e.getValue()));
                }
            }
            this.memberUserUuids = new LinkedHashSet<String>();
            if (memberUserUuids != null) {
                for (String userUuid : memberUserUuids) {
                    String uu = safe(userUuid).trim();
                    if (!uu.isBlank()) this.memberUserUuids.add(uu);
                }
            }
        }
    }

    private static final class LayersRec {
        final LinkedHashMap<String, String> tenantPermissions = new LinkedHashMap<String, String>();
        final LinkedHashMap<String, LinkedHashMap<String, String>> userPermissions = new LinkedHashMap<String, LinkedHashMap<String, String>>();
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path groups = groupsPath(tu);
            Path layers = layersPath(tu);
            Files.createDirectories(groups.getParent());
            Files.createDirectories(layers.getParent());
            if (!Files.exists(groups)) writeAtomic(groups, emptyGroupsXml());
            if (!Files.exists(layers)) writeAtomic(layers, emptyLayersXml());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<PermissionDef> permissionCatalog() {
        return CATALOG;
    }

    public List<PermissionProfile> permissionProfiles() {
        return PROFILES;
    }

    public PermissionProfile getProfile(String profileKey) {
        String pk = safe(profileKey).trim().toLowerCase(Locale.ROOT);
        if (pk.isBlank()) return null;
        for (PermissionProfile p : PROFILES) {
            if (p == null) continue;
            if (pk.equals(safe(p.key).trim().toLowerCase(Locale.ROOT))) return p;
        }
        return null;
    }

    public LinkedHashMap<String, String> profilePermissions(String profileKey) {
        PermissionProfile p = getProfile(profileKey);
        if (p == null) return new LinkedHashMap<String, String>();
        return new LinkedHashMap<String, String>(p.permissions);
    }

    public List<GroupRec> listGroups(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ArrayList<GroupRec> out = new ArrayList<GroupRec>(readGroupsLocked(tu));
            out.sort(Comparator.comparing(g -> safe(g.label).toLowerCase(Locale.ROOT)));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public GroupRec getGroupByUuid(String tenantUuid, String groupUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String gu = safe(groupUuid).trim();
        if (tu.isBlank() || gu.isBlank()) return null;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            for (GroupRec g : readGroupsLocked(tu)) {
                if (g != null && gu.equals(g.uuid)) return g;
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public GroupRec createGroup(String tenantUuid, String label, boolean enabled) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String lbl = safe(label).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("label required");
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            List<GroupRec> all = readGroupsLocked(tu);
            GroupRec rec = new GroupRec(UUID.randomUUID().toString(), enabled, lbl, Map.of(), List.of());
            all.add(rec);
            writeGroupsLocked(tu, all);
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateGroupLabel(String tenantUuid, String groupUuid, String label) throws Exception {
        String lbl = safe(label).trim();
        if (lbl.isBlank()) return false;
        return updateGroupSimple(tenantUuid, groupUuid, null, lbl);
    }

    public boolean updateGroupEnabled(String tenantUuid, String groupUuid, boolean enabled) throws Exception {
        return updateGroupSimple(tenantUuid, groupUuid, Boolean.valueOf(enabled), null);
    }

    public boolean setGroupPermission(String tenantUuid, String groupUuid, String key, String value) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String gu = safe(groupUuid).trim();
        String k = normalizePermKey(key);
        if (tu.isBlank() || gu.isBlank() || k.isBlank()) return false;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            List<GroupRec> all = readGroupsLocked(tu);
            boolean changed = false;
            ArrayList<GroupRec> out = new ArrayList<GroupRec>(all.size());
            for (GroupRec g : all) {
                if (g == null) continue;
                if (!gu.equals(g.uuid)) {
                    out.add(g);
                    continue;
                }
                LinkedHashMap<String, String> perms = new LinkedHashMap<String, String>(g.permissions);
                String nv = normalizePermValue(value);
                String prev = perms.put(k, nv);
                if (prev == null || !prev.equals(nv)) changed = true;
                out.add(new GroupRec(g.uuid, g.enabled, g.label, perms, g.memberUserUuids));
            }
            if (changed) writeGroupsLocked(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeGroupPermission(String tenantUuid, String groupUuid, String key) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String gu = safe(groupUuid).trim();
        String k = normalizePermKey(key);
        if (tu.isBlank() || gu.isBlank() || k.isBlank()) return false;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            List<GroupRec> all = readGroupsLocked(tu);
            boolean changed = false;
            ArrayList<GroupRec> out = new ArrayList<GroupRec>(all.size());
            for (GroupRec g : all) {
                if (g == null) continue;
                if (!gu.equals(g.uuid)) {
                    out.add(g);
                    continue;
                }
                LinkedHashMap<String, String> perms = new LinkedHashMap<String, String>(g.permissions);
                if (perms.remove(k) != null) changed = true;
                out.add(new GroupRec(g.uuid, g.enabled, g.label, perms, g.memberUserUuids));
            }
            if (changed) writeGroupsLocked(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean replaceGroupPermissions(String tenantUuid, String groupUuid, Map<String, String> newPermissions) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String gu = safe(groupUuid).trim();
        if (tu.isBlank() || gu.isBlank()) return false;
        ensure(tu);

        LinkedHashMap<String, String> clean = normalizePermMap(newPermissions);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            List<GroupRec> all = readGroupsLocked(tu);
            boolean changed = false;
            ArrayList<GroupRec> out = new ArrayList<GroupRec>(all.size());
            for (GroupRec g : all) {
                if (g == null) continue;
                if (!gu.equals(g.uuid)) {
                    out.add(g);
                    continue;
                }
                if (!g.permissions.equals(clean)) changed = true;
                out.add(new GroupRec(g.uuid, g.enabled, g.label, clean, g.memberUserUuids));
            }
            if (changed) writeGroupsLocked(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setGroupMembers(String tenantUuid, String groupUuid, Collection<String> userUuids) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String gu = safe(groupUuid).trim();
        if (tu.isBlank() || gu.isBlank()) return false;
        ensure(tu);

        LinkedHashSet<String> clean = new LinkedHashSet<String>();
        if (userUuids != null) {
            for (String userUuid : userUuids) {
                String uu = safe(userUuid).trim();
                if (!uu.isBlank()) clean.add(uu);
            }
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            List<GroupRec> all = readGroupsLocked(tu);
            boolean changed = false;
            ArrayList<GroupRec> out = new ArrayList<GroupRec>(all.size());
            for (GroupRec g : all) {
                if (g == null) continue;
                if (!gu.equals(g.uuid)) {
                    out.add(g);
                    continue;
                }
                if (!g.memberUserUuids.equals(clean)) changed = true;
                out.add(new GroupRec(g.uuid, g.enabled, g.label, g.permissions, clean));
            }
            if (changed) writeGroupsLocked(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean addUserToGroup(String tenantUuid, String groupUuid, String userUuid) throws Exception {
        String uu = safe(userUuid).trim();
        if (uu.isBlank()) return false;
        GroupRec g = getGroupByUuid(tenantUuid, groupUuid);
        if (g == null) return false;
        LinkedHashSet<String> members = new LinkedHashSet<String>(g.memberUserUuids);
        if (!members.add(uu)) return false;
        return setGroupMembers(tenantUuid, groupUuid, members);
    }

    public boolean removeUserFromGroup(String tenantUuid, String groupUuid, String userUuid) throws Exception {
        String uu = safe(userUuid).trim();
        if (uu.isBlank()) return false;
        GroupRec g = getGroupByUuid(tenantUuid, groupUuid);
        if (g == null) return false;
        LinkedHashSet<String> members = new LinkedHashSet<String>(g.memberUserUuids);
        if (!members.remove(uu)) return false;
        return setGroupMembers(tenantUuid, groupUuid, members);
    }

    public List<GroupRec> listGroupsForUser(String tenantUuid, String userUuid) throws Exception {
        String uu = safe(userUuid).trim();
        if (uu.isBlank()) return List.of();
        List<GroupRec> all = listGroups(tenantUuid);
        ArrayList<GroupRec> out = new ArrayList<GroupRec>();
        for (GroupRec g : all) {
            if (g == null || !g.enabled) continue;
            if (g.memberUserUuids.contains(uu)) out.add(g);
        }
        out.sort(Comparator.comparing(g -> safe(g.label).toLowerCase(Locale.ROOT)));
        return out;
    }

    public LinkedHashMap<String, String> readTenantPermissions(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return new LinkedHashMap<String, String>();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return new LinkedHashMap<String, String>(readLayersLocked(tu).tenantPermissions);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean replaceTenantPermissions(String tenantUuid, Map<String, String> newPermissions) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return false;
        ensure(tu);
        LinkedHashMap<String, String> clean = normalizePermMap(newPermissions);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            LayersRec rec = readLayersLocked(tu);
            if (rec.tenantPermissions.equals(clean)) return false;
            rec.tenantPermissions.clear();
            rec.tenantPermissions.putAll(clean);
            writeLayersLocked(tu, rec);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setTenantPermission(String tenantUuid, String key, String value) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String k = normalizePermKey(key);
        if (tu.isBlank() || k.isBlank()) return false;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            LayersRec rec = readLayersLocked(tu);
            String nv = normalizePermValue(value);
            String prev = rec.tenantPermissions.put(k, nv);
            boolean changed = prev == null || !prev.equals(nv);
            if (changed) writeLayersLocked(tu, rec);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeTenantPermission(String tenantUuid, String key) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String k = normalizePermKey(key);
        if (tu.isBlank() || k.isBlank()) return false;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            LayersRec rec = readLayersLocked(tu);
            boolean changed = rec.tenantPermissions.remove(k) != null;
            if (changed) writeLayersLocked(tu, rec);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public LinkedHashMap<String, String> readUserPermissions(String tenantUuid, String userUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || uu.isBlank()) return new LinkedHashMap<String, String>();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            LayersRec rec = readLayersLocked(tu);
            LinkedHashMap<String, String> p = rec.userPermissions.get(uu);
            if (p == null) return new LinkedHashMap<String, String>();
            return new LinkedHashMap<String, String>(p);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean replaceUserPermissions(String tenantUuid, String userUuid, Map<String, String> newPermissions) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || uu.isBlank()) return false;
        ensure(tu);

        LinkedHashMap<String, String> clean = normalizePermMap(newPermissions);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            LayersRec rec = readLayersLocked(tu);
            LinkedHashMap<String, String> current = rec.userPermissions.get(uu);
            if (current != null && current.equals(clean)) return false;
            if (clean.isEmpty()) rec.userPermissions.remove(uu);
            else rec.userPermissions.put(uu, clean);
            writeLayersLocked(tu, rec);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setUserPermission(String tenantUuid, String userUuid, String key, String value) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        String k = normalizePermKey(key);
        if (tu.isBlank() || uu.isBlank() || k.isBlank()) return false;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            LayersRec rec = readLayersLocked(tu);
            LinkedHashMap<String, String> up = rec.userPermissions.computeIfAbsent(uu, x -> new LinkedHashMap<String, String>());
            String nv = normalizePermValue(value);
            String prev = up.put(k, nv);
            boolean changed = prev == null || !prev.equals(nv);
            if (changed) writeLayersLocked(tu, rec);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean removeUserPermission(String tenantUuid, String userUuid, String key) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        String k = normalizePermKey(key);
        if (tu.isBlank() || uu.isBlank() || k.isBlank()) return false;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            LayersRec rec = readLayersLocked(tu);
            LinkedHashMap<String, String> up = rec.userPermissions.get(uu);
            if (up == null) return false;
            boolean changed = up.remove(k) != null;
            if (up.isEmpty()) rec.userPermissions.remove(uu);
            if (changed) writeLayersLocked(tu, rec);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public LinkedHashMap<String, LinkedHashMap<String, String>> readAllUserPermissions(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return new LinkedHashMap<String, LinkedHashMap<String, String>>();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            LayersRec rec = readLayersLocked(tu);
            LinkedHashMap<String, LinkedHashMap<String, String>> out = new LinkedHashMap<String, LinkedHashMap<String, String>>();
            for (Map.Entry<String, LinkedHashMap<String, String>> e : rec.userPermissions.entrySet()) {
                String uu = safe(e.getKey()).trim();
                if (uu.isBlank()) continue;
                out.put(uu, new LinkedHashMap<String, String>(e.getValue()));
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public LinkedHashMap<String, String> resolveEffectivePermissions(String tenantUuid,
                                                                      String userUuid,
                                                                      Map<String, String> rolePermissions) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || uu.isBlank()) return new LinkedHashMap<String, String>();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            LayersRec layers = readLayersLocked(tu);
            List<GroupRec> groups = readGroupsLocked(tu);
            return resolveEffectiveLocked(tu, uu, rolePermissions, layers, groups);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean refreshSessionPermissions(HttpSession session) {
        if (session == null) return false;

        Object guard = session.getAttribute(SESSION_REFRESH_GUARD);
        if (guard instanceof Boolean b && b.booleanValue()) return false;

        long now = System.currentTimeMillis();
        Object last = session.getAttribute(SESSION_PERMS_LAST_REFRESH_MS);
        if (last instanceof Long l && (now - l.longValue()) < SESSION_REFRESH_THROTTLE_MS) {
            return false;
        }

        String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
        String userUuid = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
        String roleUuid = safe((String) session.getAttribute(users_roles.S_ROLE_UUID)).trim();
        if (tenantUuid.isBlank() || userUuid.isBlank()) return false;

        session.setAttribute(SESSION_REFRESH_GUARD, Boolean.TRUE);
        try {
            LinkedHashMap<String, String> rolePerms = loadRolePermissions(tenantUuid, roleUuid);

            LinkedHashMap<String, String> effective;
            try {
                effective = resolveEffectivePermissions(tenantUuid, userUuid, rolePerms);
            } catch (Exception ex) {
                return false;
            }

            replaceSessionPermissionMap(session, effective);
            session.setAttribute(SESSION_PERMS_LAST_REFRESH_MS, Long.valueOf(now));
            return true;
        } finally {
            session.removeAttribute(SESSION_REFRESH_GUARD);
        }
    }

    public static boolean sessionHasPermission(HttpSession session, String key) {
        if (session == null) return false;
        String k = normalizePermKey(key);
        if (k.isBlank()) return false;

        String admin = permissionValue(session, "tenant_admin");
        if (isTrue(admin)) return true;

        String value = permissionValue(session, k);
        return isTrue(value);
    }

    public static boolean sessionHasAnyPermission(HttpSession session, Collection<String> keys) {
        if (session == null || keys == null || keys.isEmpty()) return false;
        if (sessionHasPermission(session, "tenant_admin")) return true;
        for (String key : keys) {
            if (sessionHasPermission(session, key)) return true;
        }
        return false;
    }

    public static String customObjectPermissionKey(String objectKey, String action) {
        String object = normalizeCustomObjectKey(objectKey);
        String act = normalizeAction(action);
        if (object.isBlank() || act.isBlank()) return "";
        return "custom_object." + object + "." + act;
    }

    public static List<String> customObjectPermissionKeys(String objectKey, String action) {
        String act = normalizeAction(action);
        if (act.isBlank()) return List.of();

        String specific = customObjectPermissionKey(objectKey, act);
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (!specific.isBlank()) out.add(specific);
        switch (act) {
            case "access" -> {
                out.add("custom_objects.records.access");
                out.add("custom_objects.manage");
            }
            case "create" -> {
                out.add("custom_objects.records.create");
                out.add("custom_objects.manage");
            }
            case "edit" -> {
                out.add("custom_objects.records.edit");
                out.add("custom_objects.manage");
            }
            case "archive" -> {
                out.add("custom_objects.records.archive");
                out.add("custom_objects.manage");
            }
            case "export" -> {
                out.add("custom_objects.records.export");
                out.add("custom_objects.manage");
            }
            default -> {
            }
        }
        return new ArrayList<String>(out);
    }

    private static String normalizeCustomObjectKey(String objectKey) {
        String key = safe(objectKey).trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) return "";
        StringBuilder sb = new StringBuilder(key.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.';
            if (ok) {
                sb.append(ch);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String out = sb.toString();
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        return out;
    }

    private static String normalizeAction(String action) {
        String v = safe(action).trim().toLowerCase(Locale.ROOT);
        if ("access".equals(v)) return "access";
        if ("view".equals(v)) return "access";
        if ("read".equals(v)) return "access";
        if ("create".equals(v)) return "create";
        if ("edit".equals(v)) return "edit";
        if ("update".equals(v)) return "edit";
        if ("archive".equals(v)) return "archive";
        if ("delete".equals(v)) return "archive";
        if ("export".equals(v)) return "export";
        return "";
    }

    private static LinkedHashMap<String, String> resolveEffectiveLocked(String tenantUuid,
                                                                         String userUuid,
                                                                         Map<String, String> rolePermissions,
                                                                         LayersRec layers,
                                                                         List<GroupRec> allGroups) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        LinkedHashMap<String, String> tenantMap = normalizePermMap(layers == null ? null : layers.tenantPermissions);
        LinkedHashMap<String, String> roleMap = normalizePermMap(rolePermissions);
        LinkedHashMap<String, String> userMap = new LinkedHashMap<String, String>();
        if (layers != null && layers.userPermissions != null) {
            LinkedHashMap<String, String> raw = layers.userPermissions.get(safe(userUuid).trim());
            userMap.putAll(normalizePermMap(raw));
        }

        boolean structuredPresent = containsStructuredPermissions(tenantMap)
                || containsStructuredPermissions(roleMap)
                || containsStructuredPermissions(userMap);

        LinkedHashMap<String, String> groupResolved = new LinkedHashMap<String, String>();
        if (allGroups != null) {
            for (GroupRec g : allGroups) {
                if (g == null || !g.enabled) continue;
                if (!g.memberUserUuids.contains(userUuid)) continue;
                if (containsStructuredPermissions(g.permissions)) structuredPresent = true;
                for (Map.Entry<String, String> e : g.permissions.entrySet()) {
                    String k = normalizePermKey(e.getKey());
                    if (k.isBlank()) continue;
                    String nv = normalizePermValue(e.getValue());
                    String cur = groupResolved.get(k);
                    if (cur == null) {
                        groupResolved.put(k, nv);
                    } else {
                        if (isTrue(cur) || isTrue(nv)) groupResolved.put(k, "true");
                        else groupResolved.put(k, "false");
                    }
                }
            }
        }

        if (!structuredPresent) {
            out.putAll(profilePermissionsStatic("legacy-standard-user"));
        }

        out.putAll(tenantMap);
        out.putAll(roleMap);
        out.putAll(groupResolved);
        out.putAll(userMap);

        applyWikiCompatibilityAliases(out);

        if (isTrue(out.get("tenant_admin"))) {
            out.put("tenant_admin", "true");
        }

        // Keep catalog keys explicit for the management UI and predictable session maps.
        for (PermissionDef def : CATALOG) {
            if (def == null) continue;
            String k = normalizePermKey(def.key);
            if (k.isBlank()) continue;
            out.putIfAbsent(k, "false");
        }

        return out;
    }

    private static void replaceSessionPermissionMap(HttpSession session, Map<String, String> nextMap) {
        if (session == null) return;

        Object oldObj = session.getAttribute(users_roles.S_PERMS_MAP);
        if (oldObj instanceof Map<?, ?> oldMap) {
            for (Object kObj : oldMap.keySet()) {
                String k = (kObj == null) ? "" : String.valueOf(kObj).trim();
                if (!k.isBlank()) session.removeAttribute(users_roles.S_PERMS_PREFX + k);
            }
        }

        LinkedHashMap<String, String> clean = normalizePermMap(nextMap);
        session.setAttribute(users_roles.S_PERMS_MAP, clean);
        for (Map.Entry<String, String> e : clean.entrySet()) {
            String k = normalizePermKey(e.getKey());
            if (k.isBlank()) continue;
            session.setAttribute(users_roles.S_PERMS_PREFX + k, normalizePermValue(e.getValue()));
        }
    }

    private static LinkedHashMap<String, String> loadRolePermissions(String tenantUuid, String roleUuid) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        String tu = safe(tenantUuid).trim();
        String ru = safe(roleUuid).trim();
        if (tu.isBlank() || ru.isBlank()) return out;
        try {
            users_roles.RoleRec role = users_roles.defaultStore().getRoleByUuid(tu, ru);
            if (role != null && role.permissions != null) {
                out.putAll(role.permissions);
            }
        } catch (Exception ignored) {
        }
        return normalizePermMap(out);
    }

    private static boolean containsStructuredPermissions(Map<String, String> map) {
        if (map == null || map.isEmpty()) return false;
        for (String k0 : map.keySet()) {
            String k = normalizePermKey(k0);
            if (k.isBlank()) continue;
            if ("tenant_admin".equals(k)) return true;
            for (PermissionDef d : CATALOG) {
                if (d == null) continue;
                if (k.equals(normalizePermKey(d.key))) return true;
            }
            if (k.startsWith("custom_object.")) return true;
        }
        return false;
    }

    private static LinkedHashMap<String, String> normalizePermMap(Map<String, String> src) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (src == null) return out;
        for (Map.Entry<String, String> e : src.entrySet()) {
            String k = normalizePermKey(e.getKey());
            if (k.isBlank()) continue;
            out.put(k, normalizePermValue(e.getValue()));
        }
        return out;
    }

    private static String normalizePermKey(String key) {
        return safe(key).trim();
    }

    private static String normalizePermValue(String value) {
        return isTrue(value) ? "true" : "false";
    }

    private static boolean isTrue(String value) {
        String v = safe(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v) || "on".equals(v);
    }

    private static String permissionValue(HttpSession session, String key) {
        if (session == null) return "";
        String k = normalizePermKey(key);
        if (k.isBlank()) return "";
        Object raw = session.getAttribute(users_roles.S_PERMS_PREFX + k);
        if (raw == null) return "";
        return String.valueOf(raw);
    }

    private boolean updateGroupSimple(String tenantUuid, String groupUuid, Boolean enabled, String label) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String gu = safe(groupUuid).trim();
        if (tu.isBlank() || gu.isBlank()) return false;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            List<GroupRec> all = readGroupsLocked(tu);
            boolean changed = false;
            ArrayList<GroupRec> out = new ArrayList<GroupRec>(all.size());
            for (GroupRec g : all) {
                if (g == null) continue;
                if (!gu.equals(g.uuid)) {
                    out.add(g);
                    continue;
                }

                boolean en = (enabled == null) ? g.enabled : enabled.booleanValue();
                String lbl = safe(label).trim();
                if (lbl.isBlank()) lbl = g.label;

                if (g.enabled != en || !safe(g.label).equals(lbl)) changed = true;
                out.add(new GroupRec(g.uuid, en, lbl, g.permissions, g.memberUserUuids));
            }
            if (changed) writeGroupsLocked(tu, out);
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, x -> new ReentrantReadWriteLock());
    }

    private static Path groupsPath(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid, "settings", "groups.xml").toAbsolutePath();
    }

    private static Path layersPath(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid, "settings", "permission_layers.xml").toAbsolutePath();
    }

    private static String safeFileToken(String s) {
        String v = safe(s).trim();
        if (v.isBlank()) return "";
        return v.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static List<GroupRec> readGroupsLocked(String tenantUuid) throws Exception {
        ArrayList<GroupRec> out = new ArrayList<GroupRec>();
        Path p = groupsPath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = (d == null) ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("group");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element e)) continue;

            String uuid = text(e, "uuid");
            boolean enabled = parseBool(text(e, "enabled"), true);
            String label = text(e, "label");
            if (uuid.isBlank() || label.isBlank()) continue;

            LinkedHashMap<String, String> perms = new LinkedHashMap<String, String>();
            Element permsEl = firstChildElement(e, "permissions");
            if (permsEl != null) {
                NodeList plist = permsEl.getElementsByTagName("permission");
                for (int j = 0; j < plist.getLength(); j++) {
                    Node pn = plist.item(j);
                    if (!(pn instanceof Element pe)) continue;
                    String k = normalizePermKey(pe.getAttribute("key"));
                    if (k.isBlank()) continue;
                    perms.put(k, normalizePermValue(pe.getTextContent()));
                }
            }

            LinkedHashSet<String> members = new LinkedHashSet<String>();
            Element membersEl = firstChildElement(e, "members");
            if (membersEl != null) {
                NodeList mlist = membersEl.getElementsByTagName("member");
                for (int j = 0; j < mlist.getLength(); j++) {
                    Node mn = mlist.item(j);
                    if (!(mn instanceof Element me)) continue;
                    String userUuid = safe(me.getAttribute("user_uuid")).trim();
                    if (!userUuid.isBlank()) members.add(userUuid);
                }
            }

            out.add(new GroupRec(uuid, enabled, label, perms, members));
        }
        return out;
    }

    private static void writeGroupsLocked(String tenantUuid, List<GroupRec> groups) throws Exception {
        Path p = groupsPath(tenantUuid);
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<groups updated=\"").append(xmlAttr(now)).append("\">\n");

        List<GroupRec> src = (groups == null) ? List.of() : groups;
        for (GroupRec g : src) {
            if (g == null) continue;
            if (safe(g.uuid).isBlank() || safe(g.label).isBlank()) continue;

            sb.append("  <group>\n");
            sb.append("    <uuid>").append(xmlText(g.uuid)).append("</uuid>\n");
            sb.append("    <enabled>").append(g.enabled ? "true" : "false").append("</enabled>\n");
            sb.append("    <label>").append(xmlText(g.label)).append("</label>\n");
            sb.append("    <members>\n");
            for (String uu : g.memberUserUuids) {
                String userUuid = safe(uu).trim();
                if (userUuid.isBlank()) continue;
                sb.append("      <member user_uuid=\"").append(xmlAttr(userUuid)).append("\"/>\n");
            }
            sb.append("    </members>\n");
            sb.append("    <permissions>\n");
            for (Map.Entry<String, String> e : g.permissions.entrySet()) {
                String k = normalizePermKey(e.getKey());
                if (k.isBlank()) continue;
                sb.append("      <permission key=\"").append(xmlAttr(k)).append("\">")
                        .append(xmlText(normalizePermValue(e.getValue())))
                        .append("</permission>\n");
            }
            sb.append("    </permissions>\n");
            sb.append("  </group>\n");
        }
        sb.append("</groups>\n");

        writeAtomic(p, sb.toString());
    }

    private static LayersRec readLayersLocked(String tenantUuid) throws Exception {
        LayersRec out = new LayersRec();
        Path p = layersPath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = (d == null) ? null : d.getDocumentElement();
        if (root == null) return out;

        Element tenantEl = firstChildElement(root, "tenantPermissions");
        if (tenantEl != null) {
            NodeList plist = tenantEl.getElementsByTagName("permission");
            for (int i = 0; i < plist.getLength(); i++) {
                Node pn = plist.item(i);
                if (!(pn instanceof Element pe)) continue;
                String k = normalizePermKey(pe.getAttribute("key"));
                if (k.isBlank()) continue;
                out.tenantPermissions.put(k, normalizePermValue(pe.getTextContent()));
            }
        }

        Element usersEl = firstChildElement(root, "userPermissions");
        if (usersEl != null) {
            NodeList ulist = usersEl.getElementsByTagName("user");
            for (int i = 0; i < ulist.getLength(); i++) {
                Node un = ulist.item(i);
                if (!(un instanceof Element ue)) continue;
                String userUuid = safe(ue.getAttribute("uuid")).trim();
                if (userUuid.isBlank()) continue;

                LinkedHashMap<String, String> perms = new LinkedHashMap<String, String>();
                NodeList plist = ue.getElementsByTagName("permission");
                for (int j = 0; j < plist.getLength(); j++) {
                    Node pn = plist.item(j);
                    if (!(pn instanceof Element pe)) continue;
                    String k = normalizePermKey(pe.getAttribute("key"));
                    if (k.isBlank()) continue;
                    perms.put(k, normalizePermValue(pe.getTextContent()));
                }
                if (!perms.isEmpty()) out.userPermissions.put(userUuid, perms);
            }
        }

        return out;
    }

    private static void writeLayersLocked(String tenantUuid, LayersRec rec) throws Exception {
        Path p = layersPath(tenantUuid);
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<permissionLayers updated=\"").append(xmlAttr(now)).append("\">\n");

        sb.append("  <tenantPermissions>\n");
        for (Map.Entry<String, String> e : rec.tenantPermissions.entrySet()) {
            String k = normalizePermKey(e.getKey());
            if (k.isBlank()) continue;
            sb.append("    <permission key=\"").append(xmlAttr(k)).append("\">")
                    .append(xmlText(normalizePermValue(e.getValue())))
                    .append("</permission>\n");
        }
        sb.append("  </tenantPermissions>\n");

        sb.append("  <userPermissions>\n");
        for (Map.Entry<String, LinkedHashMap<String, String>> ue : rec.userPermissions.entrySet()) {
            String userUuid = safe(ue.getKey()).trim();
            if (userUuid.isBlank()) continue;
            LinkedHashMap<String, String> perms = normalizePermMap(ue.getValue());
            if (perms.isEmpty()) continue;

            sb.append("    <user uuid=\"").append(xmlAttr(userUuid)).append("\">\n");
            for (Map.Entry<String, String> pe : perms.entrySet()) {
                String k = normalizePermKey(pe.getKey());
                if (k.isBlank()) continue;
                sb.append("      <permission key=\"").append(xmlAttr(k)).append("\">")
                        .append(xmlText(normalizePermValue(pe.getValue())))
                        .append("</permission>\n");
            }
            sb.append("    </user>\n");
        }
        sb.append("  </userPermissions>\n");

        sb.append("</permissionLayers>\n");
        writeAtomic(p, sb.toString());
    }

    private static String emptyGroupsXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<groups created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\"></groups>\n";
    }

    private static String emptyLayersXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<permissionLayers created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\">"
                + "<tenantPermissions></tenantPermissions><userPermissions></userPermissions>"
                + "</permissionLayers>\n";
    }

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

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String xmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

    private static String xmlText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static boolean parseBool(String s, boolean def) {
        String v = safe(s).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return def;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v) || "on".equals(v);
    }

    private static List<PermissionDef> buildCatalog() {
        ArrayList<PermissionDef> out = new ArrayList<PermissionDef>();
        out.add(def("tenant_admin", "Tenant Administrator", "Full access to all features and permissions.", "Admin", true));

        out.add(def("home.access", "Home", "Open the index dashboard and workflow cards.", "Core", false));
        out.add(def("cases.access", "Cases", "Create and manage matters in the Cases screen.", "Core", false));
        out.add(def("case_fields.access", "Case Fields", "Edit case-level key/value fields and case metadata helpers.", "Core", false));
        out.add(def("conflicts.access", "Case Conflicts", "View and search per-case conflicts XML records.", "Core", false));
        out.add(def("conflicts.manage", "Case Conflicts Manage", "Scan/update/delete per-case conflicts XML records.", "Core", false));
        out.add(def("contacts.access", "Contacts", "Use tenant contacts and matter contact links.", "Core", false));
        out.add(def("documents.access", "Documents", "Access document storage, versions, parts, preview, and redaction pages.", "Core", false));
        out.add(def("facts.access", "Facts Case Plan", "Use claims/elements/facts workflows and related actions.", "Core", false));
        out.add(def("tasks.access", "Tasks", "Manage task workflows and task list operations.", "Core", false));
        out.add(def("mail.access", "Postal Mail", "Manage inbound/outbound postal mail workflows, recipients, proof, and tracking.", "Core", false));
        out.add(def("threads.access", "Omnichannel Threads", "Use omnichannel thread inbox and manifest screens.", "Core", false));
        out.add(def("wiki.access", "Knowledge Wiki", "View and edit wiki pages and attachments (subject to wiki page keys).", "Core", false));
        out.add(def("wiki.view", "Wiki View", "View wiki pages and attachments.", "Core", false));
        out.add(def("wiki.edit", "Wiki Edit", "Edit wiki pages and upload attachments.", "Core", false));
        out.add(def("wiki.manage", "Wiki Manage", "Manage wiki structures and full wiki operations.", "Core", true));
        out.add(def("texas_law.access", "Texas Law", "Access the Texas law research and sync page.", "Core", false));
        out.add(def("forms.access", "Forms + Templates", "Use form assembly, assembled forms, template library, and template editor.", "Core", false));
        out.add(def("help.access", "Help Pages", "Open help center, guides, token guide, and markup notation pages.", "Core", false));
        out.add(def("user_settings.access", "User Settings", "Use user settings plus email/password change pages.", "Core", false));

        out.add(def("custom_objects.records.access", "Custom Object Records", "View custom object record tables and details.", "Custom Objects", false));
        out.add(def("custom_objects.records.create", "Custom Object Create", "Create custom object records.", "Custom Objects", false));
        out.add(def("custom_objects.records.edit", "Custom Object Edit", "Update custom object records.", "Custom Objects", false));
        out.add(def("custom_objects.records.archive", "Custom Object Archive", "Archive or restore custom object records.", "Custom Objects", false));
        out.add(def("custom_objects.records.export", "Custom Object Export", "Export custom object records (CSV and report outputs).", "Custom Objects", false));

        out.add(def("security.manage", "Users & Roles Management", "Manage users, roles, passwords, and role permission maps.", "Administration", true));
        out.add(def("permissions.manage", "Permission Layer Management", "Manage tenant, group, and user permission layers and profiles.", "Administration", true));
        out.add(def("tenant_fields.manage", "Tenant Fields Management", "Manage tenant-level replacement fields.", "Administration", true));
        out.add(def("attributes.manage", "Attribute Management", "Manage case/document/task/custom-object attribute schemas.", "Administration", true));
        out.add(def("custom_objects.manage", "Custom Object Definitions", "Manage custom object definitions and attribute models.", "Administration", true));
        out.add(def("business_processes.manage", "Business Processes", "Manage business process definitions and controls.", "Administration", true));
        out.add(def("business_process_reviews.manage", "BPM Reviews", "Access and manage business process review workflows.", "Administration", true));
        out.add(def("tenant_settings.manage", "Tenant Settings", "Manage tenant settings and integration settings.", "Administration", true));
        out.add(def("plugin_manager.manage", "Plugin Manager", "Manage plugin lifecycle and plugin settings.", "Administration", true));
        out.add(def("logs.view", "Logs + Activity", "View security logs and activity logs.", "Administration", true));
        out.add(def("api.credentials.manage", "API Credentials", "Create, revoke, and review API credentials.", "Administration", true));
        out.add(def("integrations.manage", "Integrations", "Manage external integration settings and sync controls.", "Administration", true));
        return List.copyOf(out);
    }

    private static PermissionDef def(String key, String label, String description, String category, boolean adminOnly) {
        return new PermissionDef(key, label, description, category, adminOnly);
    }

    private static List<PermissionProfile> buildProfiles() {
        ArrayList<PermissionProfile> out = new ArrayList<PermissionProfile>();

        out.add(profile(
                "tenant-administrator",
                "Tenant Administrator",
                "Unlimited access across all features and functions.",
                Map.of("tenant_admin", "true")
        ));

        LinkedHashMap<String, String> legacy = new LinkedHashMap<String, String>();
        legacy.put("home.access", "true");
        legacy.put("cases.access", "true");
        legacy.put("case_fields.access", "true");
        legacy.put("conflicts.access", "true");
        legacy.put("conflicts.manage", "true");
        legacy.put("contacts.access", "true");
        legacy.put("documents.access", "true");
        legacy.put("facts.access", "true");
        legacy.put("tasks.access", "true");
        legacy.put("mail.access", "true");
        legacy.put("threads.access", "true");
        legacy.put("wiki.access", "true");
        legacy.put("wiki.view", "true");
        legacy.put("wiki.edit", "true");
        legacy.put("texas_law.access", "true");
        legacy.put("forms.access", "true");
        legacy.put("help.access", "true");
        legacy.put("user_settings.access", "true");
        legacy.put("custom_objects.records.access", "true");
        legacy.put("custom_objects.records.create", "true");
        legacy.put("custom_objects.records.edit", "true");
        legacy.put("custom_objects.records.archive", "true");
        legacy.put("custom_objects.records.export", "true");
        out.add(profile(
                "legacy-standard-user",
                "Legacy Standard User",
                "Matches historical non-admin access behavior for existing workflows.",
                legacy
        ));

        LinkedHashMap<String, String> caseOps = new LinkedHashMap<String, String>(legacy);
        caseOps.put("threads.access", "false");
        caseOps.put("wiki.access", "false");
        caseOps.put("wiki.view", "false");
        caseOps.put("wiki.edit", "false");
        caseOps.put("wiki.manage", "false");
        caseOps.put("texas_law.access", "false");
        out.add(profile(
                "case-operations",
                "Case Operations",
                "Daily matter, fact, task, document, and form workflow access.",
                caseOps
        ));

        LinkedHashMap<String, String> review = new LinkedHashMap<String, String>();
        review.put("home.access", "true");
        review.put("cases.access", "true");
        review.put("conflicts.access", "true");
        review.put("documents.access", "true");
        review.put("mail.access", "true");
        review.put("forms.access", "true");
        review.put("help.access", "true");
        review.put("user_settings.access", "true");
        review.put("custom_objects.records.access", "true");
        review.put("custom_objects.records.export", "true");
        out.add(profile(
                "review-only",
                "Review-Only",
                "Read-focused access for reviewing data and assembled outputs.",
                review
        ));

        LinkedHashMap<String, String> sec = new LinkedHashMap<String, String>();
        sec.put("home.access", "true");
        sec.put("help.access", "true");
        sec.put("user_settings.access", "true");
        sec.put("conflicts.access", "true");
        sec.put("conflicts.manage", "true");
        sec.put("security.manage", "true");
        sec.put("permissions.manage", "true");
        sec.put("logs.view", "true");
        sec.put("api.credentials.manage", "true");
        sec.put("integrations.manage", "true");
        out.add(profile(
                "security-manager",
                "Security Manager",
                "Security and permission administration without full tenant admin rights.",
                sec
        ));

        LinkedHashMap<String, String> platform = new LinkedHashMap<String, String>();
        platform.put("home.access", "true");
        platform.put("help.access", "true");
        platform.put("user_settings.access", "true");
        platform.put("conflicts.access", "true");
        platform.put("conflicts.manage", "true");
        platform.put("tenant_fields.manage", "true");
        platform.put("attributes.manage", "true");
        platform.put("custom_objects.manage", "true");
        platform.put("custom_objects.records.access", "true");
        platform.put("custom_objects.records.create", "true");
        platform.put("custom_objects.records.edit", "true");
        platform.put("custom_objects.records.archive", "true");
        platform.put("custom_objects.records.export", "true");
        platform.put("business_processes.manage", "true");
        platform.put("business_process_reviews.manage", "true");
        platform.put("tenant_settings.manage", "true");
        platform.put("plugin_manager.manage", "true");
        platform.put("logs.view", "true");
        platform.put("api.credentials.manage", "true");
        platform.put("integrations.manage", "true");
        out.add(profile(
                "platform-manager",
                "Platform Manager",
                "Configuration and platform operations management.",
                platform
        ));

        LinkedHashMap<String, String> customObjectOps = new LinkedHashMap<String, String>();
        customObjectOps.put("home.access", "true");
        customObjectOps.put("help.access", "true");
        customObjectOps.put("user_settings.access", "true");
        customObjectOps.put("custom_objects.records.access", "true");
        customObjectOps.put("custom_objects.records.create", "true");
        customObjectOps.put("custom_objects.records.edit", "true");
        customObjectOps.put("custom_objects.records.archive", "true");
        customObjectOps.put("custom_objects.records.export", "true");
        out.add(profile(
                "custom-object-operator",
                "Custom Object Operator",
                "Operate published custom object records without object-definition admin rights.",
                customObjectOps
        ));

        return List.copyOf(out);
    }

    private static PermissionProfile profile(String key, String label, String description, Map<String, String> permissions) {
        return new PermissionProfile(key, label, description, permissions);
    }

    private static LinkedHashMap<String, String> profilePermissionsStatic(String profileKey) {
        String pk = safe(profileKey).trim().toLowerCase(Locale.ROOT);
        for (PermissionProfile p : PROFILES) {
            if (p == null) continue;
            if (pk.equals(safe(p.key).trim().toLowerCase(Locale.ROOT))) {
                return new LinkedHashMap<String, String>(p.permissions);
            }
        }
        return new LinkedHashMap<String, String>();
    }

    private static void applyWikiCompatibilityAliases(LinkedHashMap<String, String> map) {
        if (map == null) return;
        boolean access = isTrue(map.get("wiki.access"));
        boolean view = isTrue(map.get("wiki.view"));
        boolean edit = isTrue(map.get("wiki.edit"));
        boolean manage = isTrue(map.get("wiki.manage"));

        if (manage) {
            map.put("wiki.access", "true");
            map.put("wiki.view", "true");
            map.put("wiki.edit", "true");
            return;
        }

        if (edit) {
            map.put("wiki.view", "true");
            map.put("wiki.access", "true");
            return;
        }

        if (view) {
            map.put("wiki.access", "true");
            return;
        }

        if (access) {
            map.put("wiki.view", "true");
            map.put("wiki.edit", "true");
        }
    }
}
