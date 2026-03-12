# WYSIWYG Version Editor (ONLYOFFICE)

This project now includes a JSP-backed WYSIWYG editor flow for document part versions.

## Supported Source Formats

- `docx`
- `doc`
- `rtf`
- `rtx` (normalized to `rtf` for editor handoff)
- `odt`
- `odf` (normalized to `odt` for editor handoff)

## Runtime Configuration

Set these environment variables (or matching JVM system properties):

- `CONTROVERSIES_ONLYOFFICE_DOCSERVER_URL` (required)
  - JVM property fallback: `controversies.onlyoffice.docserver.url`
  - Example: `https://docs.yourdomain.com`
- `CONTROVERSIES_EDITOR_PUBLIC_BASE_URL` (optional but recommended behind reverse proxies)
  - JVM property fallback: `controversies.editor.public.base.url`
  - Example: `https://app.yourdomain.com`
- `CONTROVERSIES_ONLYOFFICE_JWT_SECRET` (optional, enables signed editor config token)
  - JVM property fallback: `controversies.onlyoffice.jwt.secret`
- `CONTROVERSIES_EDITOR_SESSION_TTL_SECONDS` (optional, default 28800 / 8h)
  - JVM property fallback: `controversies.editor.session.ttl.seconds`

## User Flow

1. Open `Versions` for a document part.
2. Click `Edit` on an eligible version row.
3. The editor page (`version_editor.jsp`) launches ONLYOFFICE.
4. Save in ONLYOFFICE.
5. Callback creates a **new current** part version with source `wysiwyg.onlyoffice`.

## Security Model

- Editor file and callback endpoints are token-gated:
  - `GET /version_editor/file?token=...`
  - `POST /version_editor/callback?token=...`
- Session tokens are random, short-lived, and stored under `data/sec/editor_sessions`.
- File access enforces tenant/session boundary checks.
- Callback saves are deduplicated by checksum to avoid duplicate retries.
