package io.github.headnose.dsp.settlement;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import({MongoTestContainerConfig.class, RedisTestContainerConfig.class})
class SettlementServiceApplicationTests {

	@Test
	void contextLoads() {
	}
}
