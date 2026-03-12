package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Task store with subtasks, associations, assignment history, internal notes,
 * round-robin assignment helper, and matter-linked PDF status reporting.
 */
public final class tasks {

    private static final Logger LOG = Logger.getLogger(tasks.class.getName());
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    private static final int MAX_TITLE_LEN = 280;
    private static final int MAX_BODY_LEN = 8000;
    private static final int MAX_NOTE_LEN = 8000;
    private static final int MAX_REASON_LEN = 1000;
    private static final int MAX_REF_LEN = 320;

    private static final String STATUS_OPEN = "open";
    private static final String STATUS_IN_PROGRESS = "in_progress";
    private static final String STATUS_BLOCKED = "blocked";
    private static final String STATUS_WAITING_REVIEW = "waiting_review";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_CANCELLED = "cancelled";

    private static final String PRIORITY_LOW = "low";
    private static final String PRIORITY_NORMAL = "normal";
    private static final String PRIORITY_HIGH = "high";
    private static final String PRIORITY_URGENT = "urgent";

    private static final String MODE_MANUAL = "manual";
    private static final String MODE_ROUND_ROBIN = "round_robin";

    public static final class TaskRec {
        public String uuid;
        public String matterUuid;
        public String parentTaskUuid;

        public String title;
        public String description;
        public String status;
        public String priority;

        public String assignmentMode;
        public String assignedUserUuid;

        public String dueAt;
        public String reminderAt;
        public int estimateMinutes;

        public String claimUuid;
        public String elementUuid;
        public String factUuid;

        public String documentUuid;
        public String partUuid;
        public String versionUuid;
        public int pageNumber;

        public String threadUuid;

        public String createdBy;
        public String createdAt;
        public String updatedAt;
        public String completedAt;
        public boolean archived;

        public String reportDocumentUuid;
        public String reportPartUuid;
        public String lastReportVersionUuid;
    }

    public static final class NoteRec {
        public String uuid;
        public String taskUuid;
        public String body;
        public String createdBy;
        public String createdAt;
    }

    public static final class AssignmentRec {
        public String uuid;
        public String taskUuid;
        public String mode;
        public String fromUserUuid;
        public String toUserUuid;
        public String reason;
        public String changedBy;
        public String changedAt;
    }

    public static tasks defaultStore() {
        return new tasks();
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path root = tasksRoot(tu);
            Files.createDirectories(root);
            Files.createDirectories(tasksDir(tu));
            if (!Files.exists(tasksPath(tu))) {
                writeAtomic(tasksPath(tu), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tasks updated=\"" + xmlAttr(nowIso()) + "\"></tasks>\n");
            }
            if (!Files.exists(roundRobinStatePath(tu))) {
                writeAtomic(roundRobinStatePath(tu), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<roundRobinState updated=\"" + xmlAttr(nowIso()) + "\"></roundRobinState>\n");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<TaskRec> listTasks(String tenantUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readTasksLocked(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<TaskRec> listTasksByMatter(String tenantUuid, String matterUuid) throws Exception {
        String wantedMatter = safe(matterUuid).trim();
        List<TaskRec> all = listTasks(tenantUuid);
        if (wantedMatter.isBlank()) return all;
        ArrayList<TaskRec> out = new ArrayList<TaskRec>();
        for (int i = 0; i < all.size(); i++) {
            TaskRec rec = all.get(i);
            if (rec == null) continue;
            if (!wantedMatter.equals(safe(rec.matterUuid).trim())) continue;
            out.add(rec);
        }
        sortTasks(out);
        return out;
    }

    public TaskRec getTask(String tenantUuid, String taskUuid) throws Exception {
        String id = safe(taskUuid).trim();
        if (id.isBlank()) return null;
        List<TaskRec> all = listTasks(tenantUuid);
        for (int i = 0; i < all.size(); i++) {
            TaskRec rec = all.get(i);
            if (rec == null) continue;
            if (id.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    public TaskRec createTask(String tenantUuid,
                              TaskRec in,
                              String actor,
                              String assignmentReason) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (in == null) throw new IllegalArgumentException("Task payload required.");

        TaskRec out;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<TaskRec> all = readTasksLocked(tu);

            TaskRec rec = sanitizeForCreate(in);
            rec.uuid = UUID.randomUUID().toString();
            rec.createdBy = clampLen(actor, MAX_REF_LEN).trim();
            rec.createdAt = nowIso();
            rec.updatedAt = rec.createdAt;
            rec.completedAt = isDoneStatus(rec.status) ? rec.createdAt : "";
            rec.archived = false;
            rec.reportDocumentUuid = "";
            rec.reportPartUuid = "";
            rec.lastReportVersionUuid = "";

            validateAssociations(tu, rec, all, rec.uuid);

            all.add(rec);
            sortTasks(all);
            writeTasksLocked(tu, all);

            String toAssignee = normalizeAssignmentCsv(rec.assignedUserUuid);
            if (!toAssignee.isBlank()) {
                appendAssignmentLocked(tu, rec.uuid, rec.assignmentMode, "", toAssignee, assignmentReason, actor);
            }

            out = findTaskByUuid(readTasksLocked(tu), rec.uuid);
        } finally {
            lock.writeLock().unlock();
        }

        if (out != null && !safe(out.matterUuid).trim().isBlank()) {
            out = refreshTaskReport(tu, out.uuid, actor);
        }
        if (out != null) {
            emitTaskMentions(tu, actor, out, taskMentionText(out), Map.of());
            audit("tasks.task.created", tu, actor, safe(out.matterUuid), Map.of("task_uuid", safe(out.uuid)));
            publishTaskEvent(
                    tu,
                    "task.created",
                    out,
                    Map.of("assignment_mode", safe(out.assignmentMode))
            );
        }
        return out;
    }

    public boolean updateTask(String tenantUuid,
                              TaskRec in,
                              String actor,
                              String assignmentReason) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(in == null ? "" : in.uuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (id.isBlank()) throw new IllegalArgumentException("task uuid required");

        boolean changed = false;
        boolean refreshReport = false;
        boolean mentionTextChanged = false;
        String mentionText = "";

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<TaskRec> all = readTasksLocked(tu);

            TaskRec current = findTaskByUuid(all, id);
            if (current == null) throw new IllegalArgumentException("Task not found.");

            TaskRec rec = mergeForUpdate(current, in);
            validateAssociations(tu, rec, all, id);

            if (!sameTask(current, rec)) {
                changed = true;
                String beforeMentionText = taskMentionText(current);
                String afterMentionText = taskMentionText(rec);
                mentionTextChanged = !beforeMentionText.equals(afterMentionText);
                mentionText = afterMentionText;
                replaceTaskByUuid(all, rec);
                sortTasks(all);
                writeTasksLocked(tu, all);

                String oldAssignee = normalizeAssignmentCsv(current.assignedUserUuid);
                String newAssignee = normalizeAssignmentCsv(rec.assignedUserUuid);
                String oldMode = canonicalAssignmentMode(current.assignmentMode);
                String newMode = canonicalAssignmentMode(rec.assignmentMode);
                if (!oldAssignee.equals(newAssignee) || !oldMode.equals(newMode)) {
                    appendAssignmentLocked(tu, rec.uuid, newMode, oldAssignee, newAssignee, assignmentReason, actor);
                    publishTaskEvent(
                            tu,
                            "task.assignment.changed",
                            rec,
                            Map.of(
                                    "from_assigned_user_uuid", oldAssignee,
                                    "to_assigned_user_uuid", newAssignee,
                                    "assignment_mode", newMode
                            )
                    );
                }

                refreshReport = !safe(rec.matterUuid).trim().isBlank();
            }
        } finally {
            lock.writeLock().unlock();
        }

        if (changed && refreshReport) {
            refreshTaskReport(tu, id, actor);
        }

        if (changed) {
            TaskRec latest = getTask(tu, id);
            if (latest != null) {
                if (mentionTextChanged) emitTaskMentions(tu, actor, latest, mentionText, Map.of());
                audit("tasks.task.updated", tu, actor, safe(latest.matterUuid), Map.of("task_uuid", safe(id)));
                publishTaskEvent(
                        tu,
                        "task.updated",
                        latest,
                        Map.of("status", safe(latest.status), "priority", safe(latest.priority))
                );
            }
        }

        return changed;
    }

    public boolean setArchived(String tenantUuid,
                               String taskUuid,
                               boolean archived,
                               String actor) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(taskUuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;

        boolean changed = false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<TaskRec> all = readTasksLocked(tu);
            for (int i = 0; i < all.size(); i++) {
                TaskRec rec = all.get(i);
                if (rec == null) continue;
                if (!id.equals(safe(rec.uuid).trim())) continue;
                if (rec.archived != archived) {
                    rec.archived = archived;
                    rec.updatedAt = nowIso();
                    changed = true;
                }
                break;
            }
            if (changed) {
                sortTasks(all);
                writeTasksLocked(tu, all);
            }
        } finally {
            lock.writeLock().unlock();
        }

        if (!changed) return false;

        TaskRec rec = getTask(tu, id);
        if (rec != null && !safe(rec.matterUuid).trim().isBlank()) {
            refreshTaskReport(tu, id, actor);
        }

        if (rec != null) {
            String evt = archived ? "task.archived" : "task.restored";
            audit("tasks.task." + (archived ? "archived" : "restored"), tu, actor, safe(rec.matterUuid), Map.of("task_uuid", safe(id)));
            publishTaskEvent(tu, evt, rec, Map.of("archived", archived ? "true" : "false"));
        }

        return true;
    }

    public NoteRec addNote(String tenantUuid,
                           String taskUuid,
                           String body,
                           String actor) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(taskUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (id.isBlank()) throw new IllegalArgumentException("task uuid required");

        NoteRec rec;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            TaskRec task = findTaskByUuid(readTasksLocked(tu), id);
            if (task == null) throw new IllegalArgumentException("Task not found.");

            String cleanBody = clampLen(body, MAX_NOTE_LEN).trim();
            if (cleanBody.isBlank()) throw new IllegalArgumentException("note body required");

            List<NoteRec> all = readNotesLocked(tu, id);
            rec = new NoteRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.taskUuid = id;
            rec.body = cleanBody;
            rec.createdBy = clampLen(actor, MAX_REF_LEN).trim();
            rec.createdAt = nowIso();
            all.add(rec);
            sortNotes(all);
            writeNotesLocked(tu, id, all);

            touchTaskUpdatedAtLocked(tu, id);
        } finally {
            lock.writeLock().unlock();
        }

        TaskRec task = getTask(tu, id);
        if (task != null && !safe(task.matterUuid).trim().isBlank()) {
            refreshTaskReport(tu, id, actor);
        }

        if (task != null && rec != null) {
            emitTaskMentions(
                    tu,
                    actor,
                    task,
                    safe(rec.body),
                    Map.of("note_uuid", safe(rec.uuid))
            );
            audit("tasks.note.added", tu, actor, safe(task.matterUuid), Map.of("task_uuid", safe(id), "note_uuid", safe(rec.uuid)));
            publishTaskEvent(
                    tu,
                    "task.note.added",
                    task,
                    Map.of("note_uuid", safe(rec.uuid))
            );
        }

        return rec;
    }

    public List<NoteRec> listNotes(String tenantUuid, String taskUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(taskUuid).trim();
        if (tu.isBlank() || id.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readNotesLocked(tu, id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<AssignmentRec> listAssignments(String tenantUuid, String taskUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(taskUuid).trim();
        if (tu.isBlank() || id.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAssignmentsLocked(tu, id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public String chooseRoundRobinAssignee(String tenantUuid,
                                           String queueKey,
                                           List<String> candidateUserUuids) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        if (candidateUserUuids != null) {
            for (int i = 0; i < candidateUserUuids.size(); i++) {
                String id = safe(candidateUserUuids.get(i)).trim();
                if (id.isBlank()) continue;
                unique.add(id);
            }
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("No candidates supplied for round robin assignment.");

        List<String> candidates = new ArrayList<String>(unique);
        candidates.sort(String::compareToIgnoreCase);

        String key = safe(queueKey).trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) key = "default";

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            Map<String, Integer> state = readRoundRobinStateLocked(tu);
            int previous = state.getOrDefault(key, Integer.valueOf(-1)).intValue();
            int next = previous + 1;
            if (next < 0 || next >= candidates.size()) next = 0;
            state.put(key, Integer.valueOf(next));
            writeRoundRobinStateLocked(tu, state);
            return candidates.get(next);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public TaskRec refreshTaskReport(String tenantUuid, String taskUuid, String actor) throws Exception {
        String tu = safeToken(tenantUuid);
        String tid = safe(taskUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (tid.isBlank()) throw new IllegalArgumentException("task uuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);

            List<TaskRec> rows = readTasksLocked(tu);
            TaskRec task = findTaskByUuid(rows, tid);
            if (task == null) throw new IllegalArgumentException("Task not found.");
            String matterUuid = safe(task.matterUuid).trim();
            if (matterUuid.isBlank()) return task;

            matters.MatterRec matter = matters.defaultStore().getByUuid(tu, matterUuid);
            if (matter == null || matter.trashed) throw new IllegalArgumentException("Linked matter not found.");

            List<NoteRec> notes = readNotesLocked(tu, tid);
            List<AssignmentRec> assignments = readAssignmentsLocked(tu, tid);

            List<TaskRec> matterTasks = new ArrayList<TaskRec>();
            for (int i = 0; i < rows.size(); i++) {
                TaskRec r = rows.get(i);
                if (r == null) continue;
                if (!matterUuid.equals(safe(r.matterUuid).trim())) continue;
                matterTasks.add(r);
            }
            sortTasks(matterTasks);

            documents docStore = documents.defaultStore();
            document_parts partStore = document_parts.defaultStore();
            part_versions versionStore = part_versions.defaultStore();

            String docUuid = safe(task.reportDocumentUuid).trim();
            documents.DocumentRec docRec = null;
            if (!docUuid.isBlank()) docRec = docStore.get(tu, matterUuid, docUuid);

            String shortId = safe(task.uuid);
            if (shortId.length() > 8) shortId = shortId.substring(0, 8);
            String docTitle = "Task " + shortId + " Report";

            if (docRec == null) {
                docRec = docStore.create(
                        tu,
                        matterUuid,
                        docTitle,
                        "communication",
                        "task",
                        mapDocumentStatus(task.status),
                        safe(task.assignedUserUuid).trim(),
                        "work_product",
                        "",
                        "task:" + safe(task.uuid),
                        "Auto-generated task report"
                );
                docUuid = safe(docRec == null ? "" : docRec.uuid).trim();
            } else {
                documents.DocumentRec in = new documents.DocumentRec();
                in.uuid = docRec.uuid;
                in.title = docTitle;
                in.category = safe(docRec.category).trim().isBlank() ? "communication" : safe(docRec.category);
                in.subcategory = safe(docRec.subcategory).trim().isBlank() ? "task" : safe(docRec.subcategory);
                in.status = mapDocumentStatus(task.status);
                in.owner = safe(task.assignedUserUuid).trim();
                in.privilegeLevel = safe(docRec.privilegeLevel).trim().isBlank() ? "work_product" : safe(docRec.privilegeLevel);
                in.filedOn = safe(docRec.filedOn);
                in.externalReference = "task:" + safe(task.uuid);
                in.notes = "Auto-generated task report";
                docStore.update(tu, matterUuid, in);
                docUuid = safe(docRec.uuid).trim();
            }

            String partUuid = safe(task.reportPartUuid).trim();
            document_parts.PartRec partRec = null;
            if (!partUuid.isBlank()) partRec = partStore.get(tu, matterUuid, docUuid, partUuid);
            if (partRec == null) {
                partRec = partStore.create(
                        tu,
                        matterUuid,
                        docUuid,
                        "Task Report PDF",
                        "attachment",
                        "1",
                        "internal",
                        safe(actor).trim(),
                        "Auto-generated task report history"
                );
                partUuid = safe(partRec == null ? "" : partRec.uuid).trim();
            }

            Path versionDir = partStore.partFolder(tu, matterUuid, docUuid, partUuid).resolve("version_files");
            Files.createDirectories(versionDir);
            String fileName = ("task_report_" + safe(task.uuid) + "_" + nowIso() + ".pdf")
                    .replaceAll("[^A-Za-z0-9.]", "_");
            Path reportPath = versionDir.resolve(UUID.randomUUID().toString().replace("-", "_") + "__" + fileName).toAbsolutePath().normalize();
            pdf_redaction_service.requirePathWithinTenant(reportPath, tu, "Task report path");

            writeTaskReportPdf(reportPath, task, matter, notes, assignments, matterTasks);
            long bytes = Files.size(reportPath);
            String checksum = pdf_redaction_service.sha256(reportPath);

            String versionLabel = "Task Report " + nowIso();
            part_versions.VersionRec version = versionStore.create(
                    tu,
                    matterUuid,
                    docUuid,
                    partUuid,
                    versionLabel,
                    "tasks",
                    "application/pdf",
                    checksum,
                    String.valueOf(bytes),
                    reportPath.toString(),
                    safe(actor).trim(),
                    "Generated from task updates",
                    true
            );

            task.reportDocumentUuid = docUuid;
            task.reportPartUuid = partUuid;
            task.lastReportVersionUuid = safe(version == null ? "" : version.uuid).trim();
            task.updatedAt = nowIso();
            replaceTaskByUuid(rows, task);
            sortTasks(rows);
            writeTasksLocked(tu, rows);

            audit("tasks.report.refreshed", tu, actor, matterUuid, Map.of("task_uuid", safe(task.uuid), "report_document_uuid", docUuid));
            publishTaskEvent(
                    tu,
                    "task.report.refreshed",
                    task,
                    Map.of(
                            "report_document_uuid", safe(task.reportDocumentUuid),
                            "report_part_uuid", safe(task.reportPartUuid),
                            "last_report_version_uuid", safe(task.lastReportVersionUuid)
                    )
            );

            return task;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static TaskRec sanitizeForCreate(TaskRec in) {
        TaskRec out = new TaskRec();
        out.uuid = "";
        out.matterUuid = safe(in.matterUuid).trim();
        out.parentTaskUuid = safe(in.parentTaskUuid).trim();

        out.title = clampLen(in.title, MAX_TITLE_LEN).trim();
        if (out.title.isBlank()) throw new IllegalArgumentException("Task title is required.");

        out.description = clampLen(in.description, MAX_BODY_LEN).trim();
        out.status = canonicalStatus(in.status);
        out.priority = canonicalPriority(in.priority);

        out.assignmentMode = canonicalAssignmentMode(in.assignmentMode);
        out.assignedUserUuid = normalizeAssignmentCsv(in.assignedUserUuid);

        out.dueAt = normalizeDueAt(in.dueAt);
        out.reminderAt = normalizeReminderAt(in.reminderAt);
        out.estimateMinutes = normalizeEstimateMinutes(in.estimateMinutes);

        out.claimUuid = safe(in.claimUuid).trim();
        out.elementUuid = safe(in.elementUuid).trim();
        out.factUuid = safe(in.factUuid).trim();

        out.documentUuid = safe(in.documentUuid).trim();
        out.partUuid = safe(in.partUuid).trim();
        out.versionUuid = safe(in.versionUuid).trim();
        out.pageNumber = Math.max(0, in.pageNumber);

        out.threadUuid = safe(in.threadUuid).trim();

        out.createdBy = "";
        out.createdAt = "";
        out.updatedAt = "";
        out.completedAt = "";
        out.archived = false;

        out.reportDocumentUuid = "";
        out.reportPartUuid = "";
        out.lastReportVersionUuid = "";
        return out;
    }

    private static TaskRec mergeForUpdate(TaskRec current, TaskRec in) {
        TaskRec out = new TaskRec();

        out.uuid = safe(current.uuid).trim();
        out.matterUuid = safe(in == null || in.matterUuid == null ? current.matterUuid : in.matterUuid).trim();
        out.parentTaskUuid = safe(in == null || in.parentTaskUuid == null ? current.parentTaskUuid : in.parentTaskUuid).trim();

        String title = safe(in == null || in.title == null ? current.title : in.title).trim();
        title = clampLen(title, MAX_TITLE_LEN).trim();
        if (title.isBlank()) throw new IllegalArgumentException("Task title is required.");
        out.title = title;

        out.description = clampLen(safe(in == null || in.description == null ? current.description : in.description), MAX_BODY_LEN).trim();
        out.status = canonicalStatus(in == null ? current.status : (in.status == null ? current.status : in.status));
        out.priority = canonicalPriority(in == null ? current.priority : (in.priority == null ? current.priority : in.priority));

        out.assignmentMode = canonicalAssignmentMode(in == null ? current.assignmentMode : (in.assignmentMode == null ? current.assignmentMode : in.assignmentMode));
        out.assignedUserUuid = normalizeAssignmentCsv(in == null ? current.assignedUserUuid : (in.assignedUserUuid == null ? current.assignedUserUuid : in.assignedUserUuid));

        String dueAtRaw = in == null || in.dueAt == null ? current.dueAt : in.dueAt;
        out.dueAt = normalizeDueAt(dueAtRaw);

        String reminderRaw = in == null || in.reminderAt == null ? current.reminderAt : in.reminderAt;
        out.reminderAt = normalizeReminderAt(reminderRaw);

        int est = in == null || in.estimateMinutes <= 0 ? current.estimateMinutes : in.estimateMinutes;
        out.estimateMinutes = normalizeEstimateMinutes(est);

        out.claimUuid = safe(in == null || in.claimUuid == null ? current.claimUuid : in.claimUuid).trim();
        out.elementUuid = safe(in == null || in.elementUuid == null ? current.elementUuid : in.elementUuid).trim();
        out.factUuid = safe(in == null || in.factUuid == null ? current.factUuid : in.factUuid).trim();

        out.documentUuid = safe(in == null || in.documentUuid == null ? current.documentUuid : in.documentUuid).trim();
        out.partUuid = safe(in == null || in.partUuid == null ? current.partUuid : in.partUuid).trim();
        out.versionUuid = safe(in == null || in.versionUuid == null ? current.versionUuid : in.versionUuid).trim();
        out.pageNumber = Math.max(0, in == null ? current.pageNumber : in.pageNumber);

        out.threadUuid = safe(in == null || in.threadUuid == null ? current.threadUuid : in.threadUuid).trim();

        out.createdBy = safe(current.createdBy).trim();
        out.createdAt = safe(current.createdAt).trim();
        out.updatedAt = nowIso();
        out.archived = in == null ? current.archived : in.archived;

        String prevDoneAt = safe(current.completedAt).trim();
        if (isDoneStatus(out.status)) {
            out.completedAt = prevDoneAt.isBlank() ? out.updatedAt : prevDoneAt;
        } else {
            out.completedAt = "";
        }

        out.reportDocumentUuid = safe(current.reportDocumentUuid).trim();
        out.reportPartUuid = safe(current.reportPartUuid).trim();
        out.lastReportVersionUuid = safe(current.lastReportVersionUuid).trim();
        return out;
    }

    private static boolean sameTask(TaskRec a, TaskRec b) {
        if (a == null || b == null) return false;
        return safe(a.uuid).equals(safe(b.uuid))
                && safe(a.matterUuid).equals(safe(b.matterUuid))
                && safe(a.parentTaskUuid).equals(safe(b.parentTaskUuid))
                && safe(a.title).equals(safe(b.title))
                && safe(a.description).equals(safe(b.description))
                && safe(a.status).equals(safe(b.status))
                && safe(a.priority).equals(safe(b.priority))
                && safe(a.assignmentMode).equals(safe(b.assignmentMode))
                && normalizeAssignmentCsv(a.assignedUserUuid).equals(normalizeAssignmentCsv(b.assignedUserUuid))
                && safe(a.dueAt).equals(safe(b.dueAt))
                && safe(a.reminderAt).equals(safe(b.reminderAt))
                && a.estimateMinutes == b.estimateMinutes
                && safe(a.claimUuid).equals(safe(b.claimUuid))
                && safe(a.elementUuid).equals(safe(b.elementUuid))
                && safe(a.factUuid).equals(safe(b.factUuid))
                && safe(a.documentUuid).equals(safe(b.documentUuid))
                && safe(a.partUuid).equals(safe(b.partUuid))
                && safe(a.versionUuid).equals(safe(b.versionUuid))
                && a.pageNumber == b.pageNumber
                && safe(a.threadUuid).equals(safe(b.threadUuid))
                && a.archived == b.archived;
    }

    private static void validateAssociations(String tenantUuid,
                                             TaskRec rec,
                                             List<TaskRec> allTasks,
                                             String updatingTaskUuid) throws Exception {
        String matterUuid = safe(rec.matterUuid).trim();
        if (!matterUuid.isBlank()) {
            matters.MatterRec matter = matters.defaultStore().getByUuid(tenantUuid, matterUuid);
            if (matter == null || matter.trashed) throw new IllegalArgumentException("Linked matter not found.");
        }

        if (!safe(rec.parentTaskUuid).trim().isBlank()) {
            String parentId = safe(rec.parentTaskUuid).trim();
            if (parentId.equals(safe(updatingTaskUuid).trim())) {
                throw new IllegalArgumentException("Task cannot be its own parent.");
            }
            TaskRec parent = findTaskByUuid(allTasks, parentId);
            if (parent == null) throw new IllegalArgumentException("Parent task not found.");
            if (wouldCreateCycle(allTasks, safe(updatingTaskUuid).trim(), parentId)) {
                throw new IllegalArgumentException("Parent task would create a cycle.");
            }
            if (!matterUuid.isBlank() && !safe(parent.matterUuid).trim().isBlank()
                    && !matterUuid.equals(safe(parent.matterUuid).trim())) {
                throw new IllegalArgumentException("Parent task is linked to a different matter.");
            }
        }

        String claimUuid = safe(rec.claimUuid).trim();
        String elementUuid = safe(rec.elementUuid).trim();
        String factUuid = safe(rec.factUuid).trim();

        if (!claimUuid.isBlank() || !elementUuid.isBlank() || !factUuid.isBlank()) {
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for Facts associations.");
            matter_facts factsStore = matter_facts.defaultStore();

            if (!claimUuid.isBlank()) {
                matter_facts.ClaimRec c = factsStore.getClaim(tenantUuid, matterUuid, claimUuid);
                if (c == null || c.trashed) throw new IllegalArgumentException("Associated claim not found.");
            }
            if (!elementUuid.isBlank()) {
                matter_facts.ElementRec e = factsStore.getElement(tenantUuid, matterUuid, elementUuid);
                if (e == null || e.trashed) throw new IllegalArgumentException("Associated element not found.");
                if (!claimUuid.isBlank() && !claimUuid.equals(safe(e.claimUuid).trim())) {
                    throw new IllegalArgumentException("Element does not belong to the selected claim.");
                }
            }
            if (!factUuid.isBlank()) {
                matter_facts.FactRec f = factsStore.getFact(tenantUuid, matterUuid, factUuid);
                if (f == null || f.trashed) throw new IllegalArgumentException("Associated fact not found.");
                if (!elementUuid.isBlank() && !elementUuid.equals(safe(f.elementUuid).trim())) {
                    throw new IllegalArgumentException("Fact does not belong to the selected element.");
                }
            }
        }

        String docUuid = safe(rec.documentUuid).trim();
        String partUuid = safe(rec.partUuid).trim();
        String versionUuid = safe(rec.versionUuid).trim();

        if (!docUuid.isBlank() || !partUuid.isBlank() || !versionUuid.isBlank() || rec.pageNumber > 0) {
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for document associations.");
            if (!docUuid.isBlank()) {
                documents.DocumentRec d = documents.defaultStore().get(tenantUuid, matterUuid, docUuid);
                if (d == null || d.trashed) throw new IllegalArgumentException("Associated document not found.");
            }
            if (!partUuid.isBlank()) {
                if (docUuid.isBlank()) throw new IllegalArgumentException("doc_uuid is required when part_uuid is set.");
                document_parts.PartRec p = document_parts.defaultStore().get(tenantUuid, matterUuid, docUuid, partUuid);
                if (p == null || p.trashed) throw new IllegalArgumentException("Associated part not found.");
            }
            if (!versionUuid.isBlank()) {
                if (docUuid.isBlank() || partUuid.isBlank()) {
                    throw new IllegalArgumentException("doc_uuid and part_uuid are required when version_uuid is set.");
                }
                boolean found = false;
                List<part_versions.VersionRec> versions = part_versions.defaultStore().listAll(tenantUuid, matterUuid, docUuid, partUuid);
                for (int i = 0; i < versions.size(); i++) {
                    part_versions.VersionRec v = versions.get(i);
                    if (v == null) continue;
                    if (versionUuid.equals(safe(v.uuid).trim())) {
                        found = true;
                        break;
                    }
                }
                if (!found) throw new IllegalArgumentException("Associated version not found.");
            }
        }

        String threadUuid = safe(rec.threadUuid).trim();
        if (!threadUuid.isBlank()) {
            omnichannel_tickets.TicketRec thread = omnichannel_tickets.defaultStore().getTicket(tenantUuid, threadUuid);
            if (thread == null || thread.archived) throw new IllegalArgumentException("Associated thread not found.");
            String threadMatter = safe(thread.matterUuid).trim();
            if (!matterUuid.isBlank() && !threadMatter.isBlank() && !matterUuid.equals(threadMatter)) {
                throw new IllegalArgumentException("Thread is linked to a different matter.");
            }
        }
    }

    private static boolean wouldCreateCycle(List<TaskRec> rows, String taskUuid, String newParentUuid) {
        String taskId = safe(taskUuid).trim();
        String parent = safe(newParentUuid).trim();
        if (taskId.isBlank() || parent.isBlank()) return false;

        LinkedHashMap<String, String> parentByTask = new LinkedHashMap<String, String>();
        List<TaskRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            TaskRec r = src.get(i);
            if (r == null) continue;
            String id = safe(r.uuid).trim();
            if (id.isBlank()) continue;
            parentByTask.put(id, safe(r.parentTaskUuid).trim());
        }

        String cursor = parent;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        while (!cursor.isBlank()) {
            if (taskId.equals(cursor)) return true;
            if (!seen.add(cursor)) return true;
            cursor = safe(parentByTask.get(cursor)).trim();
        }
        return false;
    }

    private static void touchTaskUpdatedAtLocked(String tenantUuid, String taskUuid) throws Exception {
        List<TaskRec> all = readTasksLocked(tenantUuid);
        for (int i = 0; i < all.size(); i++) {
            TaskRec rec = all.get(i);
            if (rec == null) continue;
            if (!safe(taskUuid).trim().equals(safe(rec.uuid).trim())) continue;
            rec.updatedAt = nowIso();
            break;
        }
        sortTasks(all);
        writeTasksLocked(tenantUuid, all);
    }

    private static void appendAssignmentLocked(String tenantUuid,
                                               String taskUuid,
                                               String mode,
                                               String fromUserUuid,
                                               String toUserUuid,
                                               String reason,
                                               String actor) throws Exception {
        List<AssignmentRec> all = readAssignmentsLocked(tenantUuid, taskUuid);

        AssignmentRec rec = new AssignmentRec();
        rec.uuid = UUID.randomUUID().toString();
        rec.taskUuid = safe(taskUuid).trim();
        rec.mode = canonicalAssignmentMode(mode);
        rec.fromUserUuid = normalizeAssignmentCsv(fromUserUuid);
        rec.toUserUuid = normalizeAssignmentCsv(toUserUuid);
        rec.reason = clampLen(reason, MAX_REASON_LEN).trim();
        rec.changedBy = clampLen(actor, MAX_REF_LEN).trim();
        rec.changedAt = nowIso();

        all.add(rec);
        sortAssignments(all);
        writeAssignmentsLocked(tenantUuid, taskUuid, all);
    }

    private static void writeTaskReportPdf(Path outputPdf,
                                           TaskRec task,
                                           matters.MatterRec matter,
                                           List<NoteRec> notes,
                                           List<AssignmentRec> assignments,
                                           List<TaskRec> matterTasks) throws Exception {
        if (outputPdf == null) throw new IllegalArgumentException("Output PDF path required.");
        Files.createDirectories(outputPdf.getParent());

        ArrayList<String> lines = new ArrayList<String>(600);
        lines.add("Task Report");
        lines.add("Generated At: " + nowIso());
        lines.add("");

        lines.add("Task Summary");
        lines.add("Matter Label: " + safe(matter == null ? "" : matter.label));
        lines.add("Title: " + safe(task == null ? "" : task.title));
        lines.add("Status: " + safe(task == null ? "" : task.status));
        lines.add("Priority: " + safe(task == null ? "" : task.priority));
        lines.add("Assigned Users: " + (safe(task == null ? "" : task.assignedUserUuid).trim().isBlank() ? "(none)" : "(assigned)"));
        lines.add("Assignment Mode: " + safe(task == null ? "" : task.assignmentMode));
        lines.add("Due At: " + safe(task == null ? "" : task.dueAt));
        lines.add("Reminder At: " + safe(task == null ? "" : task.reminderAt));
        lines.add("Estimate Minutes: " + (task == null ? 0 : task.estimateMinutes));
        lines.add("Fact Link: " + ((task != null && (!safe(task.claimUuid).trim().isBlank()
                || !safe(task.elementUuid).trim().isBlank()
                || !safe(task.factUuid).trim().isBlank())) ? "Linked" : "(none)"));
        lines.add("Document Link: " + ((task != null && (!safe(task.documentUuid).trim().isBlank()
                || !safe(task.partUuid).trim().isBlank()
                || !safe(task.versionUuid).trim().isBlank())) ? "Linked" : "(none)")
                + " | page=" + (task == null ? 0 : task.pageNumber));
        lines.add("Created By: " + displayActor(task == null ? "" : task.createdBy));
        lines.add("Created At: " + safe(task == null ? "" : task.createdAt));
        lines.add("Updated At: " + safe(task == null ? "" : task.updatedAt));
        lines.add("Completed At: " + safe(task == null ? "" : task.completedAt));
        lines.add("Archived: " + (task != null && task.archived ? "yes" : "no"));
        lines.add("");

        if (task != null && !safe(task.description).trim().isBlank()) {
            lines.add("Description");
            appendWrapped(lines, safe(task.description), 110);
            lines.add("");
        }

        lines.add("Internal Notes (" + (notes == null ? 0 : notes.size()) + ")");
        if (notes == null || notes.isEmpty()) {
            lines.add("No notes recorded.");
        } else {
            for (int i = 0; i < notes.size(); i++) {
                NoteRec n = notes.get(i);
                if (n == null) continue;
                lines.add("[" + safe(n.createdAt) + "] by " + safe(n.createdBy));
                appendWrapped(lines, safe(n.body), 110);
                lines.add("");
            }
        }

        lines.add("Assignment History (" + (assignments == null ? 0 : assignments.size()) + ")");
        if (assignments == null || assignments.isEmpty()) {
            lines.add("No assignment changes recorded.");
        } else {
            for (int i = 0; i < assignments.size(); i++) {
                AssignmentRec a = assignments.get(i);
                if (a == null) continue;
                lines.add("[" + safe(a.changedAt) + "] mode=" + safe(a.mode)
                        + " by=" + displayActor(a.changedBy));
                if (!safe(a.reason).trim().isBlank()) appendWrapped(lines, "Reason: " + safe(a.reason), 110);
            }
        }
        lines.add("");

        lines.add("Matter Task Snapshot (" + (matterTasks == null ? 0 : matterTasks.size()) + ")");
        if (matterTasks == null || matterTasks.isEmpty()) {
            lines.add("No tasks in this matter.");
        } else {
            for (int i = 0; i < matterTasks.size(); i++) {
                TaskRec r = matterTasks.get(i);
                if (r == null) continue;
                lines.add("- [" + safe(r.dueAt) + "] " + safe(r.title)
                        + " | status=" + safe(r.status)
                        + " | priority=" + safe(r.priority)
                        + " | estimate=" + r.estimateMinutes + "m"
                        + " | archived=" + (r.archived ? "yes" : "no"));
            }
        }

        try (PDDocument pdf = new PDDocument()) {
            writeLinesToPdf(pdf, lines);
            pdf.save(outputPdf.toFile());
        }
    }

    private static void writeLinesToPdf(PDDocument pdf, List<String> lines) throws Exception {
        if (pdf == null) throw new IllegalArgumentException("PDF document required.");

        final float margin = 48f;
        final float fontSize = 10f;
        final float leading = 14f;

        PDPage page = new PDPage(new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth()));
        pdf.addPage(page);

        float y = page.getMediaBox().getHeight() - margin;
        PDPageContentStream cs = new PDPageContentStream(pdf, page);
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, fontSize);
        cs.newLineAtOffset(margin, y);

        List<String> src = lines == null ? List.of() : lines;
        for (int i = 0; i < src.size(); i++) {
            String line = sanitizePdfText(src.get(i));

            if (y <= margin) {
                cs.endText();
                cs.close();

                page = new PDPage(new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth()));
                pdf.addPage(page);
                y = page.getMediaBox().getHeight() - margin;

                cs = new PDPageContentStream(pdf, page);
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, fontSize);
                cs.newLineAtOffset(margin, y);
            }

            cs.showText(line);
            cs.newLineAtOffset(0, -leading);
            y -= leading;
        }

        cs.endText();
        cs.close();
    }

    private static void appendWrapped(List<String> lines, String raw, int width) {
        if (lines == null) return;
        String text = safe(raw).replace('\r', ' ').replace('\n', ' ').trim();
        if (text.isBlank()) {
            lines.add("");
            return;
        }

        String[] words = text.split("\\s+");
        StringBuilder row = new StringBuilder(Math.max(width, 40));
        for (int i = 0; i < words.length; i++) {
            String w = safe(words[i]);
            if (w.isBlank()) continue;
            if (row.length() == 0) {
                row.append(w);
                continue;
            }
            if (row.length() + 1 + w.length() > Math.max(20, width)) {
                lines.add(row.toString());
                row.setLength(0);
                row.append(w);
            } else {
                row.append(' ').append(w);
            }
        }
        if (row.length() > 0) lines.add(row.toString());
    }

    private static String sanitizePdfText(String raw) {
        String s = safe(raw);
        StringBuilder out = new StringBuilder(Math.min(200, s.length()));
        int max = 175;
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                out.append(' ');
                continue;
            }
            if (ch < 32 || ch > 126) {
                out.append('?');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static List<TaskRec> readTasksLocked(String tenantUuid) throws Exception {
        ArrayList<TaskRec> out = new ArrayList<TaskRec>();
        Path p = tasksPath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("task");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            TaskRec rec = new TaskRec();
            rec.uuid = text(e, "uuid");
            if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();

            rec.matterUuid = text(e, "matter_uuid");
            rec.parentTaskUuid = text(e, "parent_task_uuid");
            rec.title = clampLen(text(e, "title"), MAX_TITLE_LEN).trim();
            rec.description = clampLen(text(e, "description"), MAX_BODY_LEN).trim();
            rec.status = canonicalStatus(text(e, "status"));
            rec.priority = canonicalPriority(text(e, "priority"));
            rec.assignmentMode = canonicalAssignmentMode(text(e, "assignment_mode"));
            rec.assignedUserUuid = normalizeAssignmentCsv(text(e, "assigned_user_uuid"));
            rec.dueAt = normalizeDueAtLenient(text(e, "due_at"));
            rec.reminderAt = normalizeReminderAt(text(e, "reminder_at"));
            rec.estimateMinutes = normalizeEstimateMinutesLenient(parseInt(text(e, "estimate_minutes"), 0));

            rec.claimUuid = text(e, "claim_uuid");
            rec.elementUuid = text(e, "element_uuid");
            rec.factUuid = text(e, "fact_uuid");

            rec.documentUuid = text(e, "document_uuid");
            rec.partUuid = text(e, "part_uuid");
            rec.versionUuid = text(e, "version_uuid");
            rec.pageNumber = Math.max(0, parseInt(text(e, "page_number"), 0));

            rec.threadUuid = text(e, "thread_uuid");

            rec.createdBy = text(e, "created_by");
            rec.createdAt = text(e, "created_at");
            rec.updatedAt = text(e, "updated_at");
            rec.completedAt = text(e, "completed_at");
            rec.archived = parseBool(text(e, "archived"), false);

            rec.reportDocumentUuid = text(e, "report_document_uuid");
            rec.reportPartUuid = text(e, "report_part_uuid");
            rec.lastReportVersionUuid = text(e, "last_report_version_uuid");

            if (rec.title.isBlank()) rec.title = "Task " + (i + 1);
            if (rec.dueAt.isBlank()) rec.dueAt = nowIso();
            if (rec.estimateMinutes <= 0) rec.estimateMinutes = 30;

            out.add(rec);
        }

        sortTasks(out);
        return out;
    }

    private static void writeTasksLocked(String tenantUuid, List<TaskRec> rows) throws Exception {
        Path p = tasksPath(tenantUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<tasks updated=\"").append(xmlAttr(nowIso())).append("\">\n");

        List<TaskRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            TaskRec rec = src.get(i);
            if (rec == null) continue;
            String id = safe(rec.uuid).trim();
            if (id.isBlank()) id = UUID.randomUUID().toString();

            sb.append("  <task>\n");
            writeTag(sb, "uuid", id);
            writeTag(sb, "matter_uuid", safe(rec.matterUuid).trim());
            writeTag(sb, "parent_task_uuid", safe(rec.parentTaskUuid).trim());
            writeTag(sb, "title", clampLen(rec.title, MAX_TITLE_LEN).trim());
            writeTag(sb, "description", clampLen(rec.description, MAX_BODY_LEN).trim());
            writeTag(sb, "status", canonicalStatus(rec.status));
            writeTag(sb, "priority", canonicalPriority(rec.priority));
            writeTag(sb, "assignment_mode", canonicalAssignmentMode(rec.assignmentMode));
            writeTag(sb, "assigned_user_uuid", normalizeAssignmentCsv(rec.assignedUserUuid));
            writeTag(sb, "due_at", normalizeDueAtLenient(rec.dueAt));
            writeTag(sb, "reminder_at", normalizeReminderAt(rec.reminderAt));
            writeTag(sb, "estimate_minutes", String.valueOf(Math.max(1, rec.estimateMinutes)));

            writeTag(sb, "claim_uuid", safe(rec.claimUuid).trim());
            writeTag(sb, "element_uuid", safe(rec.elementUuid).trim());
            writeTag(sb, "fact_uuid", safe(rec.factUuid).trim());

            writeTag(sb, "document_uuid", safe(rec.documentUuid).trim());
            writeTag(sb, "part_uuid", safe(rec.partUuid).trim());
            writeTag(sb, "version_uuid", safe(rec.versionUuid).trim());
            writeTag(sb, "page_number", String.valueOf(Math.max(0, rec.pageNumber)));

            writeTag(sb, "thread_uuid", safe(rec.threadUuid).trim());

            writeTag(sb, "created_by", safe(rec.createdBy).trim());
            writeTag(sb, "created_at", safe(rec.createdAt).trim());
            writeTag(sb, "updated_at", safe(rec.updatedAt).trim());
            writeTag(sb, "completed_at", safe(rec.completedAt).trim());
            writeTag(sb, "archived", rec.archived ? "true" : "false");

            writeTag(sb, "report_document_uuid", safe(rec.reportDocumentUuid).trim());
            writeTag(sb, "report_part_uuid", safe(rec.reportPartUuid).trim());
            writeTag(sb, "last_report_version_uuid", safe(rec.lastReportVersionUuid).trim());
            sb.append("  </task>\n");
        }

        sb.append("</tasks>\n");
        writeAtomic(p, sb.toString());
    }

    private static List<NoteRec> readNotesLocked(String tenantUuid, String taskUuid) throws Exception {
        ArrayList<NoteRec> out = new ArrayList<NoteRec>();
        Path p = notesPath(tenantUuid, taskUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("note");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            NoteRec rec = new NoteRec();
            rec.uuid = text(e, "uuid");
            if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();
            rec.taskUuid = safe(taskUuid).trim();
            rec.body = clampLen(text(e, "body"), MAX_NOTE_LEN).trim();
            rec.createdBy = text(e, "created_by");
            rec.createdAt = text(e, "created_at");
            out.add(rec);
        }

        sortNotes(out);
        return out;
    }

    private static void writeNotesLocked(String tenantUuid, String taskUuid, List<NoteRec> rows) throws Exception {
        Path p = notesPath(tenantUuid, taskUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<notes task_uuid=\"").append(xmlAttr(taskUuid)).append("\" updated=\"").append(xmlAttr(nowIso())).append("\">\n");

        List<NoteRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            NoteRec rec = src.get(i);
            if (rec == null) continue;
            String id = safe(rec.uuid).trim();
            if (id.isBlank()) id = UUID.randomUUID().toString();

            sb.append("  <note>\n");
            writeTag(sb, "uuid", id);
            writeTag(sb, "body", clampLen(rec.body, MAX_NOTE_LEN).trim());
            writeTag(sb, "created_by", safe(rec.createdBy).trim());
            writeTag(sb, "created_at", safe(rec.createdAt).trim());
            sb.append("  </note>\n");
        }

        sb.append("</notes>\n");
        writeAtomic(p, sb.toString());
    }

    private static List<AssignmentRec> readAssignmentsLocked(String tenantUuid, String taskUuid) throws Exception {
        ArrayList<AssignmentRec> out = new ArrayList<AssignmentRec>();
        Path p = assignmentsPath(tenantUuid, taskUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("assignment");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            AssignmentRec rec = new AssignmentRec();
            rec.uuid = text(e, "uuid");
            if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();
            rec.taskUuid = safe(taskUuid).trim();
            rec.mode = canonicalAssignmentMode(text(e, "mode"));
            rec.fromUserUuid = normalizeAssignmentCsv(text(e, "from_user_uuid"));
            rec.toUserUuid = normalizeAssignmentCsv(text(e, "to_user_uuid"));
            rec.reason = clampLen(text(e, "reason"), MAX_REASON_LEN).trim();
            rec.changedBy = text(e, "changed_by");
            rec.changedAt = text(e, "changed_at");
            out.add(rec);
        }

        sortAssignments(out);
        return out;
    }

    private static void writeAssignmentsLocked(String tenantUuid, String taskUuid, List<AssignmentRec> rows) throws Exception {
        Path p = assignmentsPath(tenantUuid, taskUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<assignments task_uuid=\"").append(xmlAttr(taskUuid)).append("\" updated=\"").append(xmlAttr(nowIso())).append("\">\n");

        List<AssignmentRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            AssignmentRec rec = src.get(i);
            if (rec == null) continue;
            String id = safe(rec.uuid).trim();
            if (id.isBlank()) id = UUID.randomUUID().toString();

            sb.append("  <assignment>\n");
            writeTag(sb, "uuid", id);
            writeTag(sb, "mode", canonicalAssignmentMode(rec.mode));
            writeTag(sb, "from_user_uuid", normalizeAssignmentCsv(rec.fromUserUuid));
            writeTag(sb, "to_user_uuid", normalizeAssignmentCsv(rec.toUserUuid));
            writeTag(sb, "reason", clampLen(rec.reason, MAX_REASON_LEN).trim());
            writeTag(sb, "changed_by", safe(rec.changedBy).trim());
            writeTag(sb, "changed_at", safe(rec.changedAt).trim());
            sb.append("  </assignment>\n");
        }

        sb.append("</assignments>\n");
        writeAtomic(p, sb.toString());
    }

    private static Map<String, Integer> readRoundRobinStateLocked(String tenantUuid) throws Exception {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        Path p = roundRobinStatePath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("cursor");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String key = safe(e.getAttribute("key")).trim();
            if (key.isBlank()) continue;
            int v = parseInt(safe(e.getTextContent()), -1);
            out.put(key, Integer.valueOf(v));
        }
        return out;
    }

    private static void writeRoundRobinStateLocked(String tenantUuid, Map<String, Integer> state) throws Exception {
        Path p = roundRobinStatePath(tenantUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<roundRobinState updated=\"").append(xmlAttr(nowIso())).append("\">\n");

        if (state != null && !state.isEmpty()) {
            List<String> keys = new ArrayList<String>(state.keySet());
            keys.sort(String::compareToIgnoreCase);
            for (int i = 0; i < keys.size(); i++) {
                String key = safe(keys.get(i)).trim();
                if (key.isBlank()) continue;
                Integer v = state.get(key);
                int idx = v == null ? -1 : v.intValue();
                sb.append("  <cursor key=\"").append(xmlAttr(key)).append("\">")
                        .append(idx)
                        .append("</cursor>\n");
            }
        }

        sb.append("</roundRobinState>\n");
        writeAtomic(p, sb.toString());
    }

    private static TaskRec findTaskByUuid(List<TaskRec> rows, String taskUuid) {
        List<TaskRec> src = rows == null ? List.of() : rows;
        String id = safe(taskUuid).trim();
        if (id.isBlank()) return null;
        for (int i = 0; i < src.size(); i++) {
            TaskRec rec = src.get(i);
            if (rec == null) continue;
            if (id.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    private static void replaceTaskByUuid(List<TaskRec> rows, TaskRec replacement) {
        if (rows == null || replacement == null) return;
        String id = safe(replacement.uuid).trim();
        if (id.isBlank()) return;
        for (int i = 0; i < rows.size(); i++) {
            TaskRec rec = rows.get(i);
            if (rec == null) continue;
            if (!id.equals(safe(rec.uuid).trim())) continue;
            rows.set(i, replacement);
            return;
        }
    }

    private static void sortTasks(List<TaskRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<TaskRec>() {
            @Override
            public int compare(TaskRec a, TaskRec b) {
                int aa = a != null && a.archived ? 1 : 0;
                int bb = b != null && b.archived ? 1 : 0;
                if (aa != bb) return Integer.compare(aa, bb);

                String ad = safe(a == null ? "" : a.dueAt);
                String bd = safe(b == null ? "" : b.dueAt);
                int byDue = ad.compareToIgnoreCase(bd);
                if (byDue != 0) return byDue;

                int ap = priorityWeight(a == null ? "" : a.priority);
                int bp = priorityWeight(b == null ? "" : b.priority);
                if (ap != bp) return Integer.compare(bp, ap);

                String at = safe(a == null ? "" : a.title);
                String bt = safe(b == null ? "" : b.title);
                int byTitle = at.compareToIgnoreCase(bt);
                if (byTitle != 0) return byTitle;

                String au = safe(a == null ? "" : a.uuid);
                String bu = safe(b == null ? "" : b.uuid);
                return au.compareToIgnoreCase(bu);
            }
        });
    }

    private static void sortNotes(List<NoteRec> rows) {
        if (rows == null) return;
        rows.sort(Comparator.comparing((NoteRec r) -> safe(r.createdAt)));
    }

    private static void sortAssignments(List<AssignmentRec> rows) {
        if (rows == null) return;
        rows.sort(Comparator.comparing((AssignmentRec r) -> safe(r.changedAt)));
    }

    private static int priorityWeight(String priority) {
        String p = canonicalPriority(priority);
        if (PRIORITY_URGENT.equals(p)) return 4;
        if (PRIORITY_HIGH.equals(p)) return 3;
        if (PRIORITY_NORMAL.equals(p)) return 2;
        return 1;
    }

    private static String mapDocumentStatus(String taskStatus) {
        String s = canonicalStatus(taskStatus);
        if (STATUS_COMPLETED.equals(s)) return "final";
        if (STATUS_CANCELLED.equals(s)) return "archived";
        if (STATUS_BLOCKED.equals(s)) return "on_hold";
        if (STATUS_WAITING_REVIEW.equals(s)) return "in_review";
        return "draft";
    }

    private static String canonicalStatus(String raw) {
        String s = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (STATUS_OPEN.equals(s)) return STATUS_OPEN;
        if (STATUS_IN_PROGRESS.equals(s)) return STATUS_IN_PROGRESS;
        if (STATUS_BLOCKED.equals(s)) return STATUS_BLOCKED;
        if (STATUS_WAITING_REVIEW.equals(s)) return STATUS_WAITING_REVIEW;
        if (STATUS_COMPLETED.equals(s)) return STATUS_COMPLETED;
        if (STATUS_CANCELLED.equals(s)) return STATUS_CANCELLED;
        return STATUS_OPEN;
    }

    private static boolean isDoneStatus(String status) {
        String s = canonicalStatus(status);
        return STATUS_COMPLETED.equals(s) || STATUS_CANCELLED.equals(s);
    }

    private static String canonicalPriority(String raw) {
        String s = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (PRIORITY_LOW.equals(s)) return PRIORITY_LOW;
        if (PRIORITY_NORMAL.equals(s)) return PRIORITY_NORMAL;
        if (PRIORITY_HIGH.equals(s)) return PRIORITY_HIGH;
        if (PRIORITY_URGENT.equals(s)) return PRIORITY_URGENT;
        return PRIORITY_NORMAL;
    }

    private static String canonicalAssignmentMode(String raw) {
        String s = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (MODE_ROUND_ROBIN.equals(s)) return MODE_ROUND_ROBIN;
        return MODE_MANUAL;
    }

    private static String normalizeAssignmentCsv(String raw) {
        String[] pieces = safe(raw).replace(';', ',').split(",");
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        for (int i = 0; i < pieces.length; i++) {
            String id = safe(pieces[i]).trim();
            if (id.isBlank()) continue;
            unique.add(id);
        }
        return String.join(",", unique);
    }

    private static String normalizeDueAt(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) throw new IllegalArgumentException("due_at is required.");
        if (!s.contains("T")) throw new IllegalArgumentException("due_at must include date and time.");
        parseDateTimeStrict(s);
        return s;
    }

    private static String normalizeDueAtLenient(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) return "";
        if (!s.contains("T")) return "";
        try {
            parseDateTimeStrict(s);
            return s;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeReminderAt(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) return "";
        if (!s.contains("T")) throw new IllegalArgumentException("reminder_at must include date and time.");
        parseDateTimeStrict(s);
        return s;
    }

    private static int normalizeEstimateMinutes(int value) {
        if (value <= 0) throw new IllegalArgumentException("estimate_minutes must be greater than zero.");
        if (value > 100000) return 100000;
        return value;
    }

    private static int normalizeEstimateMinutesLenient(int value) {
        if (value <= 0) return 30;
        if (value > 100000) return 100000;
        return value;
    }

    private static void parseDateTimeStrict(String value) {
        String s = safe(value).trim();
        if (s.isBlank()) throw new IllegalArgumentException("datetime required");
        try {
            Instant.parse(s);
            return;
        } catch (DateTimeParseException ignored) {
        }
        try {
            OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return;
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return;
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException("datetime must be ISO-8601 with date and time.");
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, k -> new ReentrantReadWriteLock());
    }

    private static Path tasksRoot(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid, "tasks").toAbsolutePath();
    }

    private static Path tasksPath(String tenantUuid) {
        return tasksRoot(tenantUuid).resolve("tasks.xml").toAbsolutePath();
    }

    private static Path roundRobinStatePath(String tenantUuid) {
        return tasksRoot(tenantUuid).resolve("round_robin_state.xml").toAbsolutePath();
    }

    private static Path tasksDir(String tenantUuid) {
        return tasksRoot(tenantUuid).resolve("tasks").toAbsolutePath();
    }

    private static Path taskDir(String tenantUuid, String taskUuid) {
        return tasksDir(tenantUuid).resolve(safeToken(taskUuid)).toAbsolutePath();
    }

    private static Path notesPath(String tenantUuid, String taskUuid) {
        return taskDir(tenantUuid, taskUuid).resolve("notes.xml").toAbsolutePath();
    }

    private static Path assignmentsPath(String tenantUuid, String taskUuid) {
        return taskDir(tenantUuid, taskUuid).resolve("assignments.xml").toAbsolutePath();
    }

    private static String safeToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (int i = 0; i < values.length; i++) {
            String v = safe(values[i]).trim();
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static String displayActor(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return "";
        if (v.contains("@")) return v;
        return "Account user";
    }

    private static String clampLen(String s, int max) {
        String v = safe(s);
        if (v.length() > max) return v.substring(0, max);
        return v;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean parseBool(String raw, boolean fallback) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return fallback;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v);
    }

    private static String nowIso() {
        return app_clock.now().toString();
    }

    private static Document parseXml(Path p) throws Exception {
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

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl == null || nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n == null ? "" : safe(n.getTextContent()).trim();
    }

    private static void writeTag(StringBuilder sb, String tag, String value) {
        sb.append("    <").append(tag).append(">")
                .append(xmlText(value))
                .append("</").append(tag).append(">\n");
    }

    private static String xmlText(String s) {
        String v = safe(s);
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String xmlAttr(String s) {
        return xmlText(s);
    }

    private static void writeAtomic(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(tmp, safe(content), java.nio.charset.StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String taskMentionText(TaskRec task) {
        if (task == null) return "";
        return (safe(task.title).trim() + "\n" + safe(task.description).trim()).trim();
    }

    private static void emitTaskMentions(String tenantUuid,
                                         String actor,
                                         TaskRec task,
                                         String text,
                                         Map<String, String> extras) {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank() || task == null) return;
        String body = safe(text).trim();
        if (body.isBlank()) return;

        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("source_type", "task");
        details.put("source_uuid", safe(task.uuid));
        details.put("source_title", firstNonBlank(safe(task.title), "Task"));
        details.put("source_path", "/tasks.jsp?task_uuid=" + safe(task.uuid));
        details.put("task_uuid", safe(task.uuid));
        details.put("matter_uuid", safe(task.matterUuid));
        details.put("due_at", safe(task.dueAt));
        if (extras != null && !extras.isEmpty()) details.putAll(extras);
        try {
            mentions.defaultService().logMentions(
                    tu,
                    safe(actor).trim(),
                    safe(task.matterUuid),
                    body,
                    details
            );
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Task mention logging skipped: " + safe(ex.getMessage()), ex);
        }
    }

    private static void audit(String action,
                              String tenantUuid,
                              String actor,
                              String matterUuid,
                              Map<String, String> details) {
        try {
            activity_log.defaultStore().logVerbose(
                    safe(action),
                    safe(tenantUuid),
                    safe(actor),
                    safe(matterUuid),
                    "",
                    details == null ? Map.of() : details
            );
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Task audit log failure: " + safe(ex.getMessage()), ex);
        }
    }

    private static void publishTaskEvent(String tenantUuid,
                                         String eventType,
                                         TaskRec task,
                                         Map<String, String> extras) {
        try {
            LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
            payload.put("tenant_uuid", safe(tenantUuid));
            payload.put("task_uuid", safe(task == null ? "" : task.uuid));
            payload.put("matter_uuid", safe(task == null ? "" : task.matterUuid));
            payload.put("status", safe(task == null ? "" : task.status));
            payload.put("priority", safe(task == null ? "" : task.priority));
            payload.put("assigned_user_uuid", safe(task == null ? "" : task.assignedUserUuid));
            payload.put("assignment_mode", safe(task == null ? "" : task.assignmentMode));
            payload.put("due_at", safe(task == null ? "" : task.dueAt));
            payload.put("estimate_minutes", String.valueOf(task == null ? 0 : task.estimateMinutes));
            payload.put("parent_task_uuid", safe(task == null ? "" : task.parentTaskUuid));
            payload.put("claim_uuid", safe(task == null ? "" : task.claimUuid));
            payload.put("element_uuid", safe(task == null ? "" : task.elementUuid));
            payload.put("fact_uuid", safe(task == null ? "" : task.factUuid));
            payload.put("document_uuid", safe(task == null ? "" : task.documentUuid));
            payload.put("part_uuid", safe(task == null ? "" : task.partUuid));
            payload.put("version_uuid", safe(task == null ? "" : task.versionUuid));
            payload.put("page_number", String.valueOf(task == null ? 0 : task.pageNumber));
            payload.put("thread_uuid", safe(task == null ? "" : task.threadUuid));
            payload.put("archived", (task != null && task.archived) ? "true" : "false");
            payload.put("report_document_uuid", safe(task == null ? "" : task.reportDocumentUuid));
            payload.put("report_part_uuid", safe(task == null ? "" : task.reportPartUuid));
            payload.put("last_report_version_uuid", safe(task == null ? "" : task.lastReportVersionUuid));
            if (extras != null && !extras.isEmpty()) payload.putAll(extras);

            business_process_manager.defaultService().triggerEvent(
                    safe(tenantUuid),
                    safe(eventType).trim().toLowerCase(Locale.ROOT),
                    payload,
                    safe(task == null ? "" : task.createdBy),
                    "tasks." + safe(eventType)
            );
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Task BPM publish skipped: " + safe(ex.getMessage()), ex);
        }
    }
}
