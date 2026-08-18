import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Parses raw "INSERT INTO `table` (...) VALUES (...), (...), ...;" blocks
 * (as extracted from the eacdb registrar dump) into a list of row tuples,
 * respecting SQL single-quote strings and backslash escapes, then emits
 * clean seed SQL for our own eac_directory schema (departments/programs/courses
 * only — code/name/id columns, no registrar-specific fields).
 */
public class ExtractSeed {

    public static void main(String[] args) throws Exception {
        String dir = args[0]; // scratch dir containing *.raw.sql

        List<List<String>> departments = parseRows(Paths.get(dir, "departments.raw.sql"));
        List<List<String>> programs = parseRows(Paths.get(dir, "programs.raw.sql"));
        List<List<String>> courses = parseRows(Paths.get(dir, "courses.raw.sql"));

        System.out.println("departments: " + departments.size());
        System.out.println("programs: " + programs.size());
        System.out.println("courses: " + courses.size());

        // departments columns: department_id, department_code, department_name, faculty_id, building_location, created_at, updated_at
        StringBuilder dSql = new StringBuilder();
        dSql.append("-- Auto-generated from eacdb registrar dump. Do not hand-edit; re-run extraction to refresh.\n");
        dSql.append("INSERT INTO departments (source_department_id, code, name) VALUES\n");
        List<String> dRows = new ArrayList<>();
        for (List<String> r : departments) {
            String id = r.get(0);
            String code = sqlQuote(r.get(1));
            String name = sqlQuote(r.get(2));
            dRows.add("(" + id + ", " + code + ", " + name + ")");
        }
        dSql.append(String.join(",\n", dRows)).append(";\n");
        Files.writeString(Paths.get(dir, "01-departments.sql"), dSql.toString());

        // programs columns: program_id, program_code, program_name, department_id, school_name, active_status, level, duration_years
        StringBuilder pSql = new StringBuilder();
        pSql.append("-- Auto-generated from eacdb registrar dump. Do not hand-edit; re-run extraction to refresh.\n");
        pSql.append("INSERT INTO programs (source_program_id, code, name, department_id, level, duration_years) VALUES\n");
        List<String> pRows = new ArrayList<>();
        for (List<String> r : programs) {
            String id = r.get(0);
            String code = sqlQuote(r.get(1));
            String name = sqlQuote(r.get(2));
            String deptSourceId = r.get(3);
            String schoolName = r.get(4);
            String level = sqlQuote(r.get(6));
            String duration = r.get(7);
            // department_id resolves via subselect against our departments.source_department_id;
            // a handful of source rows have a NULL department_id but a populated school_name
            // (e.g. program 17 'SAIC' -> 'School of Arts and Sciences') - fall back to matching
            // that against departments.name so the program isn't dropped/orphaned.
            String departmentSubselect = deptSourceId.equalsIgnoreCase("NULL")
                    ? "(SELECT id FROM departments WHERE name = " + sqlQuote(schoolName) + ")"
                    : "(SELECT id FROM departments WHERE source_department_id = " + deptSourceId + ")";
            pRows.add("(" + id + ", " + code + ", " + name + ", " + departmentSubselect + ", " + level + ", " + duration + ")");
        }
        pSql.append(String.join(",\n", pRows)).append(";\n");
        Files.writeString(Paths.get(dir, "02-programs.sql"), pSql.toString());

        // courses columns: course_id, course_code, course_title, department_id, description, ...
        StringBuilder cSql = new StringBuilder();
        cSql.append("-- Auto-generated from eacdb registrar dump. Do not hand-edit; re-run extraction to refresh.\n");
        cSql.append("INSERT INTO courses (source_course_id, code, title, department_id, description) VALUES\n");
        List<String> cRows = new ArrayList<>();
        for (List<String> r : courses) {
            String id = r.get(0);
            String code = sqlQuote(r.get(1));
            String title = sqlQuote(r.get(2));
            String deptSourceId = r.get(3);
            String desc = r.get(4).equalsIgnoreCase("NULL") ? "NULL" : sqlQuote(r.get(4));
            cRows.add("(" + id + ", " + code + ", " + title + ", (SELECT id FROM departments WHERE source_department_id = " + deptSourceId + "), " + desc + ")");
        }
        cSql.append(String.join(",\n", cRows)).append(";\n");
        Files.writeString(Paths.get(dir, "03-courses.sql"), cSql.toString());

        System.out.println("Wrote 01-departments.sql, 02-programs.sql, 03-courses.sql to " + dir);
    }

    /** Re-quote a raw MySQL-escaped string literal (without surrounding quotes) as clean SQL. */
    static String sqlQuote(String rawUnescaped) {
        // rawUnescaped already has MySQL backslash-escapes resolved to literal chars by the tokenizer.
        String escaped = rawUnescaped.replace("'", "''");
        return "'" + escaped + "'";
    }

    /** Reads a file containing one or more "INSERT INTO `t` (...) VALUES (...), (...);" statements
     *  and returns all row tuples found, as lists of column strings (NULL kept as literal "NULL"). */
    static List<List<String>> parseRows(Path file) throws IOException {
        String content = Files.readString(file);
        List<List<String>> allRows = new ArrayList<>();

        // Strip down to just the VALUES(...) portion(s).
        Matcher stmt = Pattern.compile("VALUES\\s*\\n(.*?);\\s*$", Pattern.DOTALL | Pattern.MULTILINE).matcher(content);
        while (stmt.find()) {
            String valuesBlock = stmt.group(1);
            allRows.addAll(splitRows(valuesBlock));
        }
        return allRows;
    }

    static List<List<String>> splitRows(String valuesBlock) {
        List<List<String>> rows = new ArrayList<>();
        int i = 0, n = valuesBlock.length();
        while (i < n) {
            // skip whitespace/commas/newlines between tuples
            while (i < n && (Character.isWhitespace(valuesBlock.charAt(i)) || valuesBlock.charAt(i) == ',')) i++;
            if (i >= n || valuesBlock.charAt(i) != '(') break;
            i++; // consume '('
            List<String> cols = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inString = false;
            while (i < n) {
                char c = valuesBlock.charAt(i);
                if (inString) {
                    if (c == '\\' && i + 1 < n) {
                        char next = valuesBlock.charAt(i + 1);
                        // resolve common MySQL escapes to literal characters
                        switch (next) {
                            case '\'' -> cur.append('\'');
                            case '\\' -> cur.append('\\');
                            case 'n' -> cur.append('\n');
                            case 'r' -> cur.append('\r');
                            case 't' -> cur.append('\t');
                            case '"' -> cur.append('"');
                            default -> cur.append(next);
                        }
                        i += 2;
                        continue;
                    } else if (c == '\'') {
                        inString = false;
                        i++;
                        continue;
                    } else {
                        cur.append(c);
                        i++;
                        continue;
                    }
                } else {
                    if (c == '\'') {
                        inString = true;
                        i++;
                        continue;
                    } else if (c == ',') {
                        cols.add(cur.toString().trim());
                        cur.setLength(0);
                        i++;
                        continue;
                    } else if (c == ')') {
                        cols.add(cur.toString().trim());
                        cur.setLength(0);
                        i++;
                        break;
                    } else {
                        cur.append(c);
                        i++;
                        continue;
                    }
                }
            }
            rows.add(cols);
        }
        return rows;
    }
}
