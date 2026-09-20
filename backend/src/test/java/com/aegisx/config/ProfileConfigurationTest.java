package com.aegisx.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileConfigurationTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("dev")
    class DevProfileTest {
        @Autowired
        private Environment environment;

        @Test
        void devProfileConfiguresDdlAutoUpdate() {
            assertEquals("update", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("true", environment.getProperty("spring.jpa.show-sql"));
            assertEquals("jdbc:postgresql://localhost:5432/aegisx", environment.getProperty("spring.datasource.url"));
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class TestProfileTest {
        @Autowired
        private Environment environment;

        @Test
        void testProfileConfiguresDdlAutoCreateDrop() {
            assertEquals("create-drop", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("true", environment.getProperty("spring.jpa.show-sql"));
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("prod")
    class ProdProfileTest {
        @Autowired
        private Environment environment;

        @Test
        void prodProfileConfiguresDdlAutoValidate() {
            assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));
            assertEquals("false", environment.getProperty("spring.jpa.show-sql"));
        }
    }
}
