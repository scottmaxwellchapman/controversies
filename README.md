# Controversies

Controversies is a local-first legal document workflow app built on embedded Tomcat + JSP. It is designed to run as a single Java process and store its state as files in the local `data/` directory.

It supports:
- Tenant and user login (with role-based permissions)
- Case management
- Facts case-plan management (Claims -> Elements -> Facts)
- Omnichannel thread management (Flowroute SMS/MMS + IMAP/SMTP + Graph mailbox channels)
- Tenant-level and case-level replacement fields
- DOCX/DOC/RTF/ODT/TXT template assembly
- Token-driven merge behavior (`{{case.*}}`, `{{tenant.*}}`, `{{kv.*}}`)
- Built-in business process automation with human review and undo/redo
- Activity/log views from inside the UI

---

## Table of contents
- [Who this is for](#who-this-is-for)
- [Prerequisites](#prerequisites)
- [Getting started (novice-friendly)](#getting-started-novice-friendly)
- [Daily workflow](#daily-workflow)
- [Facts Case Plan](#facts-case-plan)
- [Omnichannel Threads](#omnichannel-threads)
- [Template format support](#template-format-support)
- [Browser auto-open behavior](#browser-auto-open-behavior)
- [Running and packaging](#running-and-packaging)
- [Cross-platform compatibility](#cross-platform-compatibility)
- [Project layout](#project-layout)
- [Data and security notes](#data-and-security-notes)
- [Tenant settings guide (first-time and advanced)](#tenant-settings-guide-first-time-and-advanced)
- [Clio integration by deployment topology](#clio-integration-by-deployment-topology)
- [API (n8n/OpenClaw)](#api-n8nopenclaw)
- [Business process built-ins](#business-process-built-ins)
- [Logging and audit visibility](#logging-and-audit-visibility)
- [Troubleshooting](#troubleshooting)
- [For experienced users](#for-experienced-users)

---

## Who this is for

### New users
If you just want to launch the app, log in, and generate forms, follow **Getting started** and **Daily workflow**.

### Experienced users
If you want to script launches, package the fat JAR, tune headless/desktop behavior, or inspect where data is stored, use the **For experienced users** section.

---

## Prerequisites

- **JDK 23** (required by `maven.compiler.release=23`)
- **Maven 3.9+**
- A desktop environment only if you want automatic browser launch

Check versions:

```bash
java -version
mvn -version
```

---

## Getting started (novice-friendly)

1. **Clone and enter the project**
   ```bash
   git clone <your-repo-url>
   cd controversies
   ```

2. **Run the app**
   ```bash
   mvn exec:java
   ```

3. **Open the app**
   - On desktop environments, the app will try to open your default browser automatically.
   - If it does not, open:
     - `https://localhost:8443/` (main)
     - `http://localhost:8080/` (redirects to HTTPS)

4. **Handle the certificate warning**
   The first run creates a development self-signed certificate in `data/sec/ssl/keystore.p12`. Your browser may show a warning; proceed for local development.

5. **Log in (default bootstrap account)**
   - Tenant label: `Default Tenant`
   - Tenant password: `password`

6. **Then complete user login**
   Use the UI flow to create/manage users and roles, then sign in as a user.

---

## Daily workflow

1. **Tenant login** (`tenant_login.jsp`)
2. **User login** (`user_login.jsp`)
3. **Users & Security**: define roles/permissions and users
4. **Cases**: create matters and case-specific values
5. **Tenant Fields**: define shared/global tenant values
6. **Form Assembly**: assemble `.docx`, `.doc`, `.rtf`, `.odt`, and `.txt` templates with token replacement
7. **Assembled Forms / Logs**: inspect output and diagnostics

Main navigation is in `menu.xml` and links these pages from the header.

---

## Facts Case Plan

The app includes a visual case-plan workspace at `facts.jsp` with:

- Side-tree hierarchy: `Claims -> Elements -> Facts`
- Fact-level status/strength tracking and user-only internal notes
- Source-linking for each fact to document UUID, part UUID, version UUID, and page number
- Automatic landscape PDF report generation on hierarchy updates
- Report storage as standard matter document/part/version history

API coverage for this feature includes:

- `facts.tree.get`
- `facts.claims.*`
- `facts.elements.*`
- `facts.facts.*`
- `facts.report.refresh`

---

## Omnichannel Threads

The app includes an omnichannel thread workspace at `omnichannel.jsp` with:

- Channels:
  - `flowroute_sms` (SMS/MMS)
  - `email_imap_smtp`
  - `email_graph_user`
  - `email_graph_shared`
- Statuses, reminders, due dates, assignment/re-assignment history
- Manual assignment and optional round-robin assignment support
- Multi-user assignment values (CSV UUID list) for shared ownership
- Message timeline with internal notes (staff-only)
- Attachment/media ledger with download and manifest views

Thread report behavior:

- Thread updates can generate/regenerate a matter-linked PDF report.
- Public thread media/attachments are embedded in the PDF and image media is rendered onto dedicated pages.
- Internal-note-linked content is excluded from external-facing report content.
- `omnichannel_manifest.jsp` provides a per-thread embedded-media manifest.

---

## Template format support

Supported template file types in Form Assembly and Template Library:

- `.docx` (Word Open XML)
- `.doc` (legacy Word binary)
- `.rtf` (Rich Text Format)
- `.odt` (OpenDocument Text)
- `.txt` (plain text)

Import behavior:

- Direct uploads accept these formats.
- `.zip` imports are supported when archive contents use the same supported template types.
- Folder import recursively scans supported files.

Validation and assembly behavior:

- `.docx` uploads are validated as valid DOCX packages.
- `.odt` uploads are validated as OpenDocument packages containing `content.xml`.
- The assembler can detect misnamed `.docx`, `.odt`, `.doc`, and `.rtf` payloads even when a wrong extension is supplied.
- ODT assembly preserves ODT container structure while applying token replacements in XML content.

---

## Browser auto-open behavior

The app now restores automatic browser launch behavior when Tomcat starts in a desktop (non-headless) environment.

Behavior summary:
- If Java is running with a desktop UI and `Desktop.browse` is supported, startup opens `https://localhost:8443/`.
- If running headless (CI, server, container without desktop), startup **does not fail**; it simply skips browser launch.

Control options:
- JVM property (highest priority):
  - `-Djava.awt.headless=true` -> force headless
  - `-Djava.awt.headless=false` -> force desktop mode
- Environment variable (when JVM property is not set):
  - `CONTROVERSIES_HEADLESS=true` / `1` -> force headless
  - `CONTROVERSIES_HEADLESS=false` / `0` -> force desktop mode

Examples:

```bash
# Force headless startup
CONTROVERSIES_HEADLESS=true mvn exec:java

# Force desktop startup (attempt browser auto-open)
CONTROVERSIES_HEADLESS=false mvn exec:java

# Explicit JVM override
mvn -Dexec.jvmArgs='-Djava.awt.headless=true' exec:java
```

---

## Running and packaging

### Development run
```bash
mvn exec:java
```

### Compile/package
```bash
mvn clean package
```

This produces an executable shaded JAR like:

- `target/controversies-1.0-SNAPSHOT-all.jar`

Run it:

```bash
java -jar target/controversies-1.0-SNAPSHOT-all.jar
```

---

## Cross-platform compatibility

Controversies is designed to run on Windows, macOS, Debian Linux, and FreeBSD with JDK 23 + Maven.

Platform notes:

- Startup uses Java APIs (`Desktop`, NIO `Path`, embedded Tomcat) instead of shell-specific launch code.
- HTTPS dev keystore generation uses `keytool` from `java.home/bin` and falls back to `keytool` on `PATH`.
- File paths are built with `java.nio.file.Path` for separator compatibility.

Command quick references:

- macOS / Debian / FreeBSD:
  - `mvn exec:java`
  - `mvn -q -DskipTests package`
  - `java -jar target/controversies-1.0-SNAPSHOT-all.jar`
- Windows (PowerShell):
  - `mvn exec:java`
  - `mvn -q -DskipTests package`
  - `java -jar target\controversies-1.0-SNAPSHOT-all.jar`

---

## Project layout

```text
src/main/java/net/familylawandprobate/controversies/
  app.java                 # main entrypoint
  tomcat.java              # embedded Tomcat + HTTPS/HTTP connectors
  users_roles.java         # users, roles, auth helpers, bindings
  tenants.java             # tenant store + bootstrap default tenant
  ...

src/main/webapp/
  index.jsp
  tenant_login.jsp
  user_login.jsp
  users_roles.jsp
  cases.jsp
  facts.jsp
  omnichannel.jsp
  omnichannel_manifest.jsp
  tenant_fields.jsp
  forms.jsp
  assembled_forms.jsp
  template_library.jsp
  business_processes.jsp
  business_process_reviews.jsp
  help_center.jsp
  help_facts_novice.jsp
  help_facts_expert.jsp
  help_threads_novice.jsp
  help_threads_expert.jsp
  token_guide.jsp
  log_viewer.jsp
  ...

data/
  tenants.xml
  sec/
    random_pepper.bin
    ssl/keystore.p12
  tenants/<tenant-uuid>/...
```

---


## Tenant settings guide (first-time and advanced)

Open **Tenant Settings** from the main navigation to configure storage, Clio integration, feature flags, and security controls.

### First-time tenant setup

1. Choose a **Storage Backend** and required endpoint/credentials.
2. If using S3 with KMS, provide **S3 SSE KMS Key Id**.
3. Click **Test Storage Connection** until it reports success.
4. Configure **Clio Connection** only if this tenant needs Clio synchronization.
5. Click **Test Clio Connection** and resolve any validation messages.
6. Click **Save Settings** to persist all changes.

### Advanced/admin usage

- Leave password/secret fields blank if you are not rotating secrets.
- Use **Rotate Storage Secret** or **Rotate Clio Secret** to timestamp key rotation activity before final save.
- Feature flags can be toggled independently without changing integration credentials.
- Security Controls summarizes current status, last check times, and redaction policy.

Validation behavior:
- Clio URL/auth checks run when Clio configuration is present.
- Tenant-managed encryption requires a non-empty application encryption key.
- SSE-KMS requires a KMS key id when enabled.

---

## Clio integration by deployment topology

Use **Tenant Settings → Clio Connection** to choose an auth mode aligned with where this app is deployed.

### Public mode (web-accessible app)

Use this when Clio can reach your app over the public internet.

1. Set **Auth Mode** to `Public mode`.
2. Configure **Base URL**, **Client ID**, and **Client Secret**.
3. Set **OAuth Callback URL** to a publicly reachable endpoint served by your deployment.
4. Run **Test Clio Connection** and confirm status/health read `ok`.
5. Save settings.

Operational notes:
- This is the standard OAuth redirect flow.
- Callback endpoints must be reachable by Clio and match your Clio app registration.

### Private mode (firewalled or VPN-only app)

Use this when Clio cannot directly call into the running app.

1. Set **Auth Mode** to `Private mode`.
2. Configure **Base URL**, **Client ID**, and **Client Secret**.
3. Enter **Relay/Admin Exchange Path** with either:
   - A public relay URL that can complete OAuth and relay an auth code/token exchange, or
   - An internal runbook/SOP reference for manual admin-mediated auth code exchange.
4. Complete OAuth outside the private app boundary (relay/admin flow), then store only resulting token material in tenant settings storage.
5. Run **Test Clio Connection**, confirm health, and save settings.

Operational notes:
- Keep access/refresh token material local to this app.
- Do not expose private app endpoints publicly just to satisfy OAuth callback requirements.

## Data and security notes

- Data is persisted as XML/files under `data/`.
- A random pepper file is maintained at `data/sec/random_pepper.bin`.
- Dev HTTPS keystore is auto-generated at `data/sec/ssl/keystore.p12`.
- Tenant bootstrap credentials are intended for first-run setup; change credentials and role assignments promptly.
- API discovery endpoints are unauthenticated by design; operation endpoints require tenant API credentials.
- API server errors return a generic `server_error` response while detailed error context is logged internally.
- Thread attachment storage paths are normalized and constrained under the tenant thread attachment directory.

---


## Logging and audit visibility

Tenant events are written to per-tenant XML activity logs under:

- `data/tenants/<tenant-uuid>/logs/activity_YYYY-MM-DD.xml`

The settings workflow records detailed events for:
- Storage connection tests (including validation issues)
- Clio connection tests (including auth-mode requirements)
- Secret rotation acceptance/rejection
- Validation failures on save
- Successful setting updates
- API operation success/failure audit entries (`api.operation.*`)
- Facts case-plan lifecycle events (`facts.claim.*`, `facts.element.*`, `facts.fact.*`, `facts.report.*`)
- Omnichannel thread lifecycle events (`omnichannel.thread.*`, `omnichannel.message.*`, `omnichannel.attachment.*`)
- BPM run lifecycle and human-review queue events

Sensitive values (secrets, tokens, keys, passwords) are automatically redacted in log detail payloads.

---

## API (n8n/OpenClaw)

Controversies exposes a tenant-scoped JSON API under `/api/v1/*` for automation workflows.

### Discovery endpoints (no API credential required)

- `GET /api/v1/help` (JSON help + operation list)
- `GET /api/v1/help/readme` (plain text/markdown help)
- `GET /api/v1/ping` (health check)
- `GET /api/v1/capabilities` (machine-readable operation catalog)

### Authentication

For all non-discovery API endpoints, include:

- `X-Tenant-UUID: <tenant_uuid>`
- `X-API-Key: <api_key>`
- `X-API-Secret: <api_secret>`

API credentials are generated/revoked by tenant admins in **Tenant Settings → API Credentials**.

### Endpoint patterns

You can call operations in any of these forms:

- `POST /api/v1/execute` with JSON body:
  - `{"operation":"matters.list","params":{"include_trashed":false}}`
- `POST /api/v1/op/matters/list`
- `POST /api/v1/matters/list`

Operations are normalized as dot notation (for example, `matters.list`).

### Response envelope

Success:

```json
{
  "ok": true,
  "version": "v1",
  "operation": "matters.list",
  "tenant_uuid": "...",
  "result": {}
}
```

Error:

```json
{
  "ok": false,
  "error_code": "bad_request",
  "error": "..."
}
```

### cURL example

```bash
curl -k -X POST "https://localhost:8443/api/v1/execute" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-UUID: <tenant_uuid>" \
  -H "X-API-Key: <api_key>" \
  -H "X-API-Secret: <api_secret>" \
  -d '{"operation":"matters.list","params":{"include_trashed":false}}'
```

### Operation coverage

Current API coverage includes:

- Authentication + introspection:
  - `auth.whoami`, `activity.recent`
- API credentials:
  - `api.credentials.list`, `api.credentials.create`, `api.credentials.revoke`
- Tenant configuration:
  - `tenant.settings.get`, `tenant.settings.update`, `tenant.fields.get`, `tenant.fields.update`
- Users, roles, permissions:
  - `users.*`, `roles.*`, `roles.permission.*`, `roles.permissions.replace`
- Case/matter workflow:
  - `matters.*`, `case.attributes.*`, `case.fields.*`, `case.list_items.*`
- Facts case-plan workflow:
  - `facts.tree.get`, `facts.claims.*`, `facts.elements.*`, `facts.facts.*`, `facts.report.refresh`
- Document workflow:
  - `document.taxonomy.*`, `document.attributes.*`, `documents.*`, `document.fields.*`, `document.parts.*`, `document.versions.*`
  - `document.versions.render_page`, `document.versions.redact` (PDF version preview/redaction)
- Templates + assembly:
  - `templates.*`, `template.tools.*`, `assembler.preview`, `assembler.assemble`, `assembly.run`, `assembled_forms.*`
- Custom objects:
  - `custom_objects.*`, `custom_object_attributes.*`, `custom_object_records.*`
- Omnichannel threads:
  - `omnichannel.threads.*`, `omnichannel.messages.*`, `omnichannel.notes.add`
  - `omnichannel.attachments.*`, `omnichannel.assignments.list`
  - `omnichannel.round_robin.next_assignee`
- Texas law:
  - `texas_law.status`, `texas_law.sync_now`, `texas_law.list_dir`, `texas_law.search`, `texas_law.render_page`
- Business process manager:
  - `bpm.actions.catalog`, `bpm.processes.*`, `bpm.events.trigger`, `bpm.runs.*`, `bpm.reviews.*`

Use `/api/v1/capabilities` for the authoritative operation list.

### PDF redaction operations

These operations support automation around the interactive PDF redaction feature.

- `document.versions.render_page`
  - Inputs: `matter_uuid`, `doc_uuid`, `part_uuid`, `source_version_uuid`, `page` (0-based)
  - Output: PNG page bytes (`image_png_base64`) plus pagination metadata
- `document.versions.redact`
  - Inputs: `matter_uuid`, `doc_uuid`, `part_uuid`, `source_version_uuid`, plus redactions either as:
    - `redactions` JSON array of objects with `page`/`x_norm`/`y_norm`/`width_norm`/`height_norm`, or
    - `redactions_payload` string (`page,x,y,w,h;...`)
  - Output: newly created redacted `version` metadata and redaction engine details

### Compatibility policy

When application features are added or changed, corresponding API operations should be added or updated in the same change so automation clients stay aligned.

---

## Business process built-ins

Built-in BPM step actions now include:

- `log_note`
- `set_case_field`
- `set_case_list_item`
- `set_document_field`
- `set_tenant_field`
- `set_variable`
- `trigger_event`
- `update_thread`
- `add_thread_note`
- `human_review`

Use API operation `bpm.actions.catalog` for machine-readable action metadata including required/optional settings and reversibility.

Undo/redo support:

- Reversible out of the box:
  - `set_case_field`, `set_case_list_item`, `set_document_field`, `set_tenant_field`
- Non-reversible by design:
  - logging, variable/event actions, and message/thread-note actions

---

## Troubleshooting

### Port in use
If startup fails with `port ... is already in use`, stop the conflicting process using ports `8080` or `8443`.

### Browser did not auto-open
- Confirm you are in a desktop environment.
- Check that headless mode is not forced:
  - remove `-Djava.awt.headless=true`
  - set `CONTROVERSIES_HEADLESS=false`

### Certificate warning
Expected for local dev self-signed cert. Continue after trusting/accepting for localhost.

### `keytool not found`
Use a full JDK (not JRE-only runtime) or ensure `keytool` is available on `PATH`, then re-run startup.

### Unsupported or invalid template uploads
- If you see `Unsupported template type`, use one of: `.docx`, `.doc`, `.rtf`, `.odt`, or `.txt`.
- If you see `Invalid DOCX template package`, re-save/export the source file as a valid `.docx`.
- If you see `Invalid ODT template package`, ensure the file is a real OpenDocument Text file with `content.xml`.

---

## For experienced users

### Runtime model
- Embedded Tomcat with:
  - HTTPS connector on `8443` (primary)
  - HTTP connector on `8080` redirecting to HTTPS
- A startup preflight checks whether ports are free and fails fast if occupied.

### Non-interactive / CI use
Use headless mode explicitly:

```bash
CONTROVERSIES_HEADLESS=true mvn -q -DskipTests package
java -Djava.awt.headless=true -jar target/controversies-1.0-SNAPSHOT-all.jar
```

### Extensibility hints
- Add new pages under `src/main/webapp/` and wire navigation via `menu.xml`.
- Keep server-side logic in `src/main/java/...` classes used by JSP pages.
- Preserve local-file data compatibility when evolving XML schemas.
