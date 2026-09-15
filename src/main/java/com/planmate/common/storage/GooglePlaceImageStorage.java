package com.planmate.common.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google Place 사진 저장소 (MinIO) — {@link ProfileImageStorage}와 같은 버킷을 재사용한다
 * (프로필 이미지와 별도 버킷을 쓸 이유가 아직 없어 설정 추가 없는 쪽을 택함, 필요해지면 재검토).
 *
 * 객체 키는 placeId로부터 결정적으로 정해진다("googleplace/{placeId}.webp") — 같은 장소는
 * 항상 같은 키를 쓰므로, 캐시 레코드 없이 존재 여부만 확인해도 "이미 처리된 장소인지" 판단할 수 있다.
 * project_ai/04_기능/place/06_키워드검색_API_인프라설계.md 4-2/4-2-1절.
 */
@Slf4j
@Component
public class GooglePlaceImageStorage {

    private final MinioClient minioClient;
    private final String bucket;
    private final String publicUrl;

    public GooglePlaceImageStorage(
            MinioClient minioClient,
            @Value("${minio.bucket}") String bucket,
            @Value("${minio.public-url}") String publicUrl
    ) {
        this.minioClient = minioClient;
        this.bucket = bucket;
        this.publicUrl = publicUrl;
    }

    /**
     * ProfileImageStorage가 이미 만들어둔 버킷일 수 있지만, 빈 초기화 순서가 보장되지 않으므로
     * 독립적으로도 안전하게 버킷+공개 읽기 정책을 확인한다(ProfileImageStorage.ensureBucket()과 동일 정책).
     */
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
            // 기동은 막지 않는다 — 업로드 시점에 실패로 드러난다.
            log.warn("MinIO 버킷 확인/생성 실패: {}", e.getMessage());
        }
    }

    /** 비공개 버킷에서도 서버 자격 증명으로 저장된 사진을 읽는다. */
    public byte[] read(String placeId) {
        try (var input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(buildObjectKey(placeId)).build())) {
            return input.readAllBytes();
        } catch (ErrorResponseException e) {
            if (!"NoSuchKey".equals(e.errorResponse().code()) && !"NotFound".equals(e.errorResponse().code())) {
                log.warn("MinIO 사진 읽기 실패 (placeId={}): {}", placeId, e.getMessage());
            }
            return null;
        } catch (Exception e) {
            log.warn("MinIO 사진 읽기 실패 (placeId={}): {}", placeId, e.getMessage());
            return null;
        }
    }

    /** 이미 업로드된 사진이 있으면 URL만 조립해서 반환(Google 재호출 없음), 없으면 null. */
    public String findExistingUrl(String placeId) {
        String objectKey = buildObjectKey(placeId);
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return buildPublicUrl(objectKey);
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code()) || "NotFound".equals(e.errorResponse().code())) {
                return null;
            }
            log.warn("MinIO 객체 존재 확인 실패 (key={}): {}", objectKey, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("MinIO 객체 존재 확인 실패 (key={}): {}", objectKey, e.getMessage());
            return null;
        }
    }

    /** WebP로 변환된 사진 바이트를 업로드하고 공개 URL을 반환한다. */
    public String upload(String placeId, byte[] webpBytes) {
        String objectKey = buildObjectKey(placeId);
        try (ByteArrayInputStream input = new ByteArrayInputStream(webpBytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(input, webpBytes.length, -1)
                    .contentType("image/webp")
                    .build());
        } catch (Exception e) {
            log.warn("Google Place 사진 업로드 실패 (placeId={}): {}", placeId, e.getMessage());
            return null;
        }
        return buildPublicUrl(objectKey);
    }

    private String buildObjectKey(String placeId) {
        return "googleplace/" + placeId + ".webp";
    }

    private String buildPublicUrl(String objectKey) {
        return publicUrl + "/" + bucket + "/" + objectKey;
    }
}
