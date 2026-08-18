# eacdb taxonomy extraction

One-time (repeatable) tool that pulls `departments` / `programs` / `courses`
out of a raw `eacdb` registrar dump and produces clean seed SQL for this
app's own `eac_directory` schema — see PLAN.md §4a. Safe to re-run whenever
the school hands over a refreshed dump; it only touches `id`/`code`/`name`
columns, nothing registrar-specific (fees, schedules, prerequisites, etc.)
crosses over.

The `eacdb (15).sql` dump itself is **not** committed to this repo (see root
`.gitignore` — only `EAC-FILE-DIRECTORY/` is pushed). Keep it wherever you
received it and point the commands below at it.

## Steps

1. Extract the raw `INSERT INTO` blocks for the three tables (adjust the dump
   path as needed; run from a bash shell — Git Bash on Windows works):

   ```bash
   DUMP="../eacdb (15).sql"      # path to the registrar dump
   OUT="tools/eacdb-extract/out" # scratch output dir
   mkdir -p "$OUT"

   for t in departments programs courses; do
     awk -v t="$t" '
       $0 ~ "^INSERT INTO `"t"`" {flag=1}
       flag {print}
       flag && /;$/ {flag=0}
     ' "$DUMP" > "$OUT/$t.raw.sql"
   done
   ```

2. Run the parser (Java 21+ single-file execution, no build needed) to turn
   those raw blocks into clean, re-quoted seed SQL for our schema:

   ```bash
   java tools/eacdb-extract/ExtractSeed.java "tools/eacdb-extract/out"
   ```

   This writes `01-departments.sql`, `02-programs.sql`, `03-courses.sql` into
   that same output dir. It reports row counts parsed per table — cross-check
   those against the dump (as of the 2026-08-17 dump: 18 departments,
   34 programs, 1053 courses) before trusting the output.

3. Copy the three generated files into `src/main/resources/seed/`, replacing
   the existing ones:

   ```bash
   cp tools/eacdb-extract/out/0*.sql src/main/resources/seed/
   ```

4. Restart the app. `DataSeeder` (in `ph.edu.eac.filedirectory.seed`) loads
   those files at startup **only if the `departments` table is empty** — it
   won't clobber data you've since edited in the admin UI. To force a
   re-seed from a refreshed dump, truncate `departments`, `programs`, and
   `courses` in `eac_directory` first (cascades to `courses`/`programs` via
   FK, and to any `files` rows referencing them — check for uploads against
   those departments before doing this on a live system).

## Why a hand-rolled parser instead of just piping through `mysql`?

No local MySQL client or Python/Node runtime was available in the dev
environment this was built in. The dump's `course_title` values contain
literal commas and escaped apostrophes (e.g. `Rizal\'s Life and Works`,
`Life, Works & Writing of Rizal`), so a naive comma-split would corrupt rows.
`ExtractSeed.java` is a small quote/escape-aware tokenizer instead of a regex
guess. If you have a real MySQL client available, loading the dump into a
scratch database and exporting clean CSVs would work just as well.
