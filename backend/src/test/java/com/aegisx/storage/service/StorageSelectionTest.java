package com.aegisx.storage.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class StorageSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StorageServices.class);

    @Test
    void selectingUnimplementedS3FailsDuringStartupWithClearMessage() {
        contextRunner.withPropertyValues("app.storage.type=s3").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(S3StorageService.UNSUPPORTED_MESSAGE);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({LocalStorageService.class, S3StorageService.class})
    static class StorageServices { }
}
