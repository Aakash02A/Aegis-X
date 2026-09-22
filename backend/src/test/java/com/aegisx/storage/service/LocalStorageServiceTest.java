package com.aegisx.storage.service;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStorageServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storeKeepsPathTraversalFilenameInsideTransactionDirectory() throws Exception {
        StorageProperties properties = new StorageProperties();
        properties.setRootPath(temporaryDirectory.toString());
        LocalStorageService storageService = new LocalStorageService(properties);
        UUID transactionId = UUID.randomUUID();

        String storagePath = storageService.store(
                new MockMultipartFile("file", "../../../invoice.pdf", "application/pdf", "content".getBytes()),
                transactionId,
                DocumentType.INVOICE);

        Path storedPath = Path.of(storagePath).toAbsolutePath().normalize();
        Path transactionDirectory = temporaryDirectory.resolve(transactionId.toString()).resolve("invoice").toAbsolutePath().normalize();
        assertTrue(storedPath.startsWith(transactionDirectory));
        assertTrue(Files.exists(storedPath));
        assertFalse(Files.exists(temporaryDirectory.resolve("invoice.pdf")));
    }
}