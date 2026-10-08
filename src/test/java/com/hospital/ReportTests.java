package com.hospital;

import com.hospital.model.Patient;
import com.hospital.model.PatientIntakeForm;
import com.hospital.persistence.dao.AdmissionLogDAO.LogEntry;
import com.hospital.service.HospitalManager;
import com.hospital.service.OperationResult;
import com.hospital.service.ReportService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hospital.SelfTestRunner.check;
import static com.hospital.SelfTestRunner.deleteQuietly;
import static com.hospital.SelfTestRunner.freshManager;
import static com.hospital.SelfTestRunner.run;
import static com.hospital.SelfTestRunner.tempDbFile;
import static com.hospital.service.ReportService.csvEscape;

/** Tests for {@link ReportService}: CSV escaping, CSV exports, the handover report and permission checks. */
final class ReportTests {

    private static final String TRICKY_COMPLAINT = "Fell off ladder, \"twisted\" ankle";
    private static final String FORMULA_COMPLAINT = "=2+3 typed by mistake - back pain";

    private ReportTests() {}

    static void runAll() {
        run("Reports: csvEscape quotes per RFC 4180 and guards formula prefixes", ReportTests::escaping);
        run("Reports: patients CSV has BOM, header, 3 rows and round-trips quoted fields", ReportTests::patientsCsv);
        run("Reports: activity CSV lists REGISTERED/ADMITTED/DISCHARGED performed by admin", ReportTests::activityCsv);
        run("Reports: shift handover report has every section, the waiting patient and fits 100 columns", ReportTests::handover);
        run("Reports: exports without login are refused and write no file", ReportTests::refusedWithoutLogin);
        run("Reports: unwritable path becomes a fail result; pagination keeps headings with their table",
                ReportTests::failuresAndPaging);
    }

    // ------------------------------------------------------------------ tests

    private static void escaping() {
        check(csvEscape("plain text").equals("plain text"), "plain value changed");
        check(csvEscape("a,b").equals("\"a,b\""), "comma not quoted: " + csvEscape("a,b"));
        check(csvEscape("say \"hi\"").equals("\"say \"\"hi\"\"\""), "quotes not doubled: " + csvEscape("say \"hi\""));
        check(csvEscape("line1\nline2").equals("\"line1\nline2\""), "LF not quoted");
        check(csvEscape("line1\r\nline2").equals("\"line1\r\nline2\""), "CRLF not quoted");
        check(csvEscape(null).isEmpty(), "null should become an empty cell");
        check(csvEscape("").isEmpty(), "empty should stay empty");
        check(csvEscape("O'Brien").equals("O'Brien"), "apostrophe inside a name must not be touched");
        check(csvEscape("a-b").equals("a-b"), "dash that is not the first character must not be guarded");

        check(csvEscape("=SUM(A1:A9)").equals("'=SUM(A1:A9)"), "= not guarded");
        check(csvEscape("+1 555").equals("'+1 555"), "+ not guarded");
        check(csvEscape("-2").equals("'-2"), "- not guarded in free text");
        check(csvEscape("@cmd").equals("'@cmd"), "@ not guarded");
        check(csvEscape("\tx").equals("'\tx"), "TAB not guarded");
        check(csvEscape("\rx").equals("\"'\rx\""), "CR must be guarded and quoted: " + csvEscape("\rx"));
        check(csvEscape("=HYPERLINK(\"http://x\",\"y\")").equals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\""),
                "guard + quoting combined wrong: " + csvEscape("=HYPERLINK(\"http://x\",\"y\")"));

        check(csvEscape("-12.5", false).equals("-12.5"), "numeric column must not be guarded");
        check(csvEscape("=x", false).equals("=x"), "guard must be off for non-free-text columns");
        check(csvEscape("1,5", false).equals("\"1,5\""), "RFC quoting still applies to numeric columns");
    }

    private static void patientsCsv() throws Exception {
        File db = tempDbFile("report-patients");
        Path csvFile = Files.createTempFile("report-patients", ".csv");
        try (HospitalManager m = freshManager(db, "admin", "admin123")) {
            Scenario s = scenario(m);
            OperationResult r = new ReportService(m).exportPatientsCsv(csvFile);
            check(r.success(), "export failed: " + r.message());
            check(r.message().startsWith("Exported 3 rows to ") && r.message().endsWith(csvFile.getFileName().toString()),
                    "unexpected message: " + r.message());

            byte[] bytes = Files.readAllBytes(csvFile);
            check(bytes.length > 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF,
                    "UTF-8 BOM missing");
            String content = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            check(content.contains("\r\n"), "rows must end with CRLF");
            List<List<String>> rows = parseCsv(content);
            check(rows.get(0).equals(ReportService.PATIENT_COLUMNS), "header mismatch: " + rows.get(0));
            check(rows.size() == 4, "expected 3 data rows, got " + (rows.size() - 1));

            Map<String, Map<String, String>> byName = new HashMap<>();
            for (List<String> row : rows.subList(1, rows.size())) {
                check(row.size() == ReportService.PATIENT_COLUMNS.size(), "row has " + row.size() + " cells: " + row);
                Map<String, String> cells = new HashMap<>();
                for (int i = 0; i < row.size(); i++) cells.put(ReportService.PATIENT_COLUMNS.get(i), row.get(i));
                byName.put(cells.get("full_name"), cells);
                check(Long.parseLong(cells.get("wait_minutes")) >= 0, "wait_minutes not a number: " + cells.get("wait_minutes"));
                int esi = Integer.parseInt(cells.get("esi_level"));
                check(esi >= 1 && esi <= 5, "bad ESI " + esi);
                Double.parseDouble(cells.get("priority_score")); // numeric columns are never apostrophe-guarded
                Double.parseDouble(cells.get("severity_score"));
                check(cells.get("intake_time").matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                        "intake_time format: " + cells.get("intake_time"));
            }
            Map<String, String> waiting = byName.get(s.waiting().getFullName());
            check(waiting != null, "waiting patient missing");
            check(waiting.get("chief_complaint").equals(TRICKY_COMPLAINT), "complaint did not round-trip: " + waiting.get("chief_complaint"));
            check(waiting.get("status").equals("WAITING") && waiting.get("bed_id").isEmpty() && waiting.get("admitted_time").isEmpty(),
                    "waiting row wrong: " + waiting);

            Map<String, String> admitted = byName.get(s.admitted().getFullName());
            check(admitted.get("status").equals("ADMITTED"), "admitted status: " + admitted.get("status"));
            check(admitted.get("bed_id").equals(s.admitted().getAssignedBedId()), "bed id: " + admitted.get("bed_id"));
            check(!admitted.get("doctor").isEmpty(), "admitted patient should have a doctor name");
            check(admitted.get("esi_level").equals("1"), "critical patient should be ESI 1");

            Map<String, String> discharged = byName.get(s.discharged().getFullName());
            check(discharged.get("status").equals("DISCHARGED") && !discharged.get("discharged_time").isEmpty(),
                    "discharged row wrong: " + discharged);
            check(discharged.get("chief_complaint").equals("'" + FORMULA_COMPLAINT),
                    "formula complaint must be apostrophe-guarded: " + discharged.get("chief_complaint"));
        } finally {
            deleteQuietly(db);
            Files.deleteIfExists(csvFile);
        }
    }

    private static void activityCsv() throws Exception {
        File db = tempDbFile("report-activity");
        Path csvFile = Files.createTempFile("report-activity", ".csv");
        try (HospitalManager m = freshManager(db, "admin", "admin123")) {
            Scenario s = scenario(m);
            OperationResult r = new ReportService(m).exportActivityCsv(csvFile, 100);
            check(r.success(), "export failed: " + r.message());

            byte[] bytes = Files.readAllBytes(csvFile);
            check((bytes[0] & 0xFF) == 0xEF, "UTF-8 BOM missing");
            List<List<String>> rows = parseCsv(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
            check(rows.get(0).equals(ReportService.ACTIVITY_COLUMNS), "header mismatch: " + rows.get(0));
            List<LogEntry> log = m.recentActivity(100);
            check(rows.size() - 1 == log.size(), "expected " + log.size() + " rows, got " + (rows.size() - 1));
            check(r.message().startsWith("Exported " + log.size() + " rows"), "unexpected message: " + r.message());

            int col = ReportService.ACTIVITY_COLUMNS.indexOf("action");
            int by = ReportService.ACTIVITY_COLUMNS.indexOf("performed_by");
            int name = ReportService.ACTIVITY_COLUMNS.indexOf("patient_name");
            int id = ReportService.ACTIVITY_COLUMNS.indexOf("id");
            List<List<String>> data = rows.subList(1, rows.size());
            Set<String> actions = data.stream().map(row -> row.get(col)).collect(Collectors.toSet());
            check(actions.containsAll(Set.of("REGISTERED", "ADMITTED", "DISCHARGED")), "actions missing: " + actions);
            check(data.stream().filter(row -> row.get(col).equals("REGISTERED")).count() == 3, "expected 3 REGISTERED rows");
            check(data.stream().filter(row -> row.get(col).equals("ADMITTED")).count() == 2, "expected 2 ADMITTED rows");
            check(data.stream().allMatch(row -> row.get(by).equals("admin")), "every action was performed by admin");
            check(data.stream().anyMatch(row -> row.get(col).equals("DISCHARGED") && row.get(name).equals(s.discharged().getFullName())),
                    "DISCHARGED row should name the discharged patient");
            for (int i = 1; i < data.size(); i++) {
                check(Integer.parseInt(data.get(i).get(id)) > Integer.parseInt(data.get(i - 1).get(id)),
                        "activity CSV should be oldest first");
            }
        } finally {
            deleteQuietly(db);
            Files.deleteIfExists(csvFile);
        }
    }

    private static void handover() throws Exception {
        File db = tempDbFile("report-handover");
        Path txt = Files.createTempFile("report-handover", ".txt");
        try (HospitalManager m = freshManager(db, "admin", "admin123")) {
            ReportService reports = new ReportService(m);
            String empty = reports.shiftHandoverReport();
            check(empty.contains("WAITING QUEUE (0 patients") && empty.contains("(none)"), "empty sections should print (none)");

            Scenario s = scenario(m);
            String report = reports.shiftHandoverReport();
            for (String heading : List.of("SHIFT HANDOVER REPORT", "SUMMARY", "WAITING QUEUE", "ADMITTED PATIENTS",
                    "BEDS NEEDING ATTENTION", "DOCTOR WORKLOAD", "UNACKNOWLEDGED CRITICAL ALERTS", "RECENT ACTIVITY")) {
                check(report.contains(heading), "missing section " + heading);
            }
            check(report.contains(s.waiting().getFullName()), "waiting patient's name missing");
            check(report.contains(s.admitted().getAssignedBedId()), "admitted patient's bed missing");
            check(report.contains(m.currentUser().getFullName()), "generated-by missing");
            check(report.contains("ESI-1 RESUSCITATION"), "unacknowledged critical arrival alert missing");
            check(report.contains("CLEANING"), "discharged patient's bed should need cleaning");
            for (String line : report.split("\n")) {
                check(line.length() <= ReportService.REPORT_WIDTH, "line longer than " + ReportService.REPORT_WIDTH + ": " + line);
            }

            OperationResult saved = reports.saveShiftReport(txt);
            check(saved.success(), "save failed: " + saved.message());
            String written = Files.readString(txt, StandardCharsets.UTF_8);
            check(written.contains("DOCTOR WORKLOAD") && written.contains(s.waiting().getFullName()), "saved report incomplete");
        } finally {
            deleteQuietly(db);
            Files.deleteIfExists(txt);
        }
    }

    private static void refusedWithoutLogin() throws Exception {
        File db = tempDbFile("report-anon");
        Path dir = Files.createTempDirectory("report-anon");
        try (HospitalManager m = freshManager(db, null, null)) {
            ReportService reports = new ReportService(m);
            Path patients = dir.resolve("patients.csv");
            Path activity = dir.resolve("activity.csv");
            Path report = dir.resolve("report.txt");
            for (OperationResult r : List.of(reports.exportPatientsCsv(patients), reports.exportActivityCsv(activity, 50),
                    reports.saveShiftReport(report))) {
                check(!r.success(), "anonymous export allowed");
                check(r.message().contains("Permission denied") || r.message().toLowerCase().contains("log in"),
                        "unexpected message: " + r.message());
            }
            check(!Files.exists(patients) && !Files.exists(activity) && !Files.exists(report), "a refused export wrote a file");
        } finally {
            deleteQuietly(db);
            File[] leftovers = dir.toFile().listFiles();
            if (leftovers != null) for (File f : leftovers) deleteQuietly(f);
            deleteQuietly(dir.toFile());
        }
    }

    private static void failuresAndPaging() {
        File db = tempDbFile("report-fail");
        try (HospitalManager m = freshManager(db, "nurse", "nurse123")) {
            Path missing = Path.of(System.getProperty("java.io.tmpdir"), "no-such-dir-" + System.nanoTime(), "x.csv");
            OperationResult r = new ReportService(m).exportPatientsCsv(missing);
            check(!r.success() && r.message().startsWith("Could not write x.csv"), "unexpected result: " + r.message());
        } finally {
            deleteQuietly(db);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 25; i++) text.append("line ").append(i).append('\n');
        List<List<String>> pages = ReportService.paginate(text.toString(), 10);
        check(pages.size() == 3 && pages.get(0).size() == 10 && pages.get(2).size() == 5, "pages: " + pages);
        check(pages.get(1).get(0).equals("line 11"), "page 2 should start with line 11");
        check(ReportService.paginate("", 10).size() == 1, "empty text should still give one page");
        check(ReportService.paginate("a\nb", 0).size() == 2, "lines per page below 1 is treated as 1");

        StringBuilder sections = new StringBuilder();
        for (int i = 1; i <= 8; i++) sections.append("filler ").append(i).append('\n');
        sections.append("RECENT ACTIVITY\n").append("-".repeat(40)).append("\n  Time  Action\n  row 1\n");
        List<List<String>> kept = ReportService.paginate(sections.toString(), 10);
        check(kept.size() == 2 && kept.get(0).size() == 8, "heading should move to the next page: " + kept);
        check(kept.get(1).get(0).equals("RECENT ACTIVITY") && kept.get(1).size() == 4, "page 2 should start with the heading: " + kept);
    }

    // ------------------------------------------------------------------ helpers

    /** Patients created by {@link #scenario}: one still waiting, one admitted, one admitted then discharged. */
    private record Scenario(Patient waiting, Patient admitted, Patient discharged) {}

    private static Scenario scenario(HospitalManager m) {
        OperationResult waiting = m.registerPatient(new PatientIntakeForm("Alice Comma", 34, "Female", TRICKY_COMPLAINT,
                true, 88, 128, 82, 18, 98, 37.1, 6, 15, 2));
        OperationResult critical = m.registerPatient(new PatientIntakeForm("Bob Critical", 60, "Male",
                "Unresponsive after collapse", false, 35, 70, 40, 6, 80, 35.5, 0, 6, 5));
        OperationResult leaving = m.registerPatient(new PatientIntakeForm("Cara Discharge", 45, "Other", FORMULA_COMPLAINT,
                false, 80, 120, 80, 16, 98, 37.0, 3, 15, 1));
        check(waiting.success() && critical.success() && leaving.success(),
                "registration failed: " + waiting.message() + " / " + critical.message() + " / " + leaving.message());
        check(m.admitPatient(critical.patient().getId()).success(), "admit critical failed");
        check(m.admitPatient(leaving.patient().getId()).success(), "admit second patient failed");
        check(m.dischargePatient(leaving.patient().getId()).success(), "discharge failed");
        return new Scenario(waiting.patient(), critical.patient(), leaving.patient());
    }

    /** Minimal RFC 4180 reader: quoted fields may contain commas, doubled quotes and line breaks. */
    private static List<List<String>> parseCsv(String s) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c != '"') cell.append(c);
                else if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = false;
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
