package com.planmate.common.externalAPI;

// WeatherService가 내부적으로 잡아 계절 평균 폴백으로 전환하는 용도의 신호 예외.
// GlobalExceptionHandler로 노출되는 도메인 예외(PlanMateException 계열)가 아니다 —
// project_ai/04_기능/날씨/A_추천_조회.md 9절(폴백 정책 유지 결정) 참고.
public class OpenMeteoCallFailedException extends RuntimeException {

    public OpenMeteoCallFailedException(String message) {
        super(message);
    }

    public OpenMeteoCallFailedException(Throwable cause) {
        super(cause);
    }
}
