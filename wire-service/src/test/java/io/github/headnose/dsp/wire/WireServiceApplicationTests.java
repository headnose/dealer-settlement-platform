package io.github.headnose.dsp.wire;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(MySqlTestContainerConfig.class)
class WireServiceApplicationTests {

	@Test
	void contextLoads() {
	}
}