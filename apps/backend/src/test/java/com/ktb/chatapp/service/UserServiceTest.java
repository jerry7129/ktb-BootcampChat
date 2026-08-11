package com.ktb.chatapp.service;

import com.ktb.chatapp.dto.ProfileImageResponse;
import com.ktb.chatapp.dto.UpdateProfileRequest;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.storage.LocalStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    private static final String EMAIL = "user@example.com";

    @Mock
    private MongoOperations mongoOperations;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileService fileService;

    private UserService userService;

    @TempDir
    private Path uploadDir;

    /**
     * 실물 파일이 정말 지워지는지가 검증 대상이므로 스토리지는 목이 아니라 {@link LocalStorage} 실물을
     * @TempDir에 붙여 쓴다.
     */
    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository,
                fileService,
                new LocalStorage(uploadDir.toString()),
                mongoOperations
        );
        ReflectionTestUtils.setField(userService, "maxProfileImageSize", 5242880L);
    }

    private Path createOldProfileImageFile(String fileName) throws IOException {
        Path profilesDir = uploadDir.resolve("profiles");
        Files.createDirectories(profilesDir);
        Path oldFile = profilesDir.resolve(fileName);
        Files.writeString(oldFile, "old-image-bytes");
        return oldFile;
    }

    @Test
    @DisplayName("프로필 이미지 재업로드는 원자적으로 DB를 변경하고 기존 파일을 삭제한다")
    void uploadProfileImage_AtomicallyUpdatesAndDeletesOldFile() throws IOException {
        Path oldFile = createOldProfileImageFile("old.jpg");

        User previousUser = User.builder()
                .id("user-1")
                .email(EMAIL)
                .profileImage("profiles/old.jpg")
                .build();

        when(fileService.storeFile(any(), eq("profiles"))).thenReturn("profiles/new.jpg");

        when(mongoOperations.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(User.class)
        )).thenReturn(previousUser);

        MockMultipartFile file = new MockMultipartFile(
                "file", "new.jpg", "image/jpeg", "new-image-bytes".getBytes());

        ProfileImageResponse response = userService.uploadProfileImage(EMAIL, file);

        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(response.getImageUrl()).isEqualTo("/api/files/profiles/new.jpg");
    }

    @Test
    @DisplayName("프로필 이미지 삭제 시 기존 이미지 실물 파일을 삭제한다")
    void deleteProfileImage_DeletesProfileImageFile() throws IOException {
        Path oldFile = createOldProfileImageFile("old2.jpg");
        User user = User.builder()
                .id("user-1")
                .email(EMAIL)
                .profileImage("profiles/old2.jpg")
                .build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        userService.deleteProfileImage(EMAIL);

        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(user.getProfileImage()).isEmpty();
    }

    @Test
    @DisplayName("프로필 이름 변경은 findAndModify 한 번으로 처리한다")
    void updateUserProfile_UsesAtomicFindAndModify() {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setName("변경된 이름");

        User updatedUser = User.builder()
                .id("user-1")
                .email(EMAIL)
                .name("변경된 이름")
                .profileImage("")
                .build();

        when(mongoOperations.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(User.class)
        )).thenReturn(updatedUser);

        UserResponse response =
                userService.updateUserProfile(EMAIL, request);

        assertThat(response.getName()).isEqualTo("변경된 이름");
    }
}
