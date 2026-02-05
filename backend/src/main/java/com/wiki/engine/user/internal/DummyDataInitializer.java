package com.wiki.engine.user.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 더미 사용자 데이터 생성기.
 * "init-data" 프로필이 활성화된 경우에만 동작하며, 10만 명의 랜덤 사용자를 생성한다.
 *
 * 데이터 구성 (Datafaker 라이브러리 사용):
 * - 50% 영문 이름 기반 (username: james.parker, nickname: JamesParker)
 * - 50% 한글 이름 기반 (username: minjun.kim, nickname: 김민준)
 * - 모든 유저의 비밀번호는 "password"의 BCrypt 해시 (한 번만 계산)
 *
 * JdbcTemplate 배치 INSERT로 1000건씩 묶어서 삽입하여 성능을 최적화한다.
 *
 * 실행 예시:
 * java -jar wiki-engine.jar --spring.profiles.active=init-data
 */
@Slf4j
@Component
@Profile("init-data")
@RequiredArgsConstructor
class DummyDataInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    private static final int TOTAL_USERS = 100_000;
    private static final int BATCH_SIZE = 1000;

    @Override
    public void run(String... args) {
        // 이미 데이터가 있으면 스킵
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (count != null && count > 0) {
            log.info("users 테이블에 이미 {}건의 데이터가 존재합니다. 더미 데이터 생성을 건너뜁니다.", count);
            return;
        }

        log.info("더미 유저 데이터 생성 시작: {}명", TOTAL_USERS);
        long startTime = System.currentTimeMillis();

        // BCrypt 해시는 한 번만 계산 (약 100ms, 10만 번 하면 ~3시간이므로)
        String hashedPassword = passwordEncoder.encode("password");
        log.info("기본 비밀번호 BCrypt 해시 생성 완료");

        // 영문/한글 Faker 인스턴스
        Faker enFaker = new Faker(Locale.ENGLISH);
        Faker koFaker = new Faker(Locale.of("ko", "KR"));

        // username 중복 방지용 Set
        Set<String> usedUsernames = new HashSet<>(TOTAL_USERS);
        Set<String> usedNicknames = new HashSet<>(TOTAL_USERS);

        String sql = "INSERT IGNORE INTO users (username, nickname, password, created_at) VALUES (?, ?, ?, ?)";
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int insertedCount = 0;

        while (insertedCount < TOTAL_USERS) {
            // 50% 영문, 50% 한글
            boolean useKorean = insertedCount % 2 == 0;
            Faker faker = useKorean ? koFaker : enFaker;

            String username;
            String nickname;

            // 유니크한 username/nickname이 나올 때까지 생성
            int attempt = 0;
            do {
                String firstName = faker.name().firstName().toLowerCase().replaceAll("[^a-z가-힣]", "");
                String lastName = faker.name().lastName().toLowerCase().replaceAll("[^a-z가-힣]", "");
                int suffix = enFaker.number().numberBetween(1, 99999);

                if (useKorean) {
                    // 한글: username은 로마자 변환 대신 숫자 조합
                    username = "user_kr_" + insertedCount + "_" + suffix;
                    nickname = lastName + firstName + suffix;
                } else {
                    username = firstName + "." + lastName + "." + suffix;
                    nickname = capitalize(firstName) + capitalize(lastName) + suffix;
                }

                attempt++;
                if (attempt > 100) {
                    // 너무 많은 충돌 시 UUID 기반으로 fallback
                    String uuid = enFaker.regexify("[a-z]{8}");
                    username = uuid + "_" + insertedCount;
                    nickname = uuid + insertedCount;
                    break;
                }
            } while (usedUsernames.contains(username) || usedNicknames.contains(nickname));

            // 50자 제한에 맞게 자르기
            username = truncate(username, 50);
            nickname = truncate(nickname, 50);

            usedUsernames.add(username);
            usedNicknames.add(nickname);

            // 생성 시각을 랜덤하게 분포 (최근 2년 내)
            LocalDateTime createdAt = LocalDateTime.now()
                    .minusDays(enFaker.number().numberBetween(0, 730))
                    .minusHours(enFaker.number().numberBetween(0, 24))
                    .minusMinutes(enFaker.number().numberBetween(0, 60));

            batch.add(new Object[]{username, nickname, hashedPassword, createdAt});
            insertedCount++;

            // 배치 크기 도달 시 DB에 저장
            if (batch.size() >= BATCH_SIZE) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();

                if (insertedCount % 10000 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("유저 생성 진행: {} / {}명 ({}초)", insertedCount, TOTAL_USERS, elapsed / 1000);
                }
            }
        }

        // 마지막 남은 배치 처리
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(sql, batch);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("더미 유저 데이터 생성 완료: {}명, 소요 시간 {}초", TOTAL_USERS, elapsed / 1000);
    }

    /** 첫 글자를 대문자로 변환한다. */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /** 문자열을 지정된 길이로 자른다. */
    private String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) return str;
        return str.substring(0, maxLength);
    }
}
