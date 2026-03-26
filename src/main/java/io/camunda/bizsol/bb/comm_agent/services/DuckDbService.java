package io.camunda.bizsol.bb.comm_agent.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.bizsol.bb.comm_agent.models.DuckDbRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DuckDbService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DuckDbService(
            @Qualifier("duckDbJdbcTemplate") JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private DuckDbRecord toRecord(String id, String json) {
        try {
            return new DuckDbRecord(id, objectMapper.readValue(json, Object.class));
        } catch (JsonProcessingException e) {
            return new DuckDbRecord(id, json);
        }
    }

    public DuckDbRecord create(String id, String payload) {
        jdbcTemplate.update("INSERT INTO records (id, payload) VALUES (?, ?)", id, payload);
        return toRecord(id, payload);
    }

    public Optional<DuckDbRecord> read(String id) {
        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(
                            "SELECT id, payload FROM records WHERE id = ?",
                            (rs, rowNum) -> toRecord(rs.getString("id"), rs.getString("payload")),
                            id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public DuckDbRecord update(String id, String payload) {
        int rows = jdbcTemplate.update("UPDATE records SET payload = ? WHERE id = ?", payload, id);
        if (rows == 0) throw new IllegalArgumentException("Record not found: " + id);
        return toRecord(id, payload);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM records WHERE id = ?", id);
    }

    public List<DuckDbRecord> list() {
        return jdbcTemplate.query(
                "SELECT id, payload FROM records",
                (rs, rowNum) -> toRecord(rs.getString("id"), rs.getString("payload")));
    }
}
