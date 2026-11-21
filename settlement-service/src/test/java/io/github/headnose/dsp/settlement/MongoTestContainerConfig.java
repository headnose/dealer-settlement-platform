package io.github.headnose.dsp.settlement;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a throwaway MongoDB in Docker for integration tests, so tests never touch the
 * local settlement_db used for manual testing. @ServiceConnection points Spring Data
 * MongoDB at the container's random port automatically.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MongoTestContainerConfig {

    @Bean
    @ServiceConnection
    MongoDBContainer mongoDbContainer() {
        return new MongoDBContainer(DockerImageName.parse("mongo:7"));
    }
}