package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class business_process_manager_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void stores_each_process_as_its_own_tenant_xml_file() throws Exception {
        String tenant = "tenant-bpm-xml-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition p1 = minimalProcess("Intake A", "manual");
        business_process_manager.ProcessDefinition p2 = minimalProcess("Intake B", "matter.created");

        business_process_manager.ProcessDefinition s1 = bpm.saveProcess(tenant, p1);
        business_process_manager.ProcessDefinition s2 = bpm.saveProcess(tenant, p2);

        Path p1Path = Paths.get("data", "tenants", tenant, "bpm", "processes", s1.processUuid + ".xml").toAbsolutePath();
        Path p2Path = Paths.get("data", "tenants", tenant, "bpm", "processes", s2.processUuid + ".xml").toAbsolutePath();

        assertTrue(Files.exists(p1Path));
        assertTrue(Files.exists(p2Path));
        assertEquals(2, bpm.listProcesses(tenant).size());
    }

    @Test
    void supports_execute_then_undo_then_redo_for_reversible_actions() throws Exception {
        String tenant = "tenant-bpm-undo-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters.MatterRec matter = matters.defaultStore().create(
                tenant,
                "Undo Matter",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Undoable Case Field Update";

        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep step = new business_process_manager.ProcessStep();
        step.stepId = "set_priority";
        step.order = 10;
        step.action = "set_case_field";
        step.label = "Set Priority";
        step.settings.put("matter_uuid", "{{event.matter_uuid}}");
        step.settings.put("field_key", "priority");
        step.settings.put("field_value", "High");
        process.steps.add(step);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);

        LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("matter_uuid", matter.uuid);

        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                payload,
                "tester",
                "test"
        );

        assertNotNull(run);
        assertEquals("completed", run.status);
        assertEquals("High", case_fields.defaultStore().read(tenant, matter.uuid).get("priority"));

        business_process_manager.RunResult undone = bpm.undoRun(tenant, run.runUuid, "tester");
        assertEquals("undone", safe(undone.undoState));
        assertFalse(case_fields.defaultStore().read(tenant, matter.uuid).containsKey("priority"));

        business_process_manager.RunResult redone = bpm.redoRun(tenant, run.runUuid, "tester");
        assertEquals("active", safe(redone.undoState));
        assertEquals("High", case_fields.defaultStore().read(tenant, matter.uuid).get("priority"));
    }

    @Test
    void pauses_for_human_review_then_resumes_with_user_input() throws Exception {
        String tenant = "tenant-bpm-review-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters.MatterRec matter = matters.defaultStore().create(
                tenant,
                "Review Matter",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Human Review Flow";

        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep review = new business_process_manager.ProcessStep();
        review.stepId = "review";
        review.order = 10;
        review.action = "human_review";
        review.label = "Attorney Approval";
        review.settings.put("title", "Approve Matter Intake");
        review.settings.put("instructions", "Provide decision and notes.");
        review.settings.put("required_input_keys", "decision");
        process.steps.add(review);

        business_process_manager.ProcessStep apply = new business_process_manager.ProcessStep();
        apply.stepId = "apply";
        apply.order = 20;
        apply.action = "set_case_field";
        apply.label = "Apply Review Decision";
        apply.settings.put("matter_uuid", "{{event.matter_uuid}}");
        apply.settings.put("field_key", "review_decision");
        apply.settings.put("field_value", "{{review.decision}}");
        process.steps.add(apply);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);

        LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("matter_uuid", matter.uuid);

        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                payload,
                "tester",
                "test"
        );

        assertEquals("waiting_human_review", run.status);
        assertFalse(safe(run.humanReviewUuid).isBlank());

        List<business_process_manager.HumanReviewTask> pending = bpm.listReviews(tenant, true, 50);
        assertEquals(1, pending.size());

        LinkedHashMap<String, String> input = new LinkedHashMap<String, String>();
        input.put("decision", "approved");

        business_process_manager.HumanReviewTask done = bpm.completeReview(
                tenant,
                pending.get(0).reviewUuid,
                true,
                "reviewer",
                input,
                "Looks good"
        );

        assertEquals("approved", safe(done.status));
        assertEquals("completed", safe(done.resumeStatus));
        business_process_manager.RunResult original = bpm.getRunResult(tenant, run.runUuid);
        assertEquals("completed", safe(original == null ? "" : original.status));

        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(case_fields.defaultStore().read(tenant, matter.uuid));
        assertEquals("approved", fields.get("review_decision"));
    }

    @Test
    void supports_multiple_triggers_per_process() throws Exception {
        String tenant = "tenant-bpm-triggers-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Multiple Triggers";

        business_process_manager.ProcessTrigger t1 = new business_process_manager.ProcessTrigger();
        t1.type = "matter.created";
        process.triggers.add(t1);

        business_process_manager.ProcessTrigger t2 = new business_process_manager.ProcessTrigger();
        t2.type = "document.created";
        process.triggers.add(t2);

        business_process_manager.ProcessStep step = new business_process_manager.ProcessStep();
        step.stepId = "log";
        step.order = 10;
        step.action = "log_note";
        step.label = "Log";
        step.settings.put("message", "Triggered");
        process.steps.add(step);

        business_process_manager bpm = business_process_manager.defaultService();
        bpm.saveProcess(tenant, process);

        LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("matter_uuid", "matter-x");

        List<business_process_manager.RunResult> runs = bpm.triggerEvent(
                tenant,
                "matter.created",
                payload,
                "tester",
                "test"
        );

        assertEquals(1, runs.size());
        assertEquals("completed", runs.get(0).status);
    }

    @Test
    void supports_document_and_case_list_actions_with_undo_redo() throws Exception {
        String tenant = "tenant-bpm-doclist-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters.MatterRec matter = matters.defaultStore().create(
                tenant,
                "Doc/List Matter",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );
        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter.uuid,
                "Checklist",
                "communication",
                "notes",
                "draft",
                "tester",
                "work_product",
                "",
                "",
                ""
        );

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Doc + Case List Updates";
        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep step1 = new business_process_manager.ProcessStep();
        step1.stepId = "set_doc_field";
        step1.order = 10;
        step1.action = "set_document_field";
        step1.settings.put("matter_uuid", "{{event.matter_uuid}}");
        step1.settings.put("doc_uuid", "{{event.doc_uuid}}");
        step1.settings.put("field_key", "review_status");
        step1.settings.put("field_value", "Ready");
        process.steps.add(step1);

        business_process_manager.ProcessStep step2 = new business_process_manager.ProcessStep();
        step2.stepId = "set_case_list";
        step2.order = 20;
        step2.action = "set_case_list_item";
        step2.settings.put("matter_uuid", "{{event.matter_uuid}}");
        step2.settings.put("list_key", "next_hearing");
        step2.settings.put("list_value", "2026-04-01");
        process.steps.add(step2);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);

        LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("matter_uuid", matter.uuid);
        payload.put("doc_uuid", doc.uuid);

        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                payload,
                "tester",
                "test"
        );
        assertEquals("completed", run.status);
        assertEquals("Ready", document_fields.defaultStore().read(tenant, matter.uuid, doc.uuid).get("review_status"));
        assertTrue(safe(case_list_items.defaultStore().read(tenant, matter.uuid).get("next_hearing")).contains("2026-04-01"));

        business_process_manager.RunResult undone = bpm.undoRun(tenant, run.runUuid, "tester");
        assertEquals("undone", safe(undone.undoState));
        assertFalse(document_fields.defaultStore().read(tenant, matter.uuid, doc.uuid).containsKey("review_status"));
        assertFalse(case_list_items.defaultStore().read(tenant, matter.uuid).containsKey("next_hearing"));

        business_process_manager.RunResult redone = bpm.redoRun(tenant, run.runUuid, "tester");
        assertEquals("active", safe(redone.undoState));
        assertEquals("Ready", document_fields.defaultStore().read(tenant, matter.uuid, doc.uuid).get("review_status"));
        assertTrue(safe(case_list_items.defaultStore().read(tenant, matter.uuid).get("next_hearing")).contains("2026-04-01"));
    }

    @Test
    void supports_thread_update_and_internal_note_actions() throws Exception {
        String tenant = "tenant-bpm-thread-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        omnichannel_tickets store = omnichannel_tickets.defaultStore();
        store.ensure(tenant);
        omnichannel_tickets.TicketRec thread = store.createTicket(
                tenant,
                "",
                "flowroute_sms",
                "Intake message",
                "open",
                "normal",
                "manual",
                "",
                "",
                "",
                "Client",
                "+15551230000",
                "+15557654321",
                "sms-1",
                "",
                "inbound",
                "Initial message",
                "+15551230000",
                "+15557654321",
                false,
                "tester",
                ""
        );
        assertNotNull(thread);

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Thread Ops";
        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep update = new business_process_manager.ProcessStep();
        update.stepId = "update_thread";
        update.order = 10;
        update.action = "update_thread";
        update.settings.put("thread_uuid", "{{event.thread_uuid}}");
        update.settings.put("status", "resolved");
        update.settings.put("priority", "high");
        update.settings.put("assigned_user_uuid", "user-a,user-b");
        update.settings.put("subject", "Intake message (reviewed)");
        process.steps.add(update);

        business_process_manager.ProcessStep note = new business_process_manager.ProcessStep();
        note.stepId = "note";
        note.order = 20;
        note.action = "add_thread_note";
        note.settings.put("thread_uuid", "{{event.thread_uuid}}");
        note.settings.put("body", "Internal follow-up note");
        process.steps.add(note);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);

        LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("thread_uuid", thread.uuid);

        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                payload,
                "tester",
                "test"
        );
        assertEquals("completed", run.status);

        omnichannel_tickets.TicketRec updated = store.getTicket(tenant, thread.uuid);
        assertNotNull(updated);
        assertEquals("resolved", safe(updated.status));
        assertTrue(safe(updated.assignedUserUuid).contains("user-a"));

        List<omnichannel_tickets.MessageRec> messages = store.listMessages(tenant, thread.uuid);
        boolean foundInternal = false;
        for (omnichannel_tickets.MessageRec m : messages) {
            if (m == null) continue;
            if ("internal".equalsIgnoreCase(safe(m.direction))
                    && safe(m.body).contains("Internal follow-up note")) {
                foundInternal = true;
                break;
            }
        }
        assertTrue(foundInternal);
    }

    @Test
    void supports_task_actions_and_reversible_task_field_updates() throws Exception {
        String tenant = "tenant-bpm-task-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters.MatterRec matter = matters.defaultStore().create(
                tenant,
                "Task BPM Matter",
                "",
                "",
                "",
                "",
                ""
        );

        tasks.TaskRec create = new tasks.TaskRec();
        create.matterUuid = matter.uuid;
        create.title = "Review discovery responses";
        create.description = "Initial task seeded for BPM flow.";
        create.status = "open";
        create.priority = "normal";
        create.assignmentMode = "manual";
        create.assignedUserUuid = "user-a";
        create.dueAt = "2026-05-01T17:00";
        create.reminderAt = "";
        create.estimateMinutes = 60;
        tasks.TaskRec task = tasks.defaultStore().createTask(tenant, create, "tester", "Initial owner");
        assertNotNull(task);

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Task Ops";
        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep setField = new business_process_manager.ProcessStep();
        setField.stepId = "set_task_field";
        setField.order = 10;
        setField.action = "set_task_field";
        setField.settings.put("task_uuid", "{{event.task_uuid}}");
        setField.settings.put("field_key", "phase");
        setField.settings.put("field_value", "Discovery");
        process.steps.add(setField);

        business_process_manager.ProcessStep updateTask = new business_process_manager.ProcessStep();
        updateTask.stepId = "update_task";
        updateTask.order = 20;
        updateTask.action = "update_task";
        updateTask.settings.put("task_uuid", "{{event.task_uuid}}");
        updateTask.settings.put("status", "in_progress");
        updateTask.settings.put("priority", "high");
        updateTask.settings.put("title", "Review discovery responses (BPM)");
        process.steps.add(updateTask);

        business_process_manager.ProcessStep addNote = new business_process_manager.ProcessStep();
        addNote.stepId = "add_task_note";
        addNote.order = 30;
        addNote.action = "add_task_note";
        addNote.settings.put("task_uuid", "{{event.task_uuid}}");
        addNote.settings.put("body", "Internal BPM note");
        process.steps.add(addNote);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);

        LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
        payload.put("task_uuid", task.uuid);
        payload.put("matter_uuid", matter.uuid);

        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                payload,
                "tester",
                "test"
        );
        assertEquals("completed", run.status);

        assertEquals("Discovery", task_fields.defaultStore().read(tenant, task.uuid).get("phase"));
        tasks.TaskRec after = tasks.defaultStore().getTask(tenant, task.uuid);
        assertEquals("in_progress", safe(after.status));
        assertEquals("high", safe(after.priority));
        assertTrue(safe(after.title).contains("(BPM)"));

        List<tasks.NoteRec> notes = tasks.defaultStore().listNotes(tenant, task.uuid);
        boolean found = false;
        for (tasks.NoteRec n : notes) {
            if (n == null) continue;
            if (safe(n.body).contains("Internal BPM note")) {
                found = true;
                break;
            }
        }
        assertTrue(found);

        business_process_manager.RunResult undone = bpm.undoRun(tenant, run.runUuid, "tester");
        assertEquals("undone", safe(undone.undoState));
        assertFalse(task_fields.defaultStore().read(tenant, task.uuid).containsKey("phase"));

        business_process_manager.RunResult redone = bpm.redoRun(tenant, run.runUuid, "tester");
        assertEquals("active", safe(redone.undoState));
        assertEquals("Discovery", task_fields.defaultStore().read(tenant, task.uuid).get("phase"));
    }

    @Test
    void supports_custom_object_record_triggers_and_reversible_field_action() throws Exception {
        String tenant = "tenant-bpm-custom-object-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        custom_objects objects = custom_objects.defaultStore();
        objects.saveAll(
                tenant,
                List.of(
                        new custom_objects.ObjectRec(
                                "",
                                "billing_entry",
                                "Billing Entry",
                                "Billing Entries",
                                true,
                                true,
                                10,
                                ""
                        )
                )
        );
        custom_objects.ObjectRec object = objects.getByKey(tenant, "billing_entry");
        assertNotNull(object);

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Custom Object Trigger + Field Action";

        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "custom_object_record.created";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep setField = new business_process_manager.ProcessStep();
        setField.stepId = "set_custom_field";
        setField.order = 10;
        setField.action = "set_custom_object_record_field";
        setField.settings.put("object_uuid", "{{event.object_uuid}}");
        setField.settings.put("record_uuid", "{{event.record_uuid}}");
        setField.settings.put("field_key", "approval_status");
        setField.settings.put("field_value", "pending_review");
        process.steps.add(setField);

        business_process_manager bpm = business_process_manager.defaultService();
        bpm.saveProcess(tenant, process);

        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
        values.put("hours", "2.5");
        custom_object_records.RecordRec created = custom_object_records.defaultStore().create(
                tenant,
                object.uuid,
                "March Billing",
                values
        );
        assertNotNull(created);

        custom_object_records.RecordRec after = custom_object_records.defaultStore().getByUuid(
                tenant,
                object.uuid,
                created.uuid
        );
        assertEquals("pending_review", safe(after == null ? "" : after.values.get("approval_status")));

        List<business_process_manager.RunResult> runs = bpm.listRuns(tenant, 10);
        assertEquals(1, runs.size());
        business_process_manager.RunResult run = runs.get(0);
        assertEquals("custom_object_record.created", safe(run.eventType));
        assertEquals("completed", safe(run.status));

        business_process_manager.RunResult undone = bpm.undoRun(tenant, run.runUuid, "tester");
        assertEquals("undone", safe(undone.undoState));
        custom_object_records.RecordRec afterUndo = custom_object_records.defaultStore().getByUuid(
                tenant,
                object.uuid,
                created.uuid
        );
        assertFalse(afterUndo != null && afterUndo.values.containsKey("approval_status"));

        business_process_manager.RunResult redone = bpm.redoRun(tenant, run.runUuid, "tester");
        assertEquals("active", safe(redone.undoState));
        custom_object_records.RecordRec afterRedo = custom_object_records.defaultStore().getByUuid(
                tenant,
                object.uuid,
                created.uuid
        );
        assertEquals("pending_review", safe(afterRedo == null ? "" : afterRedo.values.get("approval_status")));
    }

    @Test
    void detects_stale_running_run_in_health_inspector() throws Exception {
        String tenant = "tenant-bpm-health-running-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, minimalProcess("Health Running", "manual"));
        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                Map.of(),
                "tester",
                "test"
        );
        assertEquals("completed", safe(run.status));

        Path runPath = Paths.get("data", "tenants", tenant, "bpm", "runs", run.runUuid + ".xml").toAbsolutePath();
        String staleStartedAt = Instant.now().minusSeconds(7200).toString();
        replaceInFile(runPath, "status=\"completed\"", "status=\"running\"");
        replaceInFile(runPath, "started_at=\"" + safe(run.startedAt) + "\"", "started_at=\"" + staleStartedAt + "\"");

        List<business_process_manager.RunHealthIssue> issues = bpm.inspectRunHealth(tenant, 30, 60, 50);
        business_process_manager.RunHealthIssue found = null;
        for (business_process_manager.RunHealthIssue issue : issues) {
            if (issue == null) continue;
            if (safe(run.runUuid).equals(safe(issue.runUuid))) {
                found = issue;
                break;
            }
        }

        assertNotNull(found);
        assertEquals("stale_running_run", safe(found.issueType));
        assertEquals("high", safe(found.severity));
    }

    @Test
    void detects_stale_human_review_and_allows_operator_force_fail() throws Exception {
        String tenant = "tenant-bpm-health-review-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Stale Review";

        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep review = new business_process_manager.ProcessStep();
        review.stepId = "review";
        review.order = 10;
        review.action = "human_review";
        review.settings.put("title", "Approve");
        process.steps.add(review);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);
        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                Map.of(),
                "tester",
                "test"
        );
        assertEquals("waiting_human_review", safe(run.status));

        List<business_process_manager.HumanReviewTask> pending = bpm.listReviews(tenant, true, 20);
        assertEquals(1, pending.size());

        String staleTime = Instant.now().minusSeconds(4 * 3600).toString();
        Path runPath = Paths.get("data", "tenants", tenant, "bpm", "runs", run.runUuid + ".xml").toAbsolutePath();
        Path reviewsPath = Paths.get("data", "tenants", tenant, "bpm", "human_reviews.xml").toAbsolutePath();
        replaceInFile(runPath, "started_at=\"" + safe(run.startedAt) + "\"", "started_at=\"" + staleTime + "\"");
        replaceInFile(
                reviewsPath,
                "<created_at>" + safe(pending.get(0).createdAt) + "</created_at>",
                "<created_at>" + staleTime + "</created_at>"
        );

        List<business_process_manager.RunHealthIssue> issues = bpm.inspectRunHealth(tenant, 30, 60, 50);
        business_process_manager.RunHealthIssue found = null;
        for (business_process_manager.RunHealthIssue issue : issues) {
            if (issue == null) continue;
            if (safe(run.runUuid).equals(safe(issue.runUuid))) {
                found = issue;
                break;
            }
        }

        assertNotNull(found);
        assertEquals("stale_human_review", safe(found.issueType));
        assertTrue(found.canResolveReview);

        business_process_manager.RunResult failed = bpm.markRunFailed(
                tenant,
                run.runUuid,
                "operator",
                "Run was stale/hung."
        );
        assertEquals("failed", safe(failed.status));

        List<business_process_manager.HumanReviewTask> pendingAfter = bpm.listReviews(tenant, true, 20);
        assertEquals(0, pendingAfter.size());

        List<business_process_manager.HumanReviewTask> allReviews = bpm.listReviews(tenant, false, 20);
        assertEquals("error", safe(allReviews.get(0).status));
    }

    @Test
    void retry_run_marks_source_run_failed_and_creates_new_run() throws Exception {
        String tenant = "tenant-bpm-health-retry-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Retry Waiting Run";
        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep review = new business_process_manager.ProcessStep();
        review.stepId = "review";
        review.order = 10;
        review.action = "human_review";
        review.settings.put("title", "Retry Review");
        process.steps.add(review);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);
        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                Map.of(),
                "tester",
                "test"
        );
        assertEquals("waiting_human_review", safe(run.status));

        business_process_manager.RunResult retried = bpm.retryRun(
                tenant,
                run.runUuid,
                "operator",
                "Retry from inspector."
        );

        assertNotNull(retried);
        assertNotEquals(safe(run.runUuid), safe(retried.runUuid));
        assertEquals("waiting_human_review", safe(retried.status));

        business_process_manager.RunResult source = bpm.getRunResult(tenant, run.runUuid);
        assertEquals("failed", safe(source == null ? "" : source.status));
        assertTrue(safe(source == null ? "" : source.message).contains(safe(retried.runUuid)));

        List<business_process_manager.HumanReviewTask> pending = bpm.listReviews(tenant, true, 50);
        assertEquals(1, pending.size());
    }

    @Test
    void human_review_assignment_requires_assigned_user_for_completion() throws Exception {
        String tenant = "tenant-bpm-review-assignee-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Assigned Human Review";
        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep review = new business_process_manager.ProcessStep();
        review.stepId = "review";
        review.order = 10;
        review.action = "human_review";
        review.settings.put("title", "Assigned reviewer required");
        review.settings.put("assignee_user_uuid", "assigned-user");
        review.settings.put("required_input_keys", "decision");
        process.steps.add(review);

        business_process_manager.ProcessStep after = new business_process_manager.ProcessStep();
        after.stepId = "after";
        after.order = 20;
        after.action = "set_variable";
        after.settings.put("name", "post_review_flag");
        after.settings.put("value", "yes");
        process.steps.add(after);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);
        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                Map.of(),
                "tester",
                "test"
        );
        assertEquals("waiting_human_review", safe(run.status));

        List<business_process_manager.HumanReviewTask> pending = bpm.listReviews(tenant, true, 20);
        assertEquals(1, pending.size());
        String reviewUuid = pending.get(0).reviewUuid;

        LinkedHashMap<String, String> input = new LinkedHashMap<String, String>();
        input.put("decision", "approved");

        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> bpm.completeReview(tenant, reviewUuid, true, "other-user", input, "Not assigned")
        );
        assertTrue(safe(denied.getMessage()).toLowerCase().contains("assigned"));

        business_process_manager.HumanReviewTask done = bpm.completeReview(
                tenant,
                reviewUuid,
                true,
                "assigned-user",
                input,
                "Approved"
        );
        assertEquals("approved", safe(done.status));
        assertEquals("completed", safe(done.resumeStatus));

        business_process_manager.RunResult original = bpm.getRunResult(tenant, run.runUuid);
        assertEquals("completed", safe(original == null ? "" : original.status));
    }

    @Test
    void supports_document_workflow_where_assembly_is_one_step_before_part_version_creation() throws Exception {
        String tenant = "tenant-bpm-doc-assembly-chain-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters.MatterRec matter = matters.defaultStore().create(
                tenant,
                "Workflow Matter",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );
        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter.uuid,
                "Final Packet",
                "pleading",
                "motion",
                "draft",
                "tester",
                "work_product",
                "",
                "",
                ""
        );

        form_templates.TemplateRec template = form_templates.defaultStore().create(
                tenant,
                "Packet Cover",
                "packet-cover.txt",
                "Matter: {{case.label}}".getBytes(StandardCharsets.UTF_8)
        );
        assertNotNull(template);

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Assemble Then Attach Version";
        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep assemble = new business_process_manager.ProcessStep();
        assemble.stepId = "assemble";
        assemble.order = 10;
        assemble.action = "document_assembly";
        assemble.settings.put("matter_uuid", "{{event.matter_uuid}}");
        assemble.settings.put("template_uuid", template.uuid);
        assemble.settings.put("output_file_name", "packet-cover.txt");
        process.steps.add(assemble);

        business_process_manager.ProcessStep attach = new business_process_manager.ProcessStep();
        attach.stepId = "attach_version";
        attach.order = 20;
        attach.action = "assembly_to_document_version";
        attach.settings.put("matter_uuid", "{{event.matter_uuid}}");
        attach.settings.put("document_uuid", "{{event.doc_uuid}}");
        attach.settings.put("part_label", "Cover Letter");
        attach.settings.put("part_type", "lead");
        attach.settings.put("version_label", "Cover Letter v1");
        process.steps.add(attach);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);

        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                Map.of("matter_uuid", matter.uuid, "doc_uuid", doc.uuid),
                "tester",
                "test"
        );
        assertEquals("completed", safe(run.status));

        List<assembled_forms.AssemblyRec> assemblies = assembled_forms.defaultStore().listByMatter(tenant, matter.uuid);
        assertEquals(1, assemblies.size());
        assembled_forms.AssemblyRec assembly = assemblies.get(0);
        assertEquals("completed", safe(assembly.status));

        List<document_parts.PartRec> parts = document_parts.defaultStore().listAll(tenant, matter.uuid, doc.uuid);
        assertEquals(1, parts.size());
        document_parts.PartRec part = parts.get(0);
        assertEquals("cover letter", safe(part.label).toLowerCase());

        List<part_versions.VersionRec> versions = part_versions.defaultStore().listAll(tenant, matter.uuid, doc.uuid, part.uuid);
        assertEquals(1, versions.size());
        part_versions.VersionRec created = versions.get(0);
        assertEquals("Cover Letter v1", safe(created.versionLabel));
        assertEquals("assembled_form:" + assembly.uuid, safe(created.source));
        assertFalse(safe(created.checksum).isBlank());

        Path storagePath = pdf_redaction_service.resolveStoragePath(created.storagePath);
        assertNotNull(storagePath);
        assertTrue(Files.exists(storagePath));
        String assembledText = Files.readString(storagePath, StandardCharsets.UTF_8);
        assertTrue(assembledText.contains("Matter: Workflow Matter"));
    }

    @Test
    void supports_deadline_rule_calculation_action_and_case_field_write() throws Exception {
        String tenant = "tenant-bpm-deadline-rule-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters.MatterRec matter = matters.defaultStore().create(
                tenant,
                "Deadline Matter",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Deadline Rule";
        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep deadline = new business_process_manager.ProcessStep();
        deadline.stepId = "deadline";
        deadline.order = 10;
        deadline.action = "deadline_rule_calculation";
        deadline.settings.put("trigger_date", "2026-01-05");
        deadline.settings.put("days_offset", "5");
        deadline.settings.put("matter_uuid", "{{event.matter_uuid}}");
        deadline.settings.put("field_key", "computed_deadline");
        process.steps.add(deadline);

        business_process_manager.ProcessStep audit = new business_process_manager.ProcessStep();
        audit.stepId = "audit";
        audit.order = 20;
        audit.action = "audit_snapshot";
        audit.settings.put("message", "Deadline calculated");
        audit.settings.put("include_keys", "vars.last_deadline_value");
        process.steps.add(audit);

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, process);

        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                Map.of("matter_uuid", matter.uuid),
                "tester",
                "test"
        );
        assertEquals("completed", safe(run.status));

        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(case_fields.defaultStore().read(tenant, matter.uuid));
        assertEquals("2026-01-10", safe(fields.get("computed_deadline")));
    }

    @Test
    void sends_aging_alarm_email_to_tenant_admin_and_dedupes() throws Exception {
        String tenant = "tenant-bpm-aging-alarm-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenant, minimalProcess("Aging Alarm", "manual"));
        business_process_manager.RunResult run = bpm.triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                Map.of(),
                "tester",
                "test"
        );
        assertEquals("completed", safe(run.status));

        Path runPath = Paths.get("data", "tenants", tenant, "bpm", "runs", run.runUuid + ".xml").toAbsolutePath();
        String staleStartedAt = Instant.now().minusSeconds(6 * 3600).toString();
        replaceInFile(runPath, "status=\"completed\"", "status=\"running\"");
        replaceInFile(runPath, "started_at=\"" + safe(run.startedAt) + "\"", "started_at=\"" + staleStartedAt + "\"");

        users_roles users = users_roles.defaultStore();
        users.ensure(tenant);
        String adminRoleUuid = "";
        for (users_roles.RoleRec role : users.listRoles(tenant)) {
            if (role == null || !role.enabled) continue;
            if ("true".equalsIgnoreCase(safe(role.permissions.get("tenant_admin")))) {
                adminRoleUuid = safe(role.uuid);
                break;
            }
        }
        assertFalse(adminRoleUuid.isBlank());
        String adminEmail = "tenant-admin+" + UUID.randomUUID() + "@example.test";
        users_roles.UserRec adminUser = users.createUser(
                tenant,
                adminEmail,
                adminRoleUuid,
                true,
                "StrongTestPassword!234".toCharArray()
        );
        assertNotNull(adminUser);

        tenant_settings settings = tenant_settings.defaultStore();
        LinkedHashMap<String, String> cfg = settings.read(tenant);
        cfg.put("email_provider", "smtp");
        cfg.put("email_from_address", "no-reply@example.test");
        cfg.put("email_smtp_host", "localhost");
        cfg.put("email_smtp_port", "25");
        cfg.put("email_smtp_auth", "false");
        settings.write(tenant, cfg);

        business_process_manager.AgingAlarmEmailResult first = bpm.sendAgingRunAlarmEmails(
                tenant,
                30,
                60,
                50,
                "tester"
        );
        assertEquals(1, first.detectedIssues);
        assertEquals(1, first.newlyAlarmedIssues);
        assertEquals(1, first.emailsQueued);
        assertTrue(first.recipients.contains(adminEmail));

        Path queuePath = Paths.get("data", "tenants", tenant, "sync", "notification_email_queue.xml").toAbsolutePath();
        String queueXmlFirst = Files.readString(queuePath, StandardCharsets.UTF_8);
        assertTrue(queueXmlFirst.contains(adminEmail));
        assertTrue(queueXmlFirst.contains("BPM aging alarm"));
        assertEquals(1, countOccurrences(queueXmlFirst, "<email>"));

        business_process_manager.AgingAlarmEmailResult second = bpm.sendAgingRunAlarmEmails(
                tenant,
                30,
                60,
                50,
                "tester"
        );
        assertEquals(1, second.detectedIssues);
        assertEquals(0, second.newlyAlarmedIssues);
        assertEquals(0, second.emailsQueued);

        String queueXmlSecond = Files.readString(queuePath, StandardCharsets.UTF_8);
        assertEquals(queueXmlFirst, queueXmlSecond);
    }

    private static business_process_manager.ProcessDefinition minimalProcess(String name, String triggerType) {
        business_process_manager.ProcessDefinition p = new business_process_manager.ProcessDefinition();
        p.name = name;

        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = triggerType;
        p.triggers.add(trigger);

        business_process_manager.ProcessStep step = new business_process_manager.ProcessStep();
        step.stepId = "log";
        step.order = 10;
        step.action = "log_note";
        step.label = "Log Step";
        step.settings.put("message", "hello");
        p.steps.add(step);

        return p;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        });
    }

    private static void replaceInFile(Path path, String target, String replacement) throws Exception {
        String current = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(current.contains(target));
        Files.writeString(path, current.replace(target, replacement), StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String text, String token) {
        String src = safe(text);
        String needle = safe(token);
        if (src.isBlank() || needle.isBlank()) return 0;
        int count = 0;
        int index = 0;
        while (true) {
            index = src.indexOf(needle, index);
            if (index < 0) break;
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
