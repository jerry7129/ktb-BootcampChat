package com.ktb.chatapp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "files")
public class File {

    @Id
    private String id;

    // 파일 다운로드/미리보기(FileAccessService.authorize)가 매 요청마다 이 필드로 조회한다.
    // 인덱스가 없으면 매 요청이 files 컬렉션 풀스캔이 된다. generateSafeFileName이
    // 타임스탬프+16자리 랜덤 hex로 만들어 실질적으로 유일하므로 unique로 건다.
    @Indexed(unique = true)
    private String filename;

    private String originalname;

    private String mimetype;

    private long size;

    private String path;

    @Field("user")
    private String user;

    @Field("uploadDate")
    @CreatedDate
    private LocalDateTime uploadDate;

    /**
     * 미리보기 지원 여부 확인
     */
    public boolean isPreviewable() {
        List<String> previewableTypes = Arrays.asList(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "video/mp4", "video/webm",
            "audio/mpeg", "audio/wav",
            "application/pdf"
        );
        return previewableTypes.contains(this.mimetype);
    }

    /**
     * Content-Disposition 헤더 생성
     */
    public String getContentDisposition(String type) {
        String encodedFilename = URLEncoder.encode(this.originalname, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        
        return String.format(
            "%s; filename=\"%s\"; filename*=UTF-8''%s",
            type,
            this.originalname,
            encodedFilename
        );
    }

    /**
     * 파일 URL 생성
     */
    public String getFileUrl(String type) {
        return String.format("/api/files/%s/%s",
            type,
            URLEncoder.encode(this.filename, StandardCharsets.UTF_8));
    }
}