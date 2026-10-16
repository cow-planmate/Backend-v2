package com.planmate.domain.destination.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.entity.DestinationAlias;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.destination.repository.DestinationAliasRepository;
import com.planmate.domain.destination.repository.DestinationRegionCodeRepository;
import com.planmate.domain.destination.repository.DestinationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 지역 색인 조립·버전 계산 단위 테스트(planmate-ai/project_ai/22 §5-2).
 * 리포지토리는 목, ObjectMapper는 실제 인스턴스(버전이 실제 JSON 바이트에서 나와야 하므로).
 */
@ExtendWith(MockitoExtension.class)
class RegionIndexCacheTest {

    @Mock
    private DestinationRepository destinationRepository;
    @Mock
    private DestinationRegionCodeRepository regionCodeRepository;
    @Mock
    private DestinationAliasRepository aliasRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 전남광주(시도) 아래 광주(구 5개 중 2개만) / 경남(시도) 아래 경남 고성
    private final Destination jeonnam = node(155, "전남광주", null);
    private final Destination gwangju = node(17, "광주", jeonnam);
    private final Destination gyeongnam = node(160, "경남", null);
    private final Destination goseong = node(300, "경남 고성", gyeongnam);

    private List<DestinationRegionCode> codes() {
        return List.of(
                code(gwangju, "12", "240"), code(gwangju, "12", "210"),
                code(jeonnam, "12", null),
                code(gyeongnam, "48", null),
                code(goseong, "48", "820"));
    }

    private List<DestinationAlias> aliases() {
        return List.of(
                alias(jeonnam, "전라남도"), alias(jeonnam, "jeonnam"), alias(jeonnam, "전남"),
                alias(gwangju, "광주광역시"),
                alias(goseong, "고성"));
    }

    private RegionIndexCache loadedWith(List<Destination> nodes, List<DestinationRegionCode> codes,
                                        List<DestinationAlias> aliases) {
        given(destinationRepository.findAllWithParent()).willReturn(nodes);
        given(regionCodeRepository.findAll()).willReturn(codes);
        given(aliasRepository.findAll()).willReturn(aliases);
        RegionIndexCache cache = new RegionIndexCache(destinationRepository, regionCodeRepository, aliasRepository,
                objectMapper);
        cache.load();
        return cache;
    }

    @Test
    @DisplayName("노드는 destinationId 순, 별칭은 사전순, 코드는 (시도, 시군구 없음 먼저) 순으로 조립된다")
    void assemblesSortedNodes() {
        RegionIndex index = loadedWith(List.of(goseong, jeonnam, gyeongnam, gwangju), codes(), aliases()).index();

        assertThat(index.nodes()).extracting(RegionIndex.Node::destinationId).containsExactly(17, 155, 160, 300);

        RegionIndex.Node gwangjuNode = index.nodes().get(0);
        assertThat(gwangjuNode.name()).isEqualTo("광주");
        assertThat(gwangjuNode.parentId()).isEqualTo(155);
        assertThat(gwangjuNode.aliases()).containsExactly("광주광역시");
        assertThat(gwangjuNode.codes()).containsExactly(
                new RegionIndex.CodePair("12", "210"), new RegionIndex.CodePair("12", "240"));

        RegionIndex.Node jeonnamNode = index.nodes().get(1);
        assertThat(jeonnamNode.parentId()).isNull();
        assertThat(jeonnamNode.aliases()).containsExactly("jeonnam", "전남", "전라남도");
        assertThat(jeonnamNode.codes()).containsExactly(new RegionIndex.CodePair("12", null));

        assertThat(index.nodes().get(2).aliases()).isEmpty();
    }

    @Test
    @DisplayName("버전 = SHA-256 hex 64자, 조회 순서가 달라도 같은 데이터면 같은 값(파드 간 일치)")
    void version_stableAcrossInputOrder() {
        String first = loadedWith(List.of(jeonnam, gwangju, gyeongnam, goseong), codes(), aliases()).version();

        List<Destination> shuffledNodes = new ArrayList<>(List.of(jeonnam, gwangju, gyeongnam, goseong));
        Collections.reverse(shuffledNodes);
        List<DestinationRegionCode> shuffledCodes = new ArrayList<>(codes());
        Collections.reverse(shuffledCodes);
        List<DestinationAlias> shuffledAliases = new ArrayList<>(aliases());
        Collections.reverse(shuffledAliases);
        String second = loadedWith(shuffledNodes, shuffledCodes, shuffledAliases).version();

        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("별칭 하나가 늘면 버전이 바뀐다 — AI 가 재조회하는 신호")
    void version_changesWhenAliasAdded() {
        String before = loadedWith(List.of(jeonnam, gwangju, gyeongnam, goseong), codes(), aliases()).version();

        List<DestinationAlias> more = new ArrayList<>(aliases());
        more.add(alias(gyeongnam, "경상남도"));
        String after = loadedWith(List.of(jeonnam, gwangju, gyeongnam, goseong), codes(), more).version();

        assertThat(after).isNotEqualTo(before);
    }

    // ---------- fixtures ----------

    private static Destination node(int id, String name, Destination parent) {
        return Destination.builder()
                .destinationId(id)
                .destinationName(name)
                .latitude(BigDecimal.ONE)
                .longitude(BigDecimal.ONE)
                .parent(parent)
                .build();
    }

    private static DestinationRegionCode code(Destination node, String regn, String signgu) {
        return DestinationRegionCode.builder().destination(node).ldongRegnCd(regn).ldongSignguCd(signgu).build();
    }

    private static DestinationAlias alias(Destination node, String alias) {
        return DestinationAlias.builder().destination(node).alias(alias).build();
    }
}
