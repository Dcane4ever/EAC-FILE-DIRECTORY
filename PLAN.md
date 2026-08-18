# EAC File Directory — Project Plan

A ResearchGate/Scribd-style academic file repository for **Emilio Aguinaldo College (EAC)**.
Central place for research papers, theses, code projects, books, PPTX, PDFs, and other
academic files — browsable by department/program/category, taggable, searchable — restricted to
`@eac.edu.ph` accounts, styled with EAC's real visual identity (confirmed below).

**Reference materials received (kept out of git — see root `.gitignore`, only `EAC-FILE-DIRECTORY/` is pushed):**
- `EAC Enrollment - Login.html` + assets — real EAC login page (from the live enrollment system at `eacmnl`, port 8082) → source of truth for branding.
- `weblogin page.png` — screenshot of the same for visual reference.
- `eacdb (15).sql` — full dump of the live `eacdb` MySQL database (enrollment/registrar system) — used **read-only, structure/sample only**, to extract departments/programs/courses.

---

## 1. Core Decisions (locked in)

| Area | Decision |
|---|---|
| **Branding** | Keep existing EAC look — real assets now in hand (see §6). Emilio Aguinaldo College: deep maroon/red + navy palette, shield emblem, "Excellence in Education" tagline. |
| **Access control** | Any user with a valid `@eac.edu.ph` email can log in. No student/faculty/staff tiering for *access* — one shared audience. |
| **Auth method** | Google Sign-In (OAuth2), restricted to the `eac.edu.ph` Google Workspace domain (`hd` parameter + server-side domain check). No custom password system. |
| **Uploads** | Any logged-in user can submit a file, but it stays in a **pending** state until an admin/moderator **approves** it. Nothing goes public unmoderated. |
| **Organization** | Hybrid: browse by **Department/Course**, browse by **Category/Type** (Research Paper, Thesis, Book, Code, PPTX, PDF, etc.), plus **tags** and **full-text/metadata search** across everything — like a ResearchGate/Scribd hybrid. |
| **Departments/Courses source** | Extracted from the real `eacdb` registrar dump (`departments`, `programs`, `courses` tables), **not** live-linked. One-time (repeatable) seed script into our own fresh database. Real hierarchy confirmed — see §4a. |
| **Backend framework** | **Spring Boot** (Java), matching existing school infra/skillset. |
| **Deployment target** | On-prem **Apache Tomcat**, deployed as a WAR to the school's own server PC. No cloud hosting. Sibling deployment to the existing `eacmnl` enrollment app (localhost:8082). |
| **Database** | **MySQL/MariaDB** — a **new, dedicated database** for this app (separate from `eacdb`). |
| **File storage** | Files stored on the **server's local disk** in a structured directory tree; database stores only **metadata + file path** (not BLOBs). |
| **Frontend** | **Thymeleaf** (server-rendered) + **Tailwind CSS**, compiled via Tailwind CLI at build time (production-appropriate for Tomcat deploy). |

---

## 2. Roles

Even though *access* is uniform, the app still needs role-based *permissions* for moderation:

- **USER** (default for every @eac.edu.ph login) — browse, search, download, upload (→ pending), track own submissions.
- **MODERATOR/ADMIN** — approve/reject pending uploads, edit metadata, manage categories/departments/tags, remove content, view reports.
- **SUPERADMIN** (optional, small set) — manage other admins, system settings, view audit logs.

Role is stored in our own app DB, not derived from Google — first login creates a `USER` record; role upgrades are manual (admin promotes another admin).

---

## 3. High-Level Architecture

```
┌─────────────────────┐
│   Browser (SPA/MVC)  │  Thymeleaf server-rendered pages OR
│   EAC-themed UI      │  React/Vue SPA calling REST API (decide in §7)
└──────────┬───────────┘
           │ HTTPS
┌──────────▼───────────────────────────────────────────────┐
│  Spring Boot app (WAR) on Apache Tomcat                    │
│  ┌────────────┐ ┌───────────────┐ ┌─────────────────────┐ │
│  │ Spring       │ │ REST/MVC       │ │ File storage service │ │
│  │ Security     │ │ Controllers    │ │ (local disk I/O)     │ │
│  │ + Google     │ │ Services       │ │                       │ │
│  │ OAuth2       │ │ Repositories   │ │                       │ │
│  └────────────┘ └───────────────┘ └─────────────────────┘ │
└──────────┬──────────────────────────────┬──────────────────┘
           │ JDBC                          │ filesystem
┌──────────▼───────────┐         ┌─────────▼─────────────┐
│  MySQL/MariaDB        │         │  /data/eac-directory/   │
│  eac_directory (new)  │         │  <dept>/<category>/...  │
└───────────────────────┘         └────────────────────────┘

     (one-time / repeatable extraction script)
┌───────────────────────┐
│ eacdb (registrar dump)  │──▶ extract depts/programs/courses ──▶ seeds eac_directory
└───────────────────────┘
```

---

## 4a. Real Department/Program/Course Hierarchy (from `eacdb`)

Confirmed structure in the source DB — we mirror this shape, not a generic "SET" placeholder:

```
departments (18 rows)  →  programs (dozens, e.g. BSIT, BSCS, BSN, BSA...)  →  courses (~1,050 rows)
```

- **departments**: `department_id`, `department_code` (e.g. `ENGR`, `SAS`, `NURS`, `BUS`, `CRIM`), `department_name` (e.g. "School of Engineering and Technology"), `building_location`.
- **programs**: `program_id`, `program_code` (e.g. `BSIT`, `BSCS`), `program_name`, `department_id` (FK), `school_name`, `level`, `duration_years`.
- **courses**: `course_id`, `course_code`, `course_title`, `department_id` (FK), `description`, units/hours fields.

Sample departments actually in the DB: School of Engineering and Technology (`ENGR`), School of Arts and Sciences (`SAS`), General Education (`GEN`), Marian School of Nursing (`NURS`), School of Nutrition and Dietetics (`NUTR`), School of Business Education (`BUS`), School of Criminology (`CRIM`), School of Dentistry (`DENT`), School of Hospitality & Tourism Management (`HOSP`), School of Medical Technology (`MED`), School of Pharmacy (`PHAR`), School of Physical/Occupational/Respiratory Therapy (`PHYS`), School of Radiologic Technology (`RAD`), School of Teacher Education (`EDUC`), School of Midwifery & Caregiving (`MIDW`), School of Medicine (`SOM`), Graduate School (`GS`), ETEEAP.

**Plan:** write a one-time extraction script (SQL or a small Spring Boot CLI runner) that reads `departments`/`programs`/`courses` from the `eacdb` dump (or a copy of it) and seeds our own `eac_directory` schema's `departments`/`programs`/`courses` tables — decoupled afterward, safe to re-run when the real list is refreshed. **We treat `programs` as a first-class level** between department and course (richer than the original department/category-only plan), so the browse tree becomes: **School/Department → Program → Course → Files**, with Category/Tag/Search cutting across all of it.

---

## 4. Data Model (draft)

- **users** — id, google_sub, email, full_name, role, created_at, last_login
- **departments** — id, code, name, source_department_id (nullable link back to eacdb, for traceability)
- **programs** — id, department_id (FK), code, name, level, source_program_id
- **courses** — id, department_id (FK), program_id (nullable FK), code, title, source_course_id
- **categories** — id, name (Research Paper, Thesis, Book, Code, PPTX, PDF, Dataset, etc.), icon
- **tags** — id, name (free-form, many-to-many with files)
- **files** — id, title, description, uploader_id, department_id, program_id (nullable), course_id (nullable), category_id, status (`PENDING`/`APPROVED`/`REJECTED`), file_path, file_size, mime_type, checksum, download_count, created_at, approved_by, approved_at
- **file_tags** — file_id, tag_id
- **file_versions** (optional, later) — for re-uploads/corrections
- **audit_log** (optional, later) — who approved/rejected/deleted what, when

---

## 5. Key Features (MVP scope)

1. Google Sign-In restricted to `@eac.edu.ph`, auto-provision user on first login.
2. Browse by Department → Category (tree navigation).
3. Search + filter by tag, category, department, file type, uploader.
4. File detail page: preview (where feasible — PDF inline viewer), metadata, download, tags.
5. Upload form: file + title/description/department/course/category/tags → goes to `PENDING`.
6. Admin dashboard: queue of pending uploads, approve/reject with optional reason, manage taxonomy (departments/courses/categories/tags).
7. "My uploads" page for users to track their own submission status.
8. Basic download counter / most-viewed sorting (nice-to-have, cheap to add).

**Explicitly deferred (v2+):** full-text search inside PDFs, versioning/revisions, comments/ratings, citation export, plagiarism checks, email notifications — flag these so scope doesn't creep today.

---

## 6. Branding Plan — confirmed from real EAC assets

Extracted from the live EAC enrollment login page (`eacmnl`):

| Token | Value | Use |
|---|---|---|
| `--primary-red` | `#b31217` | Primary brand color, buttons, accents |
| `--primary-red-hover` | `#8b0e12` | Hover/active state |
| `--primary-navy` | `#1a1a2e` | Headings, dark backgrounds |
| `--secondary-navy` | `#16213e` | Secondary dark surfaces |
| `--light-bg` | `#f8f9fa` | Page background |
| `--text-dark` | `#1a1a1a` | Body text |
| `--text-light` | `#666666` | Secondary text |
| `--border-color` | `#e8e8e8` | Borders/dividers |

- Logo: shield emblem (`emblem.png`), school name "Emilio Aguinaldo College", tagline "Excellence in Education".
- Layout pattern: split-screen login (branding panel with emblem + photo on one side, form on the other) — we'll echo this for our own login page, swapping password fields for **"Sign in with Google"** restricted to `eac.edu.ph`.
- These become **Tailwind theme tokens** (`tailwind.config.js` `extend.colors`) so every component pulls from one source — no hardcoded hex scattered across templates.
- Font: page currently uses system/serif-ish bold headings via Font Awesome + custom CSS, no external webfont detected — we'll match weight/spacing rather than import a specific typeface unless you confirm one.

---

## 7. Open Items Before/During Build

- [ ] **Full school name + logo/colors** for confirming branding direction later.
- [ ] **List of Schools/Departments/Courses** from employer (to shape the `departments`/`courses` seed and category structure, e.g. SET).
- [ ] **Frontend approach**: server-rendered Thymeleaf (simplest, fastest to stand up on Tomcat, easiest to theme later) vs. separate SPA frontend calling a REST API (more flexible, more setup). → *Recommend Thymeleaf for speed today given the "build it today" goal; revisit if you want a richer interactive UI later.*
- [ ] **Google OAuth credentials**: need a Google Cloud project + OAuth Client ID/Secret configured for `eac.edu.ph` domain restriction. You'll need admin access to create this (or IT will need to).
- [ ] **Server specs/access** for the Tomcat box (Java version installed, disk space for uploads, whether we can deploy WARs directly).
- [ ] **Max file size / allowed file types** policy (e.g. cap at 100MB, whitelist pdf/docx/pptx/zip/code archives).
- [ ] Confirm registrar DB **connection details** (host/creds) for the one-time extraction script — or if you'll just hand me a CSV/SQL dump of departments & courses instead of connecting directly.

---

## 8. Build Order (today)

1. Scaffold Spring Boot project (Web, Security, Data JPA, Thymeleaf, MySQL driver, OAuth2 Client).
2. Set up `eac_directory` MySQL schema + JPA entities (§4).
3. Wire Google OAuth2 login restricted to `eac.edu.ph`, auto-provision `users`.
4. Seed departments/categories with placeholder data (real list swapped in once you send it).
5. Build upload flow (file → disk, metadata → DB, status = PENDING).
6. Build browse/search pages (department tree, category filter, tag/search bar).
7. Build admin approval queue.
8. Apply placeholder theme; leave clear hooks for real EAC branding swap-in.
9. Package as WAR, smoke-test locally, document deployment steps for the Tomcat server.

---

*Once you confirm the frontend approach (§7) and drop in the departments/courses list whenever it's ready, we start scaffolding.*
