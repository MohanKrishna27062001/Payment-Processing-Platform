package com.mohan.payments.common.config;

import java.io.IOException;

import javax.sql.DataSource;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs the application against a PostgreSQL it starts itself, so the project can be cloned and run with
 * nothing installed and no Docker. Enabled only by {@code app.database.embedded=true}, which the
 * {@code embedded} profile sets; every other run uses the configured datasource and this class does nothing.
 * <p>
 * It is a <em>real</em> PostgreSQL, not an in-memory substitute. That matters here: the schema needs
 * {@code pg_trgm} and {@code pgcrypto}, a GIN index with {@code gin_trgm_ops}, {@code text_pattern_ops},
 * partial indexes and {@code JSONB}, and the outbox worker claims jobs with {@code FOR UPDATE SKIP LOCKED}.
 * H2 supports none of those, so running on it would mean maintaining a second schema and a worker that
 * cannot show the concurrency it was built for. The binaries ship as ordinary Maven artifacts, so "no
 * install" and "real PostgreSQL" are not in conflict.
 * <p>
 * The data directory is a temporary one and is deleted when the JVM exits, so every run starts from the
 * migrations and the seed. Nothing survives a restart -- which is the point for a demo, and the reason this
 * must never be the default.
 */
@Configuration
@ConditionalOnProperty(name = "app.database.embedded", havingValue = "true")
public class EmbeddedPostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    /**
     * Started before anything asks for a {@link DataSource}, and closed on shutdown, which stops the server
     * and removes its data directory.
     */
    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres() throws IOException {
        long started = System.currentTimeMillis();
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                // Durability buys nothing for a database that is deleted on exit, and turning it off takes a
                // noticeable slice off first-run startup while the migrations are applied.
                .setServerConfig("fsync", "off")
                .setServerConfig("full_page_writes", "off")
                .setServerConfig("synchronous_commit", "off")
                .start();
        log.info("Embedded PostgreSQL ready on port {} in {} ms. Data is temporary and is discarded on exit.",
                postgres.getPort(), System.currentTimeMillis() - started);
        return postgres;
    }

    /**
     * Supplying this bean makes Spring Boot's datasource auto-configuration back off, so the
     * {@code spring.datasource.*} properties are ignored while this profile is active. Flyway and JPA pick
     * this up like any other datasource, and run the same migrations and the same seed as a normal run.
     */
    @Bean
    public DataSource dataSource(EmbeddedPostgres postgres) {
        return postgres.getPostgresDatabase();
    }
}
