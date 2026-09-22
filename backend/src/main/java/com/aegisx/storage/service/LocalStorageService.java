package com.aegisx.storage.service;

import com.aegisx.document.entity.DocumentType;
import com.aegisx.storage.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private final StorageProperties storageProperties;

    @Override
    public String store(MultipartFile file, UUID transactionId, DocumentType documentType) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }

        Path rootPath = Paths.get(storageProperties.getRootPath()).toAbsolutePath().normalize();
        Path transactionPath = rootPath.resolve(transactionId.toString()).resolve(documentType.name().toLowerCase());
        Files.createDirectories(transactionPath);

        String originalName = file.getOriginalFilename() == null ? "upload" : Paths.get(file.getOriginalFilename()).getFileName().toString();
        String sanitizedName = UUID.randomUUID() + "_" + originalName;
        Path target = transactionPath.resolve(sanitizedName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        return target.toString();
    }

    @Override
    public Resource load(String storagePath) {
        try {
            Path filePath = Paths.get(storagePath);
            Resource resource = new UrlResource(filePath.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            }
            throw new IllegalArgumentException("Document not found at storage path: " + storagePath);
        } catch (MalformedURLException ex) {
            throw new IllegalArgumentException("Invalid storage path: " + storagePath, ex);
        }
    }

    @Override
    public void delete(String storagePath) {
        try {
            Path path = Paths.get(storagePath);
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to delete file at path: " + storagePath, ex);
        }
    }

    @Override
    public boolean exists(String storagePath) {
        return storagePath != null && Files.exists(Paths.get(storagePath));
    }
}
