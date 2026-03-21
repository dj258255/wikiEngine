#!/bin/bash
# Debezium MySQL Connector 등록
# Debezium Connect가 시작된 후 실행
# 사용법: ./register-connector.sh <debezium-connect-host> <mysql-primary-host> <mysql-password>

CONNECT_HOST="${1:-localhost:8083}"
MYSQL_HOST="${2:-10.0.0.242}"  # MySQL Primary의 private IP
MYSQL_PASSWORD="${3}"

echo "Debezium Connect 준비 대기..."
until curl -sf "http://${CONNECT_HOST}/connectors" > /dev/null 2>&1; do
    echo "  대기 중..."
    sleep 5
done
echo "Debezium Connect 준비 완료"

# 기존 connector가 있으면 삭제
curl -sf -X DELETE "http://${CONNECT_HOST}/connectors/wiki-mysql-connector" 2>/dev/null

echo "MySQL Connector 등록..."
curl -sf -X POST "http://${CONNECT_HOST}/connectors" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "wiki-mysql-connector",
    "config": {
        "connector.class": "io.debezium.connector.mysql.MySqlConnector",
        "tasks.max": "1",

        "database.hostname": "'"${MYSQL_HOST}"'",
        "database.port": "3306",
        "database.user": "debezium",
        "database.password": "'"${MYSQL_PASSWORD}"'",
        "database.server.id": "10",

        "topic.prefix": "dbserver1",
        "database.include.list": "wiki_engine",
        "table.include.list": "wiki_engine.posts",

        "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
        "schema.history.internal.kafka.topic": "_schema_history",

        "include.schema.changes": "false",
        "snapshot.mode": "when_needed",

        "key.converter": "org.apache.kafka.connect.json.JsonConverter",
        "key.converter.schemas.enable": "false",
        "value.converter": "org.apache.kafka.connect.json.JsonConverter",
        "value.converter.schemas.enable": "false",

        "tombstones.on.delete": "false",

        "heartbeat.interval.ms": "10000"
    }
}'

echo ""
echo "등록 결과:"
curl -sf "http://${CONNECT_HOST}/connectors/wiki-mysql-connector/status" | python3 -m json.tool 2>/dev/null || echo "상태 확인 실패"
