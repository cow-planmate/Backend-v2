package com.planmate.domain.destination.cache;

import java.util.List;

/**
 * 챗봇(planmate-ai)이 채팅 문장의 지역명을 노드로 해석할 때 쓰는 지역 색인.
 * 노드·지역코드·별칭 전체이며, {@code version}은 내용으로만 정해지므로 파드가 여러 개여도 같다.
 * planmate-ai/project_ai/22_지역_참조데이터_동기화_설계.md §5-2.
 */
public record RegionIndex(String version, List<Node> nodes) {

    /** {@code parentId}가 null이면 시도 노드. */
    public record Node(int destinationId, String name, Integer parentId, List<String> aliases, List<CodePair> codes) {
    }

    /** {@code signgu}가 null이면 그 시도 전체. */
    public record CodePair(String regn, String signgu) {
    }
}
