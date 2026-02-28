package net.familylawandprobate.controversies;

import java.util.Arrays;
import java.util.List;

/**
 * Lightweight CLI helper for scripted legal-document lifecycle operations.
 */
public final class document_cli {
    private document_cli() {}

    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            System.out.println("Usage: document_cli <command> ...");
            return;
        }
        String cmd = document_workflow_support.safe(args[0]).trim().toLowerCase();
        switch (cmd) {
            case "taxonomy-add":
                taxonomyAdd(args);
                break;
            case "document-create":
                documentCreate(args);
                break;
            case "part-create":
                partCreate(args);
                break;
            case "version-create":
                versionCreate(args);
                break;
            case "document-list":
                documentList(args);
                break;
            default:
                System.out.println("Unknown command: " + cmd);
        }
    }

    private static void taxonomyAdd(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("taxonomy-add <tenantUuid> <categoriesCsv> <subcategoriesCsv> <statusesCsv>");
            return;
        }
        document_taxonomy.defaultStore().addValues(args[1], csv(args[2]), csv(args[3]), csv(args[4]));
        System.out.println("ok");
    }

    private static void documentCreate(String[] args) throws Exception {
        if (args.length < 7) {
            System.out.println("document-create <tenantUuid> <matterUuid> <title> <category> <subcategory> <status> [owner]");
            return;
        }
        documents.DocumentRec rec = documents.defaultStore().create(args[1], args[2], args[3], args[4], args[5], args[6],
                args.length > 7 ? args[7] : "", "", "", "", "");
        System.out.println(rec.uuid);
    }

    private static void partCreate(String[] args) throws Exception {
        if (args.length < 7) {
            System.out.println("part-create <tenantUuid> <matterUuid> <docUuid> <label> <partType> <status>");
            return;
        }
        document_parts.PartRec rec = document_parts.defaultStore().create(args[1], args[2], args[3], args[4], args[5], args[6], "", "", "", "");
        System.out.println(rec.uuid);
    }

    private static void versionCreate(String[] args) throws Exception {
        if (args.length < 7) {
            System.out.println("version-create <tenantUuid> <matterUuid> <docUuid> <partUuid> <versionLabel> <source>");
            return;
        }
        part_versions.VersionRec rec = part_versions.defaultStore().create(args[1], args[2], args[3], args[4], args[5], args[6], "", "", "", "", "", "", true);
        System.out.println(rec.uuid);
    }

    private static void documentList(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("document-list <tenantUuid> <matterUuid>");
            return;
        }
        for (documents.DocumentRec rec : documents.defaultStore().listAll(args[1], args[2])) {
            if (rec == null) continue;
            System.out.println(rec.uuid + "\t" + rec.title + "\t" + rec.status);
        }
    }

    private static List<String> csv(String in) {
        String s = document_workflow_support.safe(in).trim();
        if (s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).toList();
    }
}
