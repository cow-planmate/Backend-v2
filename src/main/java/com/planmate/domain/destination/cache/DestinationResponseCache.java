package com.planmate.domain.destination.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planmate.domain.destination.dto.DestinationDto;
import com.planmate.domain.destination.dto.DestinationListResponse;
import com.planmate.domain.destination.dto.DestinationNodeDto;
import com.planmate.domain.destination.dto.DestinationTreeResponse;
import com.planmate.domain.destination.entity.Destination;
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
 * 여행지 1차 목록·트리 응답을 서버 기동 시 미리 만들어 메모리에 둔다.
 * 원본 데이터는 Flyway 시드로만 바뀌는 관리자 큐레이션 테이블이라 재기동 시에만 갱신한다
 * ({@link DestinationRegionCodeCache}와 같은 패턴). project_ai/04_기능/destination/B_계층_상세.md §5-2.
 *
 * <p>정렬(§4-6): {@code display_order}는 형제 노드 사이의 순서다. 트리는 시도·시군 각각 그 순서로,
 * 1차 목록은 featured 노드를 (부모 순서, 자기 순서)로 정렬한다 — 시도 노드는 자기 자신을 부모 자리로 본다.
 */
@Component
@RequiredArgsConstructor
public class DestinationResponseCache {

    private static final Comparator<Destination> SIBLING_ORDER =
            Comparator.comparingInt(Destination::getDisplayOrder)
                    .thenComparing(Destination::getDestinationId);

    private static final Comparator<Destination> FEATURED_ORDER =
            Comparator.comparingInt((Destination d) -> d.isSido() ? d.getDisplayOrder() : d.getParent().getDisplayOrder())
                    .thenComparingInt(d -> d.isSido() ? 0 : d.getDisplayOrder())
                    .thenComparing(Destination::getDestinationId);

    private final DestinationRepository destinationRepository;
    private final ObjectMapper objectMapper;

    private volatile CachedResponse<DestinationListResponse> featuredList;
    private volatile CachedResponse<DestinationTreeResponse> tree;

    @PostConstruct
    public void load() {
        List<Destination> nodes = destinationRepository.findAllWithParent();
        featuredList = withEtag(buildFeaturedList(nodes));
        tree = withEtag(buildTree(nodes));
    }

    public CachedResponse<DestinationListResponse> featuredList() {
        return featuredList;
    }

    public CachedResponse<DestinationTreeResponse> tree() {
        return tree;
    }

    private static DestinationListResponse buildFeaturedList(List<Destination> nodes) {
        return DestinationListResponse.builder()
                .destinations(nodes.stream()
                        .filter(Destination::isFeatured)
                        .sorted(FEATURED_ORDER)
                        .map(DestinationDto::from)
                        .toList())
                .build();
    }

    private static DestinationTreeResponse buildTree(List<Destination> nodes) {
        Map<Integer, List<Destination>> childrenBySidoId = nodes.stream()
                .filter(d -> !d.isSido())
                .collect(Collectors.groupingBy(d -> d.getParent().getDestinationId()));

        List<DestinationNodeDto> sidos = nodes.stream()
                .filter(Destination::isSido)
                .sorted(SIBLING_ORDER)
                .map(sido -> DestinationNodeDto.ofSido(sido,
                        childrenBySidoId.getOrDefault(sido.getDestinationId(), List.of()).stream()
                                .sorted(SIBLING_ORDER)
                                .map(DestinationNodeDto::ofSigungu)
                                .toList()))
                .toList();

        return DestinationTreeResponse.builder().sidos(sidos).build();
    }

    // ETag = 응답 JSON 바이트의 SHA-256 — 시간·서버별 값이 섞이지 않아 파드 2개에서 같은 값이 나온다(§5-2)
    private <T> CachedResponse<T> withEtag(T body) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json);
            return new CachedResponse<>(body, HexFormat.of().formatHex(hash));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("여행지 응답 캐시 생성 실패", e);
        }
    }
}
