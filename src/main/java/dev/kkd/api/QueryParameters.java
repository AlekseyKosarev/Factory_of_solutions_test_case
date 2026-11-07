package dev.kkd.api;

import dev.kkd.application.ServiceException;
import java.util.Set;
import org.springframework.util.MultiValueMap;

final class QueryParameters {
  private final MultiValueMap<String, String> values;

  QueryParameters(MultiValueMap<String, String> values, Set<String> allowed) {
    this.values = values;
    for (var entry : values.entrySet())
      if (!allowed.contains(entry.getKey())
          || entry.getValue().size() != 1
          || entry.getValue().getFirst() == null
          || entry.getValue().getFirst().isBlank())
        throw invalid("Unknown, repeated or blank parameter: " + entry.getKey());
  }

  String id(String name, boolean required) {
    String value = values.getFirst(name);
    if (value == null) {
      if (required) throw invalid("Required parameter: " + name);
      return null;
    }
    if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("Invalid identifier: " + name);
    return value;
  }

  String choice(String name, Set<String> allowed) {
    String value = values.getFirst(name);
    if (value != null && !allowed.contains(value)) throw invalid("Invalid value: " + name);
    return value;
  }

  int integer(String name, int fallback, int min, int max) {
    String value = values.getFirst(name);
    if (value == null) return fallback;
    try {
      if (!value.matches("[0-9]+")) throw new NumberFormatException();
      int n = Integer.parseInt(value);
      if (n < min || n > max) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw invalid("Out of range integer: " + name);
    }
  }

  private static ServiceException invalid(String message) {
    return new ServiceException(422, "INVALID_PARAMETER", message);
  }
}
