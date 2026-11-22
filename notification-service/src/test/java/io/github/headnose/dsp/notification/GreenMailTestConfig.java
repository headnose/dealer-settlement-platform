package io.github.headnose.dsp.notification;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
public class GreenMailTestConfig {

    // In-JVM SMTP server (port 3025), started and stopped with the Spring context,
    // so it is shared by every test class that uses the same @Import (like the containers).
    @Bean(initMethod = "start", destroyMethod = "stop")
    GreenMail greenMail() {
        return new GreenMail(ServerSetupTest.SMTP);
    }

    // Points spring.mail.* at GreenMail instead of Mailpit (application.yaml)
    @Bean
    DynamicPropertyRegistrar greenMailProperties(GreenMail greenMail) {
        return registry -> {
            registry.add("spring.mail.host", () -> "127.0.0.1");
            registry.add("spring.mail.port", () -> greenMail.getSmtp().getPort());
        };
    }
}