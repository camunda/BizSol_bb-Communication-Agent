package io.camunda.bizsol.bb.comm_agent.util;

import static io.camunda.bizsol.bb.comm_agent.util.BpmnFile.Replace.replace;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class BpmnFileTest {

    @Test
    void shouldLoadBpmnFromClasspathResource() {
        assertThatCode(() -> BpmnFile.read("bpmns/testprocess.bpmn")).doesNotThrowAnyException();
    }

    @Test
    void shouldLoadBpmnFromFileSystemPath() {
        assertThatCode(() -> BpmnFile.read("camunda-artifacts/message-receiver.bpmn"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldApplyReplacementsToClasspathResource() {
        assertThatCode(
                        () ->
                                BpmnFile.replace(
                                        "bpmns/testprocess.bpmn",
                                        replace(
                                                "test-message-reply",
                                                "test-message-reply-renamed")))
                .doesNotThrowAnyException();
    }
}
