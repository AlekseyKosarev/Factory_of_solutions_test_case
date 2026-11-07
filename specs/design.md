# KKD backend design

Status: implemented design, paired with `requirements.md`.
Updated 2026-11-07. Startup and automated Docker verification instructions are documented in README.

## 1. Stack review and decisions

| Component | Selected baseline | Rationale |
| --- | --- | --- |
| Java | Java 21, OpenJDK distribution; compile release 21 | LTS release within the assignment's Java 17+ range |
| Spring Boot | 3.5.16 | Current patch shown in the consulted 3.5 documentation; preserves the explicit Boot 3 requirement |
| Build | Maven 3.9.11 through committed Maven Wrapper | Meets Boot's Maven minimum; Wrapper version is 3.3.4 |
| Database | PostgreSQL 17.11 in Compose | Supported major above the required 15 minimum; container digest is pinned |
| Persistence | Spring JDBC (`NamedParameterJdbcTemplate`) | Explicit SQL for snapshots, recursive department queries and pagination |
| Migrations | Liquibase 4.31.1, Boot-managed | Explicit rollback blocks; use the same version for runtime and Maven migration plugin |
| Unit tests | JUnit Jupiter 5.12.2, Boot-managed | Meets the explicit JUnit 5 requirement |
| Integration tests | Boot-managed Testcontainers PostgreSQL | Verify PostgreSQL behavior without an H2 substitute |
| Runtime | Docker Compose v2, multi-stage Java build | One command starts the service and database |

The consulted Boot page identifies 4.1.2 as the latest stable version, but the assignment expressly requires Boot 3. Do not silently upgrade to Boot 4. Boot 3.5 documentation lists Java 17–25 compatibility and Maven 3.6.3+. Compatibility does not establish a maintenance/support entitlement. The 3.5.16 artifact was resolved successfully from Maven Central during implementation; lifecycle suitability for a production deployment remains a separate decision. Java 25 is also LTS; Java 21 is a deliberate conservative choice, not a claim that it is the newest Java release.

Use `spring-boot-starter-web`, `spring-boot-starter-validation`, `spring-boot-starter-jdbc`, `spring-boot-starter-actuator`, `liquibase-core`, PostgreSQL JDBC, and `spring-boot-starter-test`. Let the Boot BOM manage library versions. Use `jakarta.validation`, constructor injection and Java records for immutable domain/DTO values. Avoid unnecessary Lombok, MapStruct, JPA and separate frontend dependencies.

Official sources checked:

- [Boot 3.5 system requirements and current documented patch](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
- [Boot-managed dependency versions](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html), including Liquibase and JUnit versions above.
- [Java LTS roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html); vendor support/licensing terms do not automatically apply to all OpenJDK distributions.
- [PostgreSQL version policy](https://www.postgresql.org/support/versioning/), listing PostgreSQL 17 support through November 2029.
- [Liquibase explicit rollback definitions](https://support.liquibase.com/hc/en-us/articles/29383086010523-How-to-Define-Rollbacks).

Maven and container references are pinned in the delivered configuration. Registry manifest availability was checked; README documents the automated Docker workflow.

## 2. Components and flow

```text
api/             Controllers, query validation, exception mapping
application/     RunService, QueryService (summary/issues), Views DTOs, DemoInitializer
domain/          Immutable snapshots, Rule interface and four implementations,
                 RuleEngine, MetricCalculator, DeltaCalculator, hierarchy traversal
infrastructure/  JDBC repositories, transaction adapters, configuration
resources/db/    Liquibase changelog, schema, rule catalog, demo source fixtures
```

Domain code has no Spring, HTTP or JDBC dependencies. A rule receives an immutable snapshot and evaluation date and returns findings. The engine applies active-goal eligibility once, evaluates each rule, and rejects duplicate issue keys. Rule order must not affect results. The application layer converts findings to stored issue records.

Read endpoints query only completed immutable runs. A GET never reruns rules or consults mutable source rows. At this fixture size, loading the selected run's hierarchy and issues and calculating metrics in Java is acceptable. Issue pagination and filtering run in SQL, with matching count predicates. Avoid per-goal queries.

## 3. Database model

Use text business identifiers (1–64 characters, `[A-Za-z0-9_-]+`) for units, goals, KRs and runs. Issue IDs may be generated UUIDs. Dates use PostgreSQL `date`; operational timestamps use `timestamptz` in UTC. Numeric target/actual values use `numeric(19,4)` and weights `numeric(7,4)`.

| Table | Principal columns and constraints |
| --- | --- |
| `department` | `id` PK, `name` not null, `parent_id` nullable self FK |
| `goal` | `id` PK, `unit_id` FK, `title`, nullable `owner`, nullable `parent_goal_id` self FK, `status`, nullable `due_date` |
| `key_result` | `id` PK, `goal_id` FK, `title`, nullable `target_value`, nullable `actual_value`, non-null `weight`, nullable `fact_updated_on` |
| `quality_rule` | `code` PK, `title`, `severity`, `description`; exactly four enabled definitions |
| `quality_run` | `id` PK, `snapshot_date`, nullable `compared_to_run_id` self FK, `status`, `rule_set_version`, `created_at`, nullable `completed_at`, nullable `failure_code` |
| `run_department` | PK `(run_id, unit_id)`, copied `name`, `parent_unit_id`; composite parent FK within same run |
| `run_goal` | PK `(run_id, goal_id)`, copied goal fields; composite unit and parent-goal FKs within same run |
| `run_key_result` | PK `(run_id, kr_id)`, copied KR fields; composite goal FK within same run |
| `quality_issue` | `id` UUID PK, `run_id`, `rule_code` FK, `object_type`, `object_id`, `goal_id`, nullable `kr_id`, `severity`, `message`, `details` JSONB |

Snapshot IDs have no FK to mutable source rows: deleting or moving a live object must not break history. Snapshot tables reference their run. Composite issue FKs bind `goal_id` and optional `kr_id` to the same run; additionally bind `(run_id, kr_id, goal_id)` to a unique KR snapshot key so a KR cannot reference the wrong goal. A check constraint makes GOAL objects use `object_id = goal_id` with null `kr_id`, and KR objects use `object_id = kr_id` with non-null `kr_id`.

Unique issue key: `(run_id, rule_code, object_type, object_id)`. Copy severity into each issue so later catalog edits cannot alter history. `rule_set_version = 'v1'` is fixed for this delivery; comparison across different rule-set versions is rejected internally.

Use check constraints for goal statuses (`DRAFT`, `ACTIVE`, `COMPLETED`, `CANCELLED`), run statuses (`RUNNING`, `COMPLETED`, `FAILED`), severity and object type; weight range `[0,100]`; and self-parent/self-comparison rejection. Validate longer hierarchy and parent-goal cycles before a run. Do not constrain away the null/blank owners and invalid weight totals that rules must detect. A null deadline is allowed because no selected rule validates deadlines.

Indexes: source/snapshot parent department; source/snapshot goal unit and parent goal; KR goal; issue `(run_id, rule_code, severity)` and `(run_id, goal_id)`; run `(snapshot_date, id)`. Use explicit projection queries, parameter binding, and recursive CTEs or validated Java traversal for subtrees.

## 4. Run lifecycle and repeatable initialization

An internal `RunService.execute(runId, snapshotDate)` performs these steps:

1. Serialize run creation with a PostgreSQL advisory lock held on a dedicated connection for the operation. Reject duplicate run IDs and new runs whose date is not strictly later than the latest completed run; return an existing completed run only for idempotent demo initialization.
2. Commit a `RUNNING` row. Select the latest completed earlier run by `(snapshot_date, id)` as predecessor; demo dates are distinct. The first run has no predecessor.
3. In one repeatable-read transaction, load and validate source data, copy all departments/goals/KRs into snapshot tables, evaluate rules, persist issues, and set `COMPLETED`. The snapshot includes inactive goals for reproducible eligibility decisions.
4. On failure, roll back snapshot/results and mark the run `FAILED` in a separate transaction. On process restart, after obtaining the exclusive run lock, mark abandoned `RUNNING` runs failed. A failed run is never selected as predecessor.

No partially written results become visible. Snapshot dates are caller-supplied business dates; historical demo dates correspond to deliberate demo datasets, not a claim that arbitrary past live data can be reconstructed.

Liquibase creates schema/catalog and the first demo source state under a demo context. The application initializer executes RUN-001 through the real run service, applies the documented source changes transactionally, and executes RUN-002. Each transition checks committed state and is idempotent, including restart after the source patch but before RUN-002 completion. For a failed demo run, explicitly clean its empty failed metadata under the initialization lock before retrying; never rewrite completed results. Subsequent restarts preserve completed runs and do not apply the demo patch again.

Default Compose configuration enables the demo. An environment flag disables fixture changes and demo runs. General-purpose weekly execution remains a future caller of this same internal service.

## 5. REST contract

JSON uses snake_case; enums are lower case in API responses. Dates are ISO `YYYY-MM-DD`. Nullable fields are serialized explicitly. All three endpoints return 200 for successful reads.

### GET /api/runs

No required query parameters. Return all runs ordered by `snapshot_date DESC, id DESC`:

```json
{
  "items": [
    {
      "id": "RUN-002",
      "status": "completed",
      "snapshot_date": "2026-10-26",
      "compared_to": {"id": "RUN-001", "snapshot_date": "2026-10-19"},
      "issues_count": 7
    }
  ]
}
```

The example shows one item; the demo returns both runs. Noncompleted runs have `issues_count: null`; a completed run with no findings has zero. First-run `compared_to` is null.

### GET /api/kkd/summary?run_id=RUN-002&unit_id=HQ

Both parameters are required. The exact demo response is:

```json
{
  "run": {"id": "RUN-002", "snapshot_date": "2026-10-26"},
  "compared_to": {"id": "RUN-001", "snapshot_date": "2026-10-19"},
  "unit": {"id": "HQ", "name": "Headquarters"},
  "metrics": [
    {"key": "clean_goals_share", "title": "Доля корректных целей", "unit": "%", "value": 70.8, "previous": 75.0, "delta": -4.2, "trend": "worse"},
    {"key": "issues_per_100_goals", "title": "Проблем на 100 целей", "unit": "per_100_goals", "value": 29.2, "previous": 25.0, "delta": 4.2, "trend": "worse"},
    {"key": "critical_issues_count", "title": "Критические проблемы", "unit": "count", "value": 3, "previous": 3, "delta": 0, "trend": "unchanged"}
  ],
  "issues_by_severity": [
    {"code": "critical", "current": 3, "previous": 3, "delta": 0},
    {"code": "major", "current": 1, "previous": 2, "delta": -1},
    {"code": "minor", "current": 3, "previous": 1, "delta": 2}
  ],
  "delta": {"new": 3, "persisting": 4, "resolved": 2}
}
```

Metric ordering is fixed as shown; severity ordering is critical, major, minor. Formulas, empty-scope behavior and comparison semantics are defined in `requirements.md`.

### GET /api/kkd/issues

| Query | Required/default | Validation |
| --- | --- | --- |
| `run_id` | Required | Valid identifier; existing completed run |
| `unit_id` | Optional; all departments | Valid identifier; exists in selected run; includes descendants |
| `rule_code` | Optional; all rules | One of the four configured codes, case-sensitive |
| `severity` | Optional; all severities | `critical`, `major`, `minor` |
| `limit` | 20 | Integer, 1–100 |
| `offset` | 0 | Integer, 0–2147483647 |

Filters combine with AND. The endpoint returns current-run issues; resolved issues appear in summary counts, not as current issues. Sort by severity rank, rule code, object type and object ID, a deterministic unique order within a run.

```json
{
  "items": [
    {
      "id": "00000000-0000-0000-0000-000000000001",
      "rule_code": "GOAL_NO_OWNER",
      "severity": "critical",
      "object_type": "goal",
      "object_id": "G02",
      "goal_id": "G02",
      "unit_id": "HQ",
      "message": "Goal has no owner",
      "details": {"owner": null}
    }
  ],
  "limit": 1,
  "offset": 0,
  "total": 7
}
```

The issue UUID above is illustrative. `total` counts all matching rows before pagination, not page length. Offset past the last item returns an empty list and the unchanged total. Use a wide integer for total and avoid overflow in pagination arithmetic.

### Errors

```json
{"error": {"code": "RUN_NOT_FOUND", "message": "Run RUN-999 was not found"}}
```

Validate parameter syntax before resource lookup. Missing required, blank, repeated scalar, malformed, out-of-range, unknown rule/severity and unsupported query parameters return 422 `INVALID_PARAMETER`. A syntactically valid unknown run returns 404 `RUN_NOT_FOUND`; an unknown department in the selected snapshot returns 404 `UNIT_NOT_FOUND`. Existing RUNNING/FAILED runs return 422 `RUN_NOT_COMPLETED` for summary/issues. If both IDs are unknown, resolve run first. All application errors use the same envelope; unexpected failures return 500 `INTERNAL_ERROR` without stack traces or SQL. Configure controller advice so Spring's default missing-parameter/type-conversion responses do not leak a different format or return 400 for these query errors.

## 6. Migrations, configuration and execution

Use a root Liquibase XML changelog with an empty baseline tag followed by schema, rule catalog and demo-source changesets. Give every schema/data changeset an explicit rollback, including inserts and raw SQL. The baseline tag changeset uses Liquibase's tag behavior and has no data changes. The demo rollback removes runtime-generated demo issues/snapshots/runs in dependency order before deleting demo source rows. Schema rollback drops only application-owned tables in reverse dependency order; Liquibase retains its bookkeeping tables. Keep schema and catalog changes separate from the optional demo context.

The application performs Liquibase update before demo initialization; it is the only migration executor during normal startup. Configure a Compose PostgreSQL healthcheck and make the application wait for database health. Application readiness becomes healthy only after migrations and initialization complete. Expose a minimal Actuator health endpoint for this purpose.

Provide `scripts/check-migrations.sh` that creates an isolated disposable Compose project/database, applies migrations, starts initialization and checks the demo oracle, stops the application, rolls back to the baseline tag using the same changelog/context/version, asserts application tables are gone, reapplies, restarts initialization, and checks both demo runs and oracles again. It must fail on any unsuccessful step and clean up only its own disposable project. Merely deleting a volume and recreating it is not rollback verification. Exact commands are documented in README.

Commit `.env.example` for database name/user/password, application port, JDBC connection settings, demo flag and timezone. Provide local development defaults in Compose so bare `docker compose up` works. Bind application port 8080 by default; a host PostgreSQL port is unnecessary. Pin build/runtime images and Maven Wrapper, run the final Java container as a non-root user, and persist database data in a named volume. A second startup must leave run and issue counts unchanged.

## 7. Test organization and implementation sequence

1. Implement schema, rollback blocks, build/Compose configuration and pure domain records.
2. Implement four rules, metrics and set-based delta with focused unit tests.
3. Implement JDBC snapshots, run lifecycle, deterministic demo and PostgreSQL integration tests.
4. Implement read services/controllers, parameter/error mapping and MockMvc contract tests.
5. Execute unit/integration tests, clean startup, restart and rollback/reapply verification. Finish the reproducible Docker instructions in README.

Use Surefire for unit tests and Failsafe in an `integration` Maven profile for `*IT` classes. Integration fixtures must check actual SQL results against the independent numerical oracle in `requirements.md`, not against the same calculator used by production. Test a multi-issue goal, nested department membership, historical immutability after live edits, missing prior department, empty active-goal scope and first-run null fields. Tests must demonstrate that rule evaluation does not depend on wall-clock time.

Completion means R01–R18 have evidence and both specs agree with the delivered code. See README for the automated acceptance commands.

## 8. Implementation details resolved during delivery

- `MigrationConfiguration` selects the `demo` or `schema` Liquibase context from `DEMO_ENABLED`. The demo changeset requires explicit `@demo` context; disabling the flag on a fresh database prevents both fixture inserts and demo runs.
- Maven Wrapper 3.3.4 includes its bootstrap JAR; no global Maven installation is required.
- `scripts/check-migrations-local.sh` provides the same rollback acceptance cycle using an isolated local PostgreSQL cluster when Docker is unavailable.
- Without `TEST_JDBC_URL`, integration tests use Testcontainers PostgreSQL 17.11. The Docker verification workflow sets `TEST_JDBC_URL`, `TEST_DB_USER` and `TEST_DB_PASSWORD` to its isolated Compose database; Maven does not require Docker socket access inside the test container.
- API/application response records live in `application/Views`; the domain calculators remain free of Jackson and Spring.

- The root changelog has `logicalFilePath="db/changelog.xml"`, so application startup and Maven migration commands identify the same changesets despite different physical resource paths.

## 9. Automated Docker verification

`scripts/check-all.sh` invokes the migration acceptance script with `--tests`. It creates an isolated Compose project, starts PostgreSQL, runs all unit/API and integration tests in the `tests` build-stage container, then starts the application and performs the migration/restart cycle. The `oracle` Python container runs the independent HTTP assertions against `http://app:8080` before rollback, after reapply, and after restart. No host Python/JDK/PostgreSQL or published test ports are needed; Bash, Docker and Compose orchestrate the workflow. The existing `check-migrations.sh` entrypoint runs only the migration/restart acceptance cycle. Both paths remove their own temporary project on exit and fail on an unsuccessful check.
