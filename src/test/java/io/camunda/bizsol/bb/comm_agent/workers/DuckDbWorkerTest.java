package io.camunda.bizsol.bb.comm_agent.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.DuckDbRecord;
import io.camunda.bizsol.bb.comm_agent.models.DuckDbResult;
import io.camunda.bizsol.bb.comm_agent.models.DuckDbVariables;
import io.camunda.bizsol.bb.comm_agent.services.DuckDbService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DuckDbWorkerTest {

    @Mock private DuckDbService duckDbService;

    private DuckDbWorker workerUnderTest;

    @BeforeEach
    void setUp() {
        workerUnderTest = new DuckDbWorker(duckDbService, new ObjectMapper());
    }

    @Test
    void shouldRouteCreateWithStringPayload() {
        // given
        var record = new DuckDbRecord("id-1", "{\"key\":\"value\"}");
        when(duckDbService.create("id-1", "{\"key\":\"value\"}")).thenReturn(record);

        // when
        DuckDbResult result =
                workerUnderTest.execute(
                        new DuckDbVariables("CREATE", "id-1", "{\"key\":\"value\"}"));

        // then
        assertThat(result.duckDbResult()).isEqualTo(record);
    }

    @Test
    void shouldRouteCreateWithObjectPayload() {
        // given — payload arrives as Map when Camunda deserialises a JSON object variable
        var payload = Map.of("name", "Acme");
        var record = new DuckDbRecord("id-2", "{\"name\":\"Acme\"}");
        when(duckDbService.create(eq("id-2"), anyString())).thenReturn(record);

        // when
        DuckDbResult result =
                workerUnderTest.execute(new DuckDbVariables("CREATE", "id-2", payload));

        // then
        assertThat(result.duckDbResult()).isEqualTo(record);
        verify(duckDbService).create(eq("id-2"), anyString());
    }

    @Test
    void shouldRouteRead() {
        // given
        var record = new DuckDbRecord("id-1", "payload");
        when(duckDbService.read("id-1")).thenReturn(Optional.of(record));

        // when
        DuckDbResult result = workerUnderTest.execute(new DuckDbVariables("READ", "id-1", null));

        // then
        assertThat(result.duckDbResult()).isEqualTo(record);
    }

    @Test
    void shouldRouteUpdate() {
        // given
        var record = new DuckDbRecord("id-1", "updated");
        when(duckDbService.update("id-1", "updated")).thenReturn(record);

        // when
        DuckDbResult result =
                workerUnderTest.execute(new DuckDbVariables("UPDATE", "id-1", "updated"));

        // then
        assertThat(result.duckDbResult()).isEqualTo(record);
    }

    @Test
    void shouldRouteDelete() {
        // when
        DuckDbResult result = workerUnderTest.execute(new DuckDbVariables("DELETE", "id-1", null));

        // then
        verify(duckDbService).delete("id-1");
        assertThat(result.duckDbResult()).isNull();
    }

    @Test
    void shouldThrowForUnknownOperation() {
        // when / then
        assertThatThrownBy(
                        () ->
                                workerUnderTest.execute(
                                        new DuckDbVariables("MERGE", "id-1", "payload")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MERGE");
    }

    @Test
    void shouldRouteList() {
        // given
        var records =
                List.of(
                        new DuckDbRecord("a", "{\"type\":\"contact\"}"),
                        new DuckDbRecord("b", "{\"type\":\"ticket\"}"));
        when(duckDbService.list()).thenReturn(records);

        // when
        DuckDbResult result = workerUnderTest.execute(new DuckDbVariables("LIST", null, null));

        // then
        assertThat(result.duckDbResult()).isEqualTo(records);
    }
}
