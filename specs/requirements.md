# KKD backend requirements

Status: implemented; see README for startup and automated Docker verification instructions.
Baseline: `../Test_Case_Backend_Java.pdf`. Reviewed 2026-11-06.
This document and `design.md` are the AI-assistance artifact required by the assignment. Update both when implementation decisions change.

## 1. Objective and scope

Build a Java service that evaluates data quality of employee goals and key results (KRs), persists evaluation runs in PostgreSQL, and exposes results for a frontend. A department's results include all its descendant departments.

The deliverable is a repository runnable with `docker compose up`, with migrations, deterministic sample data, tests, and documentation. The assignment allows three days. Submission requires a repository link in the résumé's “About me” section.

The business narrative mentions a page and weekly checks; the concrete backend assignment requires three read endpoints. Frontend implementation, authentication, source-data CRUD, a public run-trigger endpoint, and weekly scheduling are outside this delivery. An internal run service must actually evaluate data; seeded issue rows alone are insufficient.

## 2. Mandatory requirements and acceptance

| ID | Requirement | Acceptance evidence |
| --- | --- | --- |
| R01 | Java 17+, Spring Boot 3, Maven, PostgreSQL 15+, Liquibase or Flyway | Build configuration and running Compose services |
| R02 | Model hierarchical departments; goals with owner, parent goal, status and deadline; KRs with target, actual, weight and fact-update date | Versioned database schema and sample rows |
| R03 | Persist rule definitions, runs and issues | Database records produced by evaluation |
| R04 | Implement four allowed rules, independently of HTTP and persistence | Pure Java rule components and unit tests |
| R05 | Apply rules only to active goals and their KRs | Inactive-goal tests |
| R06 | Seed 20–30 goals in 2–3 departments and two comparable runs, with every selected violation | Deterministic fixture and expected results below |
| R07 | One-command startup includes database, migrations and sample data | `docker compose up` on a clean checkout; no mandatory manual `.env` creation |
| R08 | Migrations support apply → rollback → reapply | Script verifies the cycle against an isolated PostgreSQL database |
| R09 | `GET /api/runs` exposes status, snapshot date, comparison run and issue count | Endpoint example and contract test |
| R10 | Summary includes at least three current/previous metrics, deltas, trends, severity breakdown and issue lifecycle counts | Summary example and calculation tests |
| R11 | Summary and issue department filters include descendants | Hierarchy test |
| R12 | Issues support department/rule/severity filters and `limit`, `offset`, `total` | Combined-filter and pagination tests |
| R13 | Issue identity across runs is rule plus object | Set-comparison test; message changes do not change identity |
| R14 | First run has no previous values or deltas | Explicit JSON nulls and tests |
| R15 | Unknown run or department returns 404; invalid parameters return 422 | Consistent error envelope tests |
| R16 | JUnit 5 covers at least two rule boundaries, a metric and a delta | `./mvnw test`, without an HTTP server |
| R17 | Environment configuration and separated API/service/data layers | `.env.example`, package structure and README |
| R18 | README explains startup, tests, migration rollback, architecture and AI use | Links to these specs and reproducible commands |

## 3. Selected rules and interpretation decisions

These choices resolve unspecified behavior in the PDF; they are not additional requirements attributed to the author.

| Rule | Severity | Object | Violation predicate |
| --- | --- | --- | --- |
| `GOAL_NO_OWNER` | critical | goal | Owner is null, empty or blank after stripping whitespace |
| `GOAL_NO_KR` | critical | goal | Active goal has zero KRs |
| `KR_WEIGHTS_SUM` | major | goal | Goal has at least one KR and its exact decimal weight sum differs from 100 |
| `KR_STALE_PROGRESS` | minor | KR | Fact-update date is null or strictly earlier than snapshot date minus 45 days |

All predicates first require an `ACTIVE` goal. A date exactly 45 days old is valid; 46 days old is stale. The snapshot date, never the machine's current date, is the evaluation reference. Future update dates do not trigger the selected stale rule. Null update dates mean progress has never been reported.

Weights use decimal arithmetic, with non-null values from 0 through 100. No rounding tolerance is applied when evaluating the sum. A goal without KRs produces only `GOAL_NO_KR`, not a weight-sum issue. Missing target and cascade-link validation are the two unselected rules. Parent-goal relationships are still stored, and department hierarchy aggregation is mandatory. A goal is counted once by owning department, never duplicated through its parent-goal chain.

## 4. Metrics and comparison

For each run and requested department subtree, let `A` be the number of active goals, `B` the number of distinct active goals with at least one issue, `I` the number of issues, and `C` the number of critical issues. A KR issue marks its owning goal as affected.

| Key | Title | Unit | Formula | Better direction |
| --- | --- | --- | --- | --- |
| `clean_goals_share` | Доля корректных целей | `%` | `100 × (A − B) / A` | Higher |
| `issues_per_100_goals` | Проблем на 100 целей | `per_100_goals` | `100 × I / A` | Lower |
| `critical_issues_count` | Критические проблемы | `count` | `C` | Lower |

Ratios are rounded to one decimal with `HALF_UP`. Delta equals displayed current minus displayed previous, avoiding disagreement with the displayed operands. Trend is `better`, `worse`, or `unchanged` from that delta and the direction above. If `A = 0`, ratio values are null; count values remain zero. If either operand is null, metric delta and trend are null.

Compare the current run only to its persisted `compared_to_run_id`. For each side, use that run's own immutable hierarchy and source snapshot. A department introduced after the previous run has an empty prior scope: zero counts, undefined ratios. A department missing from the selected current run returns 404.

Issue identity is `(rule_code, object_type, object_id)`; object type disambiguates goal and KR identifiers. Within the respective department scopes, current set `N` and previous set `P` yield:

- New: `N − P`.
- Persisting: `N ∩ P`.
- Resolved: `P − N`.

A move between departments can appear resolved in one department and new in another, while remaining persisting at their common ancestor. Changes to title, details or severity do not change identity. Inactive or deleted objects can resolve prior issues. “Resolved” means absent from the selected scope, not proof of a manual correction.

Every severity is included, even when its count is zero. With no comparison run, `compared_to`, metric previous/delta/trend, severity previous/delta, and all three lifecycle counters are null. Current metrics and severity counts remain populated.

## 5. Deterministic demo fixture

Use three departments: `HQ` → `UNIT-A` → `UNIT-B`, eight goals directly owned by each department, 24 goals total. Assign `G01`–`G08` to HQ, `G09`–`G16` to UNIT-A, and `G17`–`G24` to UNIT-B. All demo goals are active. Test inactive goals separately. Include parent-goal links from child departments to ancestor-department goals.

Create `RUN-001` at `2026-10-19` and `RUN-002` at `2026-10-26`; the latter compares to the former. Every goal except G03 has one KR, named `K01` through `K24` by goal number. Healthy defaults are a nonblank owner, weight 100, target 100, actual 50, and fact-update date `2026-10-19`. Deadlines are populated. Apply only these defects:

| Run | Missing owner | No KRs | Weight 90 instead of 100 | Stale KR date `2026-08-01` |
| --- | --- | --- | --- | --- |
| RUN-001 | G01, G02 | G03 | G04, G05 | K06 |
| RUN-002 | G02, G07 | G03 | G05 | K06, K08, K09 |

RUN-002 restores G01's owner and K04's weight. Every issue is on a distinct goal in this fixture. The HQ summary oracle is:

| Field | RUN-001 | RUN-002 | RUN-002 delta |
| --- | --- | --- | --- |
| Active goals | 24 | 24 | 0 |
| Issues | 6 | 7 | +1 |
| Clean goals share | 75.0 | 70.8 | -4.2, worse |
| Issues per 100 goals | 25.0 | 29.2 | +4.2, worse |
| Critical count | 3 | 3 | 0, unchanged |
| Major count | 2 | 1 | -1 |
| Minor count | 1 | 3 | +2 |

RUN-002 lifecycle counts: new 3 (G07 owner, K08 stale, K09 stale), persisting 4, resolved 2 (G01 owner, G04 weights). UNIT-A includes UNIT-B and has 16 active goals and one current issue; UNIT-B has eight active goals and no issues. Additional tests must put an issue in the deepest department to prove transitive aggregation.

## 6. Verification and delivery checklist

Required unit tests include owner null/blank boundaries; weight 100.00 versus 99.99 and zero-KR exclusion; progress at 45/46 days and null; inactive exclusions; duplicate affected-goal deduplication; ratio rounding and empty denominator; first-run nulls; identity unaffected by message changes; set delta; and transitive hierarchy/moved objects. The assignment's minimum is four scenarios; these cases define the implementation's intended behavior.

Use PostgreSQL integration tests for migrations, snapshot isolation, uniqueness, filtered pagination, repeat startup, and demo oracles. Controller tests may use MockMvc without a network server to verify 404/422 and error JSON. Unit tests must not require Docker; integration tests may require Docker and run separately with `./mvnw verify -Pintegration`.

The README must explain Docker startup and automated Docker test/migration commands, snapshots/rules/metrics/deltas, link these specs, and state that AI assistance was used. A historical verification-status section is not required. README also provides optional manual Postman/curl requests with expected results for the demo dataset. The author must personally supply motivation, intended team role, weekly availability and participation duration; do not invent those answers.
