package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.ProfileImageResponse;
import com.ktb.chatapp.dto.UpdateProfileRequest;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final FileService fileService;
    private final StoragePort storagePort;
    private final MongoOperations mongoOperations;

    @Value("${app.profile.image.max-size:5242880}") // 5MB
    private long maxProfileImageSize;

    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp"
    );

    /**
     * 현재 사용자 프로필 조회
     * @param email 사용자 이메일
     */
    public UserResponse getCurrentUserProfile(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        return UserResponse.from(user);
    }

    /**
     * 사용자 프로필 업데이트
     * @param email 사용자 이메일
     */
    public UserResponse updateUserProfile(
            String email,
            UpdateProfileRequest request
    ) {
        Query query = Query.query(
                Criteria.where("email").is(email.toLowerCase())
        );

        Update update = new Update()
                .set("name", request.getName())
                .set("updatedAt", LocalDateTime.now());

        FindAndModifyOptions options =
                FindAndModifyOptions.options().returnNew(true);

        User updatedUser = mongoOperations.findAndModify(
                query,
                update,
                options,
                User.class
        );

        if (updatedUser == null) {
            throw new UsernameNotFoundException(
                    "사용자를 찾을 수 없습니다."
            );
        }

        log.info(
                "사용자 프로필 업데이트 완료 - ID: {}, Name: {}",
                updatedUser.getId(),
                request.getName()
        );

        return UserResponse.from(updatedUser);
    }

    /**
     * 프로필 이미지 업로드 (보안 강화)
     * @param email 사용자 이메일
     */
    public ProfileImageResponse uploadProfileImage(
            String email,
            MultipartFile file
    ) {
        // DB나 스토리지 작업 전에 잘못된 파일을 먼저 거부
        validateProfileImageFile(file);

        // 새 파일을 먼저 저장한다.
        String profileImageKey =
                fileService.storeFile(file, "profiles");

        Query query = Query.query(
                Criteria.where("email").is(email.toLowerCase())
        );

        Update update = new Update()
                .set("profileImage", profileImageKey)
                .set("updatedAt", LocalDateTime.now());

        // 기존 이미지 key가 필요하므로 변경 전 사용자 문서를 반환받는다.
        FindAndModifyOptions options =
                FindAndModifyOptions.options().returnNew(false);

        User previousUser;

        try {
            previousUser = mongoOperations.findAndModify(
                    query,
                    update,
                    options,
                    User.class
            );
        } catch (RuntimeException e) {
            // DB 변경 실패 시 방금 저장한 새 파일을 정리한다.
            deleteProfileImageFile(profileImageKey);
            throw e;
        }

        if (previousUser == null) {
            // 사용자가 없으면 새 파일을 남기지 않는다.
            deleteProfileImageFile(profileImageKey);
            throw new UsernameNotFoundException(
                    "사용자를 찾을 수 없습니다."
            );
        }

        String oldProfileImageKey =
                previousUser.getProfileImage();

        // DB가 새 파일을 가리키도록 변경된 후 기존 파일을 삭제한다.
        if (oldProfileImageKey != null
                && !oldProfileImageKey.isEmpty()
                && !oldProfileImageKey.equals(profileImageKey)) {
            deleteProfileImageFile(oldProfileImageKey);
        }

        log.info(
                "프로필 이미지 업로드 완료 - User ID: {}, Key: {}",
                previousUser.getId(),
                profileImageKey
        );

        return ProfileImageResponse.updated(profileImageKey);
    }

    /**
     * 특정 사용자 프로필 조회
     */
    public UserResponse getUserProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        return UserResponse.from(user);
    }

    /**
     * 프로필 이미지 파일 유효성 검증
     */
    private void validateProfileImageFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("이미지가 제공되지 않았습니다.");
        }

        // 파일 크기 검증
        if (file.getSize() > maxProfileImageSize) {
            throw new IllegalArgumentException("파일 크기는 5MB를 초과할 수 없습니다.");
        }

        // Content-Type 검증
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }

        // 파일 확장자 검증 (보안을 위해 화이트리스트 유지)
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }

        // FileSecurityUtil의 static 메서드 호출
        String extension = FileUtil.getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.");
        }
    }

    /**
     * 기존 프로필 이미지 실물 삭제. 저장값이 key이므로 스토리지에 그대로 넘긴다 — 삭제 실패가 프로필 갱신
     * 자체를 막지는 않는다.
     */
    private void deleteProfileImageFile(String profileImageKey) {
        try {
            storagePort.delete(profileImageKey);
            log.info(
                    "프로필 이미지 파일 삭제 완료: {}",
                    profileImageKey
            );
        } catch (RuntimeException e) {
            log.warn(
                    "프로필 이미지 파일 삭제 실패 - Key: {}, Error: {}",
                    profileImageKey,
                    e.getMessage()
            );
        }
    }

    /**
     * 프로필 이미지 삭제
     * @param email 사용자 이메일
     */
    public void deleteProfileImage(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        if (user.getProfileImage() != null && !user.getProfileImage().isEmpty()) {
            deleteProfileImageFile(user.getProfileImage());
            user.setProfileImage("");
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
            log.info("프로필 이미지 삭제 완료 - User ID: {}", user.getId());
        }
    }

    /**
     * 회원 탈퇴 처리
     * @param email 사용자 이메일
     */
    public void deleteUserAccount(String email) {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        if (user.getProfileImage() != null && !user.getProfileImage().isEmpty()) {
            deleteProfileImageFile(user.getProfileImage());
        }

        userRepository.delete(user);
        log.info("회원 탈퇴 완료 - User ID: {}", user.getId());
    }
}
