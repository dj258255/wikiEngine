-- V2__create_event_publication.sql
-- Spring Boot 4.0 Event Publication 테이블 (프레임워크 내부 사용)

CREATE TABLE IF NOT EXISTS event_publication (
    id                     BINARY(16)     NOT NULL,
    completion_attempts    INT            NOT NULL,
    completion_date        DATETIME(6)    DEFAULT NULL,
    event_type             VARCHAR(255)   DEFAULT NULL,
    last_resubmission_date DATETIME(6)    DEFAULT NULL,
    listener_id            VARCHAR(255)   DEFAULT NULL,
    publication_date       DATETIME(6)    DEFAULT NULL,
    serialized_event       VARCHAR(255)   DEFAULT NULL,
    status                 ENUM('COMPLETED','FAILED','PROCESSING','PUBLISHED','RESUBMITTED') DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
