package com.planmate.domain.route.service;

import com.planmate.domain.route.repository.RouteCacheRepository;
import com.planmate.domain.route.repository.RouteTransitLaneCacheRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RouteCachePrunerTest {

    @Mock
    private RouteCacheRepository routeCacheRepository;
    @Mock
    private RouteTransitLaneCacheRepository laneCacheRepository;

    @Test
    @DisplayName("prune - 두 테이블 모두 현재 시각 기준으로 만료 삭제를 호출한다")
    void prune_deletesExpiredFromBothTables() {
        given(routeCacheRepository.deleteExpired(any())).willReturn(3);
        given(laneCacheRepository.deleteExpired(any())).willReturn(2);

        RouteCachePruner pruner = new RouteCachePruner(routeCacheRepository, laneCacheRepository);
        pruner.prune();

        ArgumentCaptor<LocalDateTime> routeNowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> laneNowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(routeCacheRepository).deleteExpired(routeNowCaptor.capture());
        verify(laneCacheRepository).deleteExpired(laneNowCaptor.capture());

        assertThat(routeNowCaptor.getValue()).isCloseTo(LocalDateTime.now(), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
        assertThat(laneNowCaptor.getValue()).isCloseTo(LocalDateTime.now(), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("prune - 정리 중 예외가 나도 밖으로 전파하지 않는다(스케줄러가 죽지 않아야 함)")
    void prune_exceptionDoesNotPropagate() {
        given(routeCacheRepository.deleteExpired(any())).willThrow(new RuntimeException("DB 커넥션 끊김"));

        RouteCachePruner pruner = new RouteCachePruner(routeCacheRepository, laneCacheRepository);

        assertThatCode(pruner::prune).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prune - laneCacheRepository만 실패해도 예외가 전파되지 않는다")
    void prune_laneRepositoryFails_doesNotPropagate() {
        given(routeCacheRepository.deleteExpired(any())).willReturn(1);
        given(laneCacheRepository.deleteExpired(any())).willThrow(new RuntimeException("DB 커넥션 끊김"));

        RouteCachePruner pruner = new RouteCachePruner(routeCacheRepository, laneCacheRepository);

        assertThatCode(pruner::prune).doesNotThrowAnyException();
    }
}
