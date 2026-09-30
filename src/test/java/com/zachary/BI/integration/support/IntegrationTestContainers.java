package com.zachary.BI.integration.support;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * One set of containers per JVM, shared by every integration test class so the Spring context is cached.
 * Image versions match compose.yaml so tests exercise the same infrastructure as local and production runs.
 */
public final class IntegrationTestContainers {

    public static final MySQLContainer<?> MYSQL = createMySql();

    public static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.11-alpine"))
            .withExposedPorts(6379);

    public static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.5-management"));

    private static boolean started;

    private IntegrationTestContainers() {
    }

    /**
     * Initialises MySQL with the real project scripts: create_table.sql, then every migration twice.
     * The deployment re-runs all migrations on every release, so the second pass fails the build
     * if any migration is not idempotent.
     */
    private static MySQLContainer<?> createMySql() {
        MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.11"))
                .withDatabaseName("bi_db")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                .withCopyFileToContainer(MountableFile.forHostPath("sql/create_table.sql"),
                        "/docker-entrypoint-initdb.d/00-create_table.sql");

        List<Path> migrations;
        try (Stream<Path> files = Files.list(Path.of("sql/migrations"))) {
            migrations = files.filter(file -> file.toString().endsWith(".sql")).sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        for (int pass = 1; pass <= 2; pass++) {
            for (Path migration : migrations) {
                // The entrypoint runs files in name order: "10-..." (first deploy) before "20-..." (second).
                mysql.withCopyFileToContainer(MountableFile.forHostPath(migration),
                        "/docker-entrypoint-initdb.d/%d0-%s".formatted(pass, migration.getFileName()));
            }
        }
        return mysql;
    }

    public static synchronized void start() {
        if (!started) {
            Stream.of(MYSQL, REDIS, RABBITMQ).parallel().forEach(GenericContainer::start);
            started = true;
        }
    }

    /** Used by {@link RequiresDockerCondition} to skip the suite on developer machines without Docker. */
    public static boolean isDockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }
}
