package dev.kkd.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.kkd.application.*;
import dev.kkd.application.Views.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiTest {
  private QueryService service;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    service = mock(QueryService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new KkdController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "?run_id=R",
        "?run_id=R&unit_id=",
        "?run_id=R&unit_id=HQ&unit_id=A",
        "?run_id=R&unit_id=HQ&bad=1",
        "?run_id=bad.id&unit_id=HQ"
      })
  void invalidSummaryParameters(String query) throws Exception {
    mvc.perform(get("/api/kkd/summary" + query))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "limit=0",
        "limit=101",
        "limit=1.5",
        "offset=-1",
        "offset=2147483648",
        "severity=MAJOR",
        "rule_code=CASCADE_NO_PARENT"
      })
  void invalidIssueParameters(String query) throws Exception {
    mvc.perform(get("/api/kkd/issues?run_id=R&" + query))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error.code").value("INVALID_PARAMETER"));
    verifyNoInteractions(service);
  }

  @Test
  void missingRunUsesEnvelope() throws Exception {
    when(service.summary("R", "HQ"))
        .thenThrow(new ServiceException(404, "RUN_NOT_FOUND", "Run R was not found"));
    mvc.perform(get("/api/kkd/summary?run_id=R&unit_id=HQ"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RUN_NOT_FOUND"));
  }

  @Test
  void runsReturnItems() throws Exception {
    when(service.runs()).thenReturn(new Runs(List.of()));
    mvc.perform(get("/api/runs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
  }
}
