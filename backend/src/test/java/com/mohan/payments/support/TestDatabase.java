package com.mohan.payments.support;

import java.sql.Connection;
import java.sql.DriverManager;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Chooses the PostgreSQL instance for integration tests:
 * <ol>
 *   <li>Docker available: a throwaway Testcontainers PostgreSQL (the default, e.g. in CI).</li>
 *   <li>Otherwise a local PostgreSQL (TEST_DB_URL / TEST_DB_USERNAME / TEST_DB_PASSWORD, defaulting to the local dev
 *       database), using a dedicated {@code it_tests} schema so dev data is never touched.</li>
 *   <li>Neither: integration tests are skipped (reported as aborted), unit tests still run.</li>
 * </ol>
 */
public final class TestDatabase {

    private static final String SCHEMA = "it_tests";
    private static final String LOCAL_URL = env("TEST_DB_URL", "jdbc:postgresql://localhost:5432/payments");
    private static final String LOCAL_USER = env("TEST_DB_USERNAME", "payments");
    private static final String LOCAL_PASSWORD = env("TEST_DB_PASSWORD", "payments");

    private enum Mode { CONTAINER, LOCAL, NONE }

    private static final Mode MODE;
    private static final PostgreSQLContainer CONTAINER;

    static {
        if (dockerAvailable()) {
            CONTAINER = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
            CONTAINER.start();
            MODE = Mode.CONTAINER;
        } else {
            CONTAINER = null;
            MODE = localReachable() ? Mode.LOCAL : Mode.NONE;
        }
    }

    private TestDatabase() {
    }

    public static boolean available() {
        return MODE != Mode.NONE;
    }

    public static void register(DynamicPropertyRegistry registry) {
        switch (MODE) {
            case CONTAINER -> {
                registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
                registry.add("spring.datasource.username", CONTAINER::getUsername);
                registry.add("spring.datasource.password", CONTAINER::getPassword);
            }
            case LOCAL -> {
                String separator = LOCAL_URL.contains("?") ? "&" : "?";
                // public stays on the search path: extensions such as pg_trgm are installed there once per
                // database (often already by the dev schema), and their operator classes must be visible here.
                registry.add("spring.datasource.url",
                        () -> LOCAL_URL + separator + "currentSchema=" + SCHEMA + ",public");
                registry.add("spring.datasource.username", () -> LOCAL_USER);
                registry.add("spring.datasource.password", () -> LOCAL_PASSWORD);
                registry.add("spring.flyway.schemas", () -> SCHEMA);
                registry.add("spring.flyway.default-schema", () -> SCHEMA);
                registry.add("spring.flyway.create-schemas", () -> "true");
            }
            case NONE -> {
                // Tests are skipped before the context starts; see IntegrationTestBase.
            }
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean localReachable() {
        try (Connection ignored = DriverManager.getConnection(LOCAL_URL, LOCAL_USER, LOCAL_PASSWORD)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
