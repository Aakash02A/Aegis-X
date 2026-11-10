package com.aegisx.storage.service;

import com.aegisx.document.entity.DocumentType;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Fails startup when S3 is selected because no S3 client is implemented in this application.
 * This prevents a deployment from starting successfully and failing only when evidence is used.
 */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3")
public class S3StorageService implements StorageService {

    public static final String UNSUPPORTED_MESSAGE =
            "STORAGE_TYPE=s3 is selected, but S3 storage is not implemented. "
                    + "Configure STORAGE_TYPE=local or add a supported S3 adapter.";

    @PostConstruct
    void failFastWhenSelected() {
        throw new IllegalStateException(UNSUPPORTED_MESSAGE);
    }

    @Override
    public String store(MultipartFile file, UUID transactionId, DocumentType documentType) throws IOException {
        throw unsupported();
    }

    @Override
    public Resource load(String storagePath) {
        throw unsupported();
    }

    @Override
    public void delete(String storagePath) {
        throw unsupported();
    }

    @Override
    public boolean exists(String storagePath) {
        throw unsupported();
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException(UNSUPPORTED_MESSAGE);
    }
}
