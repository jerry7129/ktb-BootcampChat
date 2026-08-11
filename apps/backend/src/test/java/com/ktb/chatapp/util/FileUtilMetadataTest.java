package com.ktb.chatapp.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FileUtilMetadataTest {

    @Test
    void acceptsValidPresignedUploadMetadata() {
        assertDoesNotThrow(() -> FileUtil.validateFileMetadata("image.png", "image/png", 1024));
    }

    @Test
    void rejectsEmptyOrOversizedPresignedUploads() {
        assertThrows(RuntimeException.class,
                () -> FileUtil.validateFileMetadata("image.png", "image/png", 0));
        assertThrows(RuntimeException.class,
                () -> FileUtil.validateFileMetadata("image.png", "image/png", 5L * 1024 * 1024 + 1));
    }

    @Test
    void rejectsMismatchedExtensionAndMimeType() {
        assertThrows(RuntimeException.class,
                () -> FileUtil.validateFileMetadata("image.jpg", "image/png", 1024));
    }
}
