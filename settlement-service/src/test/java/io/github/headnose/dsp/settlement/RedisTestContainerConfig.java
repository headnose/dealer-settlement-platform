package io.github.headnose.dsp.settlement;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

@TestConfiguration(proxyBeanMethods = false)
public class RedisTestContainerConfig {

    // Same image as docker-compose.yml. There is no dedicated Redis container class in Testcontainers,
    // so a GenericContainer plus name = "redis" tells Boot which connection details to create.
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
}