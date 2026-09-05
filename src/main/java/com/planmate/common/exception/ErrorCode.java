package com.planmate.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_002", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_003", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_004", "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_005", "서버 오류가 발생했습니다."),

    // 인증
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_001", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_002", "만료된 토큰입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_003", "이메일 또는 비밀번호가 올바르지 않습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "AUTH_004", "이미 가입된 이메일입니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "AUTH_005", "이미 사용 중인 닉네임입니다."),
    VERIFICATION_CODE_NOT_FOUND(HttpStatus.UNAUTHORIZED, "AUTH_006", "인증 요청을 찾을 수 없거나 만료되었습니다."),
    INVALID_VERIFICATION_CODE(HttpStatus.UNAUTHORIZED, "AUTH_007", "인증코드가 일치하지 않습니다."),
    PASSWORD_MISMATCH(HttpStatus.UNAUTHORIZED, "AUTH_008", "현재 비밀번호가 일치하지 않습니다."),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "AUTH_009", "정지된 계정입니다."),

    // 사용자
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_001", "사용자를 찾을 수 없습니다."),
    PROFILE_PRIVATE(HttpStatus.FORBIDDEN, "USER_002", "비공개 프로필입니다."),
    PROFILE_IMAGE_UNSUPPORTED_TYPE(HttpStatus.BAD_REQUEST, "USER_003", "지원하지 않는 이미지 형식입니다. (jpeg, png, gif, webp)"),
    PROFILE_IMAGE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "USER_004", "이미지 크기는 5MB를 넘을 수 없습니다."),
    PROFILE_IMAGE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "USER_005", "이미지 업로드에 실패했습니다."),

    // 선호 테마
    PREFERRED_THEME_NOT_FOUND(HttpStatus.NOT_FOUND, "THEME_001", "존재하지 않는 선호 테마입니다."),
    PREFERRED_THEME_CATEGORY_MISMATCH(HttpStatus.BAD_REQUEST, "THEME_002", "선호 테마의 카테고리가 요청한 카테고리와 일치하지 않습니다."),

    // 플랜
    PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "PLAN_001", "플랜을 찾을 수 없습니다."),
    PLAN_ACCESS_DENIED(HttpStatus.FORBIDDEN, "PLAN_002", "플랜에 대한 접근 권한이 없습니다."),
    PLAN_DUPLICATE_DATE(HttpStatus.BAD_REQUEST, "PLAN_003", "중복된 날짜가 포함되어 있습니다."),
    PLAN_DUPLICATE_BLOCK_TIME(HttpStatus.BAD_REQUEST, "PLAN_004", "동일한 일자·시간에 중복된 블록이 있습니다."),
    PLAN_INVALID_BLOCK_DATE(HttpStatus.BAD_REQUEST, "PLAN_005", "블록의 날짜가 일정표 목록에 없습니다."),
    PLAN_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "PLAN_006", "편집 권한이 존재하지 않습니다."),

    // 여행지
    DESTINATION_NOT_FOUND(HttpStatus.NOT_FOUND, "DEST_001", "존재하지 않는 여행지입니다."),

    // 체크리스트
    CHECKLIST_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "CHECKLIST_001", "체크리스트 항목을 찾을 수 없습니다."),
    CHECKLIST_ITEM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "CHECKLIST_002", "본인 소유가 아닌 체크리스트 항목입니다."),

    // 협업 요청
    COLLABORATION_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "COLLAB_001", "협업 요청을 찾을 수 없습니다."),
    COLLABORATION_REQUEST_ALREADY_MEMBER(HttpStatus.CONFLICT, "COLLAB_002", "이미 플랜에 대한 접근 권한이 있는 사용자입니다."),
    COLLABORATION_REQUEST_DUPLICATE_PENDING(HttpStatus.CONFLICT, "COLLAB_003", "이미 처리 대기 중인 요청이 있습니다."),
    COLLABORATION_REQUEST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "COLLAB_004", "해당 요청을 처리할 권한이 없습니다."),
    COLLABORATION_REQUEST_ALREADY_PROCESSED(HttpStatus.CONFLICT, "COLLAB_005", "이미 처리된 요청입니다."),

    // OAuth
    OAUTH_UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "OAUTH_001", "지원하지 않는 SNS 로그인입니다."),
    OAUTH_SIGNUP_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "OAUTH_002", "가입 세션이 만료되었거나 존재하지 않습니다."),
    OAUTH_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "OAUTH_003", "이메일 정보가 필요합니다."),
    OAUTH_ALREADY_REGISTERED(HttpStatus.CONFLICT, "OAUTH_004", "이미 가입된 계정입니다."),

    // 추천 장소
    KAKAO_LOCAL_CALL_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_002", "장소 검색에 실패했습니다. 잠시 후 다시 시도해주세요."),
    // 키가 없으면 카카오가 401을 주는데, 그대로 두면 "검색이 안 된다"로만 보여 원인을 못 찾는다
    KAKAO_LOCAL_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_003", "장소 검색이 설정되지 않았습니다. 서버에 api.kakao.rest-key가 필요합니다."),
    GOOGLE_PLACES_CALL_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_004", "외부 장소 검색에 실패했습니다."),
    // 추천장소(②)·장소상세(③) 공용 — tourdata 캐시 DB 조회 실패. 05_장소상세_API_설계.md §15-1로 문구 일반화.
    PLACE_RECOMMENDATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_005", "장소 정보 조회에 일시적으로 실패했습니다."),
    PLACE_DETAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "PLACE_006", "장소 상세 정보를 찾을 수 없습니다."),

    // 챗봇 (planmate-ai 연동) — project_ai/04_기능/ai/06_챗봇_연동_설계.md §5-4
    CHATBOT_AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "CHATBOT_001", "AI 서버가 일시적으로 응답하지 못했어요. 잠시 후 다시 시도해주세요."),
    CHATBOT_AI_MISCONFIGURED(HttpStatus.INTERNAL_SERVER_ERROR, "CHATBOT_002", "AI 연동 설정 오류입니다."),
    // [반영] reconcile (④-2) — project_ai/04_기능/ai/07_반영_reconcile_설계.md §9-1
    CHATBOT_APPLY_SESSION_INVALID(HttpStatus.CONFLICT, "CHATBOT_003", "반영 세션이 유효하지 않습니다. 페이지를 새로고침한 뒤 다시 시도해주세요."),
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
