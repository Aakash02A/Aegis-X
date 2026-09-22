package com.aegisx.storage.service;

import com.aegisx.document.entity.DocumentType;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

public interface StorageService {
    String store(MultipartFile file, UUID transactionId, DocumentType documentType) throws IOException;

    Resource load(String storagePath);

    void delete(String storagePath);

    boolean exists(String storagePath);
}
