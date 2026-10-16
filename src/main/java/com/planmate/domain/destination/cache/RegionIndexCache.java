package com.planmate.domain.destination.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.entity.Destination;
import com.planmate.domain.destination.entity.DestinationAlias;
import com.planmate.domain.destination.entity.DestinationRegionCode;
import com.planmate.domain.destination.repository.DestinationAliasRepository;
import com.planmate.domain.destination.repository.DestinationRegionCodeRepository;
import com.planmate.domain.destination.repository.DestinationRepository;
import jakarta.annotation.PostConstruct;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * planmate-ai 에 내려줄 지역 색인을 서버 기동 시 미리 만들어 메모리에 둔다.
 * 원본은 Flyway 시드로만 바뀌는 관리자 큐레이션 테이블이라 재기동 시에만 갱신한다
 * ({@link DestinationResponseCache}와 같은 패턴).
 *
 * <p>버전 = 색인을 정해진 순서로 직렬화한 바이트의 SHA-256. 노드는 destinationId, 별칭은 사전순,
 * 코드는 (시도, 시군구 — 없으면 앞) 순으로 정렬해 두므로 조회 순서와 무관하게 같은 데이터면 같은 값이
 * 나온다 — 파드 2개가 서로 다른 버전을 내면 AI 가 버전이 계속 바뀐다고 보고 재조회를 반복한다.
 * planmate-ai/project_ai/22_지역_참조데이터_동기화_설계.md §5-2.
 */
@Component
@RequiredArgsConstructor
public class RegionIndexCache {

    private static final Comparator<RegionIndex.CodePair> CODE_ORDER =
            Comparator.comparing(RegionIndex.CodePair::regn)
                    .thenComparing(RegionIndex.CodePair::signgu, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final DestinationRepository destinationRepository;
    private final DestinationRegionCodeRepository regionCodeRepository;
    private final DestinationAliasRepository aliasRepository;
    private final ObjectMapper objectMapper;

    private volatile RegionIndex index;

    @PostConstruct
    public void load() {
        Map<Integer, List<RegionIndex.CodePair>> codesByNode = regionCodeRepository.findAll().stream()
                .collect(Collectors.groupingBy(rc -> rc.getDestination().getDestinationId(),
                        Collectors.mapping(RegionIndexCache::toCodePair, Collectors.toList())));
        Map<Integer, List<String>> aliasesByNode = aliasRepository.findAll().stream()
                .collect(Collectors.groupingBy(a -> a.getDestination().getDestinationId(),
                        Collectors.mapping(DestinationAlias::getAlias, Collectors.toList())));

        List<RegionIndex.Node> nodes = destinationRepository.findAllWithParent().stream()
                .sorted(Comparator.comparing(Destination::getDestinationId))
                .map(d -> new RegionIndex.Node(
                        d.getDestinationId(),
                        d.getDestinationName(),
                        d.isSido() ? null : d.getParent().getDestinationId(),
                        aliasesByNode.getOrDefault(d.getDestinationId(), List.of()).stream().sorted().toList(),
                        codesByNode.getOrDefault(d.getDestinationId(), List.of()).stream().sorted(CODE_ORDER).toList()))
                .toList();

        index = new RegionIndex(sha256Hex(nodes), nodes);
    }

    public RegionIndex index() {
        return index;
    }

    public String version() {
        return index.version();
    }

    private static RegionIndex.CodePair toCodePair(DestinationRegionCode rc) {
        return new RegionIndex.CodePair(rc.getLdongRegnCd(), rc.getLdongSignguCd());
    }

    private String sha256Hex(List<RegionIndex.Node> nodes) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(nodes);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("지역 색인 생성 실패", e);
        }
    }
}
