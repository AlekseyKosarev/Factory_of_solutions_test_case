package dev.kkd.api;

import dev.kkd.application.*;
import dev.kkd.application.Views.*;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class KkdController {
  private final QueryService service;

  public KkdController(QueryService service) {
    this.service = service;
  }

  @GetMapping("/runs")
  public Runs runs(@RequestParam MultiValueMap<String, String> query) {
    new QueryParameters(query, Set.of());
    return service.runs();
  }

  @GetMapping("/kkd/summary")
  public Summary summary(@RequestParam MultiValueMap<String, String> query) {
    var params = new QueryParameters(query, Set.of("run_id", "unit_id"));
    String run = params.id("run_id", true), unit = params.id("unit_id", true);
    return service.summary(run, unit);
  }

  @GetMapping("/kkd/issues")
  public IssuePage issues(@RequestParam MultiValueMap<String, String> query) {
    var params =
        new QueryParameters(
            query, Set.of("run_id", "unit_id", "rule_code", "severity", "limit", "offset"));
    String run = params.id("run_id", true), unit = params.id("unit_id", false);
    String rule =
        params.choice(
            "rule_code",
            Set.of("GOAL_NO_OWNER", "GOAL_NO_KR", "KR_WEIGHTS_SUM", "KR_STALE_PROGRESS"));
    String severity = params.choice("severity", Set.of("critical", "major", "minor"));
    int limit = params.integer("limit", 20, 1, 100),
        offset = params.integer("offset", 0, 0, Integer.MAX_VALUE);
    return service.issues(run, unit, rule, severity, limit, offset);
  }
}
