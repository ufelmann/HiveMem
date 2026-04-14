package com.hivemem;

import com.hivemem.auth.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "spring.main.lazy-initialization=true",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jooq.JooqAutoConfiguration"
})
class HiveMemApplicationTest {

    @MockBean(name = "dbTokenService")
    private TokenService tokenService;

    @Test
    void contextLoads() {
    }
}
