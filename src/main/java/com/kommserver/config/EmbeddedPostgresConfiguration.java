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

        File dataDir = new File("komm-postgres-data");

        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                //.setPort(0) // Port 0 = automatically find an available port
                .setPort(5555)
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(false)
                .setServerConfig("listen_addresses", "localhost")
                .setServerConfig("lc_messages", "C")
                .start();

        log.debug("PostgreSQL started on port: {}", postgres.getPort());
        return postgres.getDatabase("postgres", "postgres");
    }
}