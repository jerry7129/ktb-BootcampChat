package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 기반 StoragePort 구현.
 *
 * LocalStorage는 서버 로컬 디스크(./uploads)에 저장하는데, 이 앱은 백엔드가
 * 여러 인스턴스로 배포된다 — A 인스턴스에 업로드한 파일을 B 인스턴스가 처리하는
 * 다운로드 요청은 자기 디스크에 그 파일이 없어서 항상 실패한다. S3는 인스턴스와
 * 무관한 공유 저장소라 이 문제가 없다.
 *
 * 활성화하려면 FILE_STORAGE_TYPE=s3, FILE_S3_BUCKET=<버킷명>을 설정해야 한다
 * (버킷은 미리 만들어져 있어야 하고, 이 인스턴스에 s3:PutObject/GetObject/
 * DeleteObject 권한이 있는 IAM 역할/자격증명이 필요하다).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3Storage implements StoragePort {

    private final S3Client s3Client;
    private final String bucket;
    private final String cloudFrontUrl;

    public S3Storage(
            @Value("${file.s3.bucket}") String bucket,
            @Value("${file.s3.region:ap-northeast-2}") String region,
            @Value("${file.cloudfront.url:https://d2nsun7j7a460i.cloudfront.net}") String cloudFrontUrl) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "file.storage.type=s3 인데 file.s3.bucket(FILE_S3_BUCKET)이 비어있습니다.");
        }
        this.bucket = bucket;
        this.cloudFrontUrl = cloudFrontUrl.replaceAll("/+$", "");
        this.s3Client = S3Client.builder().region(Region.of(region)).build();
    }

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromInputStream(content, size));
        } catch (S3Exception ex) {
            throw new RuntimeException("파일 저장에 실패했습니다: " + ex.getMessage(), ex);
        }
        return new StoredObject(key, size);
    }

    @Override
    public Optional<Resource> open(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            var responseStream = s3Client.getObject(request);
            return Optional.of(new InputStreamResource(responseStream));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (S3Exception ex) {
            log.error("S3에서 파일을 여는 데 실패했습니다: {}", key, ex);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (S3Exception ex) {
            throw new RuntimeException("파일 삭제에 실패했습니다: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<URI> offloadUrl(String key, Duration ttl, ContentDisposition disposition) {
        // 공개 CloudFront URL은 Content-Disposition 응답 헤더를 요청별로 바꿀 수 없다.
        // attachment 요청까지 리다이렉트하면 브라우저가 파일을 새 페이지에서 열어
        // 다운로드 이벤트와 원본 파일명이 사라지므로, 다운로드는 컨트롤러가 직접
        // 스트리밍하면서 attachment 헤더를 붙이게 한다.
        if (disposition != null && "attachment".equalsIgnoreCase(disposition.getType())) {
            return Optional.empty();
        }

        return publicUrl(key);
    }

    @Override
    public Optional<URI> publicUrl(String key) {
        String encodedKey = java.util.Arrays.stream(key.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
        return Optional.of(URI.create(cloudFrontUrl + "/" + encodedKey));
    }
}
