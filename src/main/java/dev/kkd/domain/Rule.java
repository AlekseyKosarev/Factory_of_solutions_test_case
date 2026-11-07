package dev.kkd.domain;

import dev.kkd.domain.Model.*;
import java.time.LocalDate;
import java.util.List;

public interface Rule {
  List<Finding> evaluate(Snapshot activeSnapshot, LocalDate snapshotDate);
}
