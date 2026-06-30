package org.instruct.jobenginespring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
        + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,"
        + "org.springframework.ai.model.postgresml.autoconfigure.PostgresMlEmbeddingAutoConfiguration")
class JobEngineSpringApplicationTests {

    @Test
    void contextLoads() {
    }

}
