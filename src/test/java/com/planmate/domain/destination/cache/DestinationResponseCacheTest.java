package com.planmate.domain.destination.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.dto.DestinationDto;
import com.planmate.domain.destination.dto.DestinationNodeDto;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.repository.DestinationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 1차 목록 정렬·트리 조립·ETag 계산 로직 단위 테스트(B_계층_상세.md §4-6·§5-1·§5-2).
 * 리포지토리는 목, ObjectMapper는 실제 인스턴스(ETag가 실제 JSON 바이트에서 나와야 하므로).
 */
@ExtendWith(MockitoExtension.class)
class DestinationResponseCacheTest {

    @Mock
    private DestinationRepository destinationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 서울(시도, 1차) / 울산(시도, 1차) / 경기(시도, 비노출) 아래 수원(1차)·양평(1차)·파주(비노출)
    private final Destination seoul = sido(1, "서울", 10, true);
    private final Destination ulsan = sido(31, "울산", 60, true);
    private final Destination gyeonggi = sido(29, "경기", 80, false);
    private final Destination suwon = sigungu(3, "수원", gyeonggi, 10, true);
    private final Destination paju = sigungu(45, "파주", gyeonggi, 190, false);
    private final Destination yangpyeong = sigungu(50, "양평", gyeonggi, 300, true);

    private DestinationResponseCache loadedWith(List<Destination> nodes) {
        given(destinationRepository.findAllWithParent()).willReturn(nodes);
        DestinationResponseCache cache = new DestinationResponseCache(destinationRepository, objectMapper);
        cache.load();
        return cache;
    }

    // ---------- 1차 목록 ----------

    @Test
    @DisplayName("1차 목록 - featured 노드만, (부모 순서, 자기 순서)로 정렬한다 — 시도는 자기 자신이 부모 자리")
    void featuredList_filtersAndOrdersByParentThenSelf() {
        // 입력 순서를 섞어도 결과 순서는 정렬 규칙만 따른다
        DestinationResponseCache cache = loadedWith(List.of(yangpyeong, gyeonggi, paju, ulsan, suwon, seoul));

        assertThat(cache.featuredList().body().destinations())
                .extracting(DestinationDto::destinationName)
                .containsExactly("서울", "울산", "수원", "양평");
    }

    // ---------- 트리 ----------

    @Test
    @DisplayName("트리 - 시도는 display_order 순, 각 시도의 시군도 display_order 순. 비노출 노드도 전부 포함한다")
    void tree_ordersSidosAndChildren() {
        DestinationResponseCache cache = loadedWith(List.of(yangpyeong, gyeonggi, paju, ulsan, suwon, seoul));

        List<DestinationNodeDto> sidos = cache.tree().body().sidos();
        assertThat(sidos).extracting(DestinationNodeDto::destinationName).containsExactly("서울", "울산", "경기");

        DestinationNodeDto gyeonggiNode = sidos.get(2);
        assertThat(gyeonggiNode.featured()).isFalse();
        assertThat(gyeonggiNode.children())
                .extracting(DestinationNodeDto::destinationName)
                .containsExactly("수원", "파주", "양평");
        assertThat(gyeonggiNode.children())
                .extracting(DestinationNodeDto::featured)
                .containsExactly(true, false, true);
    }

    @Test
    @DisplayName("트리 JSON - 하위가 없는 시도는 빈 배열 children, 시군 노드에는 children 키가 없다")
    void tree_jsonShape() throws Exception {
        DestinationResponseCache cache = loadedWith(List.of(seoul, gyeonggi, suwon));

        JsonNode json = objectMapper.valueToTree(cache.tree().body());
        JsonNode seoulJson = json.get("sidos").get(0);
        JsonNode suwonJson = json.get("sidos").get(1).get("children").get(0);

        assertThat(seoulJson.get("children").isArray()).isTrue();
        assertThat(seoulJson.get("children")).isEmpty();
        assertThat(suwonJson.has("children")).isFalse();
        assertThat(suwonJson.get("destinationId").asInt()).isEqualTo(3);
        assertThat(suwonJson.get("destinationName").asText()).isEqualTo("수원");
        assertThat(suwonJson.get("featured").asBoolean()).isTrue();
        // 좌표·레벨·매핑 코드는 내보내지 않는다(§5-1)
        assertThat(seoulJson.has("latitude")).isFalse();
        assertThat(seoulJson.has("level")).isFalse();
    }

    // ---------- ETag ----------

    @Test
    @DisplayName("ETag - 같은 데이터면 다시 만들어도 같은 값(파드 간 결정성), SHA-256 hex 64자")
    void etag_deterministicForSameData() {
        String first = loadedWith(List.of(seoul, gyeonggi, suwon)).tree().etag();
        String second = loadedWith(List.of(suwon, seoul, gyeonggi)).tree().etag();

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("ETag - 노드가 추가되면 값이 바뀐다(배포 반영 감지), 1차 목록과 트리는 서로 다른 ETag")
    void etag_changesWhenDataChanges() {
        DestinationResponseCache before = loadedWith(List.of(seoul, gyeonggi, suwon));
        DestinationResponseCache after = loadedWith(List.of(seoul, gyeonggi, suwon, paju));

        assertThat(after.tree().etag()).isNotEqualTo(before.tree().etag());
        assertThat(before.featuredList().etag()).isNotEqualTo(before.tree().etag());
    }

    @Test
    @DisplayName("데이터가 없으면 빈 목록·빈 트리")
    void emptyData() {
        DestinationResponseCache cache = loadedWith(List.of());

        assertThat(cache.featuredList().body().destinations()).isEmpty();
        assertThat(cache.tree().body().sidos()).isEmpty();
    }

    private static Destination sido(int id, String name, int order, boolean featured) {
        return Destination.builder()
                .destinationId(id)
                .destinationName(name)
                .latitude(BigDecimal.ONE)
                .longitude(BigDecimal.ONE)
                .isFeatured(featured)
                .displayOrder(order)
                .build();
    }

    private static Destination sigungu(int id, String name, Destination parent, int order, boolean featured) {
        return Destination.builder()
                .destinationId(id)
                .destinationName(name)
                .latitude(BigDecimal.ONE)
                .longitude(BigDecimal.ONE)
                .parent(parent)
                .isFeatured(featured)
                .displayOrder(order)
                .build();
    }
}
