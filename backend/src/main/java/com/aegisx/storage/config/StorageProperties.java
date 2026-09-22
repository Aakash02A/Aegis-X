package com.aegisx.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {
    private String rootPath = "./storage";
    private List<String> allowedContentTypes = List.of("application/pdf", "image/jpeg", "image/png");
    private int maxFileSizeMb = 20;
}
