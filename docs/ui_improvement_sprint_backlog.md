# UI Improvement Sprint Backlog

As of March 10, 2026.

## 1) Purpose

Translate the UI improvement strategy into executable tickets with estimates, dependencies, acceptance criteria, and rollout order for this JSP/Tomcat codebase.

## 2) Baseline (Current State)

Measured from `src/main/webapp`:

1. Inline styles in JSP pages: about `992` occurrences.
2. Largest UI files:
   - `forms.jsp` (~4266 lines)
   - `tenant_settings.jsp` (~1819 lines)
   - `tasks.jsp` (~1466 lines)
   - `omnichannel.jsp` (~1316 lines)
   - `facts.jsp` (~1302 lines)
   - `header.jsp` (~1243 lines)
3. Table-heavy views: at least `61` tables across JSPs.
4. Shared header/footer executes global theme + geolocation/weather logic on all pages.

## 3) Planning Assumptions

1. Sprint length: 2 weeks.
2. Team capacity: ~20 engineering days per sprint (single primary engineer with review support).
3. Estimation model:
   - Story points: 1/2/3/5/8.
   - Rough days: SP `1=0.5d`, `2=1d`, `3=1.5d`, `5=3d`, `8=5d`.
4. No functional/security behavior changes unless explicitly listed in acceptance criteria.

## 4) Epic Map

1. Epic A: UI foundation and consistency.
2. Epic B: Navigation and wayfinding.
3. Epic C: Workflow screen usability (Forms, Tasks, Facts, Omnichannel).
4. Epic D: Install and settings simplification.
5. Epic E: Accessibility, performance, and stabilization.

## 5) Sprint Roadmap

### Sprint 1: Foundation and Navigation

| ID | Title | Epic | SP | Est. Days | Depends On | Primary Files |
| --- | --- | --- | --- | --- | --- | --- |
| UI-001 | Create shared UI utility class set and page-level style conventions | A | 5 | 3.0 | None | `style.css` |
| UI-002 | Remove inline styles from `index.jsp`, `search.jsp`, `tenant_login.jsp`, `user_login.jsp` | A | 5 | 3.0 | UI-001 | `index.jsp`, `search.jsp`, `tenant_login.jsp`, `user_login.jsp`, `style.css` |
| UI-003 | Add command palette (`Cmd/Ctrl+K`) using `menu.xml` entries | B | 8 | 5.0 | UI-001 | `header.jsp`, `menu.xml`, `style.css` |
| UI-004 | Add breadcrumb + context chip pattern for workflow pages | B | 3 | 1.5 | UI-001 | `header.jsp`, `forms.jsp`, `tasks.jsp`, `facts.jsp`, `documents.jsp` |
| UI-005 | Add baseline UI telemetry hooks (navigation depth + task completion timing markers) | E | 3 | 1.5 | UI-003 | `header.jsp`, key workflow JSPs |

### Sprint 2: Workflow Core (Forms + Tasks)

| ID | Title | Epic | SP | Est. Days | Depends On | Primary Files |
| --- | --- | --- | --- | --- | --- | --- |
| UI-006 | Refactor `forms.jsp` layout into consistent two-pane structure with sticky primary actions | C | 8 | 5.0 | UI-001, UI-004 | `forms.jsp`, `style.css` |
| UI-007 | Simplify template picker modal UX and keyboard behavior | C | 5 | 3.0 | UI-006 | `forms.jsp`, `style.css` |
| UI-008 | Standardize table/filter toolbar pattern in `tasks.jsp` and add persistent filter state | C | 8 | 5.0 | UI-001, UI-005 | `tasks.jsp`, `style.css` |
| UI-009 | Introduce shared detail-pane action bar component for Tasks and Documents | C | 3 | 1.5 | UI-008 | `tasks.jsp`, `documents.jsp`, `style.css` |

### Sprint 3: Workflow Extended (Facts + Omnichannel + Settings)

| ID | Title | Epic | SP | Est. Days | Depends On | Primary Files |
| --- | --- | --- | --- | --- | --- | --- |
| UI-010 | Align `facts.jsp` tree/detail interactions with Tasks pattern | C | 8 | 5.0 | UI-008 | `facts.jsp`, `style.css` |
| UI-011 | Improve `omnichannel.jsp` timeline readability and assignment action grouping | C | 5 | 3.0 | UI-009 | `omnichannel.jsp`, `style.css` |
| UI-012 | Split `install.jsp` Step 4 into Basic/Advanced sections and reduce cognitive load | D | 8 | 5.0 | UI-001 | `install.jsp`, `style.css` |
| UI-013 | Add structured section navigation inside `tenant_settings.jsp` | D | 5 | 3.0 | UI-001 | `tenant_settings.jsp`, `style.css` |

### Sprint 4: Accessibility, Performance, and Hardening

| ID | Title | Epic | SP | Est. Days | Depends On | Primary Files |
| --- | --- | --- | --- | --- | --- | --- |
| UI-014 | Remove remaining inline styles from top 10 edited pages | A | 5 | 3.0 | UI-002, UI-006, UI-008, UI-010, UI-012 | multiple JSPs, `style.css` |
| UI-015 | Normalize ARIA, focus order, modal focus trapping, and tab semantics | E | 8 | 5.0 | UI-007, UI-010 | `forms.jsp`, `case_focus.jsp`, `tenant_settings.jsp`, `header.jsp` |
| UI-016 | Optimize global header/footer scripts and make weather/geolocation opt-in | E | 5 | 3.0 | UI-001 | `header.jsp`, `footer.jsp` |
| UI-017 | Cross-page responsive QA pass (desktop/mobile), bug fixes | E | 5 | 3.0 | UI-014, UI-015, UI-016 | all updated JSPs + CSS |
| UI-018 | Final UX measurement readout and release notes | E | 2 | 1.0 | UI-017, UI-005 | docs + minor instrumentation surfaces |

## 6) Ticket Acceptance Criteria

### UI-001

1. Shared utility classes are documented in `style.css` comments.
2. New utility classes cover recurring patterns now represented by inline style attributes.
3. No visual regressions on `index.jsp`, `tenant_login.jsp`, and `search.jsp`.

### UI-002

1. Inline style count reduced on targeted pages by at least 80%.
2. Layout parity maintained for desktop and mobile.
3. No broken forms, labels, or action buttons.

### UI-003

1. `Cmd+K`/`Ctrl+K` opens command palette from any authenticated page.
2. Searchable menu entries include groups and target URLs from `menu.xml`.
3. Keyboard navigation supports arrow keys, Enter, and Escape.

### UI-004

1. Breadcrumb visible on `forms.jsp`, `tasks.jsp`, `facts.jsp`, `documents.jsp`.
2. Active case/context chip visible when case is selected.
3. Breadcrumb links route correctly without losing existing query state.

### UI-005

1. Capture timing markers for entering and completing top workflow tasks.
2. Navigation-depth metric available for before/after comparison.
3. Instrumentation does not block UI rendering.

### UI-006

1. Forms workspace presents consistent two-pane model with stable action area.
2. Primary actions remain visible during long-content scroll.
3. Focus mode and non-focus mode both preserve existing assembly behavior.

### UI-007

1. Template picker supports complete keyboard-only operation.
2. Focus is trapped within modal while open and returned to trigger on close.
3. Search/filter interaction remains performant for large template lists.

### UI-008

1. Task filters and sorting are persistable per user scope.
2. Filter controls use a consistent toolbar layout with clear reset action.
3. Task tree and detail views stay synchronized after filter changes.

### UI-009

1. Shared action bar pattern introduced on at least Tasks and Documents.
2. Primary and destructive actions are visually separated.
3. Action bar remains responsive on mobile widths.

### UI-010

1. Facts page uses same interaction conventions as Tasks for tree + detail.
2. Selection state remains stable after save/archive/restore actions.
3. Counts and status chips remain accurate after actions.

### UI-011

1. Thread timeline readability improved (spacing, metadata grouping, action clarity).
2. Assignment controls are grouped and discoverable.
3. No regressions to message/note/attachment flows.

### UI-012

1. Install Step 4 is split into clearly labeled Basic and Advanced sections.
2. Advanced integration settings are collapsed by default.
3. Existing form submissions and backend field names remain compatible.

### UI-013

1. Tenant Settings has in-page section navigation with accurate anchors.
2. Active section state updates while scrolling.
3. Existing tab behavior continues to work.

### UI-014

1. Inline style count on targeted pages reduced to near-zero.
2. Shared class usage replaces repeated style blocks.
3. No loss of accessibility contrast/focus indicators.

### UI-015

1. Modal, tab, and menu patterns satisfy keyboard navigation expectations.
2. ARIA roles/labels are consistent and non-conflicting.
3. Screen-reader announcements exist for async status updates where needed.

### UI-016

1. Header/footer scripts are split into lazy or conditionally loaded modules.
2. Weather/geolocation calls are user-enabled rather than automatic.
3. First paint and interactive readiness improve on representative pages.

### UI-017

1. Desktop and mobile QA checklist passes on all modified pages.
2. Visual defects and overflow clipping issues are resolved.
3. No new console errors in standard workflows.

### UI-018

1. Pre/post metric comparison documented.
2. Release notes summarize UI changes by workflow area.
3. Follow-up backlog items recorded for remaining long-tail pages.

## 7) Definition of Done (All Tickets)

1. Code reviewed.
2. Manual validation on desktop and mobile viewport.
3. No broken auth/session flow.
4. No new high-severity accessibility issues.
5. If behavior changed, update user-facing docs/help where relevant.

## 8) Sequencing and Risk Controls

1. Do not redesign visual language and interaction architecture in the same ticket.
2. Always ship shared CSS utility groundwork before large page migrations.
3. Keep security-sensitive pages (`tenant_login.jsp`, `install.jsp`) behavior-stable; UI changes only unless explicitly approved.
4. Validate every sprint against baseline metrics from Section 2.

## 9) Immediate Next Actions

1. Create branches and ticket stubs for Sprint 1 (`UI-001` through `UI-005`).
2. Start with `UI-001` and `UI-002` to unlock faster migration for subsequent sprints.
3. Schedule a short checkpoint after `UI-003` to validate command palette value before deeper workflow refactors.
