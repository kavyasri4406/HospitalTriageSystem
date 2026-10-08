# Hospital Patient Triage & Bed Allocator

A desktop emergency-department system written **entirely in Java**:
JavaFX frontend → core Java services → custom data structures & algorithms → JDBC/DAO → MySQL or SQLite.

## Run it

Requires a JDK 21+ (`java` and `javac` on the PATH). All libraries are bundled in `lib/`.

| Script      | What it does                                                        |
|-------------|---------------------------------------------------------------------|
| `run.bat`   | Compiles to `out/classes` and launches the JavaFX app               |
| `build.bat` | Compiles only                                                       |
| `test.bat`  | Compiles and runs the self-test suite (`SelfTestRunner`, 44 checks) |
| `package.bat` | Builds `dist\ERTriage\ERTriage.exe`, a self-contained app with its own Java runtime |

Log in with a demo account: `admin` / `admin123`, `doctor` / `doctor123`, `nurse` / `nurse123`
(change these on the **Staff & Access** screen before real use).

## Deploying

1. Run `package.bat` (needs a full JDK 21+; it finds `jpackage` via `JAVA_HOME` or the `java` on PATH).
2. Copy the whole `dist\ERTriage` folder to the target Windows PC (USB, network share, zip).
3. Run `ERTriage.exe`. No Java installation is needed on that PC.

Data is stored in `ERTriage\data\hospital_triage.db` (SQLite). For several PCs sharing one database,
point every install at MySQL by setting environment variables before launch:
`HOSPITAL_DB_MODE=mysql`, `HOSPITAL_DB_URL`, `HOSPITAL_DB_USER`, `HOSPITAL_DB_PASSWORD`.

## Web version (Render)

The same system also runs as a website, still written only in Java: `com.hospital.web.WebServer` uses the
JDK's built-in `com.sun.net.httpserver` and renders every page from Java code (no HTML/JS files, no frameworks).
It reuses the whole backend: triage, priority queue, bed matching, roles, JDBC.

- Run locally: `java -cp "out\classes;lib\*" com.hospital.web.WebServer` then open http://localhost:8080
- Deploy: on render.com choose **New > Blueprint**, pick this repository (it reads `render.yaml` and `Dockerfile`).
- Security: PBKDF2 passwords, HttpOnly session cookies, CSRF tokens on every form, HTML escaping, strict CSP.
- On Render's free plan the SQLite file resets when the service restarts. For permanent data set
  `HOSPITAL_DB_MODE=mysql` plus `HOSPITAL_DB_URL`, `HOSPITAL_DB_USER`, `HOSPITAL_DB_PASSWORD` in Render.

## Features

- ESI triage with live preview, custom max-heap priority queue with wait-time aging
- Bed matching with fallbacks, doctor dispatch, discharge and bed cleaning workflow
- Staff login with roles (Nurse / Doctor / Administrator), PBKDF2-hashed passwords, lockout, audit trail
- Re-assessment of vitals with deterioration alerts and vitals history; bed transfers (backend)
- Predicted time-to-bed for every waiting patient (discrete-event simulation)
- Patient Records search with full timeline; Reports: shift handover, CSV export, printing
- Settings (auto-admit, occupancy alert threshold, refresh interval), keyboard shortcuts

From an IDE: run `com.hospital.MainApp` with VM options
`--module-path lib/javafx --add-modules javafx.controls`, or use `pom.xml` (`mvn javafx:run`).

### Database

By default the app tries **MySQL** at `localhost:3306` (user `root`, empty password, database
`hospital_triage_db`, created automatically). If MySQL is not reachable it falls back to an embedded
**SQLite** file at `data/hospital_triage.db`. Tables and seed data (23 beds, 8 doctors) are created on
first start — no SQL scripts to run.

| Setting  | JVM property      | Environment variable   | Default             |
|----------|-------------------|------------------------|---------------------|
| Mode     | `db.mode`         | `HOSPITAL_DB_MODE`     | `auto` (`mysql`, `sqlite`) |
| MySQL URL| `db.mysql.url`    | `HOSPITAL_DB_URL`      | localhost:3306/hospital_triage_db |
| User     | `db.user`         | `HOSPITAL_DB_USER`     | `root`              |
| Password | `db.password`     | `HOSPITAL_DB_PASSWORD` | *(empty)*           |
| SQLite   | `db.sqlite.file`  | `HOSPITAL_DB_FILE`     | `data/hospital_triage.db` |

## Architecture → code

```
JavaFX FRONTEND (com.hospital.ui)            CORE JAVA BACKEND (com.hospital.service)
  Dashboard ........ DashboardView             HospitalManager ........ facade used by the UI
  Patient Form ..... PatientFormView           TriageService .......... ESI scoring + priority queue
  Queue Board ...... QueueBoardView            BedAllocationService ... bed inventory + matching
  Bed Grid ......... BedGridView               DoctorDispatchService .. doctor assignment/workload
  Doctors .......... DoctorsView               PatientManagementService records, MRN, audit log
  Analytics ........ AnalyticsView             ValidationService ...... intake validation
  Alerts ........... AlertsView + toasts       AlertService, AnalyticsService

DATA STRUCTURES (com.hospital.datastructures) ALGORITHMS (com.hospital.algorithms)
  TriagePriorityQueue  indexed binary max-heap   EsiScoringAlgorithm ....... ESI v4 decision tree + severity
  PatientRegistry      HashMap by id / MRN       WaitTimeAgingAlgorithm .... anti-starvation aging
  BedRegistry          ArrayList+HashMap+EnumMap BedMatchingAlgorithm ...... greedy match with fallbacks
  DoctorRoster         ArrayList+HashMap         DoctorAllocationAlgorithm . cost-based, java.util.PriorityQueue

JDBC / DAO (com.hospital.persistence)          STORAGE
  DatabaseManager (connection + transactions)    MySQL  (primary)
  SchemaInitializer (DDL + seed)                 SQLite (automatic fallback)
  PatientDAO, BedDAO, DoctorDAO, AlertDAO, AdmissionLogDAO
```

Domain model (`com.hospital.model`): abstract `Patient` with `AdultPatient`, `PediatricPatient` and
`GeriatricPatient` (age-specific danger-zone vitals, risk modifier and ward), created by `PatientFactory`.

## Key algorithms

**ESI triage** (`EsiScoringAlgorithm`)
1. Needs immediate life-saving intervention (GCS ≤ 8, SpO2 < 85, SBP < 80, HR < 40 or > 150, RR < 8 or > 35) → **ESI 1**
2. High-risk complaint, GCS < 14, pain ≥ 8, unstable trauma → **ESI 2**
3. Expected resources: 0 → **ESI 5**, 1 → **ESI 4**, 2+ → step 4
4. Age-adjusted danger-zone vitals → **ESI 2**, otherwise **ESI 3**

Severity score = (level base + vitals deviation points) × age risk modifier, capped at 100.

**Wait-time aging** — `priority = severity + min(25, minutes × rate[level]) + 5 if over target`.
Lower-acuity patients gain priority while waiting so they cannot starve, but an ESI 5 can never
outrank a fresh ESI 2.

**Priority queue** — `TriagePriorityQueue` is a binary max-heap plus a `HashMap` of key → heap index:
`offer`/`poll`/`remove(key)`/`update(key)` in O(log n), `peek`/`contains` in O(1), and `rebuild()` in O(n)
after the periodic aging pass. Ties break on ESI level, then arrival time.

**Bed matching** — ideal ward from ESI level, trauma flag and age group (ICU, Trauma, Monitored,
Pediatric, General), then a fallback chain. ICU/Trauma beds are never given to ESI 4–5 patients.

**Doctor dispatch** — `cost = specialtyPenalty × 10 + loadRatio × 5`; candidates go into a min-heap and the
cheapest on-duty doctor with spare capacity is chosen.

Admission (bed + doctor + patient + audit log) runs in **one JDBC transaction**; on failure it rolls back
and in-memory state is reloaded from the database.

## Demo tips

- **Simulate 5 Arrivals** on the dashboard generates realistic patients (some already waiting).
- **+15 min** (top bar) fast-forwards the simulation clock to show aging and overdue alerts.
- **Fill Sample Patient** on the intake form shows the live ESI preview.
- **Reset Demo Data** clears patients/alerts and frees all beds.

The previous version of the project (including its HTML/JS web dashboard) is kept in `_backup_old_version/`.
