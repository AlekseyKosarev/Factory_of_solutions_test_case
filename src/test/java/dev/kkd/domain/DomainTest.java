package dev.kkd.domain;

import static org.assertj.core.api.Assertions.*;

import dev.kkd.domain.Model.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DomainTest {
  private static final LocalDate DATE = LocalDate.of(2026, 10, 26);

  private Goal goal(String id, String owner, String status) {
    return new Goal(id, "HQ", id, owner, null, status, DATE);
  }

  private KeyResult kr(String id, String goal, String weight, LocalDate updated) {
    return new KeyResult(
        id, goal, id, BigDecimal.TEN, BigDecimal.ONE, new BigDecimal(weight), updated);
  }

  private Snapshot snapshot(List<Goal> goals, List<KeyResult> krs) {
    return new Snapshot(List.of(new Department("HQ", "HQ", null)), goals, krs);
  }

  private Finding finding(String rule, String type, String id, String goal, String severity) {
    return new Finding(
        rule, severity, type, id, goal, type.equals("kr") ? id : null, "message", Map.of());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n", "\u2003"})
  void missingOwnerBoundary(String owner) {
    var s = snapshot(List.of(goal("G", owner, "ACTIVE")), List.of());
    assertThat(new NoOwnerRule().evaluate(s, DATE)).hasSize(1);
  }

  @Test
  void nonblankOwnerIsValid() {
    assertThat(
            new NoOwnerRule()
                .evaluate(snapshot(List.of(goal("G", " User ", "ACTIVE")), List.of()), DATE))
        .isEmpty();
  }

  @Test
  void weightsUseExactDecimalsAndExcludeEmptyGoals() {
    var goals = List.of(goal("G", "owner", "ACTIVE"));
    var rule = new WeightsRule();
    assertThat(
            rule.evaluate(
                snapshot(
                    goals, List.of(kr("K1", "G", "33.33", DATE), kr("K2", "G", "66.67", DATE))),
                DATE))
        .isEmpty();
    assertThat(rule.evaluate(snapshot(goals, List.of(kr("K1", "G", "99.99", DATE))), DATE))
        .hasSize(1);
    assertThat(rule.evaluate(snapshot(goals, List.of()), DATE)).isEmpty();
  }

  @Test
  void staleBoundaryIsBasedOnSnapshotDate() {
    var s =
        snapshot(
            List.of(goal("G", "owner", "ACTIVE")),
            List.of(
                kr("45", "G", "20", DATE.minusDays(45)),
                kr("46", "G", "20", DATE.minusDays(46)),
                kr("null", "G", "20", null),
                kr("future", "G", "40", DATE.plusDays(1))));
    assertThat(new StaleProgressRule().evaluate(s, DATE))
        .extracting(Finding::objectId)
        .containsExactly("46", "null");
  }

  @Test
  void engineExcludesInactiveGoalsAndTheirKrs() {
    var s =
        snapshot(
            List.of(goal("G", null, "COMPLETED"), goal("G2", null, "DRAFT")),
            List.of(kr("K", "G", "90", null)));
    assertThat(new RuleEngine().evaluate(s, DATE)).isEmpty();
  }

  @Test
  void emptyKrGoalDoesNotAlsoFailWeights() {
    var result =
        new RuleEngine().evaluate(snapshot(List.of(goal("G", "owner", "ACTIVE")), List.of()), DATE);
    assertThat(result).extracting(Finding::ruleCode).containsExactly("GOAL_NO_KR");
  }

  @Test
  void metricsDeduplicateGoalsAndRoundBeforeDelta() {
    var goals =
        List.of(
            goal("A", "owner", "ACTIVE"),
            goal("B", "owner", "ACTIVE"),
            goal("C", "owner", "ACTIVE"));
    var issues =
        List.of(
            finding("owner", "goal", "A", "A", "critical"),
            finding("stale", "kr", "K", "A", "minor"));
    var metrics = new MetricCalculator().calculate(goals, issues, goals, List.of());
    assertThat(metrics.get(0).value()).isEqualByComparingTo("66.7");
    assertThat(metrics.get(0).delta()).isEqualByComparingTo("-33.3");
    assertThat(metrics.get(0).trend()).isEqualTo("worse");
    assertThat(metrics.get(1).value()).isEqualByComparingTo("66.7");
    assertThat(metrics.get(2).value()).isEqualByComparingTo("1");
  }

  @Test
  void zeroDenominatorAndFirstRunAreNull() {
    var metrics = new MetricCalculator().calculate(List.of(), List.of(), List.of(), null);
    assertThat(metrics.get(0).value()).isNull();
    assertThat(metrics.get(1).value()).isNull();
    assertThat(metrics.get(2).value()).isEqualByComparingTo("0");
    assertThat(metrics)
        .allSatisfy(
            m -> {
              assertThat(m.previous()).isNull();
              assertThat(m.delta()).isNull();
              assertThat(m.trend()).isNull();
            });
    assertThat(new DeltaCalculator().calculate(List.of(), null))
        .isEqualTo(new DeltaCalculator.Delta(null, null, null));
  }

  @Test
  void deltaUsesRuleAndTypedObjectOnly() {
    var old = finding("owner", "goal", "A", "A", "critical");
    var changed =
        new Finding(
            "owner", "minor", "goal", "A", "A", null, "Different text", Map.of("changed", true));
    var gone = finding("weights", "goal", "B", "B", "major");
    var added = finding("stale", "kr", "A", "C", "minor");
    assertThat(new DeltaCalculator().calculate(List.of(changed, added), List.of(old, gone)))
        .isEqualTo(new DeltaCalculator.Delta(1L, 1L, 1L));
  }

  @Test
  void hierarchyIsTransitiveAndCyclesAreRejected() {
    var s =
        new Snapshot(
            List.of(
                new Department("HQ", "HQ", null),
                new Department("A", "A", "HQ"),
                new Department("B", "B", "A")),
            List.of(),
            List.of());
    assertThat(s.subtree("HQ")).containsExactlyInAnyOrder("HQ", "A", "B");
    assertThat(s.subtree("missing")).isEmpty();
    assertThatThrownBy(
            () ->
                new Snapshot(
                        List.of(new Department("A", "A", "B"), new Department("B", "B", "A")),
                        List.of(),
                        List.of())
                    .validate())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicateRuleFindingsAreRejected() {
    var rule = new NoKrRule();
    assertThatThrownBy(
            () ->
                new RuleEngine(List.of(rule, rule))
                    .evaluate(snapshot(List.of(goal("G", "owner", "ACTIVE")), List.of()), DATE))
        .isInstanceOf(IllegalStateException.class);
  }
}
