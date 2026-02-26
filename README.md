# Controversies

Controversies is a local-first legal document workflow app built on embedded Tomcat + JSP. It is designed to run as a single Java process and store its state as files in the local `data/` directory.

It supports:
- Tenant and user login (with role-based permissions)
- Case management
- Tenant-level and case-level replacement fields
- DOCX/DOC/RTF template assembly
- Token-driven merge behavior (`{{case.*}}`, `{{tenant.*}}`, `{{kv.*}}`)
- Activity/log views from inside the UI

---

## Table of contents
- [Who this is for](#who-this-is-for)
- [Prerequisites](#prerequisites)
- [Getting started (novice-friendly)](#getting-started-novice-friendly)
- [Daily workflow](#daily-workflow)
- [Browser auto-open behavior](#browser-auto-open-behavior)
- [Running and packaging](#running-and-packaging)
- [Project layout](#project-layout)
- [Data and security notes](#data-and-security-notes)
- [Clio integration by deployment topology](#clio-integration-by-deployment-topology)
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
6. **Form Assembly**: assemble templates with token replacement
7. **Assembled Forms / Logs**: inspect output and diagnostics

Main navigation is in `menu.xml` and links these pages from the header.

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
  tenant_fields.jsp
  forms.jsp
  assembled_forms.jsp
  template_library.jsp
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
Use a full JDK (not JRE-only runtime), then re-run startup.

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
