# Wiki Engine - Docker Compose 명령어
# 사용법: make dev-up, make prod-up, make dev-down 등

.PHONY: dev-up dev-down dev-logs dev-ps prod-up prod-down prod-logs prod-ps clean

# ==================== 개발 환경 ====================
dev-up:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.dev up -d

dev-down:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.dev down

dev-logs:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.dev logs -f

dev-ps:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.dev ps

dev-restart:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.dev restart

dev-build:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.dev build --no-cache

# ==================== 운영 환경 ====================
prod-up:
	docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up -d

prod-down:
	docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod down

prod-logs:
	docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod logs -f

prod-ps:
	docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod ps

prod-restart:
	docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod restart

prod-build:
	docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod build --no-cache

# ==================== 공통 ====================
clean:
	docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.dev down -v --rmi local
	docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod down -v --rmi local

# MySQL 접속
dev-mysql:
	docker exec -it wiki-mysql-dev mysql -u wiki -pwiki1234 wikidb

prod-mysql:
	docker exec -it wiki-mysql-prod mysql -u wiki_prod -p wikidb

# 부하 테스트
k6-dev:
	k6 run --out influxdb=http://localhost:8086/k6 k6/baseline-load-test.js

# 데이터 디렉토리 생성
init-data-dir:
	mkdir -p data

# 도움말
help:
	@echo "사용 가능한 명령어:"
	@echo "  make dev-up      - 개발 환경 시작"
	@echo "  make dev-down    - 개발 환경 종료"
	@echo "  make dev-logs    - 개발 환경 로그 보기"
	@echo "  make dev-ps      - 개발 환경 컨테이너 상태"
	@echo "  make dev-restart - 개발 환경 재시작"
	@echo "  make dev-build   - 개발 환경 이미지 빌드"
	@echo "  make dev-mysql   - 개발 DB 접속"
	@echo ""
	@echo "  make prod-up     - 운영 환경 시작"
	@echo "  make prod-down   - 운영 환경 종료"
	@echo "  make prod-logs   - 운영 환경 로그 보기"
	@echo "  make prod-ps     - 운영 환경 컨테이너 상태"
	@echo "  make prod-restart- 운영 환경 재시작"
	@echo "  make prod-build  - 운영 환경 이미지 빌드"
	@echo "  make prod-mysql  - 운영 DB 접속"
	@echo ""
	@echo "  make k6-dev      - k6 부하 테스트 실행"
	@echo "  make init-data-dir - 위키 덤프 데이터 디렉토리 생성"
	@echo "  make clean       - 모든 컨테이너/볼륨/이미지 삭제"
	@echo ""
	@echo "  데이터 초기화 (.env 파일에서 설정):"
	@echo "    INIT_USERS=true          - 더미 유저 10만 명 자동 생성"
	@echo "    WIKI_IMPORT_ENABLED=true - 위키 덤프 XML 자동 임포트"
	@echo "    WIKI_IMPORT_PATH=/data/xxx.xml - 임포트할 XML 경로"
	@echo "    → XML 파일은 backend/data/ 폴더에 배치"
