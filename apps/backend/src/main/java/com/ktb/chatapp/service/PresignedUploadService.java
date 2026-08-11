package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.util.FileUtil;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Service
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class PresignedUploadService {

    static final Duration URL_TTL = Duration.ofMinutes(10);
    static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final String bucket;
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final FileRepository fileRepository;

    public PresignedUploadService(
            @Value("${file.s3.bucket}") String bucket,
            @Value("${file.s3.region:ap-northeast-2}") String region,
            FileRepository fileRepository) {
        this.bucket = bucket;
        Region awsRegion = Region.of(region);
        this.s3Client = S3Client.builder().region(awsRegion).build();
        this.presigner = S3Presigner.builder().region(awsRegion).build();
        this.fileRepository = fileRepository;
    }

    public UploadTarget prepare(String originalFilename, String contentType, long size) {
        validateMetadata(originalFilename, contentType, size);

        String cleanedName = StringUtils.cleanPath(originalFilename);
        String filename = FileUtil.generateSafeFileName(cleanedName);
        String key = StorageKey.chat(filename);

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .cacheControl(CACHE_CONTROL)
                .build();
        PresignedPutObjectRequest signedRequest = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(URL_TTL)
                        .putObjectRequest(objectRequest)
                        .build());

        return new UploadTarget(
                signedRequest.url().toString(),
                filename,
                Map.of("Content-Type", contentType, "Cache-Control", CACHE_CONTROL),
                URL_TTL.toSeconds());
    }

    public File complete(
            String filename,
            String originalFilename,
            String contentType,
            long size,
            String uploaderId) {
        validateMetadata(originalFilename, contentType, size);
        if (filename == null || FileUtil.containsPathTraversal(filename) || filename.contains("/")) {
            throw new IllegalArgumentException("잘못된 파일명입니다.");
        }

        return fileRepository.findByFilename(filename)
                .map(existing -> {
                    if (!uploaderId.equals(existing.getUser())) {
                        throw new IllegalArgumentException("이미 등록된 파일입니다.");
                    }
                    return existing;
                })
                .orElseGet(() -> verifyAndSave(filename, originalFilename, contentType, size, uploaderId));
    }

    private File verifyAndSave(
            String filename,
            String originalFilename,
            String contentType,
            long size,
            String uploaderId) {
        String key = StorageKey.chat(filename);
        HeadObjectResponse object = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());

        if (object.contentLength() == null || object.contentLength() != size) {
            throw new IllegalArgumentException("업로드된 파일 크기가 요청과 다릅니다.");
        }
        if (object.contentType() == null || !object.contentType().equalsIgnoreCase(contentType)) {
            throw new IllegalArgumentException("업로드된 파일 형식이 요청과 다릅니다.");
        }

        File saved = fileRepository.save(File.builder()
                .filename(filename)
                .originalname(FileUtil.normalizeOriginalFilename(StringUtils.cleanPath(originalFilename)))
                .mimetype(contentType)
                .size(size)
                .path(key)
                .user(uploaderId)
                .uploadDate(LocalDateTime.now())
                .build());
        log.info("Presigned S3 파일 업로드 확정: {} (사용자: {})", key, uploaderId);
        return saved;
    }

    private void validateMetadata(String originalFilename, String contentType, long size) {
        try {
            FileUtil.validateFileMetadata(originalFilename, contentType, size);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    @PreDestroy
    void closeAwsClients() {
        presigner.close();
        s3Client.close();
    }

    public record UploadTarget(
            String url,
            String filename,
            Map<String, String> headers,
            long expiresInSeconds) {}
}
