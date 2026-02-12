package io.camunda.bizsol.bb.comm_agent.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.camunda.bizsol.bb.comm_agent.models.ObjectA;
import io.camunda.bizsol.bb.comm_agent.models.ProcessVariables;
import io.camunda.bizsol.bb.comm_agent.services.ServiceA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkerATest {

    @Mock private ServiceA serviceA;

    private WorkerA workerA;

    @BeforeEach
    void setUp() {
        workerA = new WorkerA(serviceA);
    }

    @Test
    void shouldProcessAAndSetConcatenatedResult() {
        // given
        ProcessVariables processVariables = new ProcessVariables();
        processVariables.setA(new ObjectA("testValue"));
        when(serviceA.processA(any(ObjectA.class))).thenReturn("testValue_byServiceA_");

        // when
        ProcessVariables result = workerA.processA(processVariables);

        // then
        assertThat(result).isSameAs(processVariables);
        assertThat(result.getConcatenatedResult()).isEqualTo("testValue_byServiceA_");
    }
}
