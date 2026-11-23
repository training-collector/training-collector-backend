-- Тренеры и авторизация
CREATE TABLE trainer (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(254) UNIQUE,
    full_name VARCHAR(255) NOT NULL,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    phone VARCHAR(64),
    telegram_user_id BIGINT CONSTRAINT uq_trainer_telegram_user_id UNIQUE,
    telegram_linked_at TIMESTAMPTZ,
    training_bot_linked_at TIMESTAMPTZ,
    signup_expires_at TIMESTAMPTZ,
    signup_telegram_in_use BOOLEAN NOT NULL DEFAULT FALSE,
    code_chat_started BOOLEAN NOT NULL DEFAULT FALSE,
    approved_at TIMESTAMPTZ,
    deactivated_at TIMESTAMPTZ,
    consent_at TIMESTAMPTZ,
    api_token_hash VARCHAR(64) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE web_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainer (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    onboarding BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_web_session_expiry ON web_session (expires_at);

CREATE TABLE admin_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE telegram_bot_identity (
    id INTEGER PRIMARY KEY CHECK (id IN (1, 2)),
    username VARCHAR(32) NOT NULL
);
CREATE UNIQUE INDEX uq_telegram_bot_role ON telegram_bot_identity (lower(username));

CREATE TABLE telegram_link (
    token_hash VARCHAR(64) PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainer (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_telegram_link_trainer ON telegram_link (trainer_id);
CREATE INDEX idx_telegram_link_expiry ON telegram_link (expires_at);

CREATE TABLE login_challenge (
    trainer_id BIGINT PRIMARY KEY REFERENCES trainer (id) ON DELETE CASCADE,
    challenge_id VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    code_hash VARCHAR(160),
    delivered_at TIMESTAMPTZ,
    heading_sent BOOLEAN NOT NULL DEFAULT FALSE,
    next_delivery_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_code_delivery ON login_challenge (next_delivery_at) WHERE delivered_at IS NULL;

CREATE TABLE trainer_access_notification (
    id BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainer (id) ON DELETE CASCADE,
    active BOOLEAN NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_access_notification_trainer ON trainer_access_notification (trainer_id, id);

-- Справочники администратора
CREATE TABLE goal_catalog (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_goal_name ON goal_catalog (lower(name));

CREATE TABLE muscle_group (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL CONSTRAINT uq_muscle_group_name UNIQUE,
    description TEXT
);

CREATE TABLE exercise_catalog (
    id BIGSERIAL PRIMARY KEY,
    muscle_group_id BIGINT NOT NULL REFERENCES muscle_group (id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    hidden BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_exercise_catalog_muscle_group ON exercise_catalog (muscle_group_id);
CREATE UNIQUE INDEX uq_live_exercise_name ON exercise_catalog (lower(name)) WHERE NOT hidden;

-- Подопечные и тренировки
CREATE TABLE client (
    id BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainer (id),
    full_name VARCHAR(255) NOT NULL,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    sex VARCHAR(8) CHECK (sex IN ('MALE', 'FEMALE')),
    age INTEGER CHECK (age BETWEEN 0 AND 130),
    telegram_username VARCHAR(32),
    phone VARCHAR(64),
    goal_id BIGINT REFERENCES goal_catalog (id) ON DELETE SET NULL,
    goal VARCHAR(128),
    notes TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_client_trainer_id ON client (trainer_id);

CREATE TABLE workout_session (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES client (id),
    trainer_id BIGINT NOT NULL REFERENCES trainer (id),
    performed_at TIMESTAMPTZ NOT NULL,
    duration_min INTEGER CHECK (duration_min > 0),
    note TEXT,
    stage VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'
        CHECK (stage IN ('CHOOSE_TEMPLATE', 'CHOOSE_EXERCISE', 'ENTER_SETS', 'REVIEW', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_workout_session_client_id ON workout_session (client_id);
CREATE INDEX idx_workout_session_performed_at ON workout_session (performed_at);
CREATE UNIQUE INDEX uq_workout_session_active_client ON workout_session (client_id) WHERE stage <> 'COMPLETED';

CREATE TABLE workout_exercise (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES workout_session (id) ON DELETE CASCADE,
    muscle_group_id BIGINT REFERENCES muscle_group (id),
    exercise_catalog_id BIGINT REFERENCES exercise_catalog (id),
    exercise_name VARCHAR(255) NOT NULL,
    order_index INTEGER NOT NULL,
    note TEXT
);
CREATE INDEX idx_workout_exercise_session_id ON workout_exercise (session_id);

CREATE TABLE exercise_set (
    id BIGSERIAL PRIMARY KEY,
    exercise_id BIGINT NOT NULL REFERENCES workout_exercise (id) ON DELETE CASCADE,
    set_number INTEGER NOT NULL,
    weight_kg NUMERIC(6, 2),
    reps INTEGER,
    duration_sec INTEGER,
    rest_sec INTEGER,
    rpe NUMERIC(3, 1),
    CONSTRAINT ck_set_metric CHECK (
        (reps IS NOT NULL AND reps > 0 AND duration_sec IS NULL) OR
        (duration_sec IS NOT NULL AND duration_sec > 0 AND reps IS NULL)
    )
);
CREATE INDEX idx_exercise_set_exercise_id ON exercise_set (exercise_id);

-- История замеров
CREATE TABLE body_measurement (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES client (id),
    measured_on DATE NOT NULL,
    height_cm NUMERIC(5, 1),
    weight_kg NUMERIC(5, 2),
    body_fat_pct NUMERIC(4, 1),
    chest_cm NUMERIC(5, 1),
    back_width_cm NUMERIC(5, 1) CHECK (back_width_cm > 0),
    waist_cm NUMERIC(5, 1),
    hips_cm NUMERIC(5, 1),
    biceps_cm NUMERIC(5, 1),
    right_biceps_cm NUMERIC(5, 1) CHECK (right_biceps_cm > 0),
    left_biceps_cm NUMERIC(5, 1) CHECK (left_biceps_cm > 0),
    right_forearm_cm NUMERIC(5, 1) CHECK (right_forearm_cm > 0),
    left_forearm_cm NUMERIC(5, 1) CHECK (left_forearm_cm > 0),
    thigh_cm NUMERIC(5, 1),
    right_thigh_cm NUMERIC(5, 1) CHECK (right_thigh_cm > 0),
    left_thigh_cm NUMERIC(5, 1) CHECK (left_thigh_cm > 0),
    calf_cm NUMERIC(5, 1),
    right_calf_cm NUMERIC(5, 1) CHECK (right_calf_cm > 0),
    left_calf_cm NUMERIC(5, 1) CHECK (left_calf_cm > 0),
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_measurement_history ON body_measurement (client_id, measured_on DESC, id DESC);

CREATE TABLE telegram_dialog (
    role integer NOT NULL,
    sender bigint NOT NULL,
    last_update bigint,
    last_seen timestamptz NOT NULL DEFAULT now(),
    lease uuid,
    lease_until timestamptz,
    draft text,
    pending_update bigint,
    pending_draft text,
    PRIMARY KEY (role, sender)
);

CREATE TABLE telegram_write_receipt (
    key uuid PRIMARY KEY,
    result text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX telegram_receipt_created ON telegram_write_receipt (created_at);
