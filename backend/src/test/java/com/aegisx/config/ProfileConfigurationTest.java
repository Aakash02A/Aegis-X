package com.aegisx.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileConfigurationTest {

    @Test
    void baseApplicationConfiguresFlywayAndJwt() {
        Properties props = loadYaml("application.yml");
        assertEquals("aegisx", props.getProperty("spring.application.name"));
        assertEquals("true", props.getProperty("spring.flyway.enabled"));
        assertNotNull(props.getProperty("jwt.secret"));
        assertNotNull(props.getProperty("jwt.expiration"));
    }

    @Test
    void devProfileConfiguresDdlAutoUpdate() {
        Properties props = loadYaml("application-dev.yml");
        assertEquals("update", props.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("true", props.getProperty("spring.jpa.show-sql"));
        assertTrue(props.getProperty("spring.datasource.url").contains("5432/aegisx"));
    }

    @Test
    void testProfileConfiguresDdlAutoCreateDrop() {
        Properties props = loadYaml("application-test.yml");
        assertEquals("create-drop", props.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("true", props.getProperty("spring.jpa.show-sql"));
    }

    @Test
    void prodProfileConfiguresDdlAutoValidate() {
        Properties props = loadYaml("application-prod.yml");
        assertEquals("validate", props.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", props.getProperty("spring.jpa.show-sql"));
    }

    private Properties loadYaml(String path) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource("src/main/resources/" + path));
        return factory.getObject();
    }
}
