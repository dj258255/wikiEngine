package com.wiki.engine.post.internal.cache;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Consistent Hashing 기반 Redis 노드 라우터.
 *
 * <p>해시 링 위에 가상 노드(virtual node)를 배치하고,
 * 키의 해시값에서 시계 방향으로 가장 가까운 노드를 선택한다.
 * 노드 추가/제거 시 ~1/N 키만 재배치된다.
 *
 * <p>ConcurrentSkipListMap은 lock-free 알고리즘으로
 * ceilingEntry() O(log N) 조회를 동시성 문제 없이 수행한다.
 */
public class ConsistentHashRouter {

    private final ConcurrentSkipListMap<Long, StringRedisTemplate> ring = new ConcurrentSkipListMap<>();
    private final List<StringRedisTemplate> nodes;
    private static final int VIRTUAL_NODES = 150;
    private int nodeCount;

    public ConsistentHashRouter(List<StringRedisTemplate> shardNodes) {
        this.nodes = new java.util.ArrayList<>(shardNodes);
        this.nodeCount = nodes.size();
        for (int i = 0; i < nodes.size(); i++) {
            addToRing(nodes.get(i), i);
        }
    }

    private void addToRing(StringRedisTemplate node, int nodeIndex) {
        for (int v = 0; v < VIRTUAL_NODES; v++) {
            long h = hash("node-" + nodeIndex + "-vnode-" + v);
            ring.put(h, node);
        }
    }

    private void removeFromRing(int nodeIndex) {
        for (int v = 0; v < VIRTUAL_NODES; v++) {
            long h = hash("node-" + nodeIndex + "-vnode-" + v);
            ring.remove(h);
        }
    }

    /**
     * 노드를 해시 링에 추가한다.
     * 기존 키 중 ~1/N만 새 노드로 이동한다 (Consistent Hashing의 핵심 이점).
     * ConcurrentSkipListMap이므로 추가 중에도 조회가 안전하다.
     */
    public void addNode(StringRedisTemplate newNode) {
        int index = nodeCount++;
        nodes.add(newNode);
        addToRing(newNode, index);
    }

    /**
     * 노드를 해시 링에서 제거한다.
     * 해당 노드가 담당하던 키는 시계 방향 다음 노드가 자동으로 담당한다.
     */
    public void removeNode(int nodeIndex) {
        removeFromRing(nodeIndex);
    }

    /**
     * 키에 해당하는 Redis 노드를 결정한다.
     * 해시 링에서 키의 해시값보다 크거나 같은 첫 번째 엔트리를 찾고,
     * 없으면 링의 첫 번째 엔트리로 순환한다.
     */
    public StringRedisTemplate getNode(String key) {
        long h = hash(key);
        Map.Entry<Long, StringRedisTemplate> entry = ring.ceilingEntry(h);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue();
    }

    /** 모든 활성 노드를 반환한다 (SCAN 등 전체 순회 시 사용). */
    public List<StringRedisTemplate> getAllNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /** 현재 링의 노드 수. */
    public int getNodeCount() {
        return nodeCount;
    }

    /** 현재 링의 가상 노드(엔트리) 수. */
    public int getRingSize() {
        return ring.size();
    }

    /**
     * Guava MurmurHash3-128의 상위 long을 양수로 변환한다.
     *
     * <p>MurmurHash3는 비암호화 해시 함수로 균등 분산에 최적화되어 있다.
     * Cassandra(토큰 링), Elasticsearch(라우팅) 등에서 사용.
     * 해시 계산(~140ns)은 Redis 네트워크 왕복(~500us) 대비 무시 가능.
     */
    private long hash(String key) {
        return Hashing.murmur3_128()
                .hashString(key, StandardCharsets.UTF_8)
                .asLong() & 0x7FFFFFFFFFFFFFFFL;
    }
}
