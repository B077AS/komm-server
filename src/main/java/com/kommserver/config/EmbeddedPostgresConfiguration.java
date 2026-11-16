package com.kommserver.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;

@Slf4j
@Configuration
public class EmbeddedPostgresConfiguration {

    @Bean
    public DataSource dataSource() throws IOException {

        File dataDir = DataDir.resolve("komm-postgres-data").toFile();
        // Where the Postgres binaries are unpacked: next to the data, not in /tmp (where a tmp cleaner can remove them
        // from under a running server, and where they were never part of the installation).
        File binaries = DataDir.resolve("pg-runtime").toFile();

        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                //.setPort(0) // Port 0 = automatically find an available port
                .setPort(5555)
                .setOverrideWorkingDirectory(binaries)
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .setServerConfig("listen_addresses", "localhost")
                .setServerConfig("lc_messages", "C")
                .start();

        log.debug("PostgreSQL started on port: {}", postgres.getPort());
        return postgres.getDatabase("postgres", "postgres");
    }
}