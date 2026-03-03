package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class tasks_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void tasks_support_subtasks_associations_notes_assignments_and_reports() throws Exception {
        String tenant = "tenant-tasks-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters matterStore = matters.defaultStore();
        matterStore.ensure(tenant);
        matters.MatterRec matter = matterStore.create(
                tenant,
                "Task Matter",
                "",
                "",
                "",
                "",
                ""
        );
        assertNotNull(matter);

        documents docs = documents.defaultStore();
        document_parts parts = document_parts.defaultStore();
        part_versions versions = part_versions.defaultStore();

        documents.DocumentRec doc = docs.create(
                tenant,
                matter.uuid,
                "Workplan Exhibit",
                "evidence",
                "exhibit",
                "draft",
                "owner",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = parts.create(
                tenant,
                matter.uuid,
                doc.uuid,
                "Main Part",
                "lead",
                "1",
                "internal",
                "owner",
                ""
        );
        Path sourcePath = parts.partFolder(tenant, matter.uuid, doc.uuid, part.uuid)
                .resolve("version_files")
                .resolve("task_source.pdf")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(sourcePath.getParent());
        Files.write(sourcePath, "task-source".getBytes(StandardCharsets.UTF_8));
        part_versions.VersionRec version = versions.create(
                tenant,
                matter.uuid,
                doc.uuid,
                part.uuid,
                "v1",
                "uploaded",
                "application/pdf",
                "abc123",
                String.valueOf(Files.size(sourcePath)),
                sourcePath.toString(),
                "owner",
                "",
                true
        );

        matter_facts factsStore = matter_facts.defaultStore();
        factsStore.ensure(tenant, matter.uuid);
        matter_facts.ClaimRec claim = factsStore.createClaim(tenant, matter.uuid, "Claim A", "", 10, "tester");
        matter_facts.ElementRec element = factsStore.createElement(tenant, matter.uuid, claim.uuid, "Element A", "", 10, "tester");
        matter_facts.FactRec fact = factsStore.createFact(
                tenant,
                matter.uuid,
                claim.uuid,
                element.uuid,
                "Fact A",
                "",
                "",
                "corroborated",
                "high",
                doc.uuid,
                part.uuid,
                version.uuid,
                2,
                10,
                "tester"
        );

        omnichannel_tickets threadStore = omnichannel_tickets.defaultStore();
        threadStore.ensure(tenant);
        omnichannel_tickets.TicketRec thread = threadStore.createTicket(
                tenant,
                matter.uuid,
                "flowroute_sms",
                "Client Intake",
                "open",
                "normal",
                "manual",
                "user-a",
                "",
                "",
                "Client",
                "+15551230000",
                "+15557654321",
                "sms-thread-1",
                "",
                "inbound",
                "Initial message",
                "+15551230000",
                "+15557654321",
                false,
                "tester",
                ""
        );

        tasks store = tasks.defaultStore();
        store.ensure(tenant);

        tasks.TaskRec create = new tasks.TaskRec();
        create.matterUuid = matter.uuid;
        create.title = "Draft discovery checklist";
        create.description = "Prepare and circulate discovery checklist draft.";
        create.status = "open";
        create.priority = "high";
        create.assignmentMode = "manual";
        create.assignedUserUuid = "user-a,user-b";
        create.dueAt = "2026-04-02T17:00";
        create.reminderAt = "2026-04-01T10:00";
        create.estimateMinutes = 120;
        create.claimUuid = claim.uuid;
        create.elementUuid = element.uuid;
        create.factUuid = fact.uuid;
        create.documentUuid = doc.uuid;
        create.partUuid = part.uuid;
        create.versionUuid = version.uuid;
        create.pageNumber = 2;
        create.threadUuid = thread.uuid;

        tasks.TaskRec parent = store.createTask(tenant, create, "tester", "Initial assignment");
        assertNotNull(parent);
        assertFalse(safe(parent.uuid).isBlank());
        assertFalse(safe(parent.reportDocumentUuid).isBlank());
        assertFalse(safe(parent.reportPartUuid).isBlank());
        assertFalse(safe(parent.lastReportVersionUuid).isBlank());

        List<tasks.AssignmentRec> assignments = store.listAssignments(tenant, parent.uuid);
        assertEquals(1, assignments.size());

        tasks.NoteRec note = store.addNote(tenant, parent.uuid, "Internal prep note", "tester");
        assertNotNull(note);
        assertFalse(safe(note.uuid).isBlank());
        List<tasks.NoteRec> notes = store.listNotes(tenant, parent.uuid);
        assertEquals(1, notes.size());

        tasks.TaskRec sub = new tasks.TaskRec();
        sub.matterUuid = matter.uuid;
        sub.parentTaskUuid = parent.uuid;
        sub.title = "Collect initial exhibits";
        sub.description = "Pull exhibit list from intake folder.";
        sub.status = "in_progress";
        sub.priority = "normal";
        sub.assignmentMode = "manual";
        sub.assignedUserUuid = "user-b";
        sub.dueAt = "2026-04-01T12:00";
        sub.reminderAt = "";
        sub.estimateMinutes = 45;
        sub.threadUuid = thread.uuid;

        tasks.TaskRec child = store.createTask(tenant, sub, "tester", "Subtask owner");
        assertNotNull(child);
        assertEquals(parent.uuid, safe(child.parentTaskUuid));

        String rr = store.chooseRoundRobinAssignee(tenant, "tasks|" + matter.uuid, List.of("user-a", "user-b", "user-c"));
        assertFalse(safe(rr).isBlank());

        tasks.TaskRec update = new tasks.TaskRec();
        update.uuid = parent.uuid;
        update.matterUuid = parent.matterUuid;
        update.parentTaskUuid = parent.parentTaskUuid;
        update.title = parent.title + " (updated)";
        update.description = parent.description;
        update.status = "in_progress";
        update.priority = parent.priority;
        update.assignmentMode = "round_robin";
        update.assignedUserUuid = rr;
        update.dueAt = parent.dueAt;
        update.reminderAt = parent.reminderAt;
        update.estimateMinutes = parent.estimateMinutes;
        update.claimUuid = parent.claimUuid;
        update.elementUuid = parent.elementUuid;
        update.factUuid = parent.factUuid;
        update.documentUuid = parent.documentUuid;
        update.partUuid = parent.partUuid;
        update.versionUuid = parent.versionUuid;
        update.pageNumber = parent.pageNumber;
        update.threadUuid = parent.threadUuid;
        update.createdBy = parent.createdBy;
        update.createdAt = parent.createdAt;
        update.updatedAt = parent.updatedAt;
        update.completedAt = parent.completedAt;
        update.archived = false;
        update.reportDocumentUuid = parent.reportDocumentUuid;
        update.reportPartUuid = parent.reportPartUuid;
        update.lastReportVersionUuid = parent.lastReportVersionUuid;

        assertTrue(store.updateTask(tenant, update, "tester", "Round-robin rebalance"));

        List<tasks.AssignmentRec> assignmentsAfter = store.listAssignments(tenant, parent.uuid);
        assertTrue(assignmentsAfter.size() >= 2);

        assertTrue(store.setArchived(tenant, child.uuid, true, "tester"));
        assertTrue(store.getTask(tenant, child.uuid).archived);
        assertTrue(store.setArchived(tenant, child.uuid, false, "tester"));
        assertFalse(store.getTask(tenant, child.uuid).archived);

        List<part_versions.VersionRec> reportVersions = versions.listAll(
                tenant,
                matter.uuid,
                parent.reportDocumentUuid,
                parent.reportPartUuid
        );
        assertFalse(reportVersions.isEmpty());
        Path reportPath = Paths.get(safe(reportVersions.get(0).storagePath)).toAbsolutePath().normalize();
        assertTrue(Files.exists(reportPath));
        assertTrue(Files.size(reportPath) > 0L);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
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
}
