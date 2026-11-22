-- Training Collector · PostgreSQL

-- Adopt databases created by the previous Flyway history. Fresh databases skip this block.
DO $upgrade$
DECLARE
    previous integer;
BEGIN
    IF to_regclass(format('%I.trainer', current_schema())) IS NULL THEN RETURN; END IF;
    IF EXISTS (
        SELECT 1 FROM training_schema_history WHERE success AND type = 'SQL'
          AND (script = 'V1__training_collector.sql' OR script = 'R__training_collector.sql')
    ) THEN RETURN; END IF;
    IF to_regclass(format('%I.flyway_schema_history', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'Existing trainer table has no recognized migration history';
    END IF;
    SELECT max(version::integer) INTO previous FROM flyway_schema_history
    WHERE success AND type = 'SQL' AND version ~ '^[0-9]+$';
    IF previous IS NULL OR previous NOT BETWEEN 1 AND 14 THEN
        RAISE EXCEPTION 'Unrecognized legacy schema version: %', previous;
    END IF;
    IF previous < 3 THEN
        ALTER TABLE trainer
            ALTER COLUMN telegram_user_id DROP NOT NULL,
            ADD COLUMN api_token_hash VARCHAR(64) UNIQUE;

        ALTER TABLE exercise_set
            ALTER COLUMN reps DROP NOT NULL,
            ADD COLUMN duration_sec INTEGER,
            ADD CONSTRAINT ck_set_metric CHECK (
                (reps IS NOT NULL AND reps > 0 AND duration_sec IS NULL) OR
                (duration_sec IS NOT NULL AND duration_sec > 0 AND reps IS NULL)
            );

        ALTER TABLE body_measurement
            ADD COLUMN height_cm NUMERIC(5,1),
            ADD COLUMN calf_cm NUMERIC(5,1);
    END IF;
    IF previous < 4 THEN
        ALTER TABLE exercise_catalog
            DROP CONSTRAINT uq_exercise_catalog_name,
            ADD COLUMN trainer_id BIGINT REFERENCES trainer (id);

        -- Historical workout rows also include exercises entered without a catalog id.
        INSERT INTO exercise_catalog (trainer_id, muscle_group_id, name)
        SELECT DISTINCT ON (s.trainer_id, e.muscle_group_id, lower(btrim(e.exercise_name)))
               s.trainer_id, e.muscle_group_id, btrim(e.exercise_name)
        FROM workout_exercise e
        JOIN workout_session s ON s.id = e.session_id
        ORDER BY s.trainer_id, e.muscle_group_id, lower(btrim(e.exercise_name)), e.id;

        UPDATE workout_exercise e
        SET exercise_catalog_id = c.id
        FROM workout_session s, exercise_catalog c
        WHERE s.id = e.session_id AND c.trainer_id = s.trainer_id
          AND c.muscle_group_id = e.muscle_group_id
          AND lower(c.name) = lower(btrim(e.exercise_name));

        DELETE FROM exercise_catalog WHERE trainer_id IS NULL;
        ALTER TABLE exercise_catalog ALTER COLUMN trainer_id SET NOT NULL;
    END IF;
    IF previous < 5 THEN
        ALTER TABLE trainer ADD COLUMN phone_verified_at TIMESTAMPTZ, ADD COLUMN username VARCHAR(254) UNIQUE;
    END IF;
    IF previous = 5 THEN
        -- Existing web trainers retain the identifier they already use to sign in.
        ALTER TABLE trainer RENAME COLUMN email TO username;
        ALTER TABLE trainer RENAME CONSTRAINT trainer_email_key TO trainer_username_key;
    END IF;
    IF previous < 7 THEN
        ALTER TABLE trainer
            ADD COLUMN first_name VARCHAR(255),
            ADD COLUMN last_name VARCHAR(255),
            ADD COLUMN telegram_linked_at TIMESTAMPTZ,
            ADD COLUMN approved_at TIMESTAMPTZ,
            ADD COLUMN signup_expires_at TIMESTAMPTZ,
            ADD COLUMN code_chat_started BOOLEAN NOT NULL DEFAULT FALSE;

        UPDATE trainer SET first_name = split_part(trim(full_name), ' ', 1),
            last_name = nullif(ltrim(substr(trim(full_name), length(split_part(trim(full_name), ' ', 1)) + 1)), '');
        UPDATE trainer SET telegram_linked_at = coalesce(phone_verified_at, created_at),
            approved_at = coalesce(phone_verified_at, created_at),
            username = coalesce(username, 'tg:' || telegram_user_id::text)
        WHERE telegram_user_id IS NOT NULL;
    END IF;
    IF previous BETWEEN 5 AND 6 THEN
        ALTER TABLE trainer DROP COLUMN password_hash;
        ALTER TABLE web_session ADD COLUMN onboarding BOOLEAN NOT NULL DEFAULT FALSE;

        -- Allow abandoned pre-upgrade web signups with no history to release their username.
        UPDATE trainer t SET signup_expires_at = coalesce(
            (SELECT max(s.expires_at) FROM web_session s WHERE s.trainer_id = t.id), now())
        WHERE t.telegram_user_id IS NULL AND t.api_token_hash IS NULL AND t.username IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM client c WHERE c.trainer_id = t.id)
          AND NOT EXISTS (SELECT 1 FROM workout_session w WHERE w.trainer_id = t.id)
          AND NOT EXISTS (SELECT 1 FROM exercise_catalog e WHERE e.trainer_id = t.id);

        DROP TABLE telegram_phone_link;
        ALTER TABLE telegram_bot_identity DROP CONSTRAINT telegram_bot_identity_id_check;
        ALTER TABLE telegram_bot_identity ADD CHECK (id IN (1, 2));
    END IF;
    IF previous < 8 THEN
        ALTER TABLE trainer ADD COLUMN training_bot_linked_at TIMESTAMPTZ;
    END IF;
    IF previous BETWEEN 8 AND 10 THEN
        -- V8 copied the Telegram signup timestamp into this column for pre-existing trainers.
        -- A profile link records its own timestamp after the trainer has signed in.
        UPDATE trainer SET training_bot_linked_at = NULL
        WHERE training_bot_linked_at = telegram_linked_at;
    END IF;
    IF previous < 9 THEN
        ALTER TABLE trainer ADD COLUMN signup_telegram_in_use BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
    IF previous BETWEEN 7 AND 9 THEN
        ALTER TABLE login_challenge ADD COLUMN heading_sent BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
    IF previous < 12 THEN
        ALTER TABLE client
            ADD COLUMN first_name VARCHAR(255),
            ADD COLUMN last_name VARCHAR(255),
            ADD COLUMN sex VARCHAR(8) CHECK (sex IN ('MALE', 'FEMALE')),
            ADD COLUMN age INTEGER CHECK (age BETWEEN 0 AND 130),
            ADD COLUMN telegram_username VARCHAR(32);

        UPDATE client
        SET first_name = split_part(full_name, ' ', 1),
            last_name = nullif(ltrim(substr(full_name, length(split_part(full_name, ' ', 1)) + 1)), '');
    END IF;
    IF previous < 13 THEN
        -- Older exercises retain their ids and their original group for existing API clients.
        ALTER TABLE exercise_catalog ALTER COLUMN muscle_group_id DROP NOT NULL;
        ALTER TABLE workout_exercise ALTER COLUMN muscle_group_id DROP NOT NULL;
    END IF;
END
$upgrade$;

CREATE TABLE IF NOT EXISTS trainer (
    id               BIGSERIAL PRIMARY KEY,
    telegram_user_id BIGINT,
    full_name        VARCHAR(255) NOT NULL,
    phone            VARCHAR(64),
    api_token_hash VARCHAR(64) UNIQUE,
    phone_verified_at TIMESTAMPTZ,
    username VARCHAR(254) UNIQUE,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    telegram_linked_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    signup_expires_at TIMESTAMPTZ,
    code_chat_started BOOLEAN NOT NULL DEFAULT FALSE,
    training_bot_linked_at TIMESTAMPTZ,
    signup_telegram_in_use BOOLEAN NOT NULL DEFAULT FALSE,
    consent_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_trainer_telegram_user_id UNIQUE (telegram_user_id)
);

CREATE TABLE IF NOT EXISTS client (
    id         BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT       NOT NULL REFERENCES trainer (id),
    full_name  VARCHAR(255) NOT NULL,
    phone      VARCHAR(64),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    sex VARCHAR(8) CHECK (sex IN ('MALE', 'FEMALE')),
    age INTEGER CHECK (age BETWEEN 0 AND 130),
    telegram_username VARCHAR(32),
    goal       VARCHAR(32),
    notes      TEXT,
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_client_trainer_id ON client (trainer_id);

CREATE TABLE IF NOT EXISTS muscle_group (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    CONSTRAINT uq_muscle_group_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS exercise_catalog (
    id              BIGSERIAL PRIMARY KEY,
    trainer_id      BIGINT NOT NULL REFERENCES trainer (id),
    muscle_group_id BIGINT REFERENCES muscle_group (id),
    name            VARCHAR(255) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_exercise_catalog_muscle_group ON exercise_catalog (muscle_group_id);
ALTER TABLE exercise_catalog ADD COLUMN IF NOT EXISTS description TEXT;

CREATE TABLE IF NOT EXISTS workout_session (
    id           BIGSERIAL PRIMARY KEY,
    client_id    BIGINT      NOT NULL REFERENCES client (id),
    trainer_id   BIGINT      NOT NULL REFERENCES trainer (id),
    performed_at TIMESTAMPTZ NOT NULL,
    duration_min INT,
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_workout_session_client_id ON workout_session (client_id);
CREATE INDEX IF NOT EXISTS idx_workout_session_performed_at ON workout_session (performed_at);
ALTER TABLE workout_session ADD COLUMN IF NOT EXISTS stage VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'
    CHECK (stage IN ('CHOOSE_TEMPLATE', 'CHOOSE_EXERCISE', 'ENTER_SETS', 'REVIEW', 'COMPLETED'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_workout_session_active_client ON workout_session (client_id)
    WHERE stage <> 'COMPLETED';

CREATE TABLE IF NOT EXISTS workout_exercise (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT       NOT NULL REFERENCES workout_session (id) ON DELETE CASCADE,
    muscle_group_id     BIGINT REFERENCES muscle_group (id),
    exercise_catalog_id BIGINT       REFERENCES exercise_catalog (id),
    exercise_name       VARCHAR(255) NOT NULL,
    order_index         INT          NOT NULL,
    note                TEXT
);
CREATE INDEX IF NOT EXISTS idx_workout_exercise_session_id ON workout_exercise (session_id);

CREATE TABLE IF NOT EXISTS exercise_set (
    id          BIGSERIAL PRIMARY KEY,
    exercise_id BIGINT       NOT NULL REFERENCES workout_exercise (id) ON DELETE CASCADE,
    set_number  INT          NOT NULL,
    weight_kg   NUMERIC(6, 2),
    reps        INT,
    duration_sec INT,
    rest_sec    INT,
    rpe         NUMERIC(3, 1),
    CONSTRAINT ck_set_metric CHECK (
        (reps IS NOT NULL AND reps > 0 AND duration_sec IS NULL) OR
        (duration_sec IS NOT NULL AND duration_sec > 0 AND reps IS NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_exercise_set_exercise_id ON exercise_set (exercise_id);

CREATE TABLE IF NOT EXISTS body_measurement (
    id           BIGSERIAL PRIMARY KEY,
    client_id    BIGINT      NOT NULL REFERENCES client (id),
    measured_on  DATE        NOT NULL,
    weight_kg    NUMERIC(5, 2),
    body_fat_pct NUMERIC(4, 1),
    chest_cm     NUMERIC(5, 1),
    waist_cm     NUMERIC(5, 1),
    hips_cm      NUMERIC(5, 1),
    biceps_cm    NUMERIC(5, 1),
    height_cm    NUMERIC(5, 1),
    calf_cm      NUMERIC(5, 1),
    thigh_cm     NUMERIC(5, 1),
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_body_measurement_client_id ON body_measurement (client_id);

CREATE TABLE IF NOT EXISTS web_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainer(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    onboarding BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_web_session_expiry ON web_session(expires_at);

CREATE TABLE IF NOT EXISTS telegram_bot_identity (
    id INTEGER PRIMARY KEY CHECK (id IN (1, 2)),
    username VARCHAR(32) NOT NULL
);

CREATE TABLE IF NOT EXISTS telegram_link (
    token_hash VARCHAR(64) PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainer(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_telegram_link_trainer ON telegram_link(trainer_id);
CREATE INDEX IF NOT EXISTS idx_telegram_link_expiry ON telegram_link(expires_at);

CREATE TABLE IF NOT EXISTS login_challenge (
    trainer_id BIGINT PRIMARY KEY REFERENCES trainer(id) ON DELETE CASCADE,
    challenge_id VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    code_hash VARCHAR(160),
    delivered_at TIMESTAMPTZ,
    heading_sent BOOLEAN NOT NULL DEFAULT FALSE,
    next_delivery_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_code_delivery ON login_challenge(next_delivery_at) WHERE delivered_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_measurement_history ON body_measurement (client_id, measured_on DESC, id DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_trainer_verified_phone ON trainer (phone) WHERE phone_verified_at IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_exercise_catalog_trainer_group_name
    ON exercise_catalog (trainer_id, muscle_group_id, lower(name));
CREATE UNIQUE INDEX IF NOT EXISTS uq_exercise_catalog_ungrouped_name
    ON exercise_catalog (trainer_id, lower(name))
    WHERE muscle_group_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_telegram_bot_role ON telegram_bot_identity(lower(username));

ALTER TABLE body_measurement
    ADD COLUMN IF NOT EXISTS right_biceps_cm NUMERIC(5,1) CHECK (right_biceps_cm > 0),
    ADD COLUMN IF NOT EXISTS left_biceps_cm NUMERIC(5,1) CHECK (left_biceps_cm > 0),
    ADD COLUMN IF NOT EXISTS right_forearm_cm NUMERIC(5,1) CHECK (right_forearm_cm > 0),
    ADD COLUMN IF NOT EXISTS left_forearm_cm NUMERIC(5,1) CHECK (left_forearm_cm > 0),
    ADD COLUMN IF NOT EXISTS back_width_cm NUMERIC(5,1) CHECK (back_width_cm > 0),
    ADD COLUMN IF NOT EXISTS right_thigh_cm NUMERIC(5,1) CHECK (right_thigh_cm > 0),
    ADD COLUMN IF NOT EXISTS left_thigh_cm NUMERIC(5,1) CHECK (left_thigh_cm > 0),
    ADD COLUMN IF NOT EXISTS right_calf_cm NUMERIC(5,1) CHECK (right_calf_cm > 0),
    ADD COLUMN IF NOT EXISTS left_calf_cm NUMERIC(5,1) CHECK (left_calf_cm > 0);

-- The legacy group-filter API needs the reference rows originally introduced in V2.
DO $groups$
BEGIN
    IF EXISTS (SELECT 1 FROM training_schema_history WHERE success AND type = 'SQL') THEN RETURN; END IF;
    IF to_regclass(format('%I.flyway_schema_history', current_schema())) IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE success AND type = 'SQL' AND version <> '1') THEN
            RETURN;
        END IF;
    END IF;
    INSERT INTO muscle_group (name) VALUES
        ('Грудь'), ('Спина'), ('Ноги'), ('Плечи'), ('Руки'), ('Пресс'), ('Всё тело')
    ON CONFLICT (name) DO NOTHING;
END
$groups$;
