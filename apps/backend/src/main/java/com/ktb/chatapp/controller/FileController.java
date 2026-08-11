package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.dto.CompleteUploadRequest;
import com.ktb.chatapp.dto.PresignedUploadRequest;
import com.ktb.chatapp.model.File;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.FileAccess;
import com.ktb.chatapp.service.FileAccessService;
import com.ktb.chatapp.service.FileService;
import com.ktb.chatapp.service.FileUploadResult;
import com.ktb.chatapp.service.PreviewNotSupportedException;
import com.ktb.chatapp.service.PresignedUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "파일 (Files)", description = "파일 업로드 및 다운로드 API")
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;
    private final FileAccessService fileAccessService;
    private final UserRepository userRepository;
    private final ObjectProvider<PresignedUploadService> presignedUploadServiceProvider;

    /**
     * 파일 업로드
     */
    @Operation(summary = "파일 업로드", description = "파일을 업로드합니다. 최대 50MB까지 가능합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "파일 업로드 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 파일",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "413", description = "파일 크기 초과",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PostMapping("/upload")
    public ResponseEntity<?> uploadFile(
            @Parameter(description = "업로드할 파일") @RequestParam("file") MultipartFile file,
            Principal principal) {
        try {
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + principal.getName()));

            FileUploadResult result = fileService.uploadFile(file, user.getId());

            if (result.isSuccess()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "파일 업로드 성공");
                
                Map<String, Object> fileData = new HashMap<>();
                fileData.put("_id", result.getFile().getId());
                fileData.put("filename", result.getFile().getFilename());
                fileData.put("originalname", result.getFile().getOriginalname());
                fileData.put("mimetype", result.getFile().getMimetype());
                fileData.put("size", result.getFile().getSize());
                fileData.put("uploadDate", result.getFile().getUploadDate());
                
                response.put("file", fileData);

                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "파일 업로드에 실패했습니다.");
                return ResponseEntity.status(500).body(errorResponse);
            }

        } catch (Exception e) {
            log.error("파일 업로드 중 에러 발생", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "파일 업로드 중 오류가 발생했습니다.");
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @PostMapping("/uploads/presign")
    public ResponseEntity<?> createPresignedUpload(
            @RequestBody PresignedUploadRequest request,
            Principal principal) {
        try {
            requireUser(principal);
            PresignedUploadService service = requirePresignedUploadService();
            PresignedUploadService.UploadTarget target = service.prepare(
                    request.originalname(), request.mimetype(), request.size());
            // /api/files/upload을 기다리는 기존 클라이언트도 presign 응답에서
            // 최종 파일 식별자를 안전하게 읽을 수 있도록 동일 filename을 노출한다.
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "upload", target,
                    "file", Map.of(
                            "filename", target.filename(),
                            "mimetype", request.mimetype())
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Presigned 업로드 URL 발급 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "업로드 URL 발급에 실패했습니다."));
        }
    }

    @PostMapping("/uploads/complete")
    public ResponseEntity<?> completePresignedUpload(
            @RequestBody CompleteUploadRequest request,
            Principal principal) {
        long startedAt = System.nanoTime();
        try {
            User user = requireUser(principal);
            File file = requirePresignedUploadService().complete(
                    request.filename(),
                    request.originalname(),
                    request.mimetype(),
                    request.size(),
                    user.getId());
            return ResponseEntity.ok(uploadSuccessResponse(file));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Presigned 업로드 확정 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "업로드 확인에 실패했습니다."));
        } finally {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("Presigned complete 전체 처리시간: filename={}, elapsedMs={}",
                    request.filename(), elapsedMs);
        }
    }

    private PresignedUploadService requirePresignedUploadService() {
        PresignedUploadService service = presignedUploadServiceProvider.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("Presigned upload is available only when S3 storage is enabled.");
        }
        return service;
    }

    private User requireUser(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + principal.getName()));
    }

    private Map<String, Object> uploadSuccessResponse(File file) {
        Map<String, Object> fileData = new HashMap<>();
        fileData.put("_id", file.getId());
        fileData.put("filename", file.getFilename());
        fileData.put("originalname", file.getOriginalname());
        fileData.put("mimetype", file.getMimetype());
        fileData.put("size", file.getSize());
        fileData.put("uploadDate", file.getUploadDate());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "파일 업로드 성공");
        response.put("file", fileData);
        return response;
    }

    /**
     * 보안이 강화된 파일 다운로드
     */
    @Operation(summary = "파일 다운로드", description = "업로드된 파일을 다운로드합니다. 본인이 업로드한 파일만 다운로드 가능합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "파일 다운로드 성공"),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "403", description = "권한 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "파일을 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<?> downloadFile(
            @Parameter(description = "다운로드할 파일명") @PathVariable String filename,
            HttpServletRequest request,
            Principal principal) {
        try {
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + principal.getName()));

            return switch (fileAccessService.forDownload(filename, user.getId())) {
                case FileAccess.Stream stream -> attachmentResponse(stream);
                case FileAccess.Redirect redirect -> redirectResponse(redirect);
            };

        } catch (Exception e) {
            log.error("파일 다운로드 중 에러 발생: {}", filename, e);
            return handleFileError(e);
        }
    }

    private ResponseEntity<?> attachmentResponse(FileAccess.Stream stream) {
        String contentDisposition = String.format(
                "attachment; filename*=UTF-8''%s",
                encodeFilename(stream.originalname())
        );

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stream.contentType()))
                .contentLength(stream.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache, no-store, must-revalidate")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Content-Disposition")
                .body(stream.resource());
    }

    private ResponseEntity<?> redirectResponse(FileAccess.Redirect redirect) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(redirect.location())
                .build();
    }

    private String encodeFilename(String originalFilename) {
        return URLEncoder.encode(originalFilename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
    }

    private ResponseEntity<?> handleFileError(Exception e) {
        String errorMessage = e.getMessage();
        int statusCode = 500;
        String responseMessage = "파일 처리 중 오류가 발생했습니다.";

        if (errorMessage != null) {
            if (errorMessage.contains("잘못된 파일명") || errorMessage.contains("Invalid filename")) {
                statusCode = 400;
                responseMessage = "잘못된 파일명입니다.";
            } else if (errorMessage.contains("인증") || errorMessage.contains("Authentication")) {
                statusCode = 401;
                responseMessage = "인증이 필요합니다.";
            } else if (errorMessage.contains("잘못된 파일 경로") || errorMessage.contains("Invalid file path")) {
                statusCode = 400;
                responseMessage = "잘못된 파일 경로입니다.";
            } else if (errorMessage.contains("찾을 수 없습니다") || errorMessage.contains("not found")) {
                statusCode = 404;
                responseMessage = "파일을 찾을 수 없습니다.";
            } else if (errorMessage.contains("메시지를 찾을 수 없습니다")) {
                statusCode = 404;
                responseMessage = "파일 메시지를 찾을 수 없습니다.";
            } else if (errorMessage.contains("권한") || errorMessage.contains("Unauthorized")) {
                statusCode = 403;
                responseMessage = "파일에 접근할 권한이 없습니다.";
            }
        }

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("message", responseMessage);

        return ResponseEntity.status(statusCode).body(errorResponse);
    }

    @GetMapping("/view/{filename:.+}")
    public ResponseEntity<?> viewFile(
            @PathVariable String filename,
            HttpServletRequest request,
            Principal principal) {
        try {
            FileAccess access;
            if (principal == null) {
                access = fileAccessService.forPublicView(filename);
            } else {
                User user = userRepository.findByEmail(principal.getName())
                        .orElseThrow(() -> new UsernameNotFoundException("User not found: " + principal.getName()));
                access = fileAccessService.forView(filename, user.getId());
            }

            return switch (access) {
                case FileAccess.Stream stream -> inlineResponse(stream);
                case FileAccess.Redirect redirect -> redirectResponse(redirect);
            };

        } catch (PreviewNotSupportedException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(415).body(errorResponse);

        } catch (Exception e) {
            log.error("파일 미리보기 중 에러 발생: {}", filename, e);
            return handleFileError(e);
        }
    }

    private ResponseEntity<?> inlineResponse(FileAccess.Stream stream) {
        String contentDisposition = String.format(
                "inline; filename=\"%s\"; filename*=UTF-8''%s",
                stream.originalname(),
                encodeFilename(stream.originalname())
        );

        // 파일명이 업로드마다 타임스탬프+랜덤값으로 유니크하게 생성되므로
        // (FileUtil.generateSafeFileName), 같은 파일명이면 내용이 절대 바뀌지 않는다 -> 영구 캐싱 가능
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stream.contentType()))
                .contentLength(stream.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .body(stream.resource());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFile(@PathVariable String id, Principal principal) {
        try {
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found: " + principal.getName()));

            boolean deleted = fileService.deleteFile(id, user.getId());

            if (deleted) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "파일이 삭제되었습니다.");
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "파일 삭제에 실패했습니다.");
                return ResponseEntity.status(400).body(errorResponse);
            }

        } catch (RuntimeException e) {
            log.error("파일 삭제 중 에러 발생: {}", id, e);
            String errorMessage = e.getMessage();
            
            if (errorMessage != null && errorMessage.contains("찾을 수 없습니다")) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "파일을 찾을 수 없습니다.");
                return ResponseEntity.status(404).body(errorResponse);
            } else if (errorMessage != null && errorMessage.contains("권한")) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "파일을 삭제할 권한이 없습니다.");
                return ResponseEntity.status(403).body(errorResponse);
            }
            
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "파일 삭제 중 오류가 발생했습니다.");
            errorResponse.put("error", errorMessage);
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}
