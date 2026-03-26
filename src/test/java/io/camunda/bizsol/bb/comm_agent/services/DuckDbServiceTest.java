package io.camunda.bizsol.bb.comm_agent.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.DuckDbRecord;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class DuckDbServiceTest {

    private static JdbcTemplate jdbcTemplate;
    private DuckDbService serviceUnderTest;

    @BeforeAll
    static void setupDatabase() throws Exception {
        var conn = DriverManager.getConnection("jdbc:duckdb:");
        var ds = new SingleConnectionDataSource(conn, false);
        jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS records (id VARCHAR PRIMARY KEY, payload TEXT)");
    }

    @BeforeEach
    void setUp() {
        serviceUnderTest = new DuckDbService(jdbcTemplate, new ObjectMapper());
        jdbcTemplate.execute("DELETE FROM records");
    }

    @Test
    void shouldCreateAndReadRecord() {
        // when
        serviceUnderTest.create("id-1", "{\"key\":\"value\"}");

        // then
        Optional<DuckDbRecord> result = serviceUnderTest.read("id-1");
        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("id-1");
        assertThat(result.get().payload()).isEqualTo(Map.of("key", "value"));
    }

    @Test
    void shouldReturnEmptyForMissingRecord() {
        // when
        Optional<DuckDbRecord> result = serviceUnderTest.read("non-existent");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldUpdateRecord() {
        // given
        serviceUnderTest.create("id-2", "old");

        // when
        DuckDbRecord updated = serviceUnderTest.update("id-2", "{\"v\":\"new\"}");

        // then
        assertThat(updated.payload()).isEqualTo(Map.of("v", "new"));
        assertThat(serviceUnderTest.read("id-2").get().payload()).isEqualTo(Map.of("v", "new"));
    }

    @Test
    void shouldThrowWhenUpdatingMissingRecord() {
        // when / then
        assertThatThrownBy(() -> serviceUnderTest.update("non-existent", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-existent");
    }

    @Test
    void shouldDeleteRecord() {
        // given
        serviceUnderTest.create("id-3", "to-delete");

        // when
        serviceUnderTest.delete("id-3");

        // then
        assertThat(serviceUnderTest.read("id-3")).isEmpty();
    }

    @Test
    void shouldListAllRecords() {
        // given
        serviceUnderTest.create("a", "{\"type\":\"contact\"}");
        serviceUnderTest.create("b", "{\"type\":\"ticket\"}");

        // when
        List<DuckDbRecord> all = serviceUnderTest.list();

        // then
        assertThat(all).hasSize(2);
        assertThat(all).extracting(DuckDbRecord::id).containsExactlyInAnyOrder("a", "b");
        assertThat(all)
                .extracting(r -> (String) ((Map<?, ?>) r.payload()).get("type"))
                .containsExactlyInAnyOrder("contact", "ticket");
    }

    @Test
    void shouldReturnEmptyListWhenNoRecords() {
        assertThat(serviceUnderTest.list()).isEmpty();
    }
}
