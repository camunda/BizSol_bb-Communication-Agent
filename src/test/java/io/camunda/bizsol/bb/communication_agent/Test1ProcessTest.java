package io.camunda.bizsol.bb.communication_agent;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import java.time.Duration;
import io.camunda.client.annotation.Deployment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@CamundaSpringProcessTest
public class Test1ProcessTest {

  /**
   * Minimal Spring Boot application used exclusively by the test context.
   * @Deployment deploys all Camunda artifacts found under camunda-artifacts/ on the classpath
   * (populated from camunda-artifacts/ via Maven testResources) before each test method.
   */
  @SpringBootApplication
  @Deployment(resources = {"classpath*:/camunda-artifacts/**/*.bpmn", "classpath*:/camunda-artifacts/**/*.dmn"})
  static class TestApplication {}

  @Autowired
  private CamundaClient client;

  @Autowired
  private CamundaProcessTestContext processTestContext;

  @Test
  void shouldCompleteTest1() {
    // Start the test-1 process
    final ProcessInstanceEvent processInstance =
        client
            .newCreateInstanceCommand()
            .bpmnProcessId("CA_Test_001-Test_1")
            .latestVersion()
            .send()
            .join();

    // Assert the process runs to completion
    CamundaAssert.assertThat(processInstance)
        .withAssertionTimeout(Duration.ofMinutes(1))
        .isCompleted();
  }
}
