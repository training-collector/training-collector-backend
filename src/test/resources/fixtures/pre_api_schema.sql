-- PostgreSQL test fixture representing a populated V2 installation.
CREATE TABLE trainer (
    id               BIGSERIAL PRIMARY KEY,
    telegram_user_id BIGINT      NOT NULL,
    full_name        VARCHAR(255) NOT NULL,
    phone            VARCHAR(64),
    consent_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_trainer_telegram_user_id UNIQUE (telegram_user_id)
);

CREATE TABLE client (
    id         BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT       NOT NULL REFERENCES trainer (id),
    full_name  VARCHAR(255) NOT NULL,
    phone      VARCHAR(64),
    goal       VARCHAR(32),
    notes      TEXT,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_client_trainer_id ON client (trainer_id);

CREATE TABLE muscle_group (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    CONSTRAINT uq_muscle_group_name UNIQUE (name)
);

CREATE TABLE exercise_catalog (
    id              BIGSERIAL PRIMARY KEY,
    muscle_group_id BIGINT       NOT NULL REFERENCES muscle_group (id),
    name            VARCHAR(255) NOT NULL,
    CONSTRAINT uq_exercise_catalog_name UNIQUE (name)
);
CREATE INDEX idx_exercise_catalog_muscle_group ON exercise_catalog (muscle_group_id);

CREATE TABLE workout_session (
    id           BIGSERIAL PRIMARY KEY,
    client_id    BIGINT      NOT NULL REFERENCES client (id),
    trainer_id   BIGINT      NOT NULL REFERENCES trainer (id),
    performed_at TIMESTAMPTZ NOT NULL,
    duration_min INT,
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_workout_session_client_id ON workout_session (client_id);
CREATE INDEX idx_workout_session_performed_at ON workout_session (performed_at);

CREATE TABLE workout_exercise (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT       NOT NULL REFERENCES workout_session (id) ON DELETE CASCADE,
    muscle_group_id     BIGINT       NOT NULL REFERENCES muscle_group (id),
    exercise_catalog_id BIGINT       REFERENCES exercise_catalog (id),
    exercise_name       VARCHAR(255) NOT NULL,
    order_index         INT          NOT NULL,
    note                TEXT
);
CREATE INDEX idx_workout_exercise_session_id ON workout_exercise (session_id);

CREATE TABLE exercise_set (
    id          BIGSERIAL PRIMARY KEY,
    exercise_id BIGINT       NOT NULL REFERENCES workout_exercise (id) ON DELETE CASCADE,
    set_number  INT          NOT NULL,
    weight_kg   NUMERIC(6, 2),
    reps        INT          NOT NULL,
    rest_sec    INT,
    rpe         NUMERIC(3, 1)
);
CREATE INDEX idx_exercise_set_exercise_id ON exercise_set (exercise_id);

CREATE TABLE body_measurement (
    id           BIGSERIAL PRIMARY KEY,
    client_id    BIGINT      NOT NULL REFERENCES client (id),
    measured_on  DATE        NOT NULL,
    weight_kg    NUMERIC(5, 2),
    body_fat_pct NUMERIC(4, 1),
    chest_cm     NUMERIC(5, 1),
    waist_cm     NUMERIC(5, 1),
    hips_cm      NUMERIC(5, 1),
    biceps_cm    NUMERIC(5, 1),
    thigh_cm     NUMERIC(5, 1),
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_body_measurement_client_id ON body_measurement (client_id);
INSERT INTO muscle_group (name) VALUES ('Грудь');
INSERT INTO exercise_catalog (muscle_group_id, name) VALUES (1, 'Жим штанги лёжа');
CREATE TABLE flyway_schema_history (version varchar(50), type varchar(20), success boolean);
INSERT INTO flyway_schema_history VALUES ('2', 'SQL', true);
