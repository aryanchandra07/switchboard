package com.switchboard.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a throw-away Postgres in Docker for tests (same image as infra/docker-compose.yml).
 * {@code @ServiceConnection} points R2DBC and Flyway at it automatically, so tests never touch
 * your real local database.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
				.asCompatibleSubstituteFor("postgres"));
	}
}
