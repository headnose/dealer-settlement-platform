package io.github.headnose.dsp.wire;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/** Throwaway MySQL for tests; @ServiceConnection overrides spring.datasource.* */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestContainerConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4");
    }
}