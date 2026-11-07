package dev.kkd;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.kkd.application.*;
import dev.kkd.infrastructure.*;
import java.time.LocalDate;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServiceIT {
  static PostgreSQLContainer<?> postgres;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry r) {
    String url = System.getenv("TEST_JDBC_URL");
    if (url == null) {
      postgres = new PostgreSQLContainer<>("postgres:17.11-bookworm");
      postgres.start();
      r.add("spring.datasource.url", postgres::getJdbcUrl);
      r.add("spring.datasource.username", postgres::getUsername);
      r.add("spring.datasource.password", postgres::getPassword);
    } else {
      r.add("spring.datasource.url", () -> url);
      r.add(
          "spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "kkd"));
      r.add(
          "spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
    }
  }

  @Autowired MockMvc mvc;
  @Autowired QueryService queries;
  @Autowired DemoInitializer demo;
  @Autowired JdbcTemplate jdbc;
  @Autowired RunService runs;
  @Autowired QualityRepository quality;
  @Autowired SnapshotRepository snapshots;

  @Test
  @Order(1)
  void seededOracleAndJsonContract() throws Exception {
    mvc.perform(get("/api/kkd/summary?run_id=RUN-002&unit_id=HQ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metrics[0].value").value(70.8))
        .andExpect(jsonPath("$.metrics[0].previous").value(75.0))
        .andExpect(jsonPath("$.metrics[0].delta").value(-4.2))
        .andExpect(jsonPath("$.metrics[0].trend").value("worse"))
        .andExpect(jsonPath("$.metrics[1].value").value(29.2))
        .andExpect(jsonPath("$.metrics[2].value").value(3))
        .andExpect(jsonPath("$.issues_by_severity[1].current").value(1))
        .andExpect(jsonPath("$.issues_by_severity[2].current").value(3))
        .andExpect(jsonPath("$.delta.new").value(3))
        .andExpect(jsonPath("$.delta.persisting").value(4))
        .andExpect(jsonPath("$.delta.resolved").value(2));
    mvc.perform(get("/api/runs"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].issues_count").value(7));
  }

  @Test
  @Order(2)
  void firstRunNulls() throws Exception {
    var response =
        mvc.perform(get("/api/kkd/summary?run_id=RUN-001&unit_id=HQ"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.compared_to").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.metrics[0].previous").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.delta.new").value(org.hamcrest.Matchers.nullValue()))
            .andReturn();
    assertThat(response.getResponse().getContentAsString())
        .contains(
            "\"previous\":null",
            "\"delta\":null",
            "\"trend\":null",
            "\"persisting\":null",
            "\"resolved\":null");
  }

  @Test
  @Order(3)
  void combinedFiltersAndPagination() throws Exception {
    mvc.perform(
            get(
                "/api/kkd/issues?run_id=RUN-002&unit_id=HQ&rule_code=KR_STALE_PROGRESS&severity=minor&limit=1&offset=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].object_id").value("K08"));
    mvc.perform(get("/api/kkd/issues?run_id=RUN-002&offset=999"))
        .andExpect(jsonPath("$.total").value(7))
        .andExpect(jsonPath("$.items").isEmpty());
    assertThat(queries.issues("RUN-002", "UNIT-A", null, null, 20, 0).total()).isEqualTo(1);
    assertThat(queries.issues("RUN-002", "UNIT-B", null, null, 20, 0).total()).isZero();
  }

  @Test
  @Order(4)
  void missingResources() throws Exception {
    mvc.perform(get("/api/kkd/summary?run_id=RUN-999&unit_id=HQ"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
    mvc.perform(get("/api/kkd/issues?run_id=RUN-002&unit_id=NOPE"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("UNIT_NOT_FOUND"));
  }

  @Test
  @Order(5)
  void restartDoesNotDuplicateAnything() {
    demo.initialize();
    demo.initialize();
    assertThat(quality.runs()).hasSize(2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM quality_issue", Long.class)).isEqualTo(13);
  }

  @Test
  @Order(6)
  void historicalResultsSurviveLiveChanges() {
    var before = queries.summary("RUN-002", "HQ");
    try {
      jdbc.update("UPDATE goal SET owner='changed',unit_id='UNIT-B' WHERE id='G02'");
      jdbc.update("UPDATE department SET name='renamed' WHERE id='HQ'");
      assertThat(queries.summary("RUN-002", "HQ")).isEqualTo(before);
      assertThat(
              snapshots.load("RUN-002").goals().stream()
                  .filter(g -> g.id().equals("G02"))
                  .findFirst()
                  .orElseThrow()
                  .unitId())
          .isEqualTo("HQ");
    } finally {
      jdbc.update("UPDATE goal SET owner=NULL,unit_id='HQ' WHERE id='G02'");
      jdbc.update("UPDATE department SET name='Headquarters' WHERE id='HQ'");
    }
  }

  @Test
  @Order(7)
  void databaseRejectsDuplicateIssueIdentity() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO quality_issue SELECT"
                        + " gen_random_uuid(),run_id,rule_code,object_type,object_id,goal_id,kr_id,severity,message,details"
                        + " FROM quality_issue LIMIT 1"))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
  }

  @Test
  @Order(8)
  void failedRunDoesNotExposePartialSnapshot() throws Exception {
    try {
      jdbc.update("UPDATE department SET parent_id='UNIT-B' WHERE id='HQ'");
      assertThatThrownBy(() -> runs.execute("RUN-BAD", LocalDate.of(2026, 11, 1)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(quality.find("RUN-BAD").orElseThrow().status()).isEqualTo("FAILED");
      assertThat(snapshots.load("RUN-BAD").goals()).isEmpty();
      mvc.perform(get("/api/kkd/issues?run_id=RUN-BAD"))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.error.code").value("RUN_NOT_COMPLETED"));
    } finally {
      jdbc.update("UPDATE department SET parent_id=NULL WHERE id='HQ'");
      jdbc.update("DELETE FROM quality_run WHERE id='RUN-BAD'");
    }
  }

  @Test
  @Order(9)
  void deepHierarchyMovedIssueAndNewDepartment() {
    try {
      jdbc.update("UPDATE goal SET owner=NULL WHERE id='G24'");
      jdbc.update("UPDATE goal SET unit_id='UNIT-B' WHERE id='G02'");
      jdbc.update("INSERT INTO department VALUES ('NEW','New unit','HQ')");
      runs.execute("RUN-003", LocalDate.of(2026, 11, 2));
      assertThat(queries.issues("RUN-003", "HQ", "GOAL_NO_OWNER", null, 20, 0).total())
          .isEqualTo(3);
      assertThat(queries.issues("RUN-003", "UNIT-A", "GOAL_NO_OWNER", null, 20, 0).total())
          .isEqualTo(2);
      assertThat(queries.summary("RUN-003", "HQ").delta().persisting()).isEqualTo(7);
      assertThat(queries.summary("RUN-003", "UNIT-B").delta().added()).isEqualTo(2);
      var empty = queries.summary("RUN-003", "NEW");
      assertThat(empty.metrics().getFirst().value()).isNull();
      assertThat(empty.metrics().getFirst().previous()).isNull();
      assertThat(empty.metrics().get(2).previous()).isEqualByComparingTo("0");
    } finally {
      jdbc.update("DELETE FROM quality_issue WHERE run_id='RUN-003'");
      jdbc.update("DELETE FROM run_key_result WHERE run_id='RUN-003'");
      jdbc.update("DELETE FROM run_goal WHERE run_id='RUN-003'");
      jdbc.update("DELETE FROM run_department WHERE run_id='RUN-003'");
      jdbc.update("DELETE FROM quality_run WHERE id='RUN-003'");
      jdbc.update("UPDATE goal SET owner='Employee 24' WHERE id='G24'");
      jdbc.update("UPDATE goal SET unit_id='HQ' WHERE id='G02'");
      jdbc.update("DELETE FROM department WHERE id='NEW'");
    }
  }
}
