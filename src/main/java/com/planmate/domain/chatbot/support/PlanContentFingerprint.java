package com.planmate.domain.chatbot.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.planmate.domain.plan.dto.GetCompletePlanResponse;
import com.planmate.domain.plan.service.PlanSnapshotReader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 플랜 상태(스냅샷)를 결정적으로 직렬화해 SHA-256 지문 하나로 만든다 — [반영] 시점의 충돌 감지용.
 *
 * <p>{@link GetCompletePlanResponse}와 {@link PlanSnapshotReader.PlanSnapshot}은 셰이프가 동일하다
 * ({@code PlanService.getCompletePlan}이 후자를 그대로 전자로 감싸 반환) — 같은 내용이면 어느 타입으로
 * 넘겨도 같은 지문이 나온다.
 *
 * <p>설계: project_ai/04_기능/ai/07_반영_reconcile_설계.md §10-1-1.
 */
public final class PlanContentFingerprint {

    private PlanContentFingerprint() {
    }

    public static String of(ObjectMapper objectMapper, GetCompletePlanResponse response) {
        return of(objectMapper, response.planFrame(), response.timetables(), response.placeBlocks());
    }

    public static String of(ObjectMapper objectMapper, PlanSnapshotReader.PlanSnapshot snapshot) {
        return of(objectMapper, snapshot.planFrame(), snapshot.timetables(), snapshot.placeBlocks());
    }

    private static String of(ObjectMapper objectMapper, Object planFrame, List<?> timetables, List<?> placeBlocks) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("planFrame", objectMapper.valueToTree(planFrame));
        root.set("timetables", objectMapper.valueToTree(timetables));
        root.set("placeBlocks", objectMapper.valueToTree(placeBlocks));

        try {
            String json = objectMapper.writeValueAsString(root);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("플랜 상태 지문 계산 실패", e);
        }
    }
}
