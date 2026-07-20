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

    // 사용자
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER_001", "사용자를 찾을 수 없습니다."),

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
    TOUR_API_CALL_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "PLACE_001", "외부 관광정보 조회에 실패했습니다."),
    ;

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
