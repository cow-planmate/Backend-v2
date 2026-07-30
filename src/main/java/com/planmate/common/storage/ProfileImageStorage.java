package com.planmate.common.storage;

import com.planmate.common.exception.ErrorCode;
import com.planmate.common.exception.PlanMateException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

/**
 * 프로필 이미지 파일 저장소 (MinIO).
 *
 * 객체 키는 사용자당 하나(profile/{userId}/{uuid}.{ext})로 두고, 교체 시 이전 파일을 지운다.
 * 키에 UUID를 넣는 이유는 같은 경로로 덮어쓰면 CDN·브라우저 캐시 때문에 이전 이미지가
 * 계속 보이기 때문이다.
 */
@Slf4j
@Component
public class ProfileImageStorage {

    private final MinioClient minioClient;
    private final String bucket;
    private final String publicUrl;

    public ProfileImageStorage(
            MinioClient minioClient,
            @Value("${minio.bucket}") String bucket,
            @Value("${minio.public-url}") String publicUrl
    ) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.publicUrl = publicUrl;
    }

    /** 버킷이 없으면 생성하고 익명 읽기 정책을 설정한다 (URL 직접 서빙). */
    @PostConstruct
    void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(bucket)
                        .config("""
                                {
                                  "Version": "2012-10-17",
                                  "Statement": [{
                                    "Effect": "Allow",
                                    "Principal": {"AWS": ["*"]},
                                    "Action": ["s3:GetObject"],
                                    "Resource": ["arn:aws:s3:::%s/*"]
                                  }]
                                }
                                """.formatted(bucket))
                        .build());
                log.info("MinIO 버킷 생성 및 공개 읽기 정책 설정: {}", bucket);
            }
        } catch (Exception e) {
            // 기동은 막지 않는다 — 업로드 시점에 실패로 드러남
            log.warn("MinIO 버킷 확인/생성 실패: {}", e.getMessage());
        }
    }

    /** 파일 검증은 호출부(ProfileImageValidator)에서 끝났다고 가정한다. */
    public String upload(UUID userId, MultipartFile file) {
        String objectKey = buildObjectKey(userId, file.getOriginalFilename());
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            log.error("프로필 이미지 업로드 실패 (key={}): {}", objectKey, e.getMessage());
            throw new PlanMateException(ErrorCode.PROFILE_IMAGE_UPLOAD_FAILED);
        }

        return publicUrl + "/" + bucket + "/" + objectKey;
    }

    /**
     * 이전 이미지 정리 — 실패해도 예외를 던지지 않는다(best-effort).
     * 이미지 교체의 본질은 새 URL을 저장하는 것이고, 옛 파일이 남는 것은 저장소 낭비일 뿐
     * 사용자에게 보이는 실패로 만들 이유가 없다.
     */
    public void delete(String url) {
        String objectKey = extractObjectKey(url);
        if (objectKey == null) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.warn("프로필 이미지 삭제 실패 (key={}): {}", objectKey, e.getMessage());
        }
    }

    // 공개 URL(publicUrl/bucket/objectKey)에서 objectKey를 뽑는다. 우리 버킷 URL이 아니면 null.
    private String extractObjectKey(String url) {
        if (url == null) {
            return null;
        }
        String prefix = publicUrl + "/" + bucket + "/";
        return url.startsWith(prefix) ? url.substring(prefix.length()) : null;
    }

    private String buildObjectKey(UUID userId, String originalFilename) {
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase();
        }
        return "profile/" + userId + "/" + UUID.randomUUID() + extension;
    }
}
