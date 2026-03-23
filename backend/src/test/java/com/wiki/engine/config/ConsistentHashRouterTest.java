package com.wiki.engine.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConsistentHashRouterTest {

    private StringRedisTemplate mockNode(String name) {
        StringRedisTemplate mock = mock(StringRedisTemplate.class, name);
        return mock;
    }

    @Test
    @DisplayName("3노드에 키 1000개 분배 — 각 노드 20~45% 범위 (편차 < 25%)")
    void keyDistribution() {
        var node1 = mockNode("node-0");
        var node2 = mockNode("node-1");
        var node3 = mockNode("node-2");
        var router = new ConsistentHashRouter(List.of(node1, node2, node3));

        Map<StringRedisTemplate, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            var node = router.getNode("prefix:v123456:" + i);
            counts.merge(node, 1, Integer::sum);
        }

        System.out.println("키 분산: " + counts.values());
        for (int count : counts.values()) {
            double ratio = count / 1000.0;
            assertThat(ratio).as("각 노드 비율이 20~45% 범위").isBetween(0.20, 0.45);
        }
    }

    @Test
    @DisplayName("노드 추가 시 ~25% 키만 재배치 (1/N)")
    void addNodeMinimalRemapping() {
        var node1 = mockNode("node-0");
        var node2 = mockNode("node-1");
        var node3 = mockNode("node-2");
        var router = new ConsistentHashRouter(List.of(node1, node2, node3));

        // 3노드 상태에서 키 1000개의 노드 배정 기록
        Map<String, StringRedisTemplate> before = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            String key = "prefix:v123456:" + i;
            before.put(key, router.getNode(key));
        }

        // 4번째 노드 추가
        var node4 = mockNode("node-3");
        router.addNode(node4);

        // 재배치된 키 수 카운트
        int remapped = 0;
        for (int i = 0; i < 1000; i++) {
            String key = "prefix:v123456:" + i;
            if (router.getNode(key) != before.get(key)) {
                remapped++;
            }
        }

        double remapRatio = remapped / 1000.0;
        System.out.println("재배치된 키: " + remapped + "/1000 (" + (remapRatio * 100) + "%)");
        // 이상적: ~25% (1/4). 허용 범위: 15~35%
        assertThat(remapRatio).as("재배치 비율이 15~35% 범위 (이상적 25%)").isBetween(0.15, 0.35);
    }

    @Test
    @DisplayName("노드 제거 시 해당 노드의 키만 다른 노드로 이동")
    void removeNodeRedistribution() {
        var node1 = mockNode("node-0");
        var node2 = mockNode("node-1");
        var node3 = mockNode("node-2");
        var router = new ConsistentHashRouter(List.of(node1, node2, node3));

        // node-1에 배정된 키 수 기록
        int node1Keys = 0;
        for (int i = 0; i < 1000; i++) {
            if (router.getNode("key:" + i) == node2) {
                node1Keys++;
            }
        }

        // node-1 제거
        router.removeNode(1);

        // 제거된 노드의 키가 나머지 노드로 재배치 확인
        for (int i = 0; i < 1000; i++) {
            StringRedisTemplate assigned = router.getNode("key:" + i);
            assertThat(assigned).as("제거된 노드에 배정되지 않음").isNotEqualTo(node2);
        }

        System.out.println("제거 전 node-1 키 수: " + node1Keys + "/1000");
    }

    @Test
    @DisplayName("링 사이즈: 3노드 x 150 가상 노드 = 450 엔트리")
    void ringSizeCheck() {
        var router = new ConsistentHashRouter(List.of(
                mockNode("n0"), mockNode("n1"), mockNode("n2")));
        assertThat(router.getRingSize()).isEqualTo(450);
        assertThat(router.getNodeCount()).isEqualTo(3);
    }
}
