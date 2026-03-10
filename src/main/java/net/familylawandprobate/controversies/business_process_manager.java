package net.familylawandprobate.controversies;

import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Tenant-scoped business process manager.
 *
 * Process definitions are persisted one-per-file:
 * data/tenants/{tenantUuid}/bpm/processes/{processUuid}.xml
 */
public final class business_process_manager {

    private static final Logger LOG = Logger.getLogger(business_process_manager.class.getName());
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final String DEFAULT_REVIEW_QUEUE_PERMISSION = "business_process_reviews.manage";

    private interface StepHandler {
        StepOutcome handle(ExecutionContext ctx, ProcessStep step, LinkedHashMap<String, String> context) throws Exception;
    }

    private static final class Holder {
        private static final business_process_manager INSTANCE = new business_process_manager();
    }

    public static business_process_manager defaultService() {
        return Holder.INSTANCE;
    }

    public static final class ProcessDefinition {
        public String processUuid = "";
        public String name = "";
        public String description = "";
        public boolean enabled = true;
        public String updatedAt = "";
        public ArrayList<ProcessTrigger> triggers = new ArrayList<ProcessTrigger>();
        public ArrayList<ProcessStep> steps = new ArrayList<ProcessStep>();
    }

    public static final class ProcessTrigger {
        public String type = "";
        public String key = "";
        public String op = "equals";
        public String value = "";
        public boolean enabled = true;
    }

    public static final class ProcessCondition {
        public String source = "event";
        public String key = "";
        public String op = "equals";
        public String value = "";
    }

    public static final class ProcessStep {
        public String stepId = "";
        public int order = 10;
        public String action = "";
        public String label = "";
        public boolean enabled = true;
        public LinkedHashMap<String, String> settings = new LinkedHashMap<String, String>();
        public ArrayList<ProcessCondition> conditions = new ArrayList<ProcessCondition>();
    }

    public static final class RunResult {
        public String runUuid = "";
        public String processUuid = "";
        public String processName = "";
        public String eventType = "";
        public String status = "";
        public String startedAt = "";
        public String completedAt = "";
        public String message = "";
        public String humanReviewUuid = "";
        public int stepCount = 0;
        public int stepsCompleted = 0;
        public int stepsSkipped = 0;
        public int errors = 0;
        public String undoState = "active"; // active, undone, undo_partial, redo_partial
    }

    public static final class RunHealthIssue {
        public String runUuid = "";
        public String processUuid = "";
        public String processName = "";
        public String status = "";
        public String startedAt = "";
        public String completedAt = "";
        public String humanReviewUuid = "";
        public String issueType = "";
        public String severity = "";
        public String summary = "";
        public String recommendation = "";
        public long ageMinutes = -1L;
        public boolean canMarkFailed = false;
        public boolean canRetry = false;
        public boolean canResolveReview = false;
    }

    public static final class AgingAlarmEmailResult {
        public int detectedIssues = 0;
        public int newlyAlarmedIssues = 0;
        public int emailsQueued = 0;
        public String emailUuid = "";
        public String emailError = "";
        public ArrayList<String> recipients = new ArrayList<String>();
    }

    public static final class HumanReviewTask {
        public String reviewUuid = "";
        public String reviewType = "human_review";
        public String processUuid = "";
        public String processName = "";
        public String requestRunUuid = "";
        public String resumedRunUuid = "";
        public String eventType = "";
        public String status = "pending"; // pending, approved, rejected, error
        public String title = "";
        public String instructions = "";
        public String comment = "";
        public String requestedByUserUuid = "";
        public String reviewedByUserUuid = "";
        public String assigneeUserUuid = "";
        public String assigneeRoleUuid = "";
        public String queuePermissionKey = "";
        public String dueAt = "";
        public String createdAt = "";
        public String resolvedAt = "";
        public int nextStepOrder = 0;
        public String resumeStatus = "";
        public String resumeMessage = "";
        public LinkedHashMap<String, String> context = new LinkedHashMap<String, String>();
        public LinkedHashMap<String, String> input = new LinkedHashMap<String, String>();
        public ArrayList<String> requiredInputKeys = new ArrayList<String>();
    }

    public static final class RunJournalEntry {
        public String createdAt = "";
        public String stepId = "";
        public String action = "";
        public String description = "";
        public boolean reversible = false;
        public LinkedHashMap<String, String> undo = new LinkedHashMap<String, String>();
        public LinkedHashMap<String, String> redo = new LinkedHashMap<String, String>();
    }

    private static final class RunLogEntry {
        final String time;
        final String level;
        final String code;
        final String stepId;
        final String message;

        RunLogEntry(String time, String level, String code, String stepId, String message) {
            this.time = safe(time);
            this.level = safe(level);
            this.code = safe(code);
            this.stepId = safe(stepId);
            this.message = safe(message);
        }
    }

    private static final class StepOutcome {
        static final String CONTINUE = "continue";
        static final String WAITING_HUMAN = "waiting_human";

        String kind = CONTINUE;
        String message = "";
        String humanReviewUuid = "";
        RunJournalEntry journal;

        static StepOutcome continueWithMessage(String message) {
            StepOutcome out = new StepOutcome();
            out.kind = CONTINUE;
            out.message = safe(message);
            return out;
        }

        static StepOutcome continueWithJournal(String message, RunJournalEntry journal) {
            StepOutcome out = continueWithMessage(message);
            out.journal = journal;
            return out;
        }

        static StepOutcome waitingHuman(String reviewUuid, String message) {
            StepOutcome out = new StepOutcome();
            out.kind = WAITING_HUMAN;
            out.humanReviewUuid = safe(reviewUuid);
            out.message = safe(message);
            return out;
        }
    }

    private static final class ExecutionContext {
        final String tenantUuid;
        final ProcessDefinition process;
        final String eventType;
        final String actorUserUuid;
        final String source;
        final String runUuid;
        final int recursionDepth;
        final ArrayList<ProcessStep> sortedSteps;

        ExecutionContext(String tenantUuid,
                         ProcessDefinition process,
                         String eventType,
                         String actorUserUuid,
                         String source,
                         String runUuid,
                         int recursionDepth,
                         ArrayList<ProcessStep> sortedSteps) {
            this.tenantUuid = safe(tenantUuid);
            this.process = process;
            this.eventType = safe(eventType);
            this.actorUserUuid = safe(actorUserUuid);
            this.source = safe(source);
            this.runUuid = safe(runUuid);
            this.recursionDepth = recursionDepth;
            this.sortedSteps = sortedSteps;
        }
    }

    private static final class RunSnapshot {
        RunResult result = new RunResult();
        LinkedHashMap<String, String> context = new LinkedHashMap<String, String>();
        ArrayList<RunLogEntry> logs = new ArrayList<RunLogEntry>();
        ArrayList<RunJournalEntry> journal = new ArrayList<RunJournalEntry>();
    }

    private final LinkedHashMap<String, StepHandler> handlers = new LinkedHashMap<String, StepHandler>();

    private business_process_manager() {
        registerDefaultHandlers();
    }

    public void ensureTenant(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Files.createDirectories(processesDir(tu));
            Files.createDirectories(runsDir(tu));
            Path reviewPath = reviewsPath(tu);
            Files.createDirectories(reviewPath.getParent());
            if (!Files.exists(reviewPath)) writeAtomic(reviewPath, emptyReviewsXml());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ProcessDefinition> listProcesses(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensureTenant(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ArrayList<ProcessDefinition> out = new ArrayList<ProcessDefinition>();
            Path dir = processesDir(tu);
            if (!Files.isDirectory(dir)) return out;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.xml")) {
                for (Path p : ds) {
                    ProcessDefinition d = readProcessFile(p);
                    if (d != null) out.add(d);
                }
            }
            sortProcesses(out);
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public ProcessDefinition getProcess(String tenantUuid, String processUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safeFileToken(processUuid);
        if (tu.isBlank() || pu.isBlank()) return null;
        ensureTenant(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            Path p = processPath(tu, pu);
            if (!Files.exists(p)) return null;
            return readProcessFile(p);
        } finally {
            lock.readLock().unlock();
        }
    }

    public ProcessDefinition saveProcess(String tenantUuid, ProcessDefinition incoming) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        ensureTenant(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ProcessDefinition clean = sanitizeProcess(incoming);
            if (clean.processUuid.isBlank()) clean.processUuid = UUID.randomUUID().toString();
            clean.updatedAt = nowIso();
            Path p = processPath(tu, clean.processUuid);
            writeAtomic(p, processToXml(clean));

            activity_log.defaultStore().logVerbose(
                    "bpm.process.saved",
                    tu,
                    "",
                    "",
                    "",
                    Map.of(
                            "process_uuid", clean.processUuid,
                            "process_name", clean.name,
                            "trigger_count", String.valueOf(clean.triggers.size()),
                            "step_count", String.valueOf(clean.steps.size())
                    )
            );
            return clean;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean deleteProcess(String tenantUuid, String processUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safeFileToken(processUuid);
        if (tu.isBlank() || pu.isBlank()) return false;
        ensureTenant(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path p = processPath(tu, pu);
            if (!Files.exists(p)) return false;
            Files.deleteIfExists(p);
            activity_log.defaultStore().logWarning(
                    "bpm.process.deleted",
                    tu,
                    "",
                    "",
                    "",
                    Map.of("process_uuid", pu)
            );
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<RunResult> triggerEvent(String tenantUuid,
                                        String eventType,
                                        Map<String, String> payload,
                                        String actorUserUuid,
                                        String source) throws Exception {
        return triggerEventInternal(tenantUuid, eventType, payload, actorUserUuid, source, 0);
    }

    public RunResult triggerProcess(String tenantUuid,
                                    String processUuid,
                                    String eventType,
                                    Map<String, String> payload,
                                    String actorUserUuid,
                                    String source) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        String pu = safeFileToken(processUuid);
        if (pu.isBlank()) throw new IllegalArgumentException("processUuid required");

        ProcessDefinition d = getProcess(tu, pu);
        if (d == null) throw new IllegalArgumentException("Process not found.");
        if (!d.enabled) throw new IllegalArgumentException("Process is disabled.");

        String evt = safe(eventType).trim();
        if (evt.isBlank()) evt = "manual";

        return executeProcess(
                tu,
                d,
                evt,
                sanitizeMap(payload),
                safe(actorUserUuid),
                safe(source),
                0,
                0,
                new LinkedHashMap<String, String>()
        );
    }

    public List<HumanReviewTask> listReviews(String tenantUuid, boolean pendingOnly, int limit) throws Exception {
        return listReviewsForUser(tenantUuid, "", pendingOnly, limit);
    }

    public List<HumanReviewTask> listReviewsForUser(String tenantUuid,
                                                    String viewerUserUuid,
                                                    boolean pendingOnly,
                                                    int limit) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensureTenant(tu);

        int max = Math.max(1, Math.min(1000, limit));
        String viewer = safe(viewerUserUuid).trim();
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<HumanReviewTask> all = readReviewsLocked(tu);
            ArrayList<HumanReviewTask> out = new ArrayList<HumanReviewTask>();
            for (HumanReviewTask t : all) {
                if (t == null) continue;
                if (pendingOnly && !"pending".equalsIgnoreCase(safe(t.status))) continue;
                if (!viewer.isBlank() && !canUserAccessReview(tu, t, viewer)) continue;
                out.add(t);
            }
            out.sort(Comparator.comparing((HumanReviewTask r) -> safe(r.createdAt)).reversed());
            if (out.size() > max) return new ArrayList<HumanReviewTask>(out.subList(0, max));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<LinkedHashMap<String, Object>> listBuiltInActions() {
        ArrayList<LinkedHashMap<String, Object>> out = new ArrayList<LinkedHashMap<String, Object>>();
        out.add(actionDescriptor(
                "log_note",
                "Write an activity log entry.",
                false,
                List.of(),
                List.of("level", "action", "message")
        ));
        out.add(actionDescriptor(
                "set_case_field",
                "Set one matter/case field value.",
                true,
                List.of("matter_uuid", "field_key", "field_value"),
                List.of("on_error")
        ));
        out.add(actionDescriptor(
                "set_case_list_item",
                "Set one case list/grid XML value.",
                true,
                List.of("matter_uuid", "list_key", "list_value"),
                List.of("on_error")
        ));
        out.add(actionDescriptor(
                "set_document_field",
                "Set one document field value.",
                true,
                List.of("matter_uuid", "doc_uuid", "field_key", "field_value"),
                List.of("on_error")
        ));
        out.add(actionDescriptor(
                "set_tenant_field",
                "Set one tenant field value.",
                true,
                List.of("field_key", "field_value"),
                List.of("on_error")
        ));
        out.add(actionDescriptor(
                "set_task_field",
                "Set one task custom-field value.",
                true,
                List.of("task_uuid", "field_key", "field_value"),
                List.of("on_error")
        ));
        out.add(actionDescriptor(
                "set_custom_object_record_field",
                "Set one custom object record field value.",
                true,
                List.of("object_uuid", "record_uuid", "field_key", "field_value"),
                List.of("on_error")
        ));
        out.add(actionDescriptor(
                "update_custom_object_record",
                "Update one custom object record (label/values/trashed).",
                false,
                List.of("object_uuid", "record_uuid"),
                List.of("label", "replace_values", "value.*", "values.*", "trashed", "on_error")
        ));
        out.add(actionDescriptor(
                "set_variable",
                "Set a process variable for later steps.",
                false,
                List.of("name", "value"),
                List.of("on_error")
        ));
        out.add(actionDescriptor(
                "trigger_event",
                "Trigger additional BPM processes by event type.",
                false,
                List.of("event_type"),
                List.of("payload.*", "on_error")
        ));
        out.add(actionDescriptor(
                "send_webhook",
                "Send outbound webhook HTTP request.",
                false,
                List.of("url"),
                List.of("method", "content_type", "body", "timeout_ms", "header.*", "payload.*", "on_error")
        ));
        out.add(actionDescriptor(
                "update_thread",
                "Update an omnichannel thread.",
                false,
                List.of("thread_uuid"),
                List.of("status", "priority", "assigned_user_uuid", "assignment_mode", "subject", "reminder_at", "due_at", "on_error")
        ));
        out.add(actionDescriptor(
                "add_thread_note",
                "Add an internal note to an omnichannel thread.",
                false,
                List.of("thread_uuid", "body"),
                List.of("actor_user_uuid", "on_error")
        ));
        out.add(actionDescriptor(
                "update_task",
                "Update a task record.",
                false,
                List.of("task_uuid"),
                List.of("matter_uuid", "parent_task_uuid", "title", "description", "status", "priority",
                        "assignment_mode", "assigned_user_uuid", "due_at", "reminder_at", "estimate_minutes",
                        "claim_uuid", "element_uuid", "fact_uuid", "document_uuid", "part_uuid", "version_uuid",
                        "page_number", "thread_uuid", "archived", "assignment_reason", "actor_user_uuid", "on_error")
        ));
        out.add(actionDescriptor(
                "add_task_note",
                "Add an internal note to a task.",
                false,
                List.of("task_uuid", "body"),
                List.of("actor_user_uuid", "on_error")
        ));
        out.add(actionDescriptor(
                "update_fact",
                "Update a matter fact record.",
                false,
                List.of("matter_uuid", "fact_uuid"),
                List.of("claim_uuid", "element_uuid", "summary", "detail", "internal_notes", "status", "strength",
                        "document_uuid", "part_uuid", "version_uuid", "page_number", "sort_order",
                        "actor_user_uuid", "on_error")
        ));
        out.add(actionDescriptor(
                "refresh_facts_report",
                "Regenerate landscape facts PDF report for a matter.",
                false,
                List.of("matter_uuid"),
                List.of("actor_user_uuid", "on_error")
        ));
        out.add(actionDescriptor(
                "document_assembly",
                "Assemble a template with matter data and store it in assembled forms history.",
                false,
                List.of("matter_uuid", "template_uuid"),
                List.of("template_label", "output_file_name", "assembly_uuid", "user_uuid", "user_email",
                        "value.*", "values.*", "override.*", "overrides.*", "on_error")
        ));
        out.add(actionDescriptor(
                "notice_communication",
                "Queue or send an outbound legal notice email.",
                false,
                List.of("to", "subject"),
                List.of("body_text", "body_html", "cc", "bcc", "from_address", "from_name", "reply_to",
                        "matter_uuid", "delivery_mode", "actor_user_uuid", "on_error")
        ));
        out.add(actionDescriptor(
                "send_for_signature",
                "Send signature notice and optionally pause for a confirmation review.",
                false,
                List.of("to", "subject"),
                List.of("body_text", "body_html", "cc", "bcc", "from_address", "from_name", "reply_to",
                        "matter_uuid", "document_uuid", "part_uuid", "version_uuid",
                        "provider_key", "provider_request_id",
                        "signature_link", "delivery_mode", "wait_for_confirmation",
                        "confirmation_title", "confirmation_instructions", "required_input_keys",
                        "assignee_user_uuid", "assignee_role_uuid", "queue_permission_key", "due_at", "on_error")
        ));
        out.add(actionDescriptor(
                "conflict_check",
                "Run a simple conflict scan over linked parties/contacts and branch on potential matches.",
                false,
                List.of("matter_uuid"),
                List.of("party_names", "on_potential", "review_title", "review_instructions", "required_input_keys",
                        "assignee_user_uuid", "assignee_role_uuid", "queue_permission_key", "due_at", "on_error")
        ));
        out.add(actionDescriptor(
                "deadline_rule_calculation",
                "Compute a legal deadline from a trigger date and day offset.",
                false,
                List.of("days_offset"),
                List.of("trigger_date", "business_days", "store_as", "matter_uuid", "field_key", "on_error")
        ));
        out.add(actionDescriptor(
                "court_filing_service",
                "Create a filing/service task and optionally require human confirmation.",
                false,
                List.of("matter_uuid", "title"),
                List.of("description", "due_at", "estimate_minutes", "priority", "status",
                        "assignment_mode", "assigned_user_uuid", "assignment_reason", "actor_user_uuid",
                        "require_review", "review_title", "review_instructions", "required_input_keys",
                        "assignee_user_uuid", "assignee_role_uuid", "queue_permission_key", "due_at", "on_error")
        ));
        out.add(actionDescriptor(
                "payment_fee_verification",
                "Verify required vs paid filing/payment amounts and branch on insufficiency.",
                false,
                List.of("required_amount"),
                List.of("paid_amount", "matter_uuid", "required_amount_field_key", "paid_amount_field_key",
                        "currency", "on_insufficient", "review_title", "review_instructions", "required_input_keys",
                        "assignee_user_uuid", "assignee_role_uuid", "queue_permission_key", "due_at", "on_error")
        ));
        out.add(actionDescriptor(
                "wait_for_external_event",
                "Pause processing until a human confirms an external event outcome.",
                false,
                List.of("title"),
                List.of("instructions", "required_input_keys", "assignee_user_uuid", "assignee_role_uuid",
                        "queue_permission_key", "due_at", "on_error")
        ));
        out.add(actionDescriptor(
                "request_information",
                "Pause for a structured information request with required response fields.",
                false,
                List.of("title"),
                List.of("instructions", "request_target", "required_input_keys", "assignee_user_uuid",
                        "assignee_role_uuid", "queue_permission_key", "due_at", "on_error")
        ));
        out.add(actionDescriptor(
                "escalation",
                "Create an escalation task and optional notice for urgent legal follow-up.",
                false,
                List.of("matter_uuid", "title"),
                List.of("description", "due_at", "estimate_minutes", "priority", "status", "assignment_mode",
                        "assigned_user_uuid", "assignment_reason", "actor_user_uuid", "notify_to",
                        "notify_subject", "notify_body_text", "notify_body_html", "on_error")
        ));
        out.add(actionDescriptor(
                "audit_snapshot",
                "Write a hashed context snapshot to activity logs for defensibility/audit trails.",
                false,
                List.of(),
                List.of("action", "message", "include_keys", "include_prefixes", "output_var", "on_error")
        ));
        out.add(actionDescriptor(
                "human_review",
                "Pause run and request human approval/input.",
                false,
                List.of("title"),
                List.of("instructions", "required_input_keys", "review_type", "assignee_user_uuid",
                        "assignee_role_uuid", "queue_permission_key", "due_at", "on_error")
        ));
        return out;
    }

    public HumanReviewTask completeReview(String tenantUuid,
                                          String reviewUuid,
                                          boolean approved,
                                          String reviewedByUserUuid,
                                          Map<String, String> input,
                                          String comment) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ru = safeFileToken(reviewUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (ru.isBlank()) throw new IllegalArgumentException("reviewUuid required");
        ensureTenant(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            List<HumanReviewTask> rows = readReviewsLocked(tu);
            HumanReviewTask found = null;
            for (HumanReviewTask row : rows) {
                if (row == null) continue;
                if (ru.equals(safeFileToken(row.reviewUuid))) {
                    found = row;
                    break;
                }
            }
            if (found == null) throw new IllegalArgumentException("Review task not found.");
            if (!"pending".equalsIgnoreCase(safe(found.status))) {
                throw new IllegalArgumentException("Review task already completed.");
            }
            ensureReviewerAuthorized(tu, found, reviewedByUserUuid);

            LinkedHashMap<String, String> in = sanitizeMap(input);
            if (approved) {
                for (String key : found.requiredInputKeys) {
                    String k = safe(key).trim();
                    if (k.isBlank()) continue;
                    if (safe(in.get(k)).trim().isBlank()) {
                        throw new IllegalArgumentException("Missing required review input: " + k);
                    }
                }
            }

            found.status = approved ? "approved" : "rejected";
            found.reviewedByUserUuid = safe(reviewedByUserUuid).trim();
            found.resolvedAt = nowIso();
            found.comment = safe(comment);
            found.input = in;

            RunResult resumed = null;
            if (approved) {
                ProcessDefinition process = getProcess(tu, found.processUuid);
                if (process == null || !process.enabled) {
                    found.status = "error";
                    found.resumeStatus = "error";
                    found.resumeMessage = "Unable to resume because process is missing or disabled.";
                } else {
                    LinkedHashMap<String, String> context = new LinkedHashMap<String, String>(sanitizeMap(found.context));
                    for (Map.Entry<String, String> e : in.entrySet()) {
                        if (e == null) continue;
                        String k = safe(e.getKey()).trim();
                        if (k.isBlank()) continue;
                        context.put("review." + k, safe(e.getValue()));
                    }

                    LinkedHashMap<String, String> payload = extractEventPayload(context);
                    resumed = executeProcess(
                            tu,
                            process,
                            safe(found.eventType),
                            payload,
                            safe(reviewedByUserUuid),
                            "bpm.human_review.complete",
                            found.nextStepOrder,
                            0,
                            context
                    );
                    found.resumedRunUuid = resumed.runUuid;
                    found.resumeStatus = resumed.status;
                    found.resumeMessage = resumed.message;
                }
            } else {
                found.resumeStatus = "rejected";
                found.resumeMessage = "Human review rejected.";
            }

            try {
                updateRequestRunAfterReviewLocked(tu, found, approved, resumed);
            } catch (Exception ex) {
                LOG.log(Level.WARNING,
                        "Unable to finalize request run after review tenant=" + tu + ", review=" + safe(found.reviewUuid)
                                + ": " + safe(ex.getMessage()),
                        ex);
            }

            writeReviewsLocked(tu, rows);
            return cloneReview(found);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public RunResult getRunResult(String tenantUuid, String runUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ru = safeFileToken(runUuid);
        if (tu.isBlank() || ru.isBlank()) return null;
        ensureTenant(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            Path p = runPath(tu, ru);
            if (!Files.exists(p)) return null;
            RunSnapshot snap = readRunSnapshot(p);
            return snap == null ? null : snap.result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<RunResult> listRuns(String tenantUuid, int limit) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensureTenant(tu);

        int max = Math.max(1, Math.min(2000, limit));
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ArrayList<RunResult> out = new ArrayList<RunResult>();
            Path dir = runsDir(tu);
            if (!Files.isDirectory(dir)) return out;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.xml")) {
                for (Path p : ds) {
                    RunSnapshot snap = readRunSnapshot(p);
                    if (snap == null || snap.result == null) continue;
                    out.add(snap.result);
                }
            }
            out.sort(Comparator.comparing((RunResult r) -> safe(r.startedAt)).reversed());
            if (out.size() > max) return new ArrayList<RunResult>(out.subList(0, max));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<RunHealthIssue> inspectRunHealth(String tenantUuid,
                                                 int runningStaleMinutes,
                                                 int reviewStaleMinutes,
                                                 int limit) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensureTenant(tu);

        int runningCutoffMinutes = Math.max(1, Math.min(43200, runningStaleMinutes));
        int reviewCutoffMinutes = Math.max(1, Math.min(43200, reviewStaleMinutes));
        int max = Math.max(1, Math.min(2000, limit));
        Instant now = Instant.now();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            LinkedHashMap<String, HumanReviewTask> reviewsByUuid = new LinkedHashMap<String, HumanReviewTask>();
            for (HumanReviewTask review : readReviewsLocked(tu)) {
                if (review == null) continue;
                String reviewUuid = safe(review.reviewUuid).trim();
                if (reviewUuid.isBlank()) continue;
                reviewsByUuid.put(reviewUuid, review);
            }

            ArrayList<RunHealthIssue> out = new ArrayList<RunHealthIssue>();
            Path dir = runsDir(tu);
            if (!Files.isDirectory(dir)) return out;

            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.xml")) {
                for (Path p : ds) {
                    RunSnapshot snap = readRunSnapshot(p);
                    if (snap == null || snap.result == null) continue;

                    RunResult run = snap.result;
                    String status = safe(run.status).trim().toLowerCase(Locale.ROOT);
                    if (!"running".equals(status) && !"waiting_human_review".equals(status)) continue;

                    if ("running".equals(status)) {
                        long ageMinutes = ageMinutes(run.startedAt, now);
                        if (ageMinutes < runningCutoffMinutes) continue;
                        RunHealthIssue issue = baseRunIssue(run);
                        issue.issueType = "stale_running_run";
                        issue.severity = "high";
                        issue.ageMinutes = ageMinutes;
                        issue.summary = "Run has remained in running state beyond the stale threshold.";
                        issue.recommendation = "Mark failed, then retry after validating downstream dependencies.";
                        issue.canMarkFailed = true;
                        issue.canRetry = true;
                        out.add(issue);
                        continue;
                    }

                    String reviewUuid = safe(run.humanReviewUuid).trim();
                    HumanReviewTask review = reviewsByUuid.get(reviewUuid);
                    if (review == null) {
                        RunHealthIssue issue = baseRunIssue(run);
                        issue.issueType = "orphaned_waiting_run";
                        issue.severity = "high";
                        issue.ageMinutes = ageMinutes(run.startedAt, now);
                        issue.summary = "Run is waiting for human review, but the review task is missing.";
                        issue.recommendation = "Mark failed and retry if still needed.";
                        issue.canMarkFailed = true;
                        issue.canRetry = true;
                        out.add(issue);
                        continue;
                    }

                    String reviewStatus = safe(review.status).trim().toLowerCase(Locale.ROOT);
                    if (!"pending".equals(reviewStatus)) {
                        RunHealthIssue issue = baseRunIssue(run);
                        issue.issueType = "resolved_review_waiting_run";
                        issue.severity = "high";
                        issue.ageMinutes = ageMinutes(run.startedAt, now);
                        issue.summary = "Run is still waiting even though its review task is " + reviewStatus + ".";
                        issue.recommendation = "Mark failed, or retry if process should continue from event payload.";
                        issue.canMarkFailed = true;
                        issue.canRetry = true;
                        out.add(issue);
                        continue;
                    }

                    long reviewAgeMinutes = ageMinutes(review.createdAt, now);
                    if (reviewAgeMinutes < reviewCutoffMinutes) continue;

                    RunHealthIssue issue = baseRunIssue(run);
                    issue.issueType = "stale_human_review";
                    issue.severity = "medium";
                    issue.ageMinutes = reviewAgeMinutes;
                    issue.summary = "Human review has been pending beyond the configured threshold.";
                    issue.recommendation = "Reject the review if obsolete, or complete the review queue to resume.";
                    issue.canMarkFailed = true;
                    issue.canRetry = true;
                    issue.canResolveReview = true;
                    out.add(issue);
                }
            }

            out.sort((a, b) -> {
                int bySeverity = Integer.compare(severityRank(safe(b.severity)), severityRank(safe(a.severity)));
                if (bySeverity != 0) return bySeverity;
                int byAge = Long.compare(Math.max(0L, b.ageMinutes), Math.max(0L, a.ageMinutes));
                if (byAge != 0) return byAge;
                return safe(b.startedAt).compareTo(safe(a.startedAt));
            });
            if (out.size() > max) return new ArrayList<RunHealthIssue>(out.subList(0, max));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public AgingAlarmEmailResult sendAgingRunAlarmEmails(String tenantUuid,
                                                         int runningStaleMinutes,
                                                         int reviewStaleMinutes,
                                                         int limit,
                                                         String actorUserUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return new AgingAlarmEmailResult();
        ensureTenant(tu);

        AgingAlarmEmailResult out = new AgingAlarmEmailResult();
        List<RunHealthIssue> issues = inspectRunHealth(tu, runningStaleMinutes, reviewStaleMinutes, limit);
        out.detectedIssues = issues.size();
        if (issues.isEmpty()) return out;

        List<String> recipients = resolveTenantAdminEmails(tu);
        if (!recipients.isEmpty()) out.recipients.addAll(recipients);
        if (recipients.isEmpty()) return out;

        String actor = safe(actorUserUuid).trim();
        if (actor.isBlank()) actor = "system.bpm_aging_alarm";

        ArrayList<RunHealthIssue> newlyAlarmed = new ArrayList<RunHealthIssue>();
        String now = nowIso();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            for (RunHealthIssue issue : issues) {
                if (issue == null) continue;
                String runUuid = safe(issue.runUuid).trim();
                if (runUuid.isBlank()) continue;

                Path p = runPath(tu, runUuid);
                if (!Files.exists(p)) continue;
                RunSnapshot snap = readRunSnapshot(p);
                if (snap == null || snap.result == null) continue;

                String issueType = safe(issue.issueType).trim().toLowerCase(Locale.ROOT);
                if (issueType.isBlank()) issueType = "stale_issue";
                String alarmCode = "aging_alarm." + issueType;
                if (hasLogCode(snap.logs, alarmCode)) continue;

                String message = "Aging alarm detected: " + safe(issue.summary)
                        + " (age_minutes=" + Math.max(0L, issue.ageMinutes) + ").";
                snap.logs.add(new RunLogEntry(now, "warning", alarmCode, "", message));
                writeRunSnapshot(tu, snap);
                newlyAlarmed.add(issue);
            }
        } finally {
            lock.writeLock().unlock();
        }

        out.newlyAlarmedIssues = newlyAlarmed.size();
        if (newlyAlarmed.isEmpty()) return out;

        String subject = out.newlyAlarmedIssues == 1
                ? "BPM aging alarm: 1 stale/incomplete run"
                : ("BPM aging alarm: " + out.newlyAlarmedIssues + " stale/incomplete runs");
        String bodyText = buildAgingAlarmEmailBody(tu, runningStaleMinutes, reviewStaleMinutes, newlyAlarmed);

        notification_emails.NotificationEmailRequest request = new notification_emails.NotificationEmailRequest(
                "",
                "",
                "",
                "",
                subject,
                bodyText,
                "",
                recipients,
                List.of(),
                List.of(),
                List.of()
        );

        try {
            String emailUuid = notification_emails.defaultStore().enqueue(tu, actor, request);
            out.emailUuid = safe(emailUuid);
            if (!out.emailUuid.isBlank()) out.emailsQueued = 1;

            activity_log.defaultStore().logWarning(
                    "bpm.run_health.alarm.email",
                    tu,
                    actor,
                    "",
                    "",
                    Map.of(
                            "issues_detected", String.valueOf(out.detectedIssues),
                            "issues_newly_alarmed", String.valueOf(out.newlyAlarmedIssues),
                            "email_uuid", out.emailUuid,
                            "recipient_count", String.valueOf(recipients.size())
                    )
            );
        } catch (Exception ex) {
            out.emailError = safe(ex.getMessage());
            LOG.log(Level.WARNING, "Unable to enqueue BPM aging alarm email for tenant=" + tu + ": " + out.emailError, ex);
        }

        return out;
    }

    public RunResult markRunFailed(String tenantUuid,
                                   String runUuid,
                                   String actorUserUuid,
                                   String operatorNote) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ru = safeFileToken(runUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (ru.isBlank()) throw new IllegalArgumentException("runUuid required");
        ensureTenant(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path p = runPath(tu, ru);
            if (!Files.exists(p)) throw new IllegalArgumentException("Run not found.");
            RunSnapshot snap = readRunSnapshot(p);
            if (snap == null || snap.result == null) throw new IllegalArgumentException("Run data missing.");

            String status = safe(snap.result.status).trim().toLowerCase(Locale.ROOT);
            if ("completed".equals(status) || "completed_with_errors".equals(status) || "failed".equals(status)) {
                snap.logs.add(new RunLogEntry(nowIso(), "info", "run.force_failed.noop", "", "Run is already terminal."));
                writeRunSnapshot(tu, snap);
                return snap.result;
            }

            String note = safe(operatorNote).trim();
            String message = "Run marked failed by operator.";
            if (!note.isBlank()) message = message + " " + note;

            snap.result.status = "failed";
            snap.result.completedAt = nowIso();
            snap.result.message = message;
            snap.logs.add(new RunLogEntry(nowIso(), "warning", "run.force_failed", "", message));

            String reviewUuid = safe(snap.result.humanReviewUuid).trim();
            if (!reviewUuid.isBlank()) {
                markReviewErrorLocked(tu, reviewUuid, safe(actorUserUuid), message);
            }

            writeRunSnapshot(tu, snap);

            activity_log.defaultStore().logWarning(
                    "bpm.run.force_failed",
                    tu,
                    safe(actorUserUuid),
                    safe(snap.context.get("event.matter_uuid")),
                    safe(snap.context.get("event.doc_uuid")),
                    Map.of(
                            "run_uuid", safe(snap.result.runUuid),
                            "process_uuid", safe(snap.result.processUuid),
                            "status_before", status
                    )
            );
            return snap.result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public RunResult retryRun(String tenantUuid,
                              String runUuid,
                              String actorUserUuid,
                              String reason) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ru = safeFileToken(runUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (ru.isBlank()) throw new IllegalArgumentException("runUuid required");
        ensureTenant(tu);

        RunSnapshot source;
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            Path p = runPath(tu, ru);
            if (!Files.exists(p)) throw new IllegalArgumentException("Run not found.");
            source = readRunSnapshot(p);
            if (source == null || source.result == null) throw new IllegalArgumentException("Run data missing.");
        } finally {
            lock.readLock().unlock();
        }

        String processUuid = safe(source.result.processUuid).trim();
        if (processUuid.isBlank()) throw new IllegalArgumentException("Run does not have process_uuid.");
        ProcessDefinition process = getProcess(tu, processUuid);
        if (process == null || !process.enabled) {
            throw new IllegalArgumentException("Unable to retry because process is missing or disabled.");
        }

        LinkedHashMap<String, String> payload = extractEventPayload(source.context);
        RunResult retried = executeProcess(
                tu,
                process,
                safe(source.result.eventType),
                payload,
                safe(actorUserUuid),
                "bpm.run.retry",
                0,
                0,
                new LinkedHashMap<String, String>()
        );

        lock.writeLock().lock();
        try {
            Path p = runPath(tu, ru);
            if (Files.exists(p)) {
                RunSnapshot refresh = readRunSnapshot(p);
                if (refresh != null && refresh.result != null) {
                    String statusBefore = safe(refresh.result.status).trim().toLowerCase(Locale.ROOT);
                    String msg = "Retried as run " + safe(retried.runUuid) + ".";
                    String note = safe(reason).trim();
                    if (!note.isBlank()) msg = msg + " " + note;

                    if (!("completed".equals(statusBefore) || "completed_with_errors".equals(statusBefore) || "failed".equals(statusBefore))) {
                        refresh.result.status = "failed";
                        refresh.result.completedAt = nowIso();
                    }
                    refresh.result.message = msg;
                    refresh.logs.add(new RunLogEntry(nowIso(), "warning", "run.retry", "", msg));

                    String reviewUuid = safe(refresh.result.humanReviewUuid).trim();
                    if (!reviewUuid.isBlank()) {
                        markReviewErrorLocked(tu, reviewUuid, safe(actorUserUuid), msg);
                    }

                    writeRunSnapshot(tu, refresh);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        activity_log.defaultStore().logWarning(
                "bpm.run.retry",
                tu,
                safe(actorUserUuid),
                safe(source.context.get("event.matter_uuid")),
                safe(source.context.get("event.doc_uuid")),
                Map.of(
                        "source_run_uuid", safe(source.result.runUuid),
                        "retry_run_uuid", safe(retried.runUuid),
                        "process_uuid", safe(source.result.processUuid),
                        "event_type", safe(source.result.eventType)
                )
        );
        return retried;
    }

    public RunResult undoRun(String tenantUuid, String runUuid, String actorUserUuid) throws Exception {
        return applyRunCompensation(tenantUuid, runUuid, true, actorUserUuid);
    }

    public RunResult redoRun(String tenantUuid, String runUuid, String actorUserUuid) throws Exception {
        return applyRunCompensation(tenantUuid, runUuid, false, actorUserUuid);
    }

    private RunResult applyRunCompensation(String tenantUuid,
                                           String runUuid,
                                           boolean undo,
                                           String actorUserUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ru = safeFileToken(runUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (ru.isBlank()) throw new IllegalArgumentException("runUuid required");
        ensureTenant(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path p = runPath(tu, ru);
            if (!Files.exists(p)) throw new IllegalArgumentException("Run not found.");

            RunSnapshot snap = readRunSnapshot(p);
            if (snap == null || snap.result == null) throw new IllegalArgumentException("Run data missing.");

            String st = safe(snap.result.status).toLowerCase(Locale.ROOT);
            if (!("completed".equals(st) || "completed_with_errors".equals(st) || "failed".equals(st))) {
                throw new IllegalArgumentException("Run is not in a compensatable state.");
            }

            if (undo && "undone".equalsIgnoreCase(safe(snap.result.undoState))) {
                snap.logs.add(new RunLogEntry(nowIso(), "info", "undo.noop", "", "Run already undone."));
                writeRunSnapshot(tu, snap);
                return snap.result;
            }

            if (!undo && !"undone".equalsIgnoreCase(safe(snap.result.undoState))
                    && !"undo_partial".equalsIgnoreCase(safe(snap.result.undoState))) {
                snap.logs.add(new RunLogEntry(nowIso(), "info", "redo.noop", "", "Run is not in undone state."));
                writeRunSnapshot(tu, snap);
                return snap.result;
            }

            ArrayList<RunJournalEntry> entries = new ArrayList<RunJournalEntry>(snap.journal);
            if (undo) entries.sort((a, b) -> safe(b.createdAt).compareTo(safe(a.createdAt)));
            else entries.sort(Comparator.comparing(a -> safe(a.createdAt)));

            int applied = 0;
            int errors = 0;
            for (RunJournalEntry e : entries) {
                if (e == null || !e.reversible) continue;
                LinkedHashMap<String, String> op = undo ? sanitizeMap(e.undo) : sanitizeMap(e.redo);
                if (op.isEmpty()) continue;

                try {
                    applyCompensationOperation(tu, op);
                    applied++;
                } catch (Exception ex) {
                    errors++;
                    snap.logs.add(new RunLogEntry(
                            nowIso(),
                            "error",
                            undo ? "undo.error" : "redo.error",
                            safe(e.stepId),
                            safe(ex.getMessage())
                    ));
                }
            }

            if (undo) {
                snap.result.undoState = errors == 0 ? "undone" : "undo_partial";
                snap.logs.add(new RunLogEntry(
                        nowIso(),
                        errors == 0 ? "warning" : "error",
                        errors == 0 ? "undo.complete" : "undo.partial",
                        "",
                        "Undo applied actions=" + applied + ", errors=" + errors
                ));
            } else {
                snap.result.undoState = errors == 0 ? "active" : "redo_partial";
                snap.logs.add(new RunLogEntry(
                        nowIso(),
                        errors == 0 ? "info" : "error",
                        errors == 0 ? "redo.complete" : "redo.partial",
                        "",
                        "Redo applied actions=" + applied + ", errors=" + errors
                ));
            }

            writeRunSnapshot(tu, snap);

            activity_log.defaultStore().logWarning(
                    undo ? "bpm.run.undo" : "bpm.run.redo",
                    tu,
                    safe(actorUserUuid),
                    safe(snap.context.get("event.matter_uuid")),
                    safe(snap.context.get("event.doc_uuid")),
                    Map.of(
                            "run_uuid", snap.result.runUuid,
                            "process_uuid", safe(snap.result.processUuid),
                            "undo_state", safe(snap.result.undoState),
                            "applied", String.valueOf(applied),
                            "errors", String.valueOf(errors)
                    )
            );

            return snap.result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void applyCompensationOperation(String tenantUuid, LinkedHashMap<String, String> op) throws Exception {
        String type = safe(op.get("type")).trim().toLowerCase(Locale.ROOT);
        if (type.isBlank()) throw new IllegalArgumentException("Compensation type missing.");

        if ("set_case_field".equals(type)) {
            String matterUuid = safe(op.get("matter_uuid")).trim();
            String fieldKey = safe(op.get("field_key")).trim();
            if (matterUuid.isBlank() || fieldKey.isBlank()) {
                throw new IllegalArgumentException("Compensation set_case_field requires matter_uuid and field_key.");
            }

            boolean hadKey = parseBool(op.get("had_key"), true);
            String value = safe(op.get("field_value"));

            case_fields store = case_fields.defaultStore();
            LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(store.read(tenantUuid, matterUuid));
            if (hadKey) fields.put(fieldKey, value);
            else fields.remove(fieldKey);
            store.write(tenantUuid, matterUuid, fields);
            return;
        }

        if ("set_tenant_field".equals(type)) {
            String fieldKey = safe(op.get("field_key")).trim();
            if (fieldKey.isBlank()) {
                throw new IllegalArgumentException("Compensation set_tenant_field requires field_key.");
            }

            boolean hadKey = parseBool(op.get("had_key"), true);
            String value = safe(op.get("field_value"));

            tenant_fields store = tenant_fields.defaultStore();
            LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(store.read(tenantUuid));
            if (hadKey) fields.put(fieldKey, value);
            else fields.remove(fieldKey);
            store.write(tenantUuid, fields);
            return;
        }

        if ("set_document_field".equals(type)) {
            String matterUuid = safe(op.get("matter_uuid")).trim();
            String docUuid = safe(op.get("doc_uuid")).trim();
            String fieldKey = safe(op.get("field_key")).trim();
            if (matterUuid.isBlank() || docUuid.isBlank() || fieldKey.isBlank()) {
                throw new IllegalArgumentException("Compensation set_document_field requires matter_uuid, doc_uuid, and field_key.");
            }

            boolean hadKey = parseBool(op.get("had_key"), true);
            String value = safe(op.get("field_value"));

            document_fields store = document_fields.defaultStore();
            LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(store.read(tenantUuid, matterUuid, docUuid));
            if (hadKey) fields.put(fieldKey, value);
            else fields.remove(fieldKey);
            store.write(tenantUuid, matterUuid, docUuid, fields);
            return;
        }

        if ("set_task_field".equals(type)) {
            String taskUuid = safe(op.get("task_uuid")).trim();
            String fieldKey = safe(op.get("field_key")).trim();
            if (taskUuid.isBlank() || fieldKey.isBlank()) {
                throw new IllegalArgumentException("Compensation set_task_field requires task_uuid and field_key.");
            }
            if (tasks.defaultStore().getTask(tenantUuid, taskUuid) == null) {
                throw new IllegalArgumentException("Compensation task not found: " + taskUuid);
            }

            boolean hadKey = parseBool(op.get("had_key"), true);
            String value = safe(op.get("field_value"));

            task_fields store = task_fields.defaultStore();
            LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(store.read(tenantUuid, taskUuid));
            if (hadKey) fields.put(fieldKey, value);
            else fields.remove(fieldKey);
            store.write(tenantUuid, taskUuid, fields);
            return;
        }

        if ("set_custom_object_record_field".equals(type)) {
            String objectUuid = safe(op.get("object_uuid")).trim();
            String recordUuid = safe(op.get("record_uuid")).trim();
            String fieldKey = safe(op.get("field_key")).trim();
            if (objectUuid.isBlank() || recordUuid.isBlank() || fieldKey.isBlank()) {
                throw new IllegalArgumentException(
                        "Compensation set_custom_object_record_field requires object_uuid, record_uuid, and field_key."
                );
            }

            custom_object_records store = custom_object_records.defaultStore();
            custom_object_records.RecordRec rec = store.getByUuid(tenantUuid, objectUuid, recordUuid);
            if (rec == null) {
                throw new IllegalArgumentException("Compensation custom object record not found: " + recordUuid);
            }

            String normalizedFieldKey = store.normalizeFieldKey(fieldKey);
            if (normalizedFieldKey.isBlank()) {
                throw new IllegalArgumentException("Compensation custom object field key is invalid.");
            }

            boolean hadKey = parseBool(op.get("had_key"), true);
            String value = safe(op.get("field_value"));

            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>(rec.values);
            if (hadKey) values.put(normalizedFieldKey, value);
            else values.remove(normalizedFieldKey);
            store.update(tenantUuid, objectUuid, recordUuid, rec.label, values);
            return;
        }

        if ("set_case_list_item".equals(type)) {
            String matterUuid = safe(op.get("matter_uuid")).trim();
            String listKey = safe(op.get("field_key")).trim();
            if (matterUuid.isBlank() || listKey.isBlank()) {
                throw new IllegalArgumentException("Compensation set_case_list_item requires matter_uuid and field_key.");
            }

            boolean hadKey = parseBool(op.get("had_key"), true);
            String value = safe(op.get("field_value"));

            case_list_items store = case_list_items.defaultStore();
            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>(store.read(tenantUuid, matterUuid));
            if (hadKey) values.put(listKey, value);
            else values.remove(listKey);
            store.write(tenantUuid, matterUuid, values);
            return;
        }

        throw new IllegalArgumentException("Unsupported compensation type: " + type);
    }

    private List<RunResult> triggerEventInternal(String tenantUuid,
                                                 String eventType,
                                                 Map<String, String> payload,
                                                 String actorUserUuid,
                                                 String source,
                                                 int recursionDepth) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        String evt = safe(eventType).trim().toLowerCase(Locale.ROOT);
        if (evt.isBlank()) return List.of();

        if (recursionDepth > 3) {
            LOG.warning("BPM recursion guard tripped for tenant=" + tu + ", event=" + evt);
            return List.of();
        }

        ensureTenant(tu);

        ArrayList<RunResult> out = new ArrayList<RunResult>();
        List<ProcessDefinition> all = listProcesses(tu);
        LinkedHashMap<String, String> eventPayload = sanitizeMap(payload);
        eventPayload.put("tenant_uuid", tu);
        String eventMatterUuid = safe(eventPayload.get("matter_uuid")).trim();
        String eventDocUuid = safe(eventPayload.get("doc_uuid")).trim();

        LinkedHashMap<String, String> triggerStart = new LinkedHashMap<String, String>();
        triggerStart.put("event_type", evt);
        triggerStart.put("source", safe(source));
        triggerStart.put("actor_user_uuid", safe(actorUserUuid));
        triggerStart.put("recursion_depth", String.valueOf(Math.max(0, recursionDepth)));
        triggerStart.put("payload_keys", String.valueOf(eventPayload.size()));
        activity_log.defaultStore().logVerbose(
                "bpm.trigger.received",
                tu,
                safe(actorUserUuid),
                eventMatterUuid,
                eventDocUuid,
                triggerStart
        );

        int matched = 0;
        for (ProcessDefinition d : all) {
            if (d == null || !d.enabled) continue;
            if (!matchesAnyTrigger(d.triggers, evt, eventPayload)) continue;
            matched++;

            try {
                RunResult run = executeProcess(
                        tu,
                        d,
                        evt,
                        eventPayload,
                        safe(actorUserUuid),
                        safe(source),
                        0,
                        recursionDepth,
                        new LinkedHashMap<String, String>()
                );
                out.add(run);
            } catch (Exception ex) {
                LOG.log(Level.WARNING,
                        "Failed BPM execution tenant=" + tu + ", process=" + safe(d.processUuid) + ", event=" + evt + ": " + safe(ex.getMessage()),
                        ex);
            }
        }

        LinkedHashMap<String, String> triggerEnd = new LinkedHashMap<String, String>();
        triggerEnd.put("event_type", evt);
        triggerEnd.put("source", safe(source));
        triggerEnd.put("matched_processes", String.valueOf(matched));
        triggerEnd.put("runs_started", String.valueOf(out.size()));
        activity_log.defaultStore().logVerbose(
                "bpm.trigger.dispatched",
                tu,
                safe(actorUserUuid),
                eventMatterUuid,
                eventDocUuid,
                triggerEnd
        );

        return out;
    }

    private RunResult executeProcess(String tenantUuid,
                                     ProcessDefinition process,
                                     String eventType,
                                     Map<String, String> payload,
                                     String actorUserUuid,
                                     String source,
                                     int startStepOrder,
                                     int recursionDepth,
                                     LinkedHashMap<String, String> seedContext) throws Exception {

        String runUuid = UUID.randomUUID().toString();
        String startedAt = nowIso();

        RunResult result = new RunResult();
        result.runUuid = runUuid;
        result.processUuid = safe(process == null ? "" : process.processUuid);
        result.processName = safe(process == null ? "" : process.name);
        result.eventType = safe(eventType);
        result.startedAt = startedAt;
        result.status = "running";
        result.undoState = "active";

        if (process == null) {
            result.status = "failed";
            result.completedAt = nowIso();
            result.message = "Process missing.";
            return result;
        }

        LinkedHashMap<String, String> context = new LinkedHashMap<String, String>();
        if (seedContext != null && !seedContext.isEmpty()) context.putAll(seedContext);

        LinkedHashMap<String, String> eventPayload = sanitizeMap(payload);
        for (Map.Entry<String, String> e : eventPayload.entrySet()) {
            if (e == null) continue;
            String key = safe(e.getKey()).trim();
            if (key.isBlank()) continue;
            String val = safe(e.getValue());
            context.put("event." + key, val);
            if (!context.containsKey(key)) context.put(key, val);
        }

        context.put("event.type", safe(eventType));
        context.put("process.uuid", safe(process.processUuid));
        context.put("process.name", safe(process.name));
        context.put("run.uuid", runUuid);
        context.put("actor.user_uuid", safe(actorUserUuid));
        context.put("source", safe(source));
        context.put("tenant.uuid", safe(tenantUuid));

        ArrayList<RunLogEntry> logs = new ArrayList<RunLogEntry>();
        logs.add(new RunLogEntry(nowIso(), "info", "run.start", "", "Process started."));
        ArrayList<RunJournalEntry> journal = new ArrayList<RunJournalEntry>();

        ArrayList<ProcessStep> steps = sortedEnabledSteps(process.steps);
        result.stepCount = steps.size();

        ExecutionContext exec = new ExecutionContext(
                tenantUuid,
                process,
                eventType,
                actorUserUuid,
                source,
                runUuid,
                recursionDepth,
                steps
        );
        activity_log activityLogs = activity_log.defaultStore();

        boolean waitingForHumanReview = false;
        boolean hardFailed = false;

        for (int i = 0; i < steps.size(); i++) {
            ProcessStep step = steps.get(i);
            if (step == null) continue;
            if (startStepOrder > 0 && step.order < startStepOrder) continue;

            String stepLabel = safe(step.label).isBlank() ? safe(step.action) : safe(step.label);

            boolean conditionsPass = evaluateConditions(step.conditions, context);
            if (!conditionsPass) {
                result.stepsSkipped++;
                logs.add(new RunLogEntry(nowIso(), "info", "step.skipped", safe(step.stepId), "Step skipped (conditions not met): " + stepLabel));
                continue;
            }

            String action = safe(step.action).trim().toLowerCase(Locale.ROOT);
            logs.add(new RunLogEntry(nowIso(), "info", "step.start", safe(step.stepId), "Executing step: " + stepLabel));
            auditBpmStep(
                    activityLogs,
                    "verbose",
                    "bpm.action.started",
                    exec,
                    step,
                    context,
                    "Executing step: " + stepLabel,
                    "step.start"
            );
            try {
                StepHandler handler = handlers.get(action);
                if (handler == null) {
                    throw new IllegalArgumentException("Unknown BPM action: " + action);
                }

                StepOutcome outcome = handler.handle(exec, step, context);
                result.stepsCompleted++;

                if (outcome != null && outcome.journal != null) {
                    RunJournalEntry entry = outcome.journal;
                    if (safe(entry.createdAt).isBlank()) entry.createdAt = nowIso();
                    if (safe(entry.stepId).isBlank()) entry.stepId = safe(step.stepId);
                    if (safe(entry.action).isBlank()) entry.action = safe(step.action);
                    journal.add(entry);
                }

                if (outcome != null && StepOutcome.WAITING_HUMAN.equals(outcome.kind)) {
                    waitingForHumanReview = true;
                    result.humanReviewUuid = safe(outcome.humanReviewUuid);
                    result.message = safe(outcome.message);
                    logs.add(new RunLogEntry(nowIso(), "warning", "step.waiting_human", safe(step.stepId), safe(outcome.message)));
                    auditBpmStep(
                            activityLogs,
                            "warning",
                            "bpm.action.waiting_human_review",
                            exec,
                            step,
                            context,
                            safe(outcome.message),
                            "step.waiting_human"
                    );
                    break;
                }

                logs.add(new RunLogEntry(nowIso(), "info", "step.complete", safe(step.stepId), safe(outcome == null ? "Step completed." : outcome.message)));
                auditBpmStep(
                        activityLogs,
                        "verbose",
                        "bpm.action.completed",
                        exec,
                        step,
                        context,
                        safe(outcome == null ? "Step completed." : outcome.message),
                        "step.complete"
                );
            } catch (Exception ex) {
                result.errors++;
                String msg = "Step failed: " + safe(ex.getMessage());
                logs.add(new RunLogEntry(nowIso(), "error", "step.error", safe(step.stepId), msg));
                auditBpmStep(
                        activityLogs,
                        "error",
                        "bpm.action.failed",
                        exec,
                        step,
                        context,
                        msg,
                        "step.error"
                );

                String onError = safe(step.settings.get("on_error")).trim().toLowerCase(Locale.ROOT);
                boolean continueOnError = "continue".equals(onError);

                if (!continueOnError) {
                    hardFailed = true;
                    result.message = msg;
                    break;
                }
            }
        }

        if (waitingForHumanReview) {
            result.status = "waiting_human_review";
            if (safe(result.message).isBlank()) result.message = "Waiting for human review.";
        } else if (hardFailed) {
            result.status = "failed";
            if (safe(result.message).isBlank()) result.message = "Process failed due to a step error.";
        } else if (result.errors > 0) {
            result.status = "completed_with_errors";
            result.message = "Process completed with recoverable step errors.";
        } else {
            result.status = "completed";
            result.message = "Process completed.";
        }

        result.completedAt = nowIso();
        logs.add(new RunLogEntry(nowIso(), "info", "run.complete", "", "Run status: " + safe(result.status)));

        RunSnapshot snap = new RunSnapshot();
        snap.result = result;
        snap.context = sanitizeMap(context);
        snap.logs = logs;
        snap.journal = journal;

        writeRunSnapshot(tenantUuid, snap);

        activity_log.defaultStore().logVerbose(
                "bpm.run." + safe(result.status),
                tenantUuid,
                safe(actorUserUuid),
                safe(context.get("event.matter_uuid")),
                safe(context.get("event.doc_uuid")),
                Map.of(
                        "process_uuid", safe(process.processUuid),
                        "process_name", safe(process.name),
                        "run_uuid", safe(result.runUuid),
                        "event_type", safe(eventType),
                        "status", safe(result.status),
                        "errors", String.valueOf(result.errors)
                )
        );

        return result;
    }

    private static void auditBpmStep(activity_log logs,
                                     String level,
                                     String action,
                                     ExecutionContext exec,
                                     ProcessStep step,
                                     LinkedHashMap<String, String> context,
                                     String message,
                                     String code) {
        if (logs == null || exec == null || step == null) return;
        try {
            String matterUuid = resolveMatterUuidForAudit(step, context);
            String docUuid = resolveDocumentUuidForAudit(step, context);

            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("process_uuid", safe(exec.process == null ? "" : exec.process.processUuid));
            details.put("process_name", safe(exec.process == null ? "" : exec.process.name));
            details.put("run_uuid", safe(exec.runUuid));
            details.put("event_type", safe(exec.eventType));
            details.put("source", safe(exec.source));
            details.put("step_id", safe(step.stepId));
            details.put("step_order", String.valueOf(Math.max(0, step.order)));
            details.put("step_action", safe(step.action));
            details.put("step_label", safe(step.label));
            details.put("code", safe(code));
            details.put("message", safe(message));

            String severity = safe(level).trim().toLowerCase(Locale.ROOT);
            if ("error".equals(severity)) {
                logs.logError(action, safe(exec.tenantUuid), safe(exec.actorUserUuid), matterUuid, docUuid, details);
            } else if ("warning".equals(severity)) {
                logs.logWarning(action, safe(exec.tenantUuid), safe(exec.actorUserUuid), matterUuid, docUuid, details);
            } else {
                logs.logVerbose(action, safe(exec.tenantUuid), safe(exec.actorUserUuid), matterUuid, docUuid, details);
            }
        } catch (Exception ignored) {
        }
    }

    private static String resolveMatterUuidForAudit(ProcessStep step, LinkedHashMap<String, String> context) {
        String matterUuid = safe(context == null ? "" : context.get("event.matter_uuid")).trim();
        if (matterUuid.isBlank()) matterUuid = safe(context == null ? "" : context.get("matter_uuid")).trim();
        if (matterUuid.isBlank()) matterUuid = safe(context == null ? "" : context.get("vars.last_matter_uuid")).trim();
        if (matterUuid.isBlank()) {
            matterUuid = safe(resolveTemplate(step == null ? "" : step.settings.get("matter_uuid"), context)).trim();
        }
        if (matterUuid.isBlank()) {
            matterUuid = safe(resolveTemplate(step == null ? "" : step.settings.get("case_uuid"), context)).trim();
        }
        return matterUuid;
    }

    private static String resolveDocumentUuidForAudit(ProcessStep step, LinkedHashMap<String, String> context) {
        String docUuid = safe(context == null ? "" : context.get("event.doc_uuid")).trim();
        if (docUuid.isBlank()) docUuid = safe(context == null ? "" : context.get("event.document_uuid")).trim();
        if (docUuid.isBlank()) docUuid = safe(context == null ? "" : context.get("doc_uuid")).trim();
        if (docUuid.isBlank()) docUuid = safe(context == null ? "" : context.get("vars.last_document_uuid")).trim();
        if (docUuid.isBlank()) {
            docUuid = safe(resolveTemplate(step == null ? "" : step.settings.get("doc_uuid"), context)).trim();
        }
        if (docUuid.isBlank()) {
            docUuid = safe(resolveTemplate(step == null ? "" : step.settings.get("document_uuid"), context)).trim();
        }
        return docUuid;
    }

    private void registerDefaultHandlers() {
        handlers.put("log_note", (exec, step, context) -> {
            String level = resolveTemplate(step.settings.get("level"), context).trim().toLowerCase(Locale.ROOT);
            String action = resolveTemplate(step.settings.get("action"), context).trim();
            if (action.isBlank()) action = "bpm.step.log_note";
            String message = resolveTemplate(step.settings.get("message"), context).trim();
            if (message.isBlank()) message = "Business process note.";

            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("message", message);
            details.put("process_uuid", safe(exec.process.processUuid));
            details.put("run_uuid", safe(exec.runUuid));
            details.put("step_id", safe(step.stepId));

            String matterUuid = safe(context.get("event.matter_uuid"));
            String docUuid = safe(context.get("event.doc_uuid"));

            activity_log logs = activity_log.defaultStore();
            if ("error".equals(level)) {
                logs.logError(action, exec.tenantUuid, exec.actorUserUuid, matterUuid, docUuid, details);
            } else if ("warning".equals(level)) {
                logs.logWarning(action, exec.tenantUuid, exec.actorUserUuid, matterUuid, docUuid, details);
            } else {
                logs.logVerbose(action, exec.tenantUuid, exec.actorUserUuid, matterUuid, docUuid, details);
            }

            return StepOutcome.continueWithMessage(message);
        });

        handlers.put("set_case_field", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for set_case_field.");

            String fieldKey = resolveTemplate(step.settings.get("field_key"), context).trim();
            if (fieldKey.isBlank()) throw new IllegalArgumentException("field_key is required for set_case_field.");

            String fieldValue = resolveTemplate(step.settings.get("field_value"), context);

            case_fields store = case_fields.defaultStore();
            LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(store.read(exec.tenantUuid, matterUuid));
            boolean hadBefore = fields.containsKey(fieldKey);
            String beforeValue = safe(fields.get(fieldKey));
            fields.put(fieldKey, fieldValue);
            store.write(exec.tenantUuid, matterUuid, fields);

            context.put("vars.last_matter_uuid", matterUuid);
            context.put("vars.last_case_field_key", fieldKey);
            context.put("vars.last_case_field_value", fieldValue);

            RunJournalEntry entry = new RunJournalEntry();
            entry.createdAt = nowIso();
            entry.stepId = safe(step.stepId);
            entry.action = "set_case_field";
            entry.description = "Updated case field " + fieldKey;
            entry.reversible = true;
            entry.undo = compensationOp("set_case_field", matterUuid, fieldKey, beforeValue, hadBefore);
            entry.redo = compensationOp("set_case_field", matterUuid, fieldKey, fieldValue, true);

            return StepOutcome.continueWithJournal("Case field updated: " + fieldKey, entry);
        });

        handlers.put("set_case_list_item", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for set_case_list_item.");

            String listKey = resolveTemplate(step.settings.get("list_key"), context).trim();
            if (listKey.isBlank()) listKey = resolveTemplate(step.settings.get("field_key"), context).trim();
            if (listKey.isBlank()) throw new IllegalArgumentException("list_key is required for set_case_list_item.");

            String listValue = resolveTemplate(step.settings.get("list_value"), context);
            if (listValue.isBlank() && step.settings.containsKey("field_value")) {
                listValue = resolveTemplate(step.settings.get("field_value"), context);
            }

            case_list_items store = case_list_items.defaultStore();
            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>(store.read(exec.tenantUuid, matterUuid));
            boolean hadBefore = values.containsKey(listKey);
            String beforeValue = safe(values.get(listKey));
            values.put(listKey, listValue);
            store.write(exec.tenantUuid, matterUuid, values);

            context.put("vars.last_matter_uuid", matterUuid);
            context.put("vars.last_case_list_key", listKey);
            context.put("vars.last_case_list_value", listValue);

            RunJournalEntry entry = new RunJournalEntry();
            entry.createdAt = nowIso();
            entry.stepId = safe(step.stepId);
            entry.action = "set_case_list_item";
            entry.description = "Updated case list item " + listKey;
            entry.reversible = true;
            entry.undo = compensationOp("set_case_list_item", matterUuid, listKey, beforeValue, hadBefore);
            entry.redo = compensationOp("set_case_list_item", matterUuid, listKey, listValue, true);

            return StepOutcome.continueWithJournal("Case list item updated: " + listKey, entry);
        });

        handlers.put("set_document_field", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for set_document_field.");

            String docUuid = resolveTemplate(step.settings.get("doc_uuid"), context).trim();
            if (docUuid.isBlank()) docUuid = safe(context.get("event.doc_uuid")).trim();
            if (docUuid.isBlank()) throw new IllegalArgumentException("doc_uuid is required for set_document_field.");

            String fieldKey = resolveTemplate(step.settings.get("field_key"), context).trim();
            if (fieldKey.isBlank()) throw new IllegalArgumentException("field_key is required for set_document_field.");

            String fieldValue = resolveTemplate(step.settings.get("field_value"), context);

            document_fields store = document_fields.defaultStore();
            LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(store.read(exec.tenantUuid, matterUuid, docUuid));
            boolean hadBefore = fields.containsKey(fieldKey);
            String beforeValue = safe(fields.get(fieldKey));
            fields.put(fieldKey, fieldValue);
            store.write(exec.tenantUuid, matterUuid, docUuid, fields);

            context.put("vars.last_matter_uuid", matterUuid);
            context.put("vars.last_document_uuid", docUuid);
            context.put("vars.last_document_field_key", fieldKey);
            context.put("vars.last_document_field_value", fieldValue);

            RunJournalEntry entry = new RunJournalEntry();
            entry.createdAt = nowIso();
            entry.stepId = safe(step.stepId);
            entry.action = "set_document_field";
            entry.description = "Updated document field " + fieldKey;
            entry.reversible = true;
            entry.undo = compensationOpWithDoc("set_document_field", matterUuid, docUuid, fieldKey, beforeValue, hadBefore);
            entry.redo = compensationOpWithDoc("set_document_field", matterUuid, docUuid, fieldKey, fieldValue, true);

            return StepOutcome.continueWithJournal("Document field updated: " + fieldKey, entry);
        });

        handlers.put("set_tenant_field", (exec, step, context) -> {
            String fieldKey = resolveTemplate(step.settings.get("field_key"), context).trim();
            if (fieldKey.isBlank()) throw new IllegalArgumentException("field_key is required for set_tenant_field.");
            String fieldValue = resolveTemplate(step.settings.get("field_value"), context);

            tenant_fields store = tenant_fields.defaultStore();
            LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(store.read(exec.tenantUuid));
            boolean hadBefore = fields.containsKey(fieldKey);
            String beforeValue = safe(fields.get(fieldKey));
            fields.put(fieldKey, fieldValue);
            store.write(exec.tenantUuid, fields);

            context.put("vars.last_tenant_field_key", fieldKey);
            context.put("vars.last_tenant_field_value", fieldValue);

            RunJournalEntry entry = new RunJournalEntry();
            entry.createdAt = nowIso();
            entry.stepId = safe(step.stepId);
            entry.action = "set_tenant_field";
            entry.description = "Updated tenant field " + fieldKey;
            entry.reversible = true;
            entry.undo = compensationOp("set_tenant_field", "", fieldKey, beforeValue, hadBefore);
            entry.redo = compensationOp("set_tenant_field", "", fieldKey, fieldValue, true);

            return StepOutcome.continueWithJournal("Tenant field updated: " + fieldKey, entry);
        });

        handlers.put("set_variable", (exec, step, context) -> {
            String varName = resolveTemplate(step.settings.get("name"), context).trim();
            if (varName.isBlank()) throw new IllegalArgumentException("name is required for set_variable.");
            String value = resolveTemplate(step.settings.get("value"), context);

            String token = normalizeToken(varName);
            if (token.isBlank()) throw new IllegalArgumentException("Variable name is invalid.");

            context.put("vars." + token, value);
            context.put("var." + token, value);
            return StepOutcome.continueWithMessage("Variable set: " + token);
        });

        handlers.put("trigger_event", (exec, step, context) -> {
            String eventType = resolveTemplate(step.settings.get("event_type"), context).trim().toLowerCase(Locale.ROOT);
            if (eventType.isBlank()) throw new IllegalArgumentException("event_type is required for trigger_event.");

            LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> e : step.settings.entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (!key.startsWith("payload.")) continue;
                String payloadKey = key.substring("payload.".length()).trim();
                if (payloadKey.isBlank()) continue;
                payload.put(payloadKey, resolveTemplate(e.getValue(), context));
            }

            List<RunResult> triggered = triggerEventInternal(
                    exec.tenantUuid,
                    eventType,
                    payload,
                    exec.actorUserUuid,
                    "bpm.step.trigger_event",
                    exec.recursionDepth + 1
            );

            context.put("vars.last_triggered_count", String.valueOf(triggered.size()));
            return StepOutcome.continueWithMessage("Triggered downstream event " + eventType + " for " + triggered.size() + " process(es).");
        });

        handlers.put("send_webhook", (exec, step, context) -> {
            String url = resolveTemplate(step.settings.get("url"), context).trim();
            if (url.isBlank()) throw new IllegalArgumentException("url is required for send_webhook.");

            String method = resolveTemplate(step.settings.get("method"), context).trim().toUpperCase(Locale.ROOT);
            if (method.isBlank()) method = "POST";
            if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
                throw new IllegalArgumentException("Unsupported webhook method: " + method);
            }

            int timeoutMs = Math.max(1000, Math.min(120000, parseInt(
                    resolveTemplate(step.settings.get("timeout_ms"), context).trim(),
                    15000
            )));

            String body = resolveTemplate(step.settings.get("body"), context);
            if (body.isBlank()) {
                LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
                for (Map.Entry<String, String> e : step.settings.entrySet()) {
                    if (e == null) continue;
                    String key = safe(e.getKey()).trim();
                    if (!key.startsWith("payload.")) continue;
                    String payloadKey = key.substring("payload.".length()).trim();
                    if (payloadKey.isBlank()) continue;
                    payload.put(payloadKey, resolveTemplate(e.getValue(), context));
                }
                if (!payload.isEmpty()) body = toSimpleJson(payload);
            }

            HttpURLConnection conn = null;
            int status = 0;
            String responseBody = "";
            try {
                conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestMethod(method);
                conn.setRequestProperty("User-Agent", "controversies-bpm/1.0");
                conn.setRequestProperty("Accept", "application/json, text/plain, */*");

                String contentType = resolveTemplate(step.settings.get("content_type"), context).trim();
                if (contentType.isBlank() && !body.isBlank()) contentType = "application/json; charset=UTF-8";
                if (!contentType.isBlank()) conn.setRequestProperty("Content-Type", contentType);

                for (Map.Entry<String, String> e : step.settings.entrySet()) {
                    if (e == null) continue;
                    String key = safe(e.getKey()).trim();
                    if (!key.startsWith("header.")) continue;
                    String headerName = key.substring("header.".length()).trim();
                    if (headerName.isBlank()) continue;
                    conn.setRequestProperty(headerName, resolveTemplate(e.getValue(), context));
                }

                boolean canWrite = !"GET".equals(method) && !"DELETE".equals(method);
                if (canWrite && !body.isBlank()) {
                    conn.setDoOutput(true);
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    conn.getOutputStream().write(bytes);
                }

                status = conn.getResponseCode();
                responseBody = readHttpBody(conn);
            } finally {
                if (conn != null) conn.disconnect();
            }

            context.put("vars.last_webhook_url", url);
            context.put("vars.last_webhook_method", method);
            context.put("vars.last_webhook_status", String.valueOf(status));
            context.put("vars.last_webhook_response", responseBody);
            context.put("vars.last_webhook_ok", (status >= 200 && status < 300) ? "true" : "false");

            if (status < 200 || status >= 300) {
                String msg = "Webhook request failed status=" + status;
                if (!responseBody.isBlank()) msg = msg + ", response=" + responseBody;
                throw new IllegalStateException(msg);
            }

            return StepOutcome.continueWithMessage("Webhook sent. status=" + status);
        });

        handlers.put("update_thread", (exec, step, context) -> {
            String threadUuid = resolveTemplate(step.settings.get("thread_uuid"), context).trim();
            if (threadUuid.isBlank()) threadUuid = safe(context.get("event.thread_uuid")).trim();
            if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required for update_thread.");

            omnichannel_tickets store = omnichannel_tickets.defaultStore();
            omnichannel_tickets.TicketRec current = store.getTicket(exec.tenantUuid, threadUuid);
            if (current == null) throw new IllegalArgumentException("Thread not found.");

            omnichannel_tickets.TicketRec in = copyThread(current);
            in.uuid = threadUuid;

            String val;
            val = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (!val.isBlank()) in.matterUuid = val;
            val = resolveTemplate(step.settings.get("channel"), context).trim();
            if (!val.isBlank()) in.channel = val;
            val = resolveTemplate(step.settings.get("subject"), context).trim();
            if (!val.isBlank()) in.subject = val;
            val = resolveTemplate(step.settings.get("status"), context).trim();
            if (!val.isBlank()) in.status = val;
            val = resolveTemplate(step.settings.get("priority"), context).trim();
            if (!val.isBlank()) in.priority = val;
            val = resolveTemplate(step.settings.get("assignment_mode"), context).trim();
            if (!val.isBlank()) in.assignmentMode = val;
            val = resolveTemplate(step.settings.get("assigned_user_uuid"), context).trim();
            if (!val.isBlank()) in.assignedUserUuid = val;
            val = resolveTemplate(step.settings.get("reminder_at"), context).trim();
            if (!val.isBlank()) in.reminderAt = val;
            val = resolveTemplate(step.settings.get("due_at"), context).trim();
            if (!val.isBlank()) in.dueAt = val;
            val = resolveTemplate(step.settings.get("customer_display"), context).trim();
            if (!val.isBlank()) in.customerDisplay = val;
            val = resolveTemplate(step.settings.get("customer_address"), context).trim();
            if (!val.isBlank()) in.customerAddress = val;
            val = resolveTemplate(step.settings.get("mailbox_address"), context).trim();
            if (!val.isBlank()) in.mailboxAddress = val;
            val = resolveTemplate(step.settings.get("thread_key"), context).trim();
            if (!val.isBlank()) in.threadKey = val;
            val = resolveTemplate(step.settings.get("external_conversation_id"), context).trim();
            if (!val.isBlank()) in.externalConversationId = val;

            if (step.settings.containsKey("mms_enabled")) {
                in.mmsEnabled = parseBool(resolveTemplate(step.settings.get("mms_enabled"), context), in.mmsEnabled);
            }
            if (step.settings.containsKey("archived")) {
                in.archived = parseBool(resolveTemplate(step.settings.get("archived"), context), in.archived);
            }

            String assignmentReason = resolveTemplate(step.settings.get("assignment_reason"), context);
            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = exec.actorUserUuid;

            boolean changed = store.updateTicket(exec.tenantUuid, in, actorUser, assignmentReason);
            context.put("vars.last_thread_uuid", threadUuid);
            context.put("vars.last_thread_updated", changed ? "true" : "false");
            return StepOutcome.continueWithMessage(changed ? "Thread updated." : "Thread unchanged.");
        });

        handlers.put("add_thread_note", (exec, step, context) -> {
            String threadUuid = resolveTemplate(step.settings.get("thread_uuid"), context).trim();
            if (threadUuid.isBlank()) threadUuid = safe(context.get("event.thread_uuid")).trim();
            if (threadUuid.isBlank()) throw new IllegalArgumentException("thread_uuid is required for add_thread_note.");

            String body = resolveTemplate(step.settings.get("body"), context).trim();
            if (body.isBlank()) throw new IllegalArgumentException("body is required for add_thread_note.");

            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = exec.actorUserUuid;

            omnichannel_tickets.MessageRec note = omnichannel_tickets.defaultStore().addMessage(
                    exec.tenantUuid,
                    threadUuid,
                    "internal",
                    body,
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    actorUser
            );
            context.put("vars.last_thread_uuid", threadUuid);
            context.put("vars.last_thread_note_uuid", safe(note == null ? "" : note.uuid));
            return StepOutcome.continueWithMessage("Internal thread note added.");
        });

        handlers.put("set_task_field", (exec, step, context) -> {
            String taskUuid = resolveTemplate(step.settings.get("task_uuid"), context).trim();
            if (taskUuid.isBlank()) taskUuid = safe(context.get("event.task_uuid")).trim();
            if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required for set_task_field.");

            if (tasks.defaultStore().getTask(exec.tenantUuid, taskUuid) == null) {
                throw new IllegalArgumentException("Task not found.");
            }

            String fieldKey = resolveTemplate(step.settings.get("field_key"), context).trim();
            if (fieldKey.isBlank()) throw new IllegalArgumentException("field_key is required for set_task_field.");
            String fieldValue = resolveTemplate(step.settings.get("field_value"), context);

            task_fields store = task_fields.defaultStore();
            LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(store.read(exec.tenantUuid, taskUuid));
            boolean hadBefore = fields.containsKey(fieldKey);
            String beforeValue = safe(fields.get(fieldKey));
            fields.put(fieldKey, fieldValue);
            store.write(exec.tenantUuid, taskUuid, fields);

            context.put("vars.last_task_uuid", taskUuid);
            context.put("vars.last_task_field_key", fieldKey);
            context.put("vars.last_task_field_value", fieldValue);

            RunJournalEntry entry = new RunJournalEntry();
            entry.createdAt = nowIso();
            entry.stepId = safe(step.stepId);
            entry.action = "set_task_field";
            entry.description = "Updated task field " + fieldKey;
            entry.reversible = true;
            entry.undo = compensationOpTask("set_task_field", taskUuid, fieldKey, beforeValue, hadBefore);
            entry.redo = compensationOpTask("set_task_field", taskUuid, fieldKey, fieldValue, true);

            return StepOutcome.continueWithJournal("Task field updated: " + fieldKey, entry);
        });

        handlers.put("set_custom_object_record_field", (exec, step, context) -> {
            String objectUuid = resolveTemplate(step.settings.get("object_uuid"), context).trim();
            if (objectUuid.isBlank()) objectUuid = safe(context.get("event.object_uuid")).trim();
            if (objectUuid.isBlank()) {
                throw new IllegalArgumentException("object_uuid is required for set_custom_object_record_field.");
            }

            String recordUuid = resolveTemplate(step.settings.get("record_uuid"), context).trim();
            if (recordUuid.isBlank()) recordUuid = safe(context.get("event.record_uuid")).trim();
            if (recordUuid.isBlank()) {
                throw new IllegalArgumentException("record_uuid is required for set_custom_object_record_field.");
            }

            String fieldKey = resolveTemplate(step.settings.get("field_key"), context).trim();
            if (fieldKey.isBlank()) {
                throw new IllegalArgumentException("field_key is required for set_custom_object_record_field.");
            }
            String fieldValue = resolveTemplate(step.settings.get("field_value"), context);

            custom_object_records store = custom_object_records.defaultStore();
            custom_object_records.RecordRec current = store.getByUuid(exec.tenantUuid, objectUuid, recordUuid);
            if (current == null) throw new IllegalArgumentException("Custom object record not found.");

            String normalizedFieldKey = store.normalizeFieldKey(fieldKey);
            if (normalizedFieldKey.isBlank()) {
                throw new IllegalArgumentException("Custom object field key is invalid.");
            }

            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>(current.values);
            boolean hadBefore = values.containsKey(normalizedFieldKey);
            String beforeValue = safe(values.get(normalizedFieldKey));
            values.put(normalizedFieldKey, fieldValue);

            boolean changed = store.update(exec.tenantUuid, objectUuid, recordUuid, current.label, values);
            context.put("vars.last_custom_object_uuid", objectUuid);
            context.put("vars.last_custom_object_record_uuid", recordUuid);
            context.put("vars.last_custom_object_field_key", normalizedFieldKey);
            context.put("vars.last_custom_object_field_value", fieldValue);

            if (!changed) {
                return StepOutcome.continueWithMessage("Custom object field unchanged: " + normalizedFieldKey);
            }

            RunJournalEntry entry = new RunJournalEntry();
            entry.createdAt = nowIso();
            entry.stepId = safe(step.stepId);
            entry.action = "set_custom_object_record_field";
            entry.description = "Updated custom object record field " + normalizedFieldKey;
            entry.reversible = true;
            entry.undo = compensationOpCustomObjectField(
                    "set_custom_object_record_field",
                    objectUuid,
                    recordUuid,
                    normalizedFieldKey,
                    beforeValue,
                    hadBefore
            );
            entry.redo = compensationOpCustomObjectField(
                    "set_custom_object_record_field",
                    objectUuid,
                    recordUuid,
                    normalizedFieldKey,
                    fieldValue,
                    true
            );

            return StepOutcome.continueWithJournal(
                    "Custom object record field updated: " + normalizedFieldKey,
                    entry
            );
        });

        handlers.put("update_custom_object_record", (exec, step, context) -> {
            String objectUuid = resolveTemplate(step.settings.get("object_uuid"), context).trim();
            if (objectUuid.isBlank()) objectUuid = safe(context.get("event.object_uuid")).trim();
            if (objectUuid.isBlank()) {
                throw new IllegalArgumentException("object_uuid is required for update_custom_object_record.");
            }

            String recordUuid = resolveTemplate(step.settings.get("record_uuid"), context).trim();
            if (recordUuid.isBlank()) recordUuid = safe(context.get("event.record_uuid")).trim();
            if (recordUuid.isBlank()) {
                throw new IllegalArgumentException("record_uuid is required for update_custom_object_record.");
            }

            custom_object_records store = custom_object_records.defaultStore();
            custom_object_records.RecordRec current = store.getByUuid(exec.tenantUuid, objectUuid, recordUuid);
            if (current == null) throw new IllegalArgumentException("Custom object record not found.");

            String label = current.label;
            if (step.settings.containsKey("label")) {
                String nextLabel = resolveTemplate(step.settings.get("label"), context).trim();
                if (!nextLabel.isBlank()) label = nextLabel;
            }

            boolean replaceValues = false;
            if (step.settings.containsKey("replace_values")) {
                replaceValues = parseBool(resolveTemplate(step.settings.get("replace_values"), context), false);
            }

            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
            if (!replaceValues) values.putAll(current.values);

            for (Map.Entry<String, String> e : step.settings.entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;

                String prefix = "";
                if (key.startsWith("value.")) prefix = "value.";
                else if (key.startsWith("values.")) prefix = "values.";
                if (prefix.isBlank()) continue;

                String fieldKey = store.normalizeFieldKey(key.substring(prefix.length()).trim());
                if (fieldKey.isBlank()) continue;
                values.put(fieldKey, resolveTemplate(e.getValue(), context));
            }

            boolean changed = store.update(exec.tenantUuid, objectUuid, recordUuid, label, values);
            if (step.settings.containsKey("trashed")) {
                boolean trashed = parseBool(resolveTemplate(step.settings.get("trashed"), context), current.trashed);
                changed = store.setTrashed(exec.tenantUuid, objectUuid, recordUuid, trashed) || changed;
            }

            custom_object_records.RecordRec refreshed = store.getByUuid(exec.tenantUuid, objectUuid, recordUuid);
            context.put("vars.last_custom_object_uuid", objectUuid);
            context.put("vars.last_custom_object_record_uuid", recordUuid);
            context.put("vars.last_custom_object_record_label", safe(refreshed == null ? "" : refreshed.label));
            context.put("vars.last_custom_object_record_updated", changed ? "true" : "false");
            return StepOutcome.continueWithMessage(changed ? "Custom object record updated." : "Custom object record unchanged.");
        });

        handlers.put("update_task", (exec, step, context) -> {
            String taskUuid = resolveTemplate(step.settings.get("task_uuid"), context).trim();
            if (taskUuid.isBlank()) taskUuid = safe(context.get("event.task_uuid")).trim();
            if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required for update_task.");

            tasks store = tasks.defaultStore();
            tasks.TaskRec current = store.getTask(exec.tenantUuid, taskUuid);
            if (current == null) throw new IllegalArgumentException("Task not found.");

            tasks.TaskRec in = copyTask(current);
            in.uuid = taskUuid;

            String val;
            val = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (!val.isBlank()) in.matterUuid = val;
            val = resolveTemplate(step.settings.get("parent_task_uuid"), context).trim();
            if (!val.isBlank()) in.parentTaskUuid = val;
            val = resolveTemplate(step.settings.get("title"), context).trim();
            if (!val.isBlank()) in.title = val;
            val = resolveTemplate(step.settings.get("description"), context).trim();
            if (!val.isBlank()) in.description = val;
            val = resolveTemplate(step.settings.get("status"), context).trim();
            if (!val.isBlank()) in.status = val;
            val = resolveTemplate(step.settings.get("priority"), context).trim();
            if (!val.isBlank()) in.priority = val;
            val = resolveTemplate(step.settings.get("assignment_mode"), context).trim();
            if (!val.isBlank()) in.assignmentMode = val;
            val = resolveTemplate(step.settings.get("assigned_user_uuid"), context).trim();
            if (!val.isBlank()) in.assignedUserUuid = val;
            val = resolveTemplate(step.settings.get("due_at"), context).trim();
            if (!val.isBlank()) in.dueAt = val;
            val = resolveTemplate(step.settings.get("reminder_at"), context).trim();
            if (!val.isBlank()) in.reminderAt = val;
            val = resolveTemplate(step.settings.get("estimate_minutes"), context).trim();
            if (!val.isBlank()) in.estimateMinutes = parseInt(val, in.estimateMinutes);
            val = resolveTemplate(step.settings.get("claim_uuid"), context).trim();
            if (!val.isBlank()) in.claimUuid = val;
            val = resolveTemplate(step.settings.get("element_uuid"), context).trim();
            if (!val.isBlank()) in.elementUuid = val;
            val = resolveTemplate(step.settings.get("fact_uuid"), context).trim();
            if (!val.isBlank()) in.factUuid = val;
            val = resolveTemplate(step.settings.get("document_uuid"), context).trim();
            if (!val.isBlank()) in.documentUuid = val;
            val = resolveTemplate(step.settings.get("part_uuid"), context).trim();
            if (!val.isBlank()) in.partUuid = val;
            val = resolveTemplate(step.settings.get("version_uuid"), context).trim();
            if (!val.isBlank()) in.versionUuid = val;
            val = resolveTemplate(step.settings.get("page_number"), context).trim();
            if (!val.isBlank()) in.pageNumber = parseInt(val, in.pageNumber);
            val = resolveTemplate(step.settings.get("thread_uuid"), context).trim();
            if (!val.isBlank()) in.threadUuid = val;
            if (step.settings.containsKey("archived")) {
                in.archived = parseBool(resolveTemplate(step.settings.get("archived"), context), in.archived);
            }

            String assignmentReason = resolveTemplate(step.settings.get("assignment_reason"), context);
            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = exec.actorUserUuid;

            boolean changed = store.updateTask(exec.tenantUuid, in, actorUser, assignmentReason);
            context.put("vars.last_task_uuid", taskUuid);
            context.put("vars.last_task_updated", changed ? "true" : "false");
            return StepOutcome.continueWithMessage(changed ? "Task updated." : "Task unchanged.");
        });

        handlers.put("add_task_note", (exec, step, context) -> {
            String taskUuid = resolveTemplate(step.settings.get("task_uuid"), context).trim();
            if (taskUuid.isBlank()) taskUuid = safe(context.get("event.task_uuid")).trim();
            if (taskUuid.isBlank()) throw new IllegalArgumentException("task_uuid is required for add_task_note.");

            String body = resolveTemplate(step.settings.get("body"), context).trim();
            if (body.isBlank()) throw new IllegalArgumentException("body is required for add_task_note.");

            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = exec.actorUserUuid;

            tasks.NoteRec note = tasks.defaultStore().addNote(exec.tenantUuid, taskUuid, body, actorUser);
            context.put("vars.last_task_uuid", taskUuid);
            context.put("vars.last_task_note_uuid", safe(note == null ? "" : note.uuid));
            return StepOutcome.continueWithMessage("Internal task note added.");
        });

        handlers.put("update_fact", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for update_fact.");

            String factUuid = resolveTemplate(step.settings.get("fact_uuid"), context).trim();
            if (factUuid.isBlank()) factUuid = safe(context.get("event.fact_uuid")).trim();
            if (factUuid.isBlank()) throw new IllegalArgumentException("fact_uuid is required for update_fact.");

            matter_facts store = matter_facts.defaultStore();
            matter_facts.FactRec current = store.getFact(exec.tenantUuid, matterUuid, factUuid);
            if (current == null) throw new IllegalArgumentException("Fact not found.");

            matter_facts.FactRec in = copyFact(current);
            in.uuid = factUuid;

            String val;
            val = resolveTemplate(step.settings.get("claim_uuid"), context).trim();
            if (!val.isBlank()) in.claimUuid = val;
            val = resolveTemplate(step.settings.get("element_uuid"), context).trim();
            if (!val.isBlank()) in.elementUuid = val;
            val = resolveTemplate(step.settings.get("summary"), context).trim();
            if (!val.isBlank()) in.summary = val;
            val = resolveTemplate(step.settings.get("detail"), context).trim();
            if (!val.isBlank()) in.detail = val;
            val = resolveTemplate(step.settings.get("internal_notes"), context).trim();
            if (!val.isBlank()) in.internalNotes = val;
            val = resolveTemplate(step.settings.get("status"), context).trim();
            if (!val.isBlank()) in.status = val;
            val = resolveTemplate(step.settings.get("strength"), context).trim();
            if (!val.isBlank()) in.strength = val;
            val = resolveTemplate(step.settings.get("document_uuid"), context).trim();
            if (!val.isBlank()) in.documentUuid = val;
            val = resolveTemplate(step.settings.get("part_uuid"), context).trim();
            if (!val.isBlank()) in.partUuid = val;
            val = resolveTemplate(step.settings.get("version_uuid"), context).trim();
            if (!val.isBlank()) in.versionUuid = val;
            val = resolveTemplate(step.settings.get("page_number"), context).trim();
            if (!val.isBlank()) in.pageNumber = parseInt(val, in.pageNumber);
            val = resolveTemplate(step.settings.get("sort_order"), context).trim();
            if (!val.isBlank()) in.sortOrder = parseInt(val, in.sortOrder);

            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = exec.actorUserUuid;

            boolean changed = store.updateFact(exec.tenantUuid, matterUuid, in, actorUser);
            context.put("vars.last_matter_uuid", matterUuid);
            context.put("vars.last_fact_uuid", factUuid);
            context.put("vars.last_fact_updated", changed ? "true" : "false");
            return StepOutcome.continueWithMessage(changed ? "Fact updated." : "Fact unchanged.");
        });

        handlers.put("refresh_facts_report", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for refresh_facts_report.");

            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = exec.actorUserUuid;

            matter_facts.ReportRec report = matter_facts.defaultStore().refreshMatterReport(exec.tenantUuid, matterUuid, actorUser);
            context.put("vars.last_matter_uuid", matterUuid);
            context.put("vars.last_facts_report_document_uuid", safe(report == null ? "" : report.reportDocumentUuid));
            context.put("vars.last_facts_report_part_uuid", safe(report == null ? "" : report.reportPartUuid));
            context.put("vars.last_facts_report_version_uuid", safe(report == null ? "" : report.lastReportVersionUuid));
            return StepOutcome.continueWithMessage("Facts report refreshed.");
        });

        handlers.put("document_assembly", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for document_assembly.");

            String templateUuid = resolveTemplate(step.settings.get("template_uuid"), context).trim();
            if (templateUuid.isBlank()) throw new IllegalArgumentException("template_uuid is required for document_assembly.");

            form_templates store = form_templates.defaultStore();
            form_templates.TemplateRec template = store.get(exec.tenantUuid, templateUuid);
            if (template == null) throw new IllegalArgumentException("Template not found for document_assembly.");

            byte[] templateBytes = store.readBytes(exec.tenantUuid, templateUuid);
            if (templateBytes == null || templateBytes.length == 0) {
                throw new IllegalArgumentException("Template body is empty for document_assembly.");
            }

            String templateExtOrName = safe(template.fileExt).trim();
            if (templateExtOrName.isBlank()) templateExtOrName = safe(template.fileName).trim();
            if (templateExtOrName.isBlank()) templateExtOrName = "txt";

            LinkedHashMap<String, String> mergeValues = buildMatterMergeValues(exec.tenantUuid, matterUuid);
            for (Map.Entry<String, String> e : step.settings.entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                String value = resolveTemplate(e.getValue(), context);
                if (key.startsWith("value.")) {
                    String token = key.substring("value.".length()).trim();
                    putToken(mergeValues, token, value);
                } else if (key.startsWith("values.")) {
                    String token = key.substring("values.".length()).trim();
                    putToken(mergeValues, token, value);
                }
            }

            LinkedHashMap<String, String> overrides = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> e : step.settings.entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                String value = resolveTemplate(e.getValue(), context);
                if (key.startsWith("override.")) {
                    String token = key.substring("override.".length()).trim();
                    if (!token.isBlank()) {
                        overrides.put(token, value);
                        applyLiteralOverride(mergeValues, token, value);
                    }
                } else if (key.startsWith("overrides.")) {
                    String token = key.substring("overrides.".length()).trim();
                    if (!token.isBlank()) {
                        overrides.put(token, value);
                        applyLiteralOverride(mergeValues, token, value);
                    }
                }
            }

            document_assembler.AssembledFile assembled = new document_assembler().assemble(templateBytes, templateExtOrName, mergeValues);
            String templateLabel = resolveTemplate(step.settings.get("template_label"), context).trim();
            if (templateLabel.isBlank()) templateLabel = safe(template.label).trim();
            if (templateLabel.isBlank()) templateLabel = "Assembled Document";

            String requestedFileName = resolveTemplate(step.settings.get("output_file_name"), context).trim();
            String outputName = suggestOutputFileName(requestedFileName, templateLabel, assembled == null ? "" : assembled.extension);
            String preferredAssemblyUuid = resolveTemplate(step.settings.get("assembly_uuid"), context).trim();
            String userUuid = resolveTemplate(step.settings.get("user_uuid"), context).trim();
            if (userUuid.isBlank()) userUuid = safe(exec.actorUserUuid).trim();
            String userEmail = resolveTemplate(step.settings.get("user_email"), context).trim();

            assembled_forms.AssemblyRec rec = assembled_forms.defaultStore().markCompleted(
                    exec.tenantUuid,
                    matterUuid,
                    preferredAssemblyUuid,
                    templateUuid,
                    templateLabel,
                    templateExtOrName,
                    userUuid,
                    userEmail,
                    overrides,
                    outputName,
                    safe(assembled == null ? "" : assembled.extension),
                    assembled == null ? new byte[0] : assembled.bytes
            );
            if (rec == null) throw new IllegalStateException("Unable to store assembled output.");

            context.put("vars.last_matter_uuid", matterUuid);
            context.put("vars.last_assembly_uuid", safe(rec.uuid));
            context.put("vars.last_assembly_file_name", safe(rec.outputFileName));
            context.put("vars.last_assembly_output_ext", safe(rec.outputFileExt));
            context.put("vars.last_assembly_template_uuid", templateUuid);
            context.put("vars.last_document_assembly_status", "completed");

            return StepOutcome.continueWithMessage("Document assembled: " + safe(rec.outputFileName));
        });

        handlers.put("notice_communication", (exec, step, context) -> {
            String toCsv = resolveTemplate(step.settings.get("to"), context).trim();
            List<String> to = parseRecipientList(toCsv);
            if (to.isEmpty()) throw new IllegalArgumentException("to is required for notice_communication.");

            String subject = resolveTemplate(step.settings.get("subject"), context).trim();
            if (subject.isBlank()) throw new IllegalArgumentException("subject is required for notice_communication.");

            String bodyText = resolveTemplate(step.settings.get("body_text"), context);
            String bodyHtml = resolveTemplate(step.settings.get("body_html"), context);
            if (bodyText.isBlank() && bodyHtml.isBlank()) {
                throw new IllegalArgumentException("Either body_text or body_html is required for notice_communication.");
            }

            String ccCsv = resolveTemplate(step.settings.get("cc"), context).trim();
            String bccCsv = resolveTemplate(step.settings.get("bcc"), context).trim();
            String fromAddress = resolveTemplate(step.settings.get("from_address"), context).trim();
            String fromName = resolveTemplate(step.settings.get("from_name"), context).trim();
            String replyTo = resolveTemplate(step.settings.get("reply_to"), context).trim();
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();

            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = safe(exec.actorUserUuid).trim();

            notification_emails.NotificationEmailRequest request = new notification_emails.NotificationEmailRequest(
                    matterUuid,
                    fromAddress,
                    fromName,
                    replyTo,
                    subject,
                    bodyText,
                    bodyHtml,
                    to,
                    parseRecipientList(ccCsv),
                    parseRecipientList(bccCsv),
                    List.of()
            );

            String deliveryMode = resolveTemplate(step.settings.get("delivery_mode"), context).trim().toLowerCase(Locale.ROOT);
            if ("send_now".equals(deliveryMode) || "immediate".equals(deliveryMode)) {
                notification_emails.SendResult sent = notification_emails.defaultStore().sendSimpleMessageNow(
                        exec.tenantUuid,
                        actorUser,
                        request
                );
                context.put("vars.last_notice_email_uuid", "");
                context.put("vars.last_notice_delivery_mode", "send_now");
                context.put("vars.last_notice_status_code", String.valueOf(sent == null ? 0 : sent.statusCode));
                context.put("vars.last_notice_provider_message_id", safe(sent == null ? "" : sent.providerMessageId));
                return StepOutcome.continueWithMessage("Notice sent immediately.");
            }

            String emailUuid = notification_emails.defaultStore().enqueue(exec.tenantUuid, actorUser, request);
            context.put("vars.last_notice_email_uuid", safe(emailUuid));
            context.put("vars.last_notice_delivery_mode", "queue");
            context.put("vars.last_notice_status_code", "queued");
            context.put("vars.last_notice_provider_message_id", "");
            return StepOutcome.continueWithMessage("Notice queued for delivery.");
        });

        handlers.put("send_for_signature", (exec, step, context) -> {
            String toCsv = resolveTemplate(step.settings.get("to"), context).trim();
            List<String> to = parseRecipientList(toCsv);
            if (to.isEmpty()) throw new IllegalArgumentException("to is required for send_for_signature.");

            String subject = resolveTemplate(step.settings.get("subject"), context).trim();
            if (subject.isBlank()) throw new IllegalArgumentException("subject is required for send_for_signature.");

            String signatureLink = resolveTemplate(step.settings.get("signature_link"), context).trim();
            String bodyText = resolveTemplate(step.settings.get("body_text"), context);
            String bodyHtml = resolveTemplate(step.settings.get("body_html"), context);
            if (bodyText.isBlank() && bodyHtml.isBlank()) {
                bodyText = "Please review and sign the attached/requested document.";
            }
            if (!signatureLink.isBlank()) {
                if (!bodyText.isBlank()) bodyText = bodyText + "\n\nSignature link: " + signatureLink;
                if (!bodyHtml.isBlank()) bodyHtml = bodyHtml + "<br/><br/>Signature link: " + signatureLink;
            }

            String ccCsv = resolveTemplate(step.settings.get("cc"), context).trim();
            String bccCsv = resolveTemplate(step.settings.get("bcc"), context).trim();
            String fromAddress = resolveTemplate(step.settings.get("from_address"), context).trim();
            String fromName = resolveTemplate(step.settings.get("from_name"), context).trim();
            String replyTo = resolveTemplate(step.settings.get("reply_to"), context).trim();
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();

            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = safe(exec.actorUserUuid).trim();

            notification_emails.NotificationEmailRequest request = new notification_emails.NotificationEmailRequest(
                    matterUuid,
                    fromAddress,
                    fromName,
                    replyTo,
                    subject,
                    bodyText,
                    bodyHtml,
                    to,
                    parseRecipientList(ccCsv),
                    parseRecipientList(bccCsv),
                    List.of()
            );

            String deliveryMode = resolveTemplate(step.settings.get("delivery_mode"), context).trim().toLowerCase(Locale.ROOT);
            String emailUuid = "";
            if ("send_now".equals(deliveryMode) || "immediate".equals(deliveryMode)) {
                notification_emails.SendResult sent = notification_emails.defaultStore().sendSimpleMessageNow(
                        exec.tenantUuid,
                        actorUser,
                        request
                );
                context.put("vars.last_signature_notice_status_code", String.valueOf(sent == null ? 0 : sent.statusCode));
                context.put("vars.last_signature_notice_provider_message_id", safe(sent == null ? "" : sent.providerMessageId));
                context.put("vars.last_signature_notice_delivery_mode", "send_now");
            } else {
                emailUuid = safe(notification_emails.defaultStore().enqueue(exec.tenantUuid, actorUser, request));
                context.put("vars.last_signature_notice_status_code", "queued");
                context.put("vars.last_signature_notice_provider_message_id", "");
                context.put("vars.last_signature_notice_delivery_mode", "queue");
            }
            context.put("vars.last_signature_notice_email_uuid", emailUuid);

            String providerKey = resolveTemplate(step.settings.get("provider_key"), context).trim();
            if (providerKey.isBlank()) providerKey = "manual_notice";
            String providerRequestId = resolveTemplate(step.settings.get("provider_request_id"), context).trim();
            String documentUuid = resolveTemplate(step.settings.get("document_uuid"), context).trim();
            if (documentUuid.isBlank()) documentUuid = safe(context.get("event.doc_uuid")).trim();
            String partUuid = resolveTemplate(step.settings.get("part_uuid"), context).trim();
            if (partUuid.isBlank()) partUuid = safe(context.get("event.part_uuid")).trim();
            String versionUuid = resolveTemplate(step.settings.get("version_uuid"), context).trim();
            if (versionUuid.isBlank()) versionUuid = safe(context.get("event.version_uuid")).trim();

            esign_requests.CreateInput signatureReq = new esign_requests.CreateInput();
            signatureReq.providerKey = providerKey;
            signatureReq.providerRequestId = providerRequestId;
            signatureReq.matterUuid = matterUuid;
            signatureReq.documentUuid = documentUuid;
            signatureReq.partUuid = partUuid;
            signatureReq.versionUuid = versionUuid;
            signatureReq.subject = subject;
            signatureReq.toCsv = String.join(",", to);
            signatureReq.ccCsv = ccCsv;
            signatureReq.bccCsv = bccCsv;
            signatureReq.signatureLink = signatureLink;
            signatureReq.deliveryMode = deliveryMode;
            signatureReq.requestedByUserUuid = actorUser;
            signatureReq.status = "sent";

            esign_requests.SignatureRequestRec createdSignature = esign_requests.defaultStore().createRequest(exec.tenantUuid, signatureReq);
            context.put("vars.last_signature_request_uuid", safe(createdSignature == null ? "" : createdSignature.requestUuid));
            context.put("vars.last_signature_provider_key", safe(createdSignature == null ? providerKey : createdSignature.providerKey));
            context.put("vars.last_signature_status", safe(createdSignature == null ? "sent" : createdSignature.status));

            try {
                LinkedHashMap<String, Object> eventPayload = new LinkedHashMap<String, Object>();
                eventPayload.put("request_uuid", safe(createdSignature == null ? "" : createdSignature.requestUuid));
                eventPayload.put("provider_key", safe(createdSignature == null ? providerKey : createdSignature.providerKey));
                eventPayload.put("status", safe(createdSignature == null ? "sent" : createdSignature.status));
                eventPayload.put("matter_uuid", safe(createdSignature == null ? matterUuid : createdSignature.matterUuid));
                eventPayload.put("document_uuid", safe(createdSignature == null ? documentUuid : createdSignature.documentUuid));
                eventPayload.put("part_uuid", safe(createdSignature == null ? partUuid : createdSignature.partUuid));
                eventPayload.put("version_uuid", safe(createdSignature == null ? versionUuid : createdSignature.versionUuid));
                integration_webhooks.defaultStore().dispatchEvent(exec.tenantUuid, "esign.request.sent", eventPayload);
            } catch (Exception ignored) {
            }

            boolean waitForConfirmation = parseBool(resolveTemplate(step.settings.get("wait_for_confirmation"), context), false);
            if (!waitForConfirmation) {
                context.put("vars.last_signature_status", "sent");
                return StepOutcome.continueWithMessage("Signature request sent.");
            }

            int nextOrder = nextStepOrder(exec.sortedSteps, step.order);
            String title = resolveTemplate(step.settings.get("confirmation_title"), context).trim();
            if (title.isBlank()) title = "Signature confirmation required";
            String instructions = resolveTemplate(step.settings.get("confirmation_instructions"), context).trim();
            if (instructions.isBlank()) {
                instructions = "Confirm signature outcome in review input. Use signature_status with signed, declined, or expired.";
            }
            String requiredCsv = resolveTemplate(step.settings.get("required_input_keys"), context).trim();
            if (requiredCsv.isBlank()) requiredCsv = "signature_status,signature_notes";

            HumanReviewTask review = enqueueHumanReview(
                    exec,
                    context,
                    nextOrder,
                    "send_for_signature",
                    title,
                    instructions,
                    requiredCsv,
                    resolveTemplate(step.settings.get("assignee_user_uuid"), context),
                    resolveTemplate(step.settings.get("assignee_role_uuid"), context),
                    resolveTemplate(step.settings.get("queue_permission_key"), context),
                    resolveTemplate(step.settings.get("due_at"), context)
            );
            context.put("vars.last_human_review_uuid", safe(review.reviewUuid));
            context.put("vars.last_human_review_type", safe(review.reviewType));
            return StepOutcome.waitingHuman(
                    review.reviewUuid,
                    "Signature confirmation required: " + safe(review.title)
            );
        });

        handlers.put("conflict_check", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for conflict_check.");

            LinkedHashSet<String> partyTokens = new LinkedHashSet<String>();
            for (String part : parseCsv(resolveTemplate(step.settings.get("party_names"), context))) {
                String token = normalizeComparable(part);
                if (!token.isBlank()) partyTokens.add(token);
            }

            contacts contactStore = contacts.defaultStore();
            matter_contacts linksStore = matter_contacts.defaultStore();

            if (partyTokens.isEmpty()) {
                List<matter_contacts.LinkRec> linked = linksStore.listByMatter(exec.tenantUuid, matterUuid);
                for (matter_contacts.LinkRec link : linked) {
                    if (link == null) continue;
                    contacts.ContactRec c = contactStore.getByUuid(exec.tenantUuid, link.contactUuid);
                    addConflictTokens(partyTokens, c);
                }
            }
            if (partyTokens.isEmpty()) throw new IllegalArgumentException("No party_names or linked contacts available for conflict_check.");

            List<matter_contacts.LinkRec> allLinks = linksStore.listAll(exec.tenantUuid);
            LinkedHashSet<String> conflictMatterUuids = new LinkedHashSet<String>();
            LinkedHashSet<String> conflictContactUuids = new LinkedHashSet<String>();
            for (matter_contacts.LinkRec link : allLinks) {
                if (link == null) continue;
                String linkedMatter = safe(link.matterUuid).trim();
                if (linkedMatter.isBlank() || matterUuid.equals(linkedMatter)) continue;
                contacts.ContactRec c = contactStore.getByUuid(exec.tenantUuid, link.contactUuid);
                if (!isPotentialConflict(partyTokens, c)) continue;
                conflictMatterUuids.add(linkedMatter);
                if (!safe(link.contactUuid).trim().isBlank()) conflictContactUuids.add(safe(link.contactUuid).trim());
            }

            int conflictCount = conflictMatterUuids.size();
            String status = conflictCount > 0 ? "potential_conflict" : "clear";
            context.put("vars.last_conflict_status", status);
            context.put("vars.last_conflict_count", String.valueOf(conflictCount));
            context.put("vars.last_conflict_matter_uuids", String.join(",", conflictMatterUuids));
            context.put("vars.last_conflict_contact_uuids", String.join(",", conflictContactUuids));

            if (conflictCount <= 0) {
                return StepOutcome.continueWithMessage("Conflict check clear.");
            }

            String onPotential = resolveTemplate(step.settings.get("on_potential"), context).trim().toLowerCase(Locale.ROOT);
            if ("fail".equals(onPotential) || "error".equals(onPotential)) {
                throw new IllegalStateException("Potential conflict detected for matter(s): " + String.join(",", conflictMatterUuids));
            }
            if ("require_review".equals(onPotential) || "review".equals(onPotential)) {
                int nextOrder = nextStepOrder(exec.sortedSteps, step.order);
                String title = resolveTemplate(step.settings.get("review_title"), context).trim();
                if (title.isBlank()) title = "Potential conflict review";
                String instructions = resolveTemplate(step.settings.get("review_instructions"), context).trim();
                if (instructions.isBlank()) {
                    instructions = "Potential conflict detected. Confirm whether to proceed.";
                }
                String requiredCsv = resolveTemplate(step.settings.get("required_input_keys"), context).trim();
                if (requiredCsv.isBlank()) requiredCsv = "decision,notes";

                HumanReviewTask review = enqueueHumanReview(
                        exec,
                        context,
                        nextOrder,
                        "conflict_check",
                        title,
                        instructions,
                        requiredCsv,
                        resolveTemplate(step.settings.get("assignee_user_uuid"), context),
                        resolveTemplate(step.settings.get("assignee_role_uuid"), context),
                        resolveTemplate(step.settings.get("queue_permission_key"), context),
                        resolveTemplate(step.settings.get("due_at"), context)
                );
                context.put("vars.last_human_review_uuid", safe(review.reviewUuid));
                context.put("vars.last_human_review_type", safe(review.reviewType));
                return StepOutcome.waitingHuman(
                        review.reviewUuid,
                        "Potential conflict requires review."
                );
            }

            return StepOutcome.continueWithMessage("Potential conflict detected.");
        });

        handlers.put("deadline_rule_calculation", (exec, step, context) -> {
            String triggerRaw = resolveTemplate(step.settings.get("trigger_date"), context).trim();
            if (triggerRaw.isBlank()) triggerRaw = safe(context.get("event.trigger_date")).trim();
            if (triggerRaw.isBlank()) triggerRaw = safe(context.get("event.due_at")).trim();
            if (triggerRaw.isBlank()) triggerRaw = safe(context.get("event.created_at")).trim();
            if (triggerRaw.isBlank()) triggerRaw = nowIso();

            String offsetRaw = resolveTemplate(step.settings.get("days_offset"), context).trim();
            if (offsetRaw.isBlank()) throw new IllegalArgumentException("days_offset is required for deadline_rule_calculation.");
            int offsetDays = parseInt(offsetRaw, Integer.MIN_VALUE);
            if (offsetDays == Integer.MIN_VALUE) throw new IllegalArgumentException("days_offset must be an integer.");

            boolean businessDays = parseBool(resolveTemplate(step.settings.get("business_days"), context), false);
            LocalDate triggerDate = parseFlexibleDate(triggerRaw);
            if (triggerDate == null) {
                throw new IllegalArgumentException("Unable to parse trigger_date for deadline_rule_calculation.");
            }

            LocalDate deadline = addDays(triggerDate, offsetDays, businessDays);
            String deadlineValue = deadline == null ? "" : deadline.toString();
            if (deadlineValue.isBlank()) throw new IllegalStateException("Unable to calculate deadline.");

            String storeAs = normalizeToken(resolveTemplate(step.settings.get("store_as"), context));
            if (storeAs.isBlank()) storeAs = "calculated_deadline";
            context.put("vars." + storeAs, deadlineValue);
            context.put("var." + storeAs, deadlineValue);
            context.put("vars.last_deadline_value", deadlineValue);
            context.put("vars.last_deadline_trigger", triggerDate.toString());
            context.put("vars.last_deadline_business_days", businessDays ? "true" : "false");
            context.put("vars.last_deadline_offset_days", String.valueOf(offsetDays));

            String fieldKey = resolveTemplate(step.settings.get("field_key"), context).trim();
            if (!fieldKey.isBlank()) {
                String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
                if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
                if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required when field_key is set.");
                case_fields store = case_fields.defaultStore();
                LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(store.read(exec.tenantUuid, matterUuid));
                fields.put(fieldKey, deadlineValue);
                store.write(exec.tenantUuid, matterUuid, fields);
                context.put("vars.last_matter_uuid", matterUuid);
                context.put("vars.last_deadline_field_key", fieldKey);
            }

            return StepOutcome.continueWithMessage("Deadline calculated: " + deadlineValue);
        });

        handlers.put("court_filing_service", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for court_filing_service.");

            String title = resolveTemplate(step.settings.get("title"), context).trim();
            if (title.isBlank()) throw new IllegalArgumentException("title is required for court_filing_service.");

            String dueAt = resolveTemplate(step.settings.get("due_at"), context).trim();
            if (dueAt.isBlank()) dueAt = Instant.now().plusSeconds(86400L).toString();

            int estimateMinutes = parseInt(resolveTemplate(step.settings.get("estimate_minutes"), context).trim(), 60);
            if (estimateMinutes <= 0) estimateMinutes = 60;

            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = safe(exec.actorUserUuid).trim();

            tasks.TaskRec in = new tasks.TaskRec();
            in.matterUuid = matterUuid;
            in.title = title;
            in.description = resolveTemplate(step.settings.get("description"), context);
            in.status = resolveTemplate(step.settings.get("status"), context).trim();
            if (in.status.isBlank()) in.status = "open";
            in.priority = resolveTemplate(step.settings.get("priority"), context).trim();
            if (in.priority.isBlank()) in.priority = "high";
            in.assignmentMode = resolveTemplate(step.settings.get("assignment_mode"), context).trim();
            if (in.assignmentMode.isBlank()) in.assignmentMode = "manual";
            in.assignedUserUuid = resolveTemplate(step.settings.get("assigned_user_uuid"), context).trim();
            in.dueAt = dueAt;
            in.reminderAt = resolveTemplate(step.settings.get("reminder_at"), context).trim();
            in.estimateMinutes = estimateMinutes;

            String assignmentReason = resolveTemplate(step.settings.get("assignment_reason"), context);
            tasks.TaskRec created = tasks.defaultStore().createTask(exec.tenantUuid, in, actorUser, assignmentReason);
            if (created == null) throw new IllegalStateException("Unable to create filing/service task.");

            context.put("vars.last_matter_uuid", matterUuid);
            context.put("vars.last_court_filing_task_uuid", safe(created.uuid));
            context.put("vars.last_court_filing_status", "queued");

            boolean requireReview = parseBool(resolveTemplate(step.settings.get("require_review"), context), false);
            if (!requireReview) {
                return StepOutcome.continueWithMessage("Court filing/service task created.");
            }

            int nextOrder = nextStepOrder(exec.sortedSteps, step.order);
            String reviewTitle = resolveTemplate(step.settings.get("review_title"), context).trim();
            if (reviewTitle.isBlank()) reviewTitle = "Court filing/service review";
            String reviewInstructions = resolveTemplate(step.settings.get("review_instructions"), context).trim();
            if (reviewInstructions.isBlank()) reviewInstructions = "Approve before filing/service execution.";
            String requiredCsv = resolveTemplate(step.settings.get("required_input_keys"), context).trim();
            if (requiredCsv.isBlank()) requiredCsv = "decision,notes";

            HumanReviewTask review = enqueueHumanReview(
                    exec,
                    context,
                    nextOrder,
                    "court_filing_service",
                    reviewTitle,
                    reviewInstructions,
                    requiredCsv,
                    resolveTemplate(step.settings.get("assignee_user_uuid"), context),
                    resolveTemplate(step.settings.get("assignee_role_uuid"), context),
                    resolveTemplate(step.settings.get("queue_permission_key"), context),
                    resolveTemplate(step.settings.get("due_at"), context)
            );
            context.put("vars.last_human_review_uuid", safe(review.reviewUuid));
            context.put("vars.last_human_review_type", safe(review.reviewType));
            return StepOutcome.waitingHuman(review.reviewUuid, "Court filing/service requires review.");
        });

        handlers.put("payment_fee_verification", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();

            String requiredRaw = resolveTemplate(step.settings.get("required_amount"), context).trim();
            String paidRaw = resolveTemplate(step.settings.get("paid_amount"), context).trim();

            if (!matterUuid.isBlank()) {
                LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(case_fields.defaultStore().read(exec.tenantUuid, matterUuid));
                String requiredFieldKey = resolveTemplate(step.settings.get("required_amount_field_key"), context).trim();
                if (requiredFieldKey.isBlank()) requiredFieldKey = "filing_fee_required";
                String paidFieldKey = resolveTemplate(step.settings.get("paid_amount_field_key"), context).trim();
                if (paidFieldKey.isBlank()) paidFieldKey = "filing_fee_paid";
                if (requiredRaw.isBlank()) requiredRaw = safe(fields.get(requiredFieldKey)).trim();
                if (paidRaw.isBlank()) paidRaw = safe(fields.get(paidFieldKey)).trim();
            }

            BigDecimal required = parseMoney(requiredRaw);
            if (required == null) throw new IllegalArgumentException("required_amount is required for payment_fee_verification.");
            BigDecimal paid = parseMoney(paidRaw);
            if (paid == null) paid = BigDecimal.ZERO;

            BigDecimal delta = paid.subtract(required).setScale(2, RoundingMode.HALF_UP);
            boolean ready = delta.compareTo(BigDecimal.ZERO) >= 0;
            String status = ready ? "ready" : "insufficient";
            String currency = resolveTemplate(step.settings.get("currency"), context).trim();
            if (currency.isBlank()) currency = "USD";

            context.put("vars.last_payment_required", moneyToString(required));
            context.put("vars.last_payment_paid", moneyToString(paid));
            context.put("vars.last_payment_delta", moneyToString(delta));
            context.put("vars.last_payment_currency", currency);
            context.put("vars.last_payment_status", status);
            if (!matterUuid.isBlank()) context.put("vars.last_matter_uuid", matterUuid);

            if (ready) {
                return StepOutcome.continueWithMessage("Payment/fee verification ready.");
            }

            String onInsufficient = resolveTemplate(step.settings.get("on_insufficient"), context).trim().toLowerCase(Locale.ROOT);
            if ("fail".equals(onInsufficient) || "error".equals(onInsufficient)) {
                throw new IllegalStateException("Payment/fee verification insufficient.");
            }
            if ("require_review".equals(onInsufficient) || "review".equals(onInsufficient)) {
                int nextOrder = nextStepOrder(exec.sortedSteps, step.order);
                String reviewTitle = resolveTemplate(step.settings.get("review_title"), context).trim();
                if (reviewTitle.isBlank()) reviewTitle = "Payment/Fee verification review";
                String reviewInstructions = resolveTemplate(step.settings.get("review_instructions"), context).trim();
                if (reviewInstructions.isBlank()) reviewInstructions = "Insufficient payment detected. Confirm next action.";
                String requiredCsv = resolveTemplate(step.settings.get("required_input_keys"), context).trim();
                if (requiredCsv.isBlank()) requiredCsv = "decision,notes";

                HumanReviewTask review = enqueueHumanReview(
                        exec,
                        context,
                        nextOrder,
                        "payment_fee_verification",
                        reviewTitle,
                        reviewInstructions,
                        requiredCsv,
                        resolveTemplate(step.settings.get("assignee_user_uuid"), context),
                        resolveTemplate(step.settings.get("assignee_role_uuid"), context),
                        resolveTemplate(step.settings.get("queue_permission_key"), context),
                        resolveTemplate(step.settings.get("due_at"), context)
                );
                context.put("vars.last_human_review_uuid", safe(review.reviewUuid));
                context.put("vars.last_human_review_type", safe(review.reviewType));
                return StepOutcome.waitingHuman(review.reviewUuid, "Payment/fee verification requires review.");
            }

            return StepOutcome.continueWithMessage("Payment/fee verification insufficient.");
        });

        handlers.put("wait_for_external_event", (exec, step, context) -> {
            int nextOrder = nextStepOrder(exec.sortedSteps, step.order);
            String title = resolveTemplate(step.settings.get("title"), context).trim();
            if (title.isBlank()) title = "Wait for external event";
            String instructions = resolveTemplate(step.settings.get("instructions"), context).trim();
            if (instructions.isBlank()) instructions = "Record external event status to resume process.";
            String requiredCsv = resolveTemplate(step.settings.get("required_input_keys"), context).trim();
            if (requiredCsv.isBlank()) requiredCsv = "event_status,event_notes";

            HumanReviewTask review = enqueueHumanReview(
                    exec,
                    context,
                    nextOrder,
                    "wait_for_external_event",
                    title,
                    instructions,
                    requiredCsv,
                    resolveTemplate(step.settings.get("assignee_user_uuid"), context),
                    resolveTemplate(step.settings.get("assignee_role_uuid"), context),
                    resolveTemplate(step.settings.get("queue_permission_key"), context),
                    resolveTemplate(step.settings.get("due_at"), context)
            );
            context.put("vars.last_human_review_uuid", safe(review.reviewUuid));
            context.put("vars.last_human_review_type", safe(review.reviewType));
            return StepOutcome.waitingHuman(review.reviewUuid, "Waiting for external event: " + safe(review.title));
        });

        handlers.put("request_information", (exec, step, context) -> {
            int nextOrder = nextStepOrder(exec.sortedSteps, step.order);
            String title = resolveTemplate(step.settings.get("title"), context).trim();
            if (title.isBlank()) title = "Information request";
            String instructions = resolveTemplate(step.settings.get("instructions"), context).trim();
            String requestTarget = resolveTemplate(step.settings.get("request_target"), context).trim();
            if (!requestTarget.isBlank()) {
                instructions = instructions.isBlank()
                        ? ("Request target: " + requestTarget)
                        : (instructions + "\nTarget: " + requestTarget);
            }
            if (instructions.isBlank()) instructions = "Provide the requested information to continue.";
            String requiredCsv = resolveTemplate(step.settings.get("required_input_keys"), context).trim();
            if (requiredCsv.isBlank()) requiredCsv = "response";

            HumanReviewTask review = enqueueHumanReview(
                    exec,
                    context,
                    nextOrder,
                    "request_information",
                    title,
                    instructions,
                    requiredCsv,
                    resolveTemplate(step.settings.get("assignee_user_uuid"), context),
                    resolveTemplate(step.settings.get("assignee_role_uuid"), context),
                    resolveTemplate(step.settings.get("queue_permission_key"), context),
                    resolveTemplate(step.settings.get("due_at"), context)
            );
            context.put("vars.last_human_review_uuid", safe(review.reviewUuid));
            context.put("vars.last_human_review_type", safe(review.reviewType));
            return StepOutcome.waitingHuman(review.reviewUuid, "Information request queued: " + safe(review.title));
        });

        handlers.put("escalation", (exec, step, context) -> {
            String matterUuid = resolveTemplate(step.settings.get("matter_uuid"), context).trim();
            if (matterUuid.isBlank()) matterUuid = safe(context.get("event.matter_uuid")).trim();
            if (matterUuid.isBlank()) throw new IllegalArgumentException("matter_uuid is required for escalation.");

            String title = resolveTemplate(step.settings.get("title"), context).trim();
            if (title.isBlank()) throw new IllegalArgumentException("title is required for escalation.");

            String dueAt = resolveTemplate(step.settings.get("due_at"), context).trim();
            if (dueAt.isBlank()) dueAt = Instant.now().plusSeconds(4L * 3600L).toString();
            int estimateMinutes = parseInt(resolveTemplate(step.settings.get("estimate_minutes"), context).trim(), 30);
            if (estimateMinutes <= 0) estimateMinutes = 30;

            String actorUser = resolveTemplate(step.settings.get("actor_user_uuid"), context).trim();
            if (actorUser.isBlank()) actorUser = safe(exec.actorUserUuid).trim();

            tasks.TaskRec in = new tasks.TaskRec();
            in.matterUuid = matterUuid;
            in.title = title;
            in.description = resolveTemplate(step.settings.get("description"), context);
            in.status = resolveTemplate(step.settings.get("status"), context).trim();
            if (in.status.isBlank()) in.status = "open";
            in.priority = resolveTemplate(step.settings.get("priority"), context).trim();
            if (in.priority.isBlank()) in.priority = "urgent";
            in.assignmentMode = resolveTemplate(step.settings.get("assignment_mode"), context).trim();
            if (in.assignmentMode.isBlank()) in.assignmentMode = "manual";
            in.assignedUserUuid = resolveTemplate(step.settings.get("assigned_user_uuid"), context).trim();
            in.dueAt = dueAt;
            in.reminderAt = resolveTemplate(step.settings.get("reminder_at"), context).trim();
            in.estimateMinutes = estimateMinutes;

            String assignmentReason = resolveTemplate(step.settings.get("assignment_reason"), context);
            tasks.TaskRec created = tasks.defaultStore().createTask(exec.tenantUuid, in, actorUser, assignmentReason);
            if (created == null) throw new IllegalStateException("Unable to create escalation task.");

            context.put("vars.last_matter_uuid", matterUuid);
            context.put("vars.last_escalation_task_uuid", safe(created.uuid));
            context.put("vars.last_escalation_status", "created");

            String notifyToCsv = resolveTemplate(step.settings.get("notify_to"), context).trim();
            List<String> notifyTo = parseRecipientList(notifyToCsv);
            if (!notifyTo.isEmpty()) {
                String notifySubject = resolveTemplate(step.settings.get("notify_subject"), context).trim();
                if (notifySubject.isBlank()) notifySubject = "Escalation: " + title;
                String notifyBodyText = resolveTemplate(step.settings.get("notify_body_text"), context);
                String notifyBodyHtml = resolveTemplate(step.settings.get("notify_body_html"), context);
                if (notifyBodyText.isBlank() && notifyBodyHtml.isBlank()) {
                    notifyBodyText = "Escalation task created: " + safe(created.title) + " (task_uuid=" + safe(created.uuid) + ")";
                }

                notification_emails.NotificationEmailRequest req = new notification_emails.NotificationEmailRequest(
                        matterUuid,
                        "",
                        "",
                        "",
                        notifySubject,
                        notifyBodyText,
                        notifyBodyHtml,
                        notifyTo,
                        List.of(),
                        List.of(),
                        List.of()
                );
                String emailUuid = notification_emails.defaultStore().enqueue(exec.tenantUuid, actorUser, req);
                context.put("vars.last_escalation_notice_email_uuid", safe(emailUuid));
            } else {
                context.put("vars.last_escalation_notice_email_uuid", "");
            }

            return StepOutcome.continueWithMessage("Escalation task created.");
        });

        handlers.put("audit_snapshot", (exec, step, context) -> {
            String action = resolveTemplate(step.settings.get("action"), context).trim();
            if (action.isBlank()) action = "bpm.audit.snapshot";
            String message = resolveTemplate(step.settings.get("message"), context).trim();

            LinkedHashSet<String> keys = new LinkedHashSet<String>();
            for (String key : parseCsv(resolveTemplate(step.settings.get("include_keys"), context))) {
                String k = safe(key).trim();
                if (!k.isBlank()) keys.add(k);
            }

            ArrayList<String> prefixes = parseCsv(resolveTemplate(step.settings.get("include_prefixes"), context));
            if (prefixes.isEmpty()) prefixes = parseCsv("event.,review.,vars.");
            for (Map.Entry<String, String> e : sanitizeMap(context).entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                for (String prefix : prefixes) {
                    String p = safe(prefix).trim();
                    if (p.isBlank()) continue;
                    if (key.startsWith(p)) {
                        keys.add(key);
                        break;
                    }
                }
            }

            ArrayList<String> sortedKeys = new ArrayList<String>(keys);
            sortedKeys.sort(String::compareTo);
            StringBuilder payload = new StringBuilder(1024);
            for (String key : sortedKeys) {
                payload.append(key).append("=").append(safe(context.get(key))).append("\n");
            }
            String snapshotHash = sha256Hex(payload.toString());

            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("snapshot_hash", snapshotHash);
            details.put("key_count", String.valueOf(sortedKeys.size()));
            details.put("message", message);
            details.put("process_uuid", safe(exec.process == null ? "" : exec.process.processUuid));
            details.put("run_uuid", safe(exec.runUuid));
            details.put("step_id", safe(step.stepId));
            if (!sortedKeys.isEmpty()) {
                details.put("keys", String.join(",", sortedKeys.subList(0, Math.min(sortedKeys.size(), 40))));
            }
            activity_log.defaultStore().logVerbose(
                    action,
                    exec.tenantUuid,
                    exec.actorUserUuid,
                    safe(context.get("event.matter_uuid")),
                    safe(context.get("event.doc_uuid")),
                    details
            );

            String outputVar = normalizeToken(resolveTemplate(step.settings.get("output_var"), context));
            if (outputVar.isBlank()) outputVar = "last_audit_snapshot_hash";
            context.put("vars." + outputVar, snapshotHash);
            context.put("var." + outputVar, snapshotHash);
            context.put("vars.last_audit_snapshot_hash", snapshotHash);
            return StepOutcome.continueWithMessage("Audit snapshot recorded.");
        });

        handlers.put("human_review", (exec, step, context) -> {
            int nextOrder = nextStepOrder(exec.sortedSteps, step.order);
            HumanReviewTask review = enqueueHumanReview(
                    exec,
                    context,
                    nextOrder,
                    resolveTemplate(step.settings.get("review_type"), context),
                    resolveTemplate(step.settings.get("title"), context),
                    resolveTemplate(step.settings.get("instructions"), context),
                    resolveTemplate(step.settings.get("required_input_keys"), context),
                    resolveTemplate(step.settings.get("assignee_user_uuid"), context),
                    resolveTemplate(step.settings.get("assignee_role_uuid"), context),
                    resolveTemplate(step.settings.get("queue_permission_key"), context),
                    resolveTemplate(step.settings.get("due_at"), context)
            );
            context.put("vars.last_human_review_uuid", safe(review.reviewUuid));
            context.put("vars.last_human_review_type", safe(review.reviewType));
            return StepOutcome.waitingHuman(
                    review.reviewUuid,
                    "Human review required: " + safe(review.title)
            );
        });
    }

    private HumanReviewTask enqueueHumanReview(ExecutionContext exec,
                                               LinkedHashMap<String, String> context,
                                               int nextStepOrder,
                                               String reviewType,
                                               String title,
                                               String instructions,
                                               String requiredCsv,
                                               String assigneeUserUuid,
                                               String assigneeRoleUuid,
                                               String queuePermissionKey,
                                               String dueAt) throws Exception {

        HumanReviewTask task = new HumanReviewTask();
        task.reviewUuid = UUID.randomUUID().toString();
        task.reviewType = normalizeReviewType(reviewType);
        task.processUuid = safe(exec.process.processUuid);
        task.processName = safe(exec.process.name);
        task.eventType = safe(exec.eventType);
        task.requestRunUuid = safe(exec.runUuid);
        task.status = "pending";
        task.title = safe(title).trim();
        if (task.title.isBlank()) task.title = "Review process step";
        task.instructions = safe(instructions).trim();
        task.requestedByUserUuid = safe(exec.actorUserUuid);
        task.assigneeUserUuid = safe(assigneeUserUuid).trim();
        task.assigneeRoleUuid = safe(assigneeRoleUuid).trim();
        task.queuePermissionKey = normalizePermissionKey(queuePermissionKey);
        if (task.assigneeUserUuid.isBlank() && task.assigneeRoleUuid.isBlank() && task.queuePermissionKey.isBlank()) {
            task.queuePermissionKey = DEFAULT_REVIEW_QUEUE_PERMISSION;
        }
        task.dueAt = safe(dueAt).trim();
        task.createdAt = nowIso();
        task.nextStepOrder = nextStepOrder;
        task.context = new LinkedHashMap<String, String>(sanitizeMap(context));
        task.requiredInputKeys = parseCsv(requiredCsv);

        ReentrantReadWriteLock lock = lockFor(exec.tenantUuid);
        lock.writeLock().lock();
        try {
            List<HumanReviewTask> rows = readReviewsLocked(exec.tenantUuid);
            rows.add(task);
            writeReviewsLocked(exec.tenantUuid, rows);
        } finally {
            lock.writeLock().unlock();
        }

        activity_log.defaultStore().logWarning(
                "bpm.human_review.queued",
                exec.tenantUuid,
                exec.actorUserUuid,
                safe(context.get("event.matter_uuid")),
                safe(context.get("event.doc_uuid")),
                Map.of(
                        "review_uuid", task.reviewUuid,
                        "review_type", task.reviewType,
                        "process_uuid", task.processUuid,
                        "run_uuid", task.requestRunUuid,
                        "title", task.title,
                        "assignee_user_uuid", task.assigneeUserUuid,
                        "assignee_role_uuid", task.assigneeRoleUuid
                )
        );

        return task;
    }

    private static int nextStepOrder(List<ProcessStep> steps, int currentOrder) {
        if (steps == null || steps.isEmpty()) return 0;
        int curr = Math.max(0, currentOrder);
        for (ProcessStep s : steps) {
            if (s == null) continue;
            if (s.order > curr) return s.order;
        }
        return 0;
    }

    private static boolean matchesAnyTrigger(List<ProcessTrigger> triggers, String eventType, Map<String, String> payload) {
        if (triggers == null || triggers.isEmpty()) return false;
        String evt = safe(eventType).trim().toLowerCase(Locale.ROOT);
        if (evt.isBlank()) return false;

        LinkedHashMap<String, String> event = sanitizeMap(payload);
        for (ProcessTrigger t : triggers) {
            if (t == null || !t.enabled) continue;
            String type = safe(t.type).trim().toLowerCase(Locale.ROOT);
            if (type.isBlank() || !evt.equals(type)) continue;

            ProcessCondition cond = new ProcessCondition();
            cond.source = "event";
            cond.key = safe(t.key);
            cond.op = safe(t.op).isBlank() ? "equals" : safe(t.op);
            cond.value = safe(t.value);

            LinkedHashMap<String, String> ctx = new LinkedHashMap<String, String>();
            for (Map.Entry<String, String> e : event.entrySet()) {
                if (e == null) continue;
                String k = safe(e.getKey()).trim();
                if (k.isBlank()) continue;
                ctx.put("event." + k, safe(e.getValue()));
                if (!ctx.containsKey(k)) ctx.put(k, safe(e.getValue()));
            }

            if (safe(cond.key).trim().isBlank()) return true;
            if (evaluateCondition(cond, ctx)) return true;
        }
        return false;
    }

    private static boolean evaluateConditions(List<ProcessCondition> conditions, Map<String, String> context) {
        if (conditions == null || conditions.isEmpty()) return true;
        for (ProcessCondition c : conditions) {
            if (!evaluateCondition(c, context)) return false;
        }
        return true;
    }

    private static boolean evaluateCondition(ProcessCondition c, Map<String, String> context) {
        if (c == null) return true;

        String key = safe(c.key).trim();
        if (key.isBlank()) return true;

        String source = safe(c.source).trim().toLowerCase(Locale.ROOT);
        if (source.isBlank()) source = "event";

        String op = safe(c.op).trim().toLowerCase(Locale.ROOT);
        if (op.isBlank()) op = "equals";

        String ctxKey = key;
        if ("event".equals(source) && !key.startsWith("event.")) ctxKey = "event." + key;
        if ("vars".equals(source) && !key.startsWith("vars.")) ctxKey = "vars." + key;
        if ("review".equals(source) && !key.startsWith("review.")) ctxKey = "review." + key;

        String actual = safe(context == null ? "" : context.get(ctxKey));
        String expected = resolveTemplate(safe(c.value), context == null ? Map.of() : context);

        switch (op) {
            case "exists":
                return !actual.trim().isBlank();
            case "missing":
                return actual.trim().isBlank();
            case "not_equals":
                return !actual.equalsIgnoreCase(expected);
            case "contains":
                return actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            case "starts_with":
                return actual.toLowerCase(Locale.ROOT).startsWith(expected.toLowerCase(Locale.ROOT));
            case "ends_with":
                return actual.toLowerCase(Locale.ROOT).endsWith(expected.toLowerCase(Locale.ROOT));
            case "in": {
                for (String option : parseCsv(expected)) {
                    if (actual.equalsIgnoreCase(safe(option).trim())) return true;
                }
                return false;
            }
            case "greater_than": {
                Double a = asDouble(actual);
                Double b = asDouble(expected);
                if (a == null || b == null) return false;
                return a.doubleValue() > b.doubleValue();
            }
            case "less_than": {
                Double a = asDouble(actual);
                Double b = asDouble(expected);
                if (a == null || b == null) return false;
                return a.doubleValue() < b.doubleValue();
            }
            case "equals":
            default:
                return actual.equalsIgnoreCase(expected);
        }
    }

    private static Double asDouble(String raw) {
        try {
            return Double.valueOf(Double.parseDouble(safe(raw).trim()));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveTemplate(String raw, Map<String, String> context) {
        String in = safe(raw);
        if (in.isBlank()) return "";

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}").matcher(in);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String token = safe(m.group(1)).trim();
            String val = safe(context == null ? "" : context.get(token));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static ProcessDefinition sanitizeProcess(ProcessDefinition incoming) {
        ProcessDefinition out = new ProcessDefinition();
        if (incoming == null) return out;

        out.processUuid = safe(incoming.processUuid).trim();
        out.name = safe(incoming.name).trim();
        if (out.name.isBlank()) out.name = "Business Process";
        out.description = safe(incoming.description).trim();
        out.enabled = incoming.enabled;

        ArrayList<ProcessTrigger> triggers = new ArrayList<ProcessTrigger>();
        if (incoming.triggers != null) {
            for (ProcessTrigger t : incoming.triggers) {
                if (t == null) continue;
                ProcessTrigger next = new ProcessTrigger();
                next.type = safe(t.type).trim().toLowerCase(Locale.ROOT);
                if (next.type.isBlank()) continue;
                next.key = safe(t.key).trim();
                next.op = safe(t.op).trim();
                if (next.op.isBlank()) next.op = "equals";
                next.value = safe(t.value);
                next.enabled = t.enabled;
                triggers.add(next);
            }
        }

        if (triggers.isEmpty()) {
            ProcessTrigger manual = new ProcessTrigger();
            manual.type = "manual";
            manual.enabled = true;
            triggers.add(manual);
        }

        out.triggers = triggers;

        ArrayList<ProcessStep> steps = new ArrayList<ProcessStep>();
        if (incoming.steps != null) {
            for (int i = 0; i < incoming.steps.size(); i++) {
                ProcessStep s = incoming.steps.get(i);
                if (s == null) continue;
                ProcessStep next = new ProcessStep();
                next.stepId = safe(s.stepId).trim();
                if (next.stepId.isBlank()) next.stepId = "step_" + (i + 1);
                next.order = s.order <= 0 ? (i + 1) * 10 : s.order;
                next.action = safe(s.action).trim().toLowerCase(Locale.ROOT);
                if (next.action.isBlank()) continue;
                next.label = safe(s.label).trim();
                if (next.label.isBlank()) next.label = next.action;
                next.enabled = s.enabled;
                next.settings = sanitizeMap(s.settings);
                next.conditions = sanitizeConditions(s.conditions);
                steps.add(next);
            }
        }

        steps.sort(Comparator.comparingInt(a -> Math.max(0, a.order)));
        out.steps = steps;
        out.updatedAt = safe(incoming.updatedAt);
        return out;
    }

    private static ArrayList<ProcessCondition> sanitizeConditions(List<ProcessCondition> input) {
        ArrayList<ProcessCondition> out = new ArrayList<ProcessCondition>();
        if (input == null) return out;
        for (ProcessCondition c : input) {
            if (c == null) continue;
            ProcessCondition next = new ProcessCondition();
            next.source = safe(c.source).trim().toLowerCase(Locale.ROOT);
            if (next.source.isBlank()) next.source = "event";
            next.key = safe(c.key).trim();
            if (next.key.isBlank()) continue;
            next.op = safe(c.op).trim().toLowerCase(Locale.ROOT);
            if (next.op.isBlank()) next.op = "equals";
            next.value = safe(c.value);
            out.add(next);
        }
        return out;
    }

    private static ArrayList<ProcessStep> sortedEnabledSteps(List<ProcessStep> steps) {
        ArrayList<ProcessStep> out = new ArrayList<ProcessStep>();
        if (steps == null) return out;
        for (ProcessStep s : steps) {
            if (s == null || !s.enabled) continue;
            out.add(s);
        }
        out.sort(Comparator.comparingInt(a -> Math.max(0, a.order)));
        return out;
    }

    private static void sortProcesses(List<ProcessDefinition> rows) {
        if (rows == null) return;
        rows.sort(Comparator.comparing(a -> safe(a.name).toLowerCase(Locale.ROOT)));
    }

    private static ProcessDefinition readProcessFile(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return null;
        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return null;

        ProcessDefinition out = new ProcessDefinition();
        out.processUuid = safe(root.getAttribute("uuid")).trim();
        if (out.processUuid.isBlank()) {
            String fn = safe(p.getFileName() == null ? "" : p.getFileName().toString());
            out.processUuid = fn.endsWith(".xml") ? fn.substring(0, fn.length() - 4) : fn;
        }
        out.enabled = parseBool(root.getAttribute("enabled"), true);
        out.updatedAt = safe(root.getAttribute("updated_at"));

        out.name = text(root, "name");
        out.description = text(root, "description");

        Element triggersEl = firstChild(root, "triggers");
        if (triggersEl != null) {
            NodeList nl = triggersEl.getElementsByTagName("trigger");
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                ProcessTrigger t = new ProcessTrigger();
                t.type = safe(text(e, "type")).trim().toLowerCase(Locale.ROOT);
                t.key = safe(text(e, "key")).trim();
                t.op = safe(text(e, "op")).trim();
                if (t.op.isBlank()) t.op = "equals";
                t.value = safe(text(e, "value"));
                t.enabled = parseBool(e.getAttribute("enabled"), true);
                if (!t.type.isBlank()) out.triggers.add(t);
            }
        }

        Element stepsEl = firstChild(root, "steps");
        if (stepsEl != null) {
            NodeList nl = stepsEl.getElementsByTagName("step");
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                ProcessStep s = new ProcessStep();
                s.stepId = safe(e.getAttribute("id")).trim();
                if (s.stepId.isBlank()) s.stepId = "step_" + (i + 1);
                s.order = parseInt(e.getAttribute("order"), (i + 1) * 10);
                s.action = safe(e.getAttribute("action")).trim().toLowerCase(Locale.ROOT);
                s.enabled = parseBool(e.getAttribute("enabled"), true);
                s.label = text(e, "label");

                Element settingsEl = firstChild(e, "settings");
                if (settingsEl != null) {
                    NodeList sl = settingsEl.getElementsByTagName("setting");
                    for (int si = 0; si < sl.getLength(); si++) {
                        Node sn = sl.item(si);
                        if (!(sn instanceof Element)) continue;
                        Element se = (Element) sn;
                        String key = safe(se.getAttribute("key")).trim();
                        if (key.isBlank()) continue;
                        s.settings.put(key, safe(se.getTextContent()));
                    }
                }

                Element conditionsEl = firstChild(e, "conditions");
                if (conditionsEl != null) {
                    NodeList cl = conditionsEl.getElementsByTagName("condition");
                    for (int ci = 0; ci < cl.getLength(); ci++) {
                        Node cn = cl.item(ci);
                        if (!(cn instanceof Element)) continue;
                        Element ce = (Element) cn;
                        ProcessCondition c = new ProcessCondition();
                        c.source = safe(ce.getAttribute("source")).trim().toLowerCase(Locale.ROOT);
                        if (c.source.isBlank()) c.source = "event";
                        c.key = safe(ce.getAttribute("key")).trim();
                        c.op = safe(ce.getAttribute("op")).trim().toLowerCase(Locale.ROOT);
                        if (c.op.isBlank()) c.op = "equals";
                        c.value = safe(ce.getAttribute("value"));
                        if (!c.key.isBlank()) s.conditions.add(c);
                    }
                }

                if (!s.action.isBlank()) out.steps.add(s);
            }
        }

        return sanitizeProcess(out);
    }

    private static String processToXml(ProcessDefinition process) {
        ProcessDefinition p = sanitizeProcess(process);

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<business_process uuid=\"").append(xmlAttr(p.processUuid)).append("\" enabled=\"")
                .append(p.enabled ? "true" : "false")
                .append("\" updated_at=\"").append(xmlAttr(p.updatedAt)).append("\">\n");
        sb.append("  <name>").append(xmlText(p.name)).append("</name>\n");
        sb.append("  <description>").append(xmlText(p.description)).append("</description>\n");

        sb.append("  <triggers>\n");
        for (ProcessTrigger t : p.triggers) {
            if (t == null) continue;
            sb.append("    <trigger enabled=\"").append(t.enabled ? "true" : "false").append("\">\n");
            sb.append("      <type>").append(xmlText(t.type)).append("</type>\n");
            sb.append("      <key>").append(xmlText(t.key)).append("</key>\n");
            sb.append("      <op>").append(xmlText(t.op)).append("</op>\n");
            sb.append("      <value>").append(xmlText(t.value)).append("</value>\n");
            sb.append("    </trigger>\n");
        }
        sb.append("  </triggers>\n");

        sb.append("  <steps>\n");
        for (ProcessStep s : p.steps) {
            if (s == null) continue;
            sb.append("    <step id=\"").append(xmlAttr(s.stepId)).append("\" order=\"")
                    .append(Math.max(0, s.order)).append("\" action=\"")
                    .append(xmlAttr(s.action)).append("\" enabled=\"")
                    .append(s.enabled ? "true" : "false")
                    .append("\">\n");
            sb.append("      <label>").append(xmlText(s.label)).append("</label>\n");

            sb.append("      <settings>\n");
            for (Map.Entry<String, String> e : sanitizeMap(s.settings).entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                sb.append("        <setting key=\"").append(xmlAttr(key)).append("\">")
                        .append(xmlText(e.getValue())).append("</setting>\n");
            }
            sb.append("      </settings>\n");

            sb.append("      <conditions>\n");
            for (ProcessCondition c : sanitizeConditions(s.conditions)) {
                if (c == null) continue;
                sb.append("        <condition source=\"").append(xmlAttr(c.source))
                        .append("\" key=\"").append(xmlAttr(c.key))
                        .append("\" op=\"").append(xmlAttr(c.op))
                        .append("\" value=\"").append(xmlAttr(c.value))
                        .append("\"/>\n");
            }
            sb.append("      </conditions>\n");
            sb.append("    </step>\n");
        }
        sb.append("  </steps>\n");
        sb.append("</business_process>\n");

        return sb.toString();
    }

    private static List<HumanReviewTask> readReviewsLocked(String tenantUuid) throws Exception {
        ArrayList<HumanReviewTask> out = new ArrayList<HumanReviewTask>();
        Path p = reviewsPath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("review");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            HumanReviewTask t = new HumanReviewTask();
            t.reviewUuid = text(e, "review_uuid");
            t.reviewType = normalizeReviewType(text(e, "review_type"));
            t.processUuid = text(e, "process_uuid");
            t.processName = text(e, "process_name");
            t.requestRunUuid = text(e, "request_run_uuid");
            t.resumedRunUuid = text(e, "resumed_run_uuid");
            t.eventType = text(e, "event_type");
            t.status = text(e, "status");
            if (t.status.isBlank()) t.status = "pending";
            t.title = text(e, "title");
            t.instructions = text(e, "instructions");
            t.comment = text(e, "comment");
            t.requestedByUserUuid = text(e, "requested_by_user_uuid");
            t.reviewedByUserUuid = text(e, "reviewed_by_user_uuid");
            t.assigneeUserUuid = text(e, "assignee_user_uuid");
            t.assigneeRoleUuid = text(e, "assignee_role_uuid");
            t.queuePermissionKey = normalizePermissionKey(text(e, "queue_permission_key"));
            if (t.assigneeUserUuid.isBlank() && t.assigneeRoleUuid.isBlank() && t.queuePermissionKey.isBlank()) {
                t.queuePermissionKey = DEFAULT_REVIEW_QUEUE_PERMISSION;
            }
            t.dueAt = text(e, "due_at");
            t.createdAt = text(e, "created_at");
            t.resolvedAt = text(e, "resolved_at");
            t.nextStepOrder = parseInt(text(e, "next_step_order"), 0);
            t.resumeStatus = text(e, "resume_status");
            t.resumeMessage = text(e, "resume_message");
            t.requiredInputKeys = parseCsv(text(e, "required_input_keys"));

            Element contextEl = firstChild(e, "context");
            if (contextEl != null) {
                NodeList kl = contextEl.getElementsByTagName("item");
                for (int ki = 0; ki < kl.getLength(); ki++) {
                    Node kn = kl.item(ki);
                    if (!(kn instanceof Element)) continue;
                    Element ke = (Element) kn;
                    String key = safe(ke.getAttribute("key")).trim();
                    if (key.isBlank()) continue;
                    t.context.put(key, safe(ke.getTextContent()));
                }
            }

            Element inputEl = firstChild(e, "input");
            if (inputEl != null) {
                NodeList il = inputEl.getElementsByTagName("item");
                for (int ii = 0; ii < il.getLength(); ii++) {
                    Node in = il.item(ii);
                    if (!(in instanceof Element)) continue;
                    Element ie = (Element) in;
                    String key = safe(ie.getAttribute("key")).trim();
                    if (key.isBlank()) continue;
                    t.input.put(key, safe(ie.getTextContent()));
                }
            }

            if (!safe(t.reviewUuid).isBlank()) out.add(t);
        }

        return out;
    }

    private static void writeReviewsLocked(String tenantUuid, List<HumanReviewTask> rows) throws Exception {
        Path p = reviewsPath(tenantUuid);
        Files.createDirectories(p.getParent());

        String now = nowIso();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<bpm_reviews updated_at=\"").append(xmlAttr(now)).append("\">\n");

        List<HumanReviewTask> src = rows == null ? List.of() : rows;
        for (HumanReviewTask t : src) {
            if (t == null) continue;
            if (safe(t.reviewUuid).trim().isBlank()) continue;

            sb.append("  <review>\n");
            writeTag(sb, "review_uuid", t.reviewUuid);
            writeTag(sb, "review_type", normalizeReviewType(t.reviewType));
            writeTag(sb, "process_uuid", t.processUuid);
            writeTag(sb, "process_name", t.processName);
            writeTag(sb, "request_run_uuid", t.requestRunUuid);
            writeTag(sb, "resumed_run_uuid", t.resumedRunUuid);
            writeTag(sb, "event_type", t.eventType);
            writeTag(sb, "status", t.status);
            writeTag(sb, "title", t.title);
            writeTag(sb, "instructions", t.instructions);
            writeTag(sb, "comment", t.comment);
            writeTag(sb, "requested_by_user_uuid", t.requestedByUserUuid);
            writeTag(sb, "reviewed_by_user_uuid", t.reviewedByUserUuid);
            writeTag(sb, "assignee_user_uuid", t.assigneeUserUuid);
            writeTag(sb, "assignee_role_uuid", t.assigneeRoleUuid);
            writeTag(sb, "queue_permission_key", normalizePermissionKey(t.queuePermissionKey));
            writeTag(sb, "due_at", t.dueAt);
            writeTag(sb, "created_at", t.createdAt);
            writeTag(sb, "resolved_at", t.resolvedAt);
            writeTag(sb, "next_step_order", String.valueOf(Math.max(0, t.nextStepOrder)));
            writeTag(sb, "resume_status", t.resumeStatus);
            writeTag(sb, "resume_message", t.resumeMessage);
            writeTag(sb, "required_input_keys", String.join(",", parseCsv(t.requiredInputKeys)));

            sb.append("    <context>\n");
            for (Map.Entry<String, String> e : sanitizeMap(t.context).entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                sb.append("      <item key=\"").append(xmlAttr(key)).append("\">")
                        .append(xmlText(e.getValue())).append("</item>\n");
            }
            sb.append("    </context>\n");

            sb.append("    <input>\n");
            for (Map.Entry<String, String> e : sanitizeMap(t.input).entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                sb.append("      <item key=\"").append(xmlAttr(key)).append("\">")
                        .append(xmlText(e.getValue())).append("</item>\n");
            }
            sb.append("    </input>\n");

            sb.append("  </review>\n");
        }

        sb.append("</bpm_reviews>\n");
        writeAtomic(p, sb.toString());
    }

    private static HumanReviewTask cloneReview(HumanReviewTask in) {
        HumanReviewTask out = new HumanReviewTask();
        if (in == null) return out;
        out.reviewUuid = in.reviewUuid;
        out.reviewType = in.reviewType;
        out.processUuid = in.processUuid;
        out.processName = in.processName;
        out.requestRunUuid = in.requestRunUuid;
        out.resumedRunUuid = in.resumedRunUuid;
        out.eventType = in.eventType;
        out.status = in.status;
        out.title = in.title;
        out.instructions = in.instructions;
        out.comment = in.comment;
        out.requestedByUserUuid = in.requestedByUserUuid;
        out.reviewedByUserUuid = in.reviewedByUserUuid;
        out.assigneeUserUuid = in.assigneeUserUuid;
        out.assigneeRoleUuid = in.assigneeRoleUuid;
        out.queuePermissionKey = in.queuePermissionKey;
        out.dueAt = in.dueAt;
        out.createdAt = in.createdAt;
        out.resolvedAt = in.resolvedAt;
        out.nextStepOrder = in.nextStepOrder;
        out.resumeStatus = in.resumeStatus;
        out.resumeMessage = in.resumeMessage;
        out.context = sanitizeMap(in.context);
        out.input = sanitizeMap(in.input);
        out.requiredInputKeys = parseCsv(in.requiredInputKeys);
        return out;
    }

    private static boolean canUserAccessReview(String tenantUuid, HumanReviewTask review, String viewerUserUuid) {
        String tu = safeFileToken(tenantUuid);
        String viewer = safe(viewerUserUuid).trim();
        if (tu.isBlank() || viewer.isBlank() || review == null) return false;

        String assigneeUser = safe(review.assigneeUserUuid).trim();
        if (!assigneeUser.isBlank()) {
            return assigneeUser.equals(viewer);
        }

        String assigneeRole = safe(review.assigneeRoleUuid).trim();
        if (!assigneeRole.isBlank()) {
            return userHasRole(tu, viewer, assigneeRole);
        }

        String permissionKey = normalizePermissionKey(review.queuePermissionKey);
        if (permissionKey.isBlank()) return true;
        if (DEFAULT_REVIEW_QUEUE_PERMISSION.equals(permissionKey)) return true;
        return hasUserPermission(tu, viewer, permissionKey);
    }

    private static void ensureReviewerAuthorized(String tenantUuid,
                                                 HumanReviewTask review,
                                                 String reviewedByUserUuid) {
        String reviewer = safe(reviewedByUserUuid).trim();
        if (reviewer.isBlank()) {
            throw new IllegalArgumentException("reviewed_by_user_uuid is required.");
        }
        if (canUserAccessReview(tenantUuid, review, reviewer)) return;

        String assigneeUser = safe(review == null ? "" : review.assigneeUserUuid).trim();
        if (!assigneeUser.isBlank()) {
            throw new IllegalArgumentException("Review is assigned to a different user.");
        }
        String assigneeRole = safe(review == null ? "" : review.assigneeRoleUuid).trim();
        if (!assigneeRole.isBlank()) {
            throw new IllegalArgumentException("Review is assigned to a different role.");
        }
        throw new IllegalArgumentException("Reviewer does not have permission to complete this review.");
    }

    private static boolean hasUserPermission(String tenantUuid, String userUuid, String permissionKey) {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        String key = normalizePermissionKey(permissionKey);
        if (tu.isBlank() || uu.isBlank()) return false;
        if (key.isBlank()) key = DEFAULT_REVIEW_QUEUE_PERMISSION;

        try {
            users_roles users = users_roles.defaultStore();
            users_roles.UserRec user = users.getUserByUuid(tu, uu);
            if (user == null || !user.enabled) return false;
            users_roles.RoleRec role = users.getRoleByUuid(tu, safe(user.roleUuid));

            LinkedHashMap<String, String> rolePermissions = new LinkedHashMap<String, String>();
            if (role != null && role.permissions != null) rolePermissions.putAll(role.permissions);

            LinkedHashMap<String, String> effective = permission_layers.defaultStore()
                    .resolveEffectivePermissions(tu, uu, rolePermissions);
            if ("true".equalsIgnoreCase(safe(effective.get("tenant_admin")).trim())) return true;
            return "true".equalsIgnoreCase(safe(effective.get(key)).trim());
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean userHasRole(String tenantUuid, String userUuid, String roleUuid) {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        String role = safe(roleUuid).trim();
        if (tu.isBlank() || uu.isBlank() || role.isBlank()) return false;
        try {
            users_roles.UserRec user = users_roles.defaultStore().getUserByUuid(tu, uu);
            if (user == null || !user.enabled) return false;
            return role.equals(safe(user.roleUuid).trim());
        } catch (Exception ex) {
            return false;
        }
    }

    private static String normalizeReviewType(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) v = "human_review";
        v = v.replaceAll("[^a-z0-9._-]", "_").replaceAll("_+", "_");
        while (v.startsWith("_")) v = v.substring(1);
        while (v.endsWith("_")) v = v.substring(0, v.length() - 1);
        if (v.isBlank()) v = "human_review";
        if (v.length() > 80) v = v.substring(0, 80);
        return v;
    }

    private static String normalizePermissionKey(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "";
        v = v.replaceAll("[^a-z0-9._-]", "_").replaceAll("_+", "_");
        while (v.startsWith("_")) v = v.substring(1);
        while (v.endsWith("_")) v = v.substring(0, v.length() - 1);
        if (v.length() > 180) v = v.substring(0, 180);
        return v;
    }

    private static List<String> parseRecipientList(String raw) {
        String v = safe(raw).replace(';', ',');
        return parseCsv(v);
    }

    private static LocalDate parseFlexibleDate(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) return null;

        try {
            return Instant.parse(s).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(s).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(s).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ignored) {
        }

        int t = s.indexOf('T');
        if (t > 0) {
            try {
                return LocalDate.parse(s.substring(0, t));
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static LocalDate addDays(LocalDate start, int offsetDays, boolean businessDays) {
        if (start == null) return null;
        if (!businessDays || offsetDays == 0) return start.plusDays(offsetDays);

        int step = offsetDays > 0 ? 1 : -1;
        int remaining = Math.abs(offsetDays);
        LocalDate cursor = start;
        while (remaining > 0) {
            cursor = cursor.plusDays(step);
            DayOfWeek dow = cursor.getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) continue;
            remaining--;
        }
        return cursor;
    }

    private static BigDecimal parseMoney(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) return null;
        s = s.replace("$", "").replace(",", "").trim();
        if (s.isBlank()) return null;
        try {
            return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String moneyToString(BigDecimal value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(safe(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 0xff)));
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static String normalizeComparable(String raw) {
        String s = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return "";
        s = s.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
        if (s.length() > 220) s = s.substring(0, 220);
        return s;
    }

    private static void addConflictTokens(Set<String> out, contacts.ContactRec c) {
        if (out == null || c == null) return;
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        candidates.add(normalizeComparable(c.displayName));
        candidates.add(normalizeComparable((safe(c.givenName) + " " + safe(c.surname)).trim()));
        candidates.add(normalizeComparable(c.companyName));
        candidates.add(normalizeComparable(c.emailPrimary));
        candidates.add(normalizeComparable(c.emailSecondary));
        candidates.add(normalizeComparable(c.emailTertiary));
        for (String candidate : candidates) {
            if (candidate.isBlank()) continue;
            if (candidate.length() < 3) continue;
            out.add(candidate);
        }
    }

    private static boolean isPotentialConflict(Set<String> partyTokens, contacts.ContactRec c) {
        if (partyTokens == null || partyTokens.isEmpty() || c == null) return false;
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        addConflictTokens(candidates, c);
        if (candidates.isEmpty()) return false;

        for (String token : partyTokens) {
            String normalizedToken = normalizeComparable(token);
            if (normalizedToken.isBlank() || normalizedToken.length() < 3) continue;
            for (String candidate : candidates) {
                if (candidate.isBlank()) continue;
                if (candidate.equals(normalizedToken)) return true;
                if (normalizedToken.length() >= 6 && candidate.contains(normalizedToken)) return true;
                if (candidate.length() >= 6 && normalizedToken.contains(candidate)) return true;
            }
        }
        return false;
    }

    private static LinkedHashMap<String, String> buildMatterMergeValues(String tenantUuid, String matterUuid) throws Exception {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();

        String tenantLabel = "";
        tenants.Tenant t = tenants.defaultStore().getByUuid(tenantUuid);
        if (t != null) tenantLabel = safe(t.label);

        matters.MatterRec m = matters.defaultStore().getByUuid(tenantUuid, matterUuid);

        LinkedHashMap<String, String> tenantKv = new LinkedHashMap<String, String>(tenant_fields.defaultStore().read(tenantUuid));
        LinkedHashMap<String, String> caseKv = new LinkedHashMap<String, String>(case_fields.defaultStore().read(tenantUuid, matterUuid));
        LinkedHashMap<String, String> listKv = new LinkedHashMap<String, String>(case_list_items.defaultStore().read(tenantUuid, matterUuid));
        LinkedHashMap<String, String> settingsKv = new LinkedHashMap<String, String>(tenant_settings.defaultStore().read(tenantUuid));

        putToken(out, "tenant.uuid", tenantUuid);
        putToken(out, "tenant.label", tenantLabel);

        if (m != null) {
            String cause = safe(caseKv.get("cause_docket_number"));
            if (cause.isBlank()) cause = safe(m.causeDocketNumber);
            String county = safe(caseKv.get("county"));
            if (county.isBlank()) county = safe(m.county);

            putToken(out, "case.uuid", safe(m.uuid));
            putToken(out, "case.label", safe(m.label));
            putToken(out, "case.cause_docket_number", cause);
            putToken(out, "case.county", county);
        }

        tenant_fields tenantStore = tenant_fields.defaultStore();
        for (Map.Entry<String, String> e : tenantKv.entrySet()) {
            if (e == null) continue;
            String nk = tenantStore.normalizeKey(e.getKey());
            if (nk.isBlank()) continue;
            String v = safe(e.getValue());
            putToken(out, "tenant." + nk, v);
            if (!out.containsKey("kv." + nk)) putToken(out, "kv." + nk, v);
            if (!out.containsKey(nk)) putToken(out, nk, v);
        }

        case_fields caseStore = case_fields.defaultStore();
        for (Map.Entry<String, String> e : caseKv.entrySet()) {
            if (e == null) continue;
            String nk = caseStore.normalizeKey(e.getKey());
            if (nk.isBlank()) continue;
            String v = safe(e.getValue());
            putToken(out, "case." + nk, v);
            putToken(out, "kv." + nk, v);
            putToken(out, nk, v);
        }

        case_list_items listStore = case_list_items.defaultStore();
        for (Map.Entry<String, String> e : listKv.entrySet()) {
            if (e == null) continue;
            String nk = listStore.normalizeKey(e.getKey());
            if (nk.isBlank()) continue;
            String v = safe(e.getValue());
            putToken(out, "case." + nk, v);
            putToken(out, "kv." + nk, v);
            putToken(out, nk, v);
        }

        boolean advanced = "true".equalsIgnoreCase(safe(settingsKv.get("feature_advanced_assembly")));
        putToken(out, "tenant.advanced_assembly_enabled", advanced ? "true" : "false");
        putToken(out, "advanced_assembly_enabled", advanced ? "true" : "false");
        putToken(out, "kv.advanced_assembly_enabled", advanced ? "true" : "false");

        boolean async = "true".equalsIgnoreCase(safe(settingsKv.get("feature_async_sync")));
        putToken(out, "tenant.async_sync_enabled", async ? "true" : "false");
        putToken(out, "async_sync_enabled", async ? "true" : "false");
        putToken(out, "kv.async_sync_enabled", async ? "true" : "false");

        return out;
    }

    private static void putToken(Map<String, String> values, String key, String value) {
        if (values == null) return;
        String k = safe(key).trim();
        if (k.isBlank()) return;
        values.put(k, safe(value));
    }

    private static void applyLiteralOverride(Map<String, String> mergeValues, String tokenLiteral, String value) {
        if (mergeValues == null) return;
        String key = safe(tokenLiteral).trim();
        if (key.isBlank()) return;

        String v = safe(value);
        mergeValues.put(key, v);

        int dot = key.indexOf('.');
        if (dot <= 0 || dot + 1 >= key.length()) return;

        String prefix = safe(key.substring(0, dot)).toLowerCase(Locale.ROOT);
        String tail = safe(key.substring(dot + 1)).trim();
        if (tail.isBlank()) return;

        if ("case".equals(prefix) || "kv".equals(prefix)) {
            mergeValues.put("case." + tail, v);
            mergeValues.put("kv." + tail, v);
            mergeValues.put(tail, v);
        } else if ("tenant".equals(prefix)) {
            mergeValues.put("tenant." + tail, v);
            mergeValues.put("kv." + tail, v);
            mergeValues.put(tail, v);
        }
    }

    private static String suggestOutputFileName(String requested, String templateLabel, String ext) {
        String v = safe(requested).trim();
        if (v.isBlank()) {
            String base = safe(templateLabel).trim();
            if (base.isBlank()) base = "assembled";
            base = base.replaceAll("[^A-Za-z0-9.]", "_");
            v = base;
        }
        v = v.replaceAll("[^A-Za-z0-9.]", "_");
        if (v.isBlank()) v = "assembled";
        if (!v.contains(".")) {
            String e = safe(ext).trim().toLowerCase(Locale.ROOT);
            if (!e.isBlank()) v = v + "." + e;
        }
        return v;
    }

    private static RunHealthIssue baseRunIssue(RunResult run) {
        RunHealthIssue issue = new RunHealthIssue();
        if (run == null) return issue;
        issue.runUuid = safe(run.runUuid);
        issue.processUuid = safe(run.processUuid);
        issue.processName = safe(run.processName);
        issue.status = safe(run.status);
        issue.startedAt = safe(run.startedAt);
        issue.completedAt = safe(run.completedAt);
        issue.humanReviewUuid = safe(run.humanReviewUuid);
        return issue;
    }

    private static int severityRank(String severity) {
        String s = safe(severity).trim().toLowerCase(Locale.ROOT);
        if ("high".equals(s)) return 3;
        if ("medium".equals(s)) return 2;
        if ("low".equals(s)) return 1;
        return 0;
    }

    private static boolean hasLogCode(List<RunLogEntry> logs, String code) {
        String target = safe(code).trim();
        if (target.isBlank()) return false;
        List<RunLogEntry> xs = logs == null ? List.of() : logs;
        for (RunLogEntry row : xs) {
            if (row == null) continue;
            if (target.equalsIgnoreCase(safe(row.code).trim())) return true;
        }
        return false;
    }

    private static String buildAgingAlarmEmailBody(String tenantUuid,
                                                   int runningStaleMinutes,
                                                   int reviewStaleMinutes,
                                                   List<RunHealthIssue> issues) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("Business process aging alarm detected.\n\n");
        sb.append("Tenant: ").append(safe(tenantUuid)).append("\n");
        sb.append("Detected at: ").append(nowIso()).append("\n");
        sb.append("Running stale threshold (minutes): ").append(Math.max(1, runningStaleMinutes)).append("\n");
        sb.append("Human-review stale threshold (minutes): ").append(Math.max(1, reviewStaleMinutes)).append("\n");
        sb.append("Issue count: ").append(issues == null ? 0 : issues.size()).append("\n\n");
        sb.append("Issues:\n");
        if (issues != null) {
            for (RunHealthIssue issue : issues) {
                if (issue == null) continue;
                sb.append("- type=").append(safe(issue.issueType));
                sb.append(", severity=").append(safe(issue.severity));
                sb.append(", age_minutes=").append(Math.max(0L, issue.ageMinutes));
                sb.append(", run_uuid=").append(safe(issue.runUuid));
                sb.append(", process=").append(safe(issue.processName));
                sb.append(", status=").append(safe(issue.status)).append("\n");
                String summary = safe(issue.summary).trim();
                if (!summary.isBlank()) sb.append("  summary: ").append(summary).append("\n");
                String rec = safe(issue.recommendation).trim();
                if (!rec.isBlank()) sb.append("  recommendation: ").append(rec).append("\n");
            }
        }
        sb.append("\nOpen Business Processes > Run Health Inspector to triage.\n");
        return sb.toString();
    }

    private static List<String> resolveTenantAdminEmails(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        try {
            users_roles ur = users_roles.defaultStore();
            permission_layers layers = permission_layers.defaultStore();

            LinkedHashMap<String, LinkedHashMap<String, String>> rolePermsByUuid = new LinkedHashMap<String, LinkedHashMap<String, String>>();
            for (users_roles.RoleRec role : ur.listRoles(tu)) {
                if (role == null) continue;
                String roleUuid = safe(role.uuid).trim();
                if (roleUuid.isBlank()) continue;
                rolePermsByUuid.put(roleUuid, new LinkedHashMap<String, String>(role.permissions));
            }

            LinkedHashSet<String> recipients = new LinkedHashSet<String>();
            for (users_roles.UserRec user : ur.listUsers(tu)) {
                if (user == null || !user.enabled) continue;
                String userUuid = safe(user.uuid).trim();
                if (userUuid.isBlank()) continue;
                String email = safe(user.emailAddress).trim().toLowerCase(Locale.ROOT);
                if (!looksLikeEmail(email)) continue;

                LinkedHashMap<String, String> rolePerms = rolePermsByUuid.getOrDefault(
                        safe(user.roleUuid).trim(),
                        new LinkedHashMap<String, String>()
                );
                LinkedHashMap<String, String> effective = layers.resolveEffectivePermissions(tu, userUuid, rolePerms);
                if (!"true".equalsIgnoreCase(safe(effective.get("tenant_admin")).trim())) continue;
                recipients.add(email);
            }
            return new ArrayList<String>(recipients);
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Unable to resolve tenant admin emails for tenant=" + tu + ": " + safe(ex.getMessage()), ex);
            return List.of();
        }
    }

    private static boolean looksLikeEmail(String raw) {
        String v = safe(raw).trim();
        int at = v.indexOf('@');
        if (at <= 0 || at == v.length() - 1) return false;
        return v.indexOf('.', at + 1) > at + 1;
    }

    private static void markReviewErrorLocked(String tenantUuid,
                                              String reviewUuid,
                                              String actorUserUuid,
                                              String message) throws Exception {
        String target = safe(reviewUuid).trim();
        if (target.isBlank()) return;

        List<HumanReviewTask> rows = readReviewsLocked(tenantUuid);
        boolean changed = false;
        for (HumanReviewTask row : rows) {
            if (row == null) continue;
            if (!target.equals(safe(row.reviewUuid).trim())) continue;
            if (!"pending".equalsIgnoreCase(safe(row.status))) continue;

            row.status = "error";
            row.reviewedByUserUuid = safe(actorUserUuid);
            row.resolvedAt = nowIso();
            row.resumeStatus = "error";
            row.resumeMessage = safe(message).isBlank() ? "Run marked failed by operator." : safe(message);

            String comment = safe(row.comment).trim();
            String note = safe(message).trim();
            if (!note.isBlank()) {
                if (comment.isBlank()) row.comment = note;
                else if (!comment.contains(note)) row.comment = comment + " | " + note;
            }
            changed = true;
            break;
        }

        if (changed) {
            writeReviewsLocked(tenantUuid, rows);
        }
    }

    private static void updateRequestRunAfterReviewLocked(String tenantUuid,
                                                          HumanReviewTask review,
                                                          boolean approved,
                                                          RunResult resumed) throws Exception {
        if (review == null) return;
        String requestRunUuid = safe(review.requestRunUuid).trim();
        if (requestRunUuid.isBlank()) return;

        Path runFile = runPath(tenantUuid, requestRunUuid);
        if (!Files.exists(runFile)) return;
        RunSnapshot snap = readRunSnapshot(runFile);
        if (snap == null || snap.result == null) return;

        String current = safe(snap.result.status).trim().toLowerCase(Locale.ROOT);
        if (!"waiting_human_review".equals(current) && !"running".equals(current)) return;

        String now = nowIso();
        snap.result.completedAt = now;

        if (approved) {
            String downstreamRunUuid = safe(resumed == null ? review.resumedRunUuid : resumed.runUuid);
            String downstreamStatus = safe(resumed == null ? review.resumeStatus : resumed.status).trim().toLowerCase(Locale.ROOT);

            if ("failed".equals(downstreamStatus)) snap.result.status = "failed";
            else if ("completed_with_errors".equals(downstreamStatus)) snap.result.status = "completed_with_errors";
            else snap.result.status = "completed";

            String msg = "Human review approved.";
            if (!downstreamRunUuid.isBlank()) msg = msg + " Continued in run " + downstreamRunUuid + ".";
            if (!downstreamStatus.isBlank()) msg = msg + " Resume status=" + downstreamStatus + ".";
            snap.result.message = msg;
            snap.logs.add(new RunLogEntry(now, "info", "review.approved", "", msg));
        } else {
            snap.result.status = "failed";
            String msg = "Human review rejected.";
            String comment = safe(review.comment).trim();
            if (!comment.isBlank()) msg = msg + " Comment: " + comment;
            snap.result.message = msg;
            snap.logs.add(new RunLogEntry(now, "warning", "review.rejected", "", msg));
        }

        writeRunSnapshot(tenantUuid, snap);
    }

    private static LinkedHashMap<String, String> extractEventPayload(Map<String, String> context) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (context == null) return out;
        for (Map.Entry<String, String> e : context.entrySet()) {
            if (e == null) continue;
            String key = safe(e.getKey()).trim();
            if (!key.startsWith("event.")) continue;
            String tail = key.substring("event.".length()).trim();
            if (tail.isBlank()) continue;
            out.put(tail, safe(e.getValue()));
        }
        return out;
    }

    private static LinkedHashMap<String, String> compensationOp(String type,
                                                                 String matterUuid,
                                                                 String fieldKey,
                                                                 String fieldValue,
                                                                 boolean hadKey) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        out.put("type", safe(type).trim());
        out.put("matter_uuid", safe(matterUuid));
        out.put("field_key", safe(fieldKey));
        out.put("field_value", safe(fieldValue));
        out.put("had_key", hadKey ? "true" : "false");
        return out;
    }

    private static LinkedHashMap<String, String> compensationOpWithDoc(String type,
                                                                        String matterUuid,
                                                                        String docUuid,
                                                                        String fieldKey,
                                                                        String fieldValue,
                                                                        boolean hadKey) {
        LinkedHashMap<String, String> out = compensationOp(type, matterUuid, fieldKey, fieldValue, hadKey);
        out.put("doc_uuid", safe(docUuid));
        return out;
    }

    private static LinkedHashMap<String, String> compensationOpTask(String type,
                                                                     String taskUuid,
                                                                     String fieldKey,
                                                                     String fieldValue,
                                                                     boolean hadKey) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        out.put("type", safe(type).trim());
        out.put("task_uuid", safe(taskUuid));
        out.put("field_key", safe(fieldKey));
        out.put("field_value", safe(fieldValue));
        out.put("had_key", hadKey ? "true" : "false");
        return out;
    }

    private static LinkedHashMap<String, String> compensationOpCustomObjectField(String type,
                                                                                  String objectUuid,
                                                                                  String recordUuid,
                                                                                  String fieldKey,
                                                                                  String fieldValue,
                                                                                  boolean hadKey) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        out.put("type", safe(type).trim());
        out.put("object_uuid", safe(objectUuid));
        out.put("record_uuid", safe(recordUuid));
        out.put("field_key", safe(fieldKey));
        out.put("field_value", safe(fieldValue));
        out.put("had_key", hadKey ? "true" : "false");
        return out;
    }

    private static LinkedHashMap<String, Object> actionDescriptor(String action,
                                                                  String summary,
                                                                  boolean reversible,
                                                                  List<String> requiredSettings,
                                                                  List<String> optionalSettings) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("action", safe(action));
        row.put("summary", safe(summary));
        row.put("reversible", reversible);
        row.put("required_settings", parseCsv(requiredSettings));
        row.put("optional_settings", parseCsv(optionalSettings));
        return row;
    }

    private static String readHttpBody(HttpURLConnection conn) {
        if (conn == null) return "";
        InputStream in = null;
        try {
            in = conn.getInputStream();
        } catch (Exception ignored) {
            try {
                in = conn.getErrorStream();
            } catch (Exception ignored2) {
                in = null;
            }
        }
        if (in == null) return "";
        try (InputStream src = in) {
            byte[] bytes = src.readAllBytes();
            String out = new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8).trim();
            if (out.length() > 1200) out = out.substring(0, 1200);
            return out;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String toSimpleJson(Map<String, String> map) {
        LinkedHashMap<String, String> rows = sanitizeMap(map);
        if (rows.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder(512);
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, String> e : rows.entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey()).trim();
            if (k.isBlank()) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(jsonEsc(k)).append("\":\"").append(jsonEsc(safe(e.getValue()))).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonEsc(String in) {
        String s = safe(in);
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", Integer.valueOf(ch)));
                    else sb.append(ch);
                    break;
            }
        }
        return sb.toString();
    }

    private static tasks.TaskRec copyTask(tasks.TaskRec in) {
        tasks.TaskRec out = new tasks.TaskRec();
        if (in == null) return out;
        out.uuid = safe(in.uuid);
        out.matterUuid = safe(in.matterUuid);
        out.parentTaskUuid = safe(in.parentTaskUuid);
        out.title = safe(in.title);
        out.description = safe(in.description);
        out.status = safe(in.status);
        out.priority = safe(in.priority);
        out.assignmentMode = safe(in.assignmentMode);
        out.assignedUserUuid = safe(in.assignedUserUuid);
        out.dueAt = safe(in.dueAt);
        out.reminderAt = safe(in.reminderAt);
        out.estimateMinutes = in.estimateMinutes;
        out.claimUuid = safe(in.claimUuid);
        out.elementUuid = safe(in.elementUuid);
        out.factUuid = safe(in.factUuid);
        out.documentUuid = safe(in.documentUuid);
        out.partUuid = safe(in.partUuid);
        out.versionUuid = safe(in.versionUuid);
        out.pageNumber = in.pageNumber;
        out.threadUuid = safe(in.threadUuid);
        out.createdBy = safe(in.createdBy);
        out.createdAt = safe(in.createdAt);
        out.updatedAt = safe(in.updatedAt);
        out.completedAt = safe(in.completedAt);
        out.archived = in.archived;
        out.reportDocumentUuid = safe(in.reportDocumentUuid);
        out.reportPartUuid = safe(in.reportPartUuid);
        out.lastReportVersionUuid = safe(in.lastReportVersionUuid);
        return out;
    }

    private static matter_facts.FactRec copyFact(matter_facts.FactRec in) {
        matter_facts.FactRec out = new matter_facts.FactRec();
        if (in == null) return out;
        out.uuid = safe(in.uuid);
        out.claimUuid = safe(in.claimUuid);
        out.elementUuid = safe(in.elementUuid);
        out.summary = safe(in.summary);
        out.detail = safe(in.detail);
        out.internalNotes = safe(in.internalNotes);
        out.status = safe(in.status);
        out.strength = safe(in.strength);
        out.documentUuid = safe(in.documentUuid);
        out.partUuid = safe(in.partUuid);
        out.versionUuid = safe(in.versionUuid);
        out.pageNumber = in.pageNumber;
        out.sortOrder = in.sortOrder;
        out.createdAt = safe(in.createdAt);
        out.updatedAt = safe(in.updatedAt);
        out.trashed = in.trashed;
        return out;
    }

    private static omnichannel_tickets.TicketRec copyThread(omnichannel_tickets.TicketRec in) {
        omnichannel_tickets.TicketRec out = new omnichannel_tickets.TicketRec();
        if (in == null) return out;
        out.uuid = safe(in.uuid);
        out.matterUuid = safe(in.matterUuid);
        out.channel = safe(in.channel);
        out.subject = safe(in.subject);
        out.status = safe(in.status);
        out.priority = safe(in.priority);
        out.assignmentMode = safe(in.assignmentMode);
        out.assignedUserUuid = safe(in.assignedUserUuid);
        out.reminderAt = safe(in.reminderAt);
        out.dueAt = safe(in.dueAt);
        out.customerDisplay = safe(in.customerDisplay);
        out.customerAddress = safe(in.customerAddress);
        out.mailboxAddress = safe(in.mailboxAddress);
        out.threadKey = safe(in.threadKey);
        out.externalConversationId = safe(in.externalConversationId);
        out.createdAt = safe(in.createdAt);
        out.updatedAt = safe(in.updatedAt);
        out.lastInboundAt = safe(in.lastInboundAt);
        out.lastOutboundAt = safe(in.lastOutboundAt);
        out.inboundCount = in.inboundCount;
        out.outboundCount = in.outboundCount;
        out.mmsEnabled = in.mmsEnabled;
        out.archived = in.archived;
        out.reportDocumentUuid = safe(in.reportDocumentUuid);
        out.reportPartUuid = safe(in.reportPartUuid);
        out.lastReportVersionUuid = safe(in.lastReportVersionUuid);
        return out;
    }

    private static RunSnapshot readRunSnapshot(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return null;
        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return null;

        RunSnapshot snap = new RunSnapshot();

        RunResult r = new RunResult();
        r.runUuid = safe(root.getAttribute("uuid"));
        r.processUuid = safe(root.getAttribute("process_uuid"));
        r.processName = safe(root.getAttribute("process_name"));
        r.eventType = safe(root.getAttribute("event_type"));
        r.status = safe(root.getAttribute("status"));
        r.startedAt = safe(root.getAttribute("started_at"));
        r.completedAt = safe(root.getAttribute("completed_at"));
        r.undoState = safe(root.getAttribute("undo_state"));
        if (r.undoState.isBlank()) r.undoState = "active";

        Element summaryEl = firstChild(root, "summary");
        if (summaryEl != null) {
            r.message = text(summaryEl, "message");
            r.stepCount = parseInt(text(summaryEl, "step_count"), 0);
            r.stepsCompleted = parseInt(text(summaryEl, "steps_completed"), 0);
            r.stepsSkipped = parseInt(text(summaryEl, "steps_skipped"), 0);
            r.errors = parseInt(text(summaryEl, "errors"), 0);
            r.humanReviewUuid = text(summaryEl, "human_review_uuid");
        }

        snap.result = r;

        Element contextEl = firstChild(root, "context");
        if (contextEl != null) {
            NodeList items = contextEl.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Node n = items.item(i);
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                String key = safe(e.getAttribute("key")).trim();
                if (key.isBlank()) continue;
                snap.context.put(key, safe(e.getTextContent()));
            }
        }

        Element eventsEl = firstChild(root, "events");
        if (eventsEl != null) {
            NodeList items = eventsEl.getElementsByTagName("event");
            for (int i = 0; i < items.getLength(); i++) {
                Node n = items.item(i);
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                snap.logs.add(new RunLogEntry(
                        safe(e.getAttribute("time")),
                        safe(e.getAttribute("level")),
                        safe(e.getAttribute("code")),
                        safe(e.getAttribute("step_id")),
                        safe(e.getTextContent())
                ));
            }
        }

        Element journalEl = firstChild(root, "journal");
        if (journalEl != null) {
            NodeList items = journalEl.getElementsByTagName("entry");
            for (int i = 0; i < items.getLength(); i++) {
                Node n = items.item(i);
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;

                RunJournalEntry je = new RunJournalEntry();
                je.createdAt = safe(e.getAttribute("created_at"));
                je.stepId = safe(e.getAttribute("step_id"));
                je.action = safe(e.getAttribute("action"));
                je.description = safe(e.getAttribute("description"));
                je.reversible = parseBool(e.getAttribute("reversible"), false);

                Element undoEl = firstChild(e, "undo");
                if (undoEl != null) {
                    NodeList ops = undoEl.getElementsByTagName("item");
                    for (int ui = 0; ui < ops.getLength(); ui++) {
                        Node un = ops.item(ui);
                        if (!(un instanceof Element)) continue;
                        Element ue = (Element) un;
                        String key = safe(ue.getAttribute("key")).trim();
                        if (key.isBlank()) continue;
                        je.undo.put(key, safe(ue.getTextContent()));
                    }
                }

                Element redoEl = firstChild(e, "redo");
                if (redoEl != null) {
                    NodeList ops = redoEl.getElementsByTagName("item");
                    for (int ri = 0; ri < ops.getLength(); ri++) {
                        Node rn = ops.item(ri);
                        if (!(rn instanceof Element)) continue;
                        Element re = (Element) rn;
                        String key = safe(re.getAttribute("key")).trim();
                        if (key.isBlank()) continue;
                        je.redo.put(key, safe(re.getTextContent()));
                    }
                }

                snap.journal.add(je);
            }
        }

        return snap;
    }

    private static void writeRunSnapshot(String tenantUuid, RunSnapshot snap) throws Exception {
        if (snap == null || snap.result == null) return;
        Path p = runPath(tenantUuid, snap.result.runUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(12288);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<bpm_run uuid=\"").append(xmlAttr(snap.result.runUuid)).append("\" process_uuid=\"")
                .append(xmlAttr(snap.result.processUuid)).append("\" process_name=\"")
                .append(xmlAttr(snap.result.processName)).append("\" event_type=\"")
                .append(xmlAttr(snap.result.eventType)).append("\" status=\"")
                .append(xmlAttr(snap.result.status)).append("\" started_at=\"")
                .append(xmlAttr(snap.result.startedAt)).append("\" completed_at=\"")
                .append(xmlAttr(snap.result.completedAt)).append("\" undo_state=\"")
                .append(xmlAttr(safe(snap.result.undoState).isBlank() ? "active" : snap.result.undoState))
                .append("\">\n");

        sb.append("  <summary>\n");
        writeTag(sb, "message", snap.result.message);
        writeTag(sb, "step_count", String.valueOf(snap.result.stepCount));
        writeTag(sb, "steps_completed", String.valueOf(snap.result.stepsCompleted));
        writeTag(sb, "steps_skipped", String.valueOf(snap.result.stepsSkipped));
        writeTag(sb, "errors", String.valueOf(snap.result.errors));
        writeTag(sb, "human_review_uuid", snap.result.humanReviewUuid);
        sb.append("  </summary>\n");

        sb.append("  <context>\n");
        LinkedHashMap<String, String> ctx = sanitizeMap(snap.context);
        for (Map.Entry<String, String> e : ctx.entrySet()) {
            if (e == null) continue;
            String key = safe(e.getKey()).trim();
            if (key.isBlank()) continue;
            sb.append("    <item key=\"").append(xmlAttr(key)).append("\">")
                    .append(xmlText(e.getValue())).append("</item>\n");
        }
        sb.append("  </context>\n");

        sb.append("  <events>\n");
        List<RunLogEntry> xs = snap.logs == null ? List.of() : snap.logs;
        for (RunLogEntry e : xs) {
            if (e == null) continue;
            sb.append("    <event time=\"").append(xmlAttr(e.time)).append("\" level=\"")
                    .append(xmlAttr(e.level)).append("\" code=\"")
                    .append(xmlAttr(e.code)).append("\" step_id=\"")
                    .append(xmlAttr(e.stepId)).append("\">")
                    .append(xmlText(e.message)).append("</event>\n");
        }
        sb.append("  </events>\n");

        sb.append("  <journal>\n");
        List<RunJournalEntry> journal = snap.journal == null ? List.of() : snap.journal;
        for (RunJournalEntry je : journal) {
            if (je == null) continue;
            sb.append("    <entry created_at=\"").append(xmlAttr(je.createdAt)).append("\" step_id=\"")
                    .append(xmlAttr(je.stepId)).append("\" action=\"")
                    .append(xmlAttr(je.action)).append("\" description=\"")
                    .append(xmlAttr(je.description)).append("\" reversible=\"")
                    .append(je.reversible ? "true" : "false").append("\">\n");

            sb.append("      <undo>\n");
            for (Map.Entry<String, String> e : sanitizeMap(je.undo).entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                sb.append("        <item key=\"").append(xmlAttr(key)).append("\">")
                        .append(xmlText(e.getValue())).append("</item>\n");
            }
            sb.append("      </undo>\n");

            sb.append("      <redo>\n");
            for (Map.Entry<String, String> e : sanitizeMap(je.redo).entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                sb.append("        <item key=\"").append(xmlAttr(key)).append("\">")
                        .append(xmlText(e.getValue())).append("</item>\n");
            }
            sb.append("      </redo>\n");

            sb.append("    </entry>\n");
        }
        sb.append("  </journal>\n");

        sb.append("</bpm_run>\n");
        writeAtomic(p, sb.toString());
    }

    private static String emptyReviewsXml() {
        String now = nowIso();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<bpm_reviews updated_at=\"" + xmlAttr(now) + "\"></bpm_reviews>\n";
    }

    private static void writeTag(StringBuilder sb, String tag, String value) {
        sb.append("    <").append(tag).append(">")
                .append(xmlText(value))
                .append("</").append(tag).append(">\n");
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, k -> new ReentrantReadWriteLock());
    }

    private static Path bpmDir(String tenantUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "bpm").toAbsolutePath();
    }

    private static Path processesDir(String tenantUuid) {
        return bpmDir(tenantUuid).resolve("processes");
    }

    private static Path processPath(String tenantUuid, String processUuid) {
        return processesDir(tenantUuid).resolve(safeFileToken(processUuid) + ".xml").toAbsolutePath();
    }

    private static Path runsDir(String tenantUuid) {
        return bpmDir(tenantUuid).resolve("runs");
    }

    private static Path runPath(String tenantUuid, String runUuid) {
        return runsDir(tenantUuid).resolve(safeFileToken(runUuid) + ".xml").toAbsolutePath();
    }

    private static Path reviewsPath(String tenantUuid) {
        return bpmDir(tenantUuid).resolve("human_reviews.xml").toAbsolutePath();
    }

    private static LinkedHashMap<String, String> sanitizeMap(Map<String, String> in) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (in == null) return out;
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (e == null) continue;
            String key = safe(e.getKey()).trim();
            if (key.isBlank()) continue;
            out.put(key, safe(e.getValue()));
        }
        return out;
    }

    private static ArrayList<String> parseCsv(String raw) {
        ArrayList<String> out = new ArrayList<String>();
        String s = safe(raw).trim();
        if (s.isBlank()) return out;

        String[] parts = s.split(",");
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String part : parts) {
            String v = safe(part).trim();
            if (v.isBlank()) continue;
            String key = v.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);
            out.add(v);
        }
        return out;
    }

    private static ArrayList<String> parseCsv(List<String> raw) {
        if (raw == null || raw.isEmpty()) return new ArrayList<String>();
        return parseCsv(String.join(",", raw));
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

    private static Element firstChild(Element parent, String tag) {
        if (parent == null || safe(tag).isBlank()) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            if (tag.equals(e.getTagName())) return e;
        }
        return null;
    }

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        Element el = firstChild(parent, childTag);
        return el == null ? "" : safe(el.getTextContent()).trim();
    }

    private static void writeAtomic(Path p, String content) throws Exception {
        if (p == null) return;
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(tmp, safe(content), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean parseBool(String raw, boolean def) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return def;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
    }

    private static int parseInt(String raw, int def) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static Instant parseInstant(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long ageMinutes(String startedAt, Instant now) {
        Instant start = parseInstant(startedAt);
        if (start == null) return -1L;
        Instant ref = now == null ? Instant.now() : now;
        long seconds = ref.getEpochSecond() - start.getEpochSecond();
        if (seconds < 0) return 0L;
        return seconds / 60L;
    }

    private static String nowIso() {
        return Instant.now().toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeFileToken(String s) {
        return safe(s).trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String normalizeToken(String key) {
        String k = safe(key).trim().toLowerCase(Locale.ROOT);
        if (k.isBlank()) return "";
        StringBuilder sb = new StringBuilder(k.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < k.length(); i++) {
            char ch = k.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.';
            if (ok) {
                sb.append(ch);
                lastUnderscore = false;
            } else if (!lastUnderscore && sb.length() > 0) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String out = sb.toString();
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        if (out.length() > 80) out = out.substring(0, 80);
        return out;
    }

    private static String xmlText(String s) {
        return safe(s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String xmlAttr(String s) {
        return xmlText(s);
    }
}
