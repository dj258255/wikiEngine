/**
 * k6 부하 테스트 — Lucene + Nori 검색엔진.
 *
 * 전체 API를 실제 커뮤니티 트래픽 비율로 시뮬레이션한다.
 *   - 읽기 (85%): 검색, 자동완성, 목록 조회, 상세 조회
 *   - 쓰기 (15%): 글 생성 (NRT 증분 색인), 좋아요 (Row Lock 경합)
 *
 * 검색어를 빈도별로 분리하여 Lucene posting list 길이에 따른 성능 차이를 측정한다.
 *   - 희귀 토큰: posting list 짧음 → 빠름 (예: "페텔", "algorithm")
 *   - 중빈도 토큰: 일반 사용자 검색 패턴 (예: "삼성전자", "프로그래밍")
 *   - 고빈도 토큰: posting list 길음 → 스트레스 (예: "대한민국", "history")
 *
 * 프로필 (PROFILE 환경변수):
 *   - smoke:  스크립트 검증용 (2분, 5 VU)
 *   - load:   baseline 측정용 (20분, 최대 100 VU) ← 기본값
 *   - stress: 한계점 탐색용 (25분, 최대 200 VU)
 *   - soak:   장기 안정성 검증용 (4시간 10분, 50 VU)
 *
 * 실행 방법:
 *   # 1단계: smoke — 스크립트가 정상 동작하는지 확인
 *   k6 run -e PROFILE=smoke -e BASE_URL=http://<서버ip>:8080 k6/baseline-load-test.js
 *
 *   # 2단계: load — baseline 성능 측정 (약 20분)
 *   k6 run -e PROFILE=load -e BASE_URL=http://<서버ip>:8080 k6/baseline-load-test.js
 *
 *   # 3단계: stress — 시스템 한계 확인 (약 25분)
 *   k6 run -e PROFILE=stress -e BASE_URL=http://<서버ip>:8080 k6/baseline-load-test.js
 *
 *   # 4단계: soak — 메모리 누수/커넥션 풀 고갈 확인 (약 4시간)
 *   k6 run -e PROFILE=soak -e BASE_URL=http://<서버ip>:8080 k6/baseline-load-test.js
 *
 *   # Grafana 대시보드 연동 (권장)
 *   k6 run --out influxdb=http://<서버ip>:8086/k6 \
 *     -e PROFILE=load -e BASE_URL=http://<서버ip>:8080 k6/baseline-load-test.js
 */
import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// ─── 커스텀 메트릭 ──────────────────────────────────────

const searchDuration = new Trend('search_duration', true);
const searchRareDuration = new Trend('search_rare_duration', true);
const searchMediumDuration = new Trend('search_medium_duration', true);
const searchHighDuration = new Trend('search_high_duration', true);
const autocompleteDuration = new Trend('autocomplete_duration', true);
const listDuration = new Trend('list_duration', true);
const detailDuration = new Trend('detail_duration', true);
const writeDuration = new Trend('write_duration', true);
const errorRate = new Rate('errors');

// ─── 설정 ──────────────────────────────────────────────

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_PREFIX = `${BASE_URL}/api/v1.0`;
const PROFILE = (__ENV.PROFILE || 'load').toLowerCase();

// ─── 프로필별 부하 패턴 ─────────────────────────────────
// k6 공식 가이드 권장 패턴: smoke → load → stress → soak 순서로 실행
//
// | 프로필  | 총 시간   | 최대 VU | 목적                          |
// |---------|-----------|---------|-------------------------------|
// | smoke   | 2분       | 5       | 스크립트 정상 동작 확인        |
// | load    | 20분      | 100     | baseline 성능 측정 (기본값)    |
// | stress  | 25분      | 200     | 시스템 한계점·병목 탐색        |
// | soak    | 4시간 10분 | 50     | 메모리 누수·커넥션 풀 고갈     |

const PROFILES = {
    // smoke: 스크립트 검증 — 최소 부하로 에러 없이 돌아가는지만 확인
    smoke: [
        { duration: '30s', target: 5 },    // 워밍업: 0 → 5 VU
        { duration: '1m',  target: 5 },    // 유지: 5 VU
        { duration: '30s', target: 0 },    // 쿨다운: 5 → 0 VU
    ],

    // load: baseline 측정 — 일상 트래픽 시뮬레이션 (DAU 1,000~2,000명 수준)
    load: [
        { duration: '2m',  target: 50 },   // 워밍업: 0 → 50 VU
        { duration: '10m', target: 50 },   // 정상 부하 유지: 50 VU
        { duration: '2m',  target: 100 },  // 피크 부하: 50 → 100 VU
        { duration: '5m',  target: 100 },  // 피크 유지: 100 VU
        { duration: '1m',  target: 0 },    // 쿨다운: 100 → 0 VU
    ],

    // stress: 한계 탐색 — 시스템이 어디서 무너지는지 확인
    stress: [
        { duration: '2m',  target: 100 },  // 워밍업: 0 → 100 VU
        { duration: '5m',  target: 100 },  // 정상 부하 유지: 100 VU
        { duration: '2m',  target: 200 },  // 과부하: 100 → 200 VU
        { duration: '10m', target: 200 },  // 과부하 유지: 200 VU
        { duration: '2m',  target: 100 },  // 회복: 200 → 100 VU
        { duration: '3m',  target: 100 },  // 회복 확인: 100 VU
        { duration: '1m',  target: 0 },    // 쿨다운: 100 → 0 VU
    ],

    // soak: 장기 안정성 — 메모리 누수, GC pause, 커넥션 풀 고갈 감지
    soak: [
        { duration: '5m',   target: 50 },  // 워밍업: 0 → 50 VU
        { duration: '4h',   target: 50 },  // 장기 유지: 50 VU (4시간)
        { duration: '5m',   target: 0 },   // 쿨다운: 50 → 0 VU
    ],
};

const stages = PROFILES[PROFILE];
if (!stages) {
    throw new Error(`알 수 없는 PROFILE: "${PROFILE}". smoke, load, stress, soak 중 선택하세요.`);
}

// 프로필별 최대 VU 수 — setup에서 이만큼만 계정 생성
const MAX_VU_BY_PROFILE = {
    smoke: 5,
    load: 100,
    stress: 200,
    soak: 50,
};

export const options = {
    stages,
    setupTimeout: '120s',
    thresholds: {
        // 글로벌 SLO 안전망 — 전체 API가 최소한 이 안에 들어와야 함
        http_req_duration: ['p(95)<3000', 'p(99)<5000'],

        // 엔드포인트별 SLA — 각 API의 실제 성능 기대치
        'search_duration': ['p(95)<300', 'p(99)<500'],
        'autocomplete_duration': ['p(95)<200', 'p(99)<300'],
        'list_duration': ['p(95)<5000'],         // OFFSET 페이지네이션 baseline (개선 후 하향)
        'detail_duration': ['p(95)<200'],
        'write_duration': ['p(95)<300'],
        errors: ['rate<0.01'],
    },
};

// ─── 테스트 데이터 (빈도별 분리) ─────────────────────────

// 희귀 토큰 — posting list 짧음, 캐시 miss 위주
const RARE_QUERIES = [
    '페텔', '흑요석', 'algorithm', 'quaternion', '메타세쿼이아',
    'triskelion', '갈릴레이', 'fibonacci', '디오판토스', 'holography',
];

// 중빈도 토큰 — 일반 사용자 검색 패턴
const MEDIUM_QUERIES = [
    '삼성전자', '인공지능', '프로그래밍', '축구', '물리학',
    'computer', 'science', 'programming', 'database', 'philosophy',
    '서울특별시', '경제', '컴퓨터', '영화', '문학',
];

// 고빈도 토큰 — posting list 길음, Lucene 스트레스 테스트
const HIGH_FREQ_QUERIES = [
    '대한민국', '한국', '역사', '대한', '사람',
    'history', 'United States', 'world', 'people', 'time',
];

// 자동완성 prefix (한국어 + 영문)
const AUTOCOMPLETE_PREFIXES = [
    '삼성', '인공', '대한', '프로', '역사',
    'comp', 'sci', 'hist', 'math', 'prog',
    '서울', '경제', '과학', 'art', 'uni',
];

// 빈도별 가중치 — 실제 트래픽 패턴 반영
// 커뮤니티에서 대부분의 검색은 중빈도 키워드
// 반환: { query, freq } — freq는 'rare' | 'medium' | 'high'
function pickSearchQuery() {
    const roll = randomInt(1, 100);
    if (roll <= 10) return { query: randomItem(RARE_QUERIES), freq: 'rare' };        // 10%: 희귀
    if (roll <= 70) return { query: randomItem(MEDIUM_QUERIES), freq: 'medium' };    // 60%: 중빈도
    return { query: randomItem(HIGH_FREQ_QUERIES), freq: 'high' };                   // 30%: 고빈도
}

// ─── 유틸 함수 ─────────────────────────────────────────

function randomItem(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function randomInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

// ─── 인증 (VU 초기화 시 1회 로그인) ────────────────────

// VU마다 고유 계정으로 로그인하여 쿠키를 획득한다.
// setup()에서 테스트 계정을 미리 생성하고, 각 VU가 로그인한다.
const TEST_USER_PREFIX = 'k6user';
const TEST_PASSWORD = 'Test1234!';

const MAX_TEST_USERS = MAX_VU_BY_PROFILE[PROFILE] || 200;

export function setup() {
    console.log(`\n[k6] 프로필: ${PROFILE.toUpperCase()}`);
    console.log(`[k6] 대상 서버: ${BASE_URL}`);
    console.log(`[k6] 생성할 계정 수: ${MAX_TEST_USERS}\n`);

    // 1. 서버 헬스체크 — 서버 불통이면 계정 생성 전에 빠르게 실패
    // /posts?page=0&size=1 사용: Slice 전환으로 COUNT 제거됨, 타임아웃 30초
    const healthCheck = http.get(`${API_PREFIX}/posts?page=0&size=1`, {
        timeout: '30s',
    });
    if (healthCheck.status === 0) {
        throw new Error(
            `서버 연결 실패: ${BASE_URL}\n` +
            `  → 서버가 실행 중인지 확인하세요 (docker ps)\n` +
            `  → 방화벽/보안그룹에서 8080 포트가 열려있는지 확인하세요\n` +
            `  → curl ${API_PREFIX}/posts?page=0&size=1 로 직접 테스트하세요`
        );
    }
    console.log(`[k6] 서버 연결 확인 완료 (${healthCheck.status})\n`);

    // 2. 테스트 계정 생성 (이미 존재하면 409 → 무시)
    for (let i = 1; i <= MAX_TEST_USERS; i++) {
        http.post(`${API_PREFIX}/auth/signup`, JSON.stringify({
            username: `${TEST_USER_PREFIX}${i}`,
            nickname: `K6테스터${i}`,
            password: TEST_PASSWORD,
        }), { headers: { 'Content-Type': 'application/json' } });
    }
    console.log(`[k6] 계정 생성/확인 완료: ${MAX_TEST_USERS}개\n`);
    return {};
}

// VU별 로그인 (쿠키 jar에 자동 저장)
function ensureLoggedIn() {
    const jar = http.cookieJar();
    const cookies = jar.cookiesForURL(BASE_URL);

    if (!cookies.token || cookies.token.length === 0) {
        const vuId = (__VU % MAX_TEST_USERS) + 1;
        const res = http.post(`${API_PREFIX}/auth/login`, JSON.stringify({
            username: `${TEST_USER_PREFIX}${vuId}`,
            password: TEST_PASSWORD,
        }), { headers: { 'Content-Type': 'application/json' } });

        check(res, { '로그인 성공': (r) => r.status === 200 });
    }
}

// ─── 메인 시나리오 ─────────────────────────────────────

export default function () {
    const scenario = randomInt(1, 100);

    if (scenario <= 35) {
        testSearch();           // 35%: 검색
    } else if (scenario <= 55) {
        testAutocomplete();     // 20%: 자동완성
    } else if (scenario <= 70) {
        testPostList();         // 15%: 목록 조회
    } else if (scenario <= 85) {
        testPostDetail();       // 15%: 상세 조회
    } else if (scenario <= 93) {
        testCreatePost();       //  8%: 글 생성 (NRT 색인 포함)
    } else {
        testLikePost();         //  7%: 좋아요
    }

    sleep(randomInt(1, 3));
}

// ─── 읽기 시나리오 ─────────────────────────────────────

function testSearch() {
    group('검색', function () {
        const { query, freq } = pickSearchQuery();
        const page = randomInt(0, 30);  // 서버 MAX_SEARCH_PAGE=30에 맞춤
        const url = `${API_PREFIX}/posts/search?q=${encodeURIComponent(query)}&page=${page}&size=20`;

        const res = http.get(url, { tags: { name: 'search' } });
        searchDuration.add(res.timings.duration);

        // 빈도별 메트릭 기록 — Grafana에서 빈도별 성능 비교 가능
        const freqMetrics = { rare: searchRareDuration, medium: searchMediumDuration, high: searchHighDuration };
        freqMetrics[freq].add(res.timings.duration);

        const success = check(res, {
            '검색 응답 200': (r) => r.status === 200,
            '검색 결과 존재': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    return body.data && body.data.content !== undefined;
                } catch (e) {
                    return false;
                }
            },
        });

        errorRate.add(!success);
    });
}

function testAutocomplete() {
    group('자동완성', function () {
        const prefix = randomItem(AUTOCOMPLETE_PREFIXES);
        const url = `${API_PREFIX}/posts/autocomplete?prefix=${encodeURIComponent(prefix)}`;

        const res = http.get(url, { tags: { name: 'autocomplete' } });
        autocompleteDuration.add(res.timings.duration);

        const success = check(res, {
            '자동완성 응답 200': (r) => r.status === 200,
            '자동완성 결과 배열': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    return Array.isArray(body.data);
                } catch (e) {
                    return false;
                }
            },
        });

        errorRate.add(!success);
    });
}

function testPostList() {
    group('최신 게시글 목록', function () {
        // 서버 MAX_LIST_PAGE=30에 맞춤 (Google/네이버도 ~30페이지 제한)
        const page = randomInt(0, 30);
        const url = `${API_PREFIX}/posts?page=${page}&size=20`;

        const res = http.get(url, { tags: { name: 'list' } });
        listDuration.add(res.timings.duration);

        const success = check(res, {
            '목록 응답 200': (r) => r.status === 200,
        });

        errorRate.add(!success);
    });
}

function testPostDetail() {
    group('상세 조회', function () {
        const postId = randomInt(1, 10000);
        const url = `${API_PREFIX}/posts/${postId}`;

        const res = http.get(url, { tags: { name: 'detail' } });
        detailDuration.add(res.timings.duration);

        const success = check(res, {
            '상세 응답 200 또는 404': (r) => r.status === 200 || r.status === 404,
        });

        errorRate.add(!success);
    });
}

// ─── 쓰기 시나리오 ─────────────────────────────────────

/** 글 생성: NRT 증분 색인이 동시 부하에서도 동작하는지 검증 */
function testCreatePost() {
    group('글 생성', function () {
        ensureLoggedIn();

        const res = http.post(`${API_PREFIX}/posts`, JSON.stringify({
            title: `k6 부하테스트 게시글 ${Date.now()}`,
            content: `부하 테스트 중 생성된 게시글입니다. VU=${__VU} ITER=${__ITER}`,
            categoryId: randomInt(1, 10),
        }), {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'create' },
        });
        writeDuration.add(res.timings.duration);

        const success = check(res, {
            '글 생성 201': (r) => r.status === 201,
        });

        errorRate.add(!success);
    });
}

/** 좋아요: Row Lock 경합 + 동시성 테스트 */
function testLikePost() {
    group('좋아요', function () {
        ensureLoggedIn();

        const postId = randomInt(1, 1000);
        const res = http.post(`${API_PREFIX}/posts/${postId}/like`, null, {
            tags: { name: 'like' },
        });
        writeDuration.add(res.timings.duration);

        const success = check(res, {
            // 200: 좋아요 성공, 409: 이미 좋아요함 — 둘 다 정상
            '좋아요 200 또는 409': (r) => r.status === 200 || r.status === 409,
        });

        errorRate.add(!success);
    });
}

// ─── 테스트 종료 후 요약 ───────────────────────────────

export function handleSummary(data) {
    const m = (metric) => data.metrics[metric] ? data.metrics[metric].values : null;
    const fmt = (values, key) => (values && values[key] != null) ? values[key].toFixed(2) : 'N/A';

    const httpDur = m('http_req_duration');
    const search = m('search_duration');
    const searchRare = m('search_rare_duration');
    const searchMedium = m('search_medium_duration');
    const searchHigh = m('search_high_duration');
    const autocomplete = m('autocomplete_duration');
    const list = m('list_duration');
    const detail = m('detail_duration');
    const write = m('write_duration');

    console.log(`\n========== Lucene ${PROFILE.toUpperCase()} 테스트 결과 ==========`);
    console.log(`  프로필: ${PROFILE.toUpperCase()}`);
    console.log(`  테스트 일시: ${new Date().toISOString()}`);
    console.log(`  총 요청 수: ${data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0}`);
    console.log('');
    console.log('  ── 전체 ──');
    console.log(`    평균: ${fmt(httpDur, 'avg')}ms  P95: ${fmt(httpDur, 'p(95)')}ms  P99: ${fmt(httpDur, 'p(99)')}ms`);
    console.log('  ── 검색 (전체) ──');
    console.log(`    평균: ${fmt(search, 'avg')}ms  P95: ${fmt(search, 'p(95)')}ms  P99: ${fmt(search, 'p(99)')}ms`);
    console.log('  ── 검색 (희귀 토큰 10%) ──');
    console.log(`    평균: ${fmt(searchRare, 'avg')}ms  P95: ${fmt(searchRare, 'p(95)')}ms  P99: ${fmt(searchRare, 'p(99)')}ms`);
    console.log('  ── 검색 (중빈도 토큰 60%) ──');
    console.log(`    평균: ${fmt(searchMedium, 'avg')}ms  P95: ${fmt(searchMedium, 'p(95)')}ms  P99: ${fmt(searchMedium, 'p(99)')}ms`);
    console.log('  ── 검색 (고빈도 토큰 30%) ──');
    console.log(`    평균: ${fmt(searchHigh, 'avg')}ms  P95: ${fmt(searchHigh, 'p(95)')}ms  P99: ${fmt(searchHigh, 'p(99)')}ms`);
    console.log('  ── 자동완성 ──');
    console.log(`    평균: ${fmt(autocomplete, 'avg')}ms  P95: ${fmt(autocomplete, 'p(95)')}ms  P99: ${fmt(autocomplete, 'p(99)')}ms`);
    console.log('  ── 최신 게시글 목록 ──');
    console.log(`    평균: ${fmt(list, 'avg')}ms  P95: ${fmt(list, 'p(95)')}ms`);
    console.log('  ── 상세 조회 ──');
    console.log(`    평균: ${fmt(detail, 'avg')}ms  P95: ${fmt(detail, 'p(95)')}ms`);
    console.log('  ── 쓰기 (생성+좋아요) ──');
    console.log(`    평균: ${fmt(write, 'avg')}ms  P95: ${fmt(write, 'p(95)')}ms`);
    console.log('');
    console.log(`  에러율: ${data.metrics.errors ? (data.metrics.errors.values.rate * 100).toFixed(2) : '0'}%`);
    console.log('================================================\n');

    return {
        [`k6/${PROFILE}-result.json`]: JSON.stringify(data, null, 2),
    };
}
