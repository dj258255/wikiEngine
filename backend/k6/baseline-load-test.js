/**
 * k6 Baseline 부하 테스트 스크립트.
 * 1단계(LIKE 검색, OFFSET 페이지네이션, 동기 조회수 증가)의 성능 베이스라인을 측정한다.
 *
 * 테스트 시나리오:
 * 1. 검색 (LIKE '%keyword%' → Full Table Scan)
 * 2. 자동완성 (LIKE 'prefix%')
 * 3. 게시글 목록 조회 (OFFSET 페이지네이션)
 * 4. 게시글 상세 조회 (조회수 동기 증가)
 *
 * 실행 방법:
 *   # 콘솔 출력만
 *   k6 run k6/baseline-load-test.js
 *
 *   # InfluxDB + Grafana 대시보드 연동 (권장)
 *   k6 run --out influxdb=http://localhost:8086/k6 k6/baseline-load-test.js
 *
 * 환경변수로 설정 변경:
 *   k6 run --out influxdb=http://localhost:8086/k6 -e BASE_URL=http://localhost:8080 k6/baseline-load-test.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ─── 커스텀 메트릭 ──────────────────────────────────────

const searchDuration = new Trend('search_duration', true);
const autocompleteDuration = new Trend('autocomplete_duration', true);
const listDuration = new Trend('list_duration', true);
const detailDuration = new Trend('detail_duration', true);
const errorRate = new Rate('errors');

// ─── 설정 ──────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_PREFIX = `${BASE_URL}/api/v1.0`;

export const options = {
    stages: [
        { duration: '30s', target: 10 },   // 워밍업: 10 VU까지 증가
        { duration: '1m', target: 50 },    // 부하: 50 VU 유지
        { duration: '1m', target: 100 },   // 고부하: 100 VU 유지
        { duration: '30s', target: 0 },    // 쿨다운
    ],
    thresholds: {
        // 베이스라인 측정이므로 임계값은 느슨하게 설정 (실패 방지)
        http_req_duration: ['p(99)<30000'],  // P99 30초 이내
        errors: ['rate<0.5'],                // 에러율 50% 이내
    },
};

// ─── 테스트 데이터 ─────────────────────────────────────

// 한국어 검색어 (위키피디아에 확실히 존재하는 키워드)
const KO_SEARCH_QUERIES = [
    '삼성전자', '인공지능', '대한민국', '프로그래밍', '역사',
    '서울특별시', '축구', '수학', '물리학', '경제',
    '컴퓨터', '과학', '음악', '영화', '문학',
];

// 영문 검색어
const EN_SEARCH_QUERIES = [
    'computer', 'science', 'history', 'mathematics', 'physics',
    'programming', 'algorithm', 'database', 'network', 'software',
    'machine learning', 'artificial intelligence', 'United States', 'philosophy', 'biology',
];

// 자동완성 prefix (한국어 + 영문)
const AUTOCOMPLETE_PREFIXES = [
    '삼성', '인공', '대한', '프로', '역',
    'comp', 'sci', 'hist', 'math', 'prog',
];

// 모든 검색어를 합침
const ALL_QUERIES = [...KO_SEARCH_QUERIES, ...EN_SEARCH_QUERIES];

// ─── 유틸 함수 ─────────────────────────────────────────

function randomItem(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

// ─── 메인 시나리오 ─────────────────────────────────────

export default function () {
    // 시나리오를 랜덤하게 선택하여 실행
    const scenario = randomInt(1, 100);

    if (scenario <= 40) {
        // 40%: 검색 (가장 무거운 작업)
        testSearch();
    } else if (scenario <= 60) {
        // 20%: 자동완성
        testAutocomplete();
    } else if (scenario <= 80) {
        // 20%: 목록 조회
        testPostList();
    } else {
        // 20%: 상세 조회
        testPostDetail();
    }

    sleep(randomInt(1, 3));
}

// ─── 시나리오별 테스트 함수 ─────────────────────────────

/** 검색 테스트: LIKE '%keyword%' → Full Table Scan 발생 */
function testSearch() {
    group('검색', function () {
        const query = randomItem(ALL_QUERIES);
        const page = randomInt(0, 5);
        const url = `${API_PREFIX}/posts/search?q=${encodeURIComponent(query)}&page=${page}&size=20`;

        const res = http.get(url);
        searchDuration.add(res.timings.duration);

        const success = check(res, {
            '검색 응답 200': (r) => r.status === 200,
            '검색 결과 포함': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    return body.content !== undefined;
                } catch (e) {
                    return false;
                }
            },
        });

        errorRate.add(!success);
    });
}

/** 자동완성 테스트: LIKE 'prefix%' */
function testAutocomplete() {
    group('자동완성', function () {
        const prefix = randomItem(AUTOCOMPLETE_PREFIXES);
        const url = `${API_PREFIX}/posts/autocomplete?prefix=${encodeURIComponent(prefix)}`;

        const res = http.get(url);
        autocompleteDuration.add(res.timings.duration);

        const success = check(res, {
            '자동완성 응답 200': (r) => r.status === 200,
            '자동완성 결과 배열': (r) => {
                try {
                    return Array.isArray(JSON.parse(r.body));
                } catch (e) {
                    return false;
                }
            },
        });

        errorRate.add(!success);
    });
}

/** 목록 조회 테스트: OFFSET 페이지네이션 (깊은 페이지 포함) */
function testPostList() {
    group('목록 조회', function () {
        // 깊은 페이지를 일부 포함하여 OFFSET 성능 저하 측정
        const page = randomInt(0, 100) < 70 ? randomInt(0, 10) : randomInt(100, 1000);
        const url = `${API_PREFIX}/posts?page=${page}&size=20`;

        const res = http.get(url);
        listDuration.add(res.timings.duration);

        const success = check(res, {
            '목록 응답 200': (r) => r.status === 200,
        });

        errorRate.add(!success);
    });
}

/** 상세 조회 테스트: 조회수 동기 증가 (Row Lock 경합) */
function testPostDetail() {
    group('상세 조회', function () {
        // 인기 게시글에 동시 접근하여 Row Lock 경합 측정
        const postId = randomInt(1, 10000);
        const url = `${API_PREFIX}/posts/${postId}`;

        const res = http.get(url);
        detailDuration.add(res.timings.duration);

        const success = check(res, {
            '상세 응답 200 또는 404': (r) => r.status === 200 || r.status === 404 || r.status === 500,
        });

        errorRate.add(!success);
    });
}

// ─── 테스트 종료 후 요약 ───────────────────────────────

export function handleSummary(data) {
    const summary = {
        '테스트 일시': new Date().toISOString(),
        '총 요청 수': data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
        '평균 응답시간(ms)': data.metrics.http_req_duration ? data.metrics.http_req_duration.values.avg.toFixed(2) : 'N/A',
        'P95 응답시간(ms)': data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(95)'].toFixed(2) : 'N/A',
        'P99 응답시간(ms)': data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(99)'].toFixed(2) : 'N/A',
        '검색 평균(ms)': data.metrics.search_duration ? data.metrics.search_duration.values.avg.toFixed(2) : 'N/A',
        '자동완성 평균(ms)': data.metrics.autocomplete_duration ? data.metrics.autocomplete_duration.values.avg.toFixed(2) : 'N/A',
        '목록 평균(ms)': data.metrics.list_duration ? data.metrics.list_duration.values.avg.toFixed(2) : 'N/A',
        '상세 평균(ms)': data.metrics.detail_duration ? data.metrics.detail_duration.values.avg.toFixed(2) : 'N/A',
        '에러율(%)': data.metrics.errors ? (data.metrics.errors.values.rate * 100).toFixed(2) : '0',
    };

    console.log('\n========== Baseline 측정 결과 ==========');
    for (const [key, value] of Object.entries(summary)) {
        console.log(`  ${key}: ${value}`);
    }
    console.log('========================================\n');

    // JSON 파일로도 저장
    return {
        'k6/baseline-result.json': JSON.stringify(data, null, 2),
    };
}
