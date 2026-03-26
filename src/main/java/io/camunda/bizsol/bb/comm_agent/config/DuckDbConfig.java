package io.camunda.bizsol.bb.comm_agent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class DuckDbConfig {

    @Value("${duckdb.file-path:./data/comm-agent.db}")
    private String filePath;

    @Bean
    DataSource duckDbDataSource() {
        if (!filePath.isEmpty()) {
            var parent = Paths.get(filePath).getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    throw new IllegalStateException("Cannot create DuckDB directory: " + parent, e);
                }
            }
        }
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.duckdb.DuckDBDriver");
        ds.setUrl("jdbc:duckdb:" + filePath);
        return ds;
    }

    @Bean
    JdbcTemplate duckDbJdbcTemplate(DataSource duckDbDataSource) {
        JdbcTemplate jt = new JdbcTemplate(duckDbDataSource);
        jt.execute("CREATE TABLE IF NOT EXISTS records (id VARCHAR PRIMARY KEY, payload TEXT)");
        return jt;
    }
}
