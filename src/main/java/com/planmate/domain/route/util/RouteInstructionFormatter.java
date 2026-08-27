package com.planmate.domain.route.util;

import com.planmate.common.externalAPI.OsrmRouting.RouteStep;
import java.util.Map;

/**
 * OSRM maneuver 코드({@code type} + {@code modifier})를 한국어 안내 문구로 바꾼다.
 * OSRM은 안내 문장을 내려주지 않고 코드만 주므로(문구 생성은 osrm-text-instructions 같은
 * 클라이언트 라이브러리의 몫이다) 서버에서 직접 만든다.
 *
 * 도로명({@code name})은 OSM 데이터에 의존해 국내에서는 비어 있는 구간이 흔하다.
 * 비면 도로명 없이 방향만 안내한다.
 */
public final class RouteInstructionFormatter {

    private static final Map<String, String> DIRECTIONS = Map.ofEntries(
            Map.entry("uturn", "유턴"),
            Map.entry("sharp right", "우측으로 크게 회전"),
            Map.entry("right", "우회전"),
            Map.entry("slight right", "우측으로 약간 회전"),
            Map.entry("straight", "직진"),
            Map.entry("slight left", "좌측으로 약간 회전"),
            Map.entry("left", "좌회전"),
            Map.entry("sharp left", "좌측으로 크게 회전"));

    private RouteInstructionFormatter() {
    }

    public static String format(RouteStep step) {
        if (step == null) {
            return null;
        }
        String type = step.maneuverType();
        if (type == null) {
            return null;
        }

        String road = (step.name() == null || step.name().isBlank()) ? null : step.name();
        // Map.of()는 null 키 조회에도 NPE를 던진다 — modifier는 depart 등에서 흔히 비어 있다.
        String direction = step.maneuverModifier() == null ? null : DIRECTIONS.get(step.maneuverModifier());

        return switch (type) {
            case "depart" -> road != null ? road + "에서 출발" : "출발";
            case "arrive" -> arriveText(step.maneuverModifier());
            case "turn" -> withRoad(direction != null ? direction : "회전", road);
            case "new name" -> road != null ? road + " 방면으로 계속 이동" : "계속 이동";
            case "continue" -> withRoad(direction != null && !"straight".equals(step.maneuverModifier())
                    ? direction + " 후 계속 이동" : "계속 이동", road);
            case "merge" -> withRoad(direction != null ? direction + "해 합류" : "합류", road);
            case "on ramp" -> withRoad("진입로로 진입", road);
            case "off ramp" -> withRoad("출구로 진출", road);
            case "fork" -> withRoad(direction != null ? "갈림길에서 " + direction : "갈림길에서 진행", road);
            case "end of road" -> withRoad(direction != null ? "도로 끝에서 " + direction : "도로 끝에서 진행", road);
            case "roundabout", "rotary" -> roundaboutText(step.exit(), road);
            case "roundabout turn" -> withRoad(direction != null ? "회전교차로에서 " + direction : "회전교차로에서 진행", road);
            case "exit roundabout", "exit rotary" -> withRoad("회전교차로에서 진출", road);
            case "notification" -> withRoad("계속 이동", road);
            default -> withRoad(direction != null ? direction : "계속 이동", road);
        };
    }

    private static String arriveText(String modifier) {
        if ("left".equals(modifier)) {
            return "목적지가 왼쪽에 있습니다";
        }
        if ("right".equals(modifier)) {
            return "목적지가 오른쪽에 있습니다";
        }
        return "목적지에 도착";
    }

    private static String roundaboutText(Integer exit, String road) {
        String base = exit != null && exit > 0
                ? "회전교차로에서 " + exit + "번째 출구로 진출"
                : "회전교차로에서 진출";
        return withRoad(base, road);
    }

    /** 도로명이 있으면 "OO로 방면으로 " 접두를 붙인다. */
    private static String withRoad(String action, String road) {
        return road != null ? road + " 방면으로 " + action : action;
    }
}
