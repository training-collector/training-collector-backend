-- Тренеры и авторизация
CREATE TABLE trainer (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(254) UNIQUE,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255),
    -- Имя хранится один раз: полное вычисляется из имени и фамилии.
    full_name VARCHAR(511) GENERATED ALWAYS AS (first_name || coalesce(' ' || last_name, '')) STORED,
    -- IANA-пояс браузера тренера; бот считает в нём «сегодня» и даты тренировок.
    time_zone VARCHAR(64),
    telegram_user_id BIGINT CONSTRAINT uq_trainer_telegram_user_id UNIQUE,
    telegram_linked_at TIMESTAMPTZ,
    training_bot_linked_at TIMESTAMPTZ,
    signup_expires_at TIMESTAMPTZ,
    signup_telegram_in_use BOOLEAN NOT NULL DEFAULT FALSE,
    code_chat_started BOOLEAN NOT NULL DEFAULT FALSE,
    approved_at TIMESTAMPTZ,
    deactivated_at TIMESTAMPTZ,
    api_token_hash VARCHAR(64) UNIQUE,
    api_token_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE web_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainer (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    onboarding BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip VARCHAR(64),
    user_agent VARCHAR(255)
);
CREATE INDEX idx_web_session_expiry ON web_session (expires_at);
CREATE INDEX idx_web_session_trainer ON web_session (trainer_id);

CREATE TABLE admin_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip VARCHAR(64),
    user_agent VARCHAR(255)
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

-- Запрос входа: тренер разрешает или отклоняет его кнопкой в боте авторизации.
-- Сессию получает только браузер, который знает секрет запроса.
CREATE TABLE login_request (
    id VARCHAR(32) PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainer (id) ON DELETE CASCADE,
    secret_hash VARCHAR(64) NOT NULL UNIQUE,
    -- Число, которое показывает сайт и повторяет сообщение в Telegram.
    number SMALLINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'DENIED')),
    ip VARCHAR(64),
    user_agent VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    next_delivery_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_request_trainer ON login_request (trainer_id);
CREATE INDEX idx_login_delivery ON login_request (next_delivery_at) WHERE delivered_at IS NULL;

-- Сообщение о доступе: одна строка на тренера, значение имеет только последнее состояние.
CREATE TABLE trainer_notice (
    id BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT NOT NULL UNIQUE REFERENCES trainer (id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('ACTIVATED', 'DEACTIVATED')),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_trainer_notice_due ON trainer_notice (next_attempt_at);

-- Справочники администратора
CREATE TABLE goal_catalog (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uq_goal_name ON goal_catalog (lower(name));

CREATE TABLE muscle_group (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT
);
CREATE UNIQUE INDEX uq_muscle_group_name ON muscle_group (lower(name));

CREATE TABLE exercise_catalog (
    id BIGSERIAL PRIMARY KEY,
    muscle_group_id BIGINT NOT NULL REFERENCES muscle_group (id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    hidden BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_exercise_catalog_muscle_group ON exercise_catalog (muscle_group_id);
CREATE UNIQUE INDEX uq_live_exercise_name ON exercise_catalog (lower(name)) WHERE NOT hidden;

-- Подопечные и тренировки. История удаляется вместе с тренером и подопечным.
CREATE TABLE client (
    id BIGSERIAL PRIMARY KEY,
    trainer_id BIGINT NOT NULL REFERENCES trainer (id) ON DELETE CASCADE,
    first_name VARCHAR(255) NOT NULL,
    last_name VARCHAR(255),
    full_name VARCHAR(511) GENERATED ALWAYS AS (first_name || coalesce(' ' || last_name, '')) STORED,
    sex VARCHAR(8) CHECK (sex IN ('MALE', 'FEMALE')),
    age INTEGER CHECK (age BETWEEN 0 AND 130),
    telegram_username VARCHAR(32),
    goal_id BIGINT REFERENCES goal_catalog (id) ON DELETE SET NULL,
    goal VARCHAR(128),
    notes TEXT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_client_trainer_id ON client (trainer_id);
CREATE INDEX idx_client_goal ON client (goal_id);

CREATE TABLE workout_session (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL REFERENCES client (id) ON DELETE CASCADE,
    trainer_id BIGINT NOT NULL REFERENCES trainer (id) ON DELETE CASCADE,
    performed_at TIMESTAMPTZ NOT NULL,
    duration_min INTEGER CHECK (duration_min > 0),
    note TEXT,
    stage VARCHAR(20) NOT NULL DEFAULT 'COMPLETED'
        CHECK (stage IN ('CHOOSE_EXERCISE', 'ENTER_SETS', 'REVIEW', 'COMPLETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_workout_session_history ON workout_session (client_id, performed_at DESC, id DESC);
CREATE INDEX idx_workout_session_trainer ON workout_session (trainer_id);
CREATE UNIQUE INDEX uq_workout_session_active_client ON workout_session (client_id) WHERE stage <> 'COMPLETED';

-- Запись тренировки хранит название упражнения и переживает его удаление из справочника.
CREATE TABLE workout_exercise (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES workout_session (id) ON DELETE CASCADE,
    exercise_catalog_id BIGINT REFERENCES exercise_catalog (id) ON DELETE SET NULL,
    exercise_name VARCHAR(255) NOT NULL,
    order_index INTEGER NOT NULL,
    note TEXT
);
CREATE INDEX idx_workout_exercise_session_id ON workout_exercise (session_id);
CREATE INDEX idx_workout_exercise_catalog ON workout_exercise (exercise_catalog_id);

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
    client_id BIGINT NOT NULL REFERENCES client (id) ON DELETE CASCADE,
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
    -- Растёт при каждой правке; защищает от одновременного редактирования.
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_measurement_history ON body_measurement (client_id, measured_on DESC, id DESC);

-- Служебные таблицы Telegram: состояние диалога и квитанции записи
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
    attempts integer NOT NULL DEFAULT 0,
    PRIMARY KEY (role, sender)
);
CREATE INDEX idx_telegram_dialog_seen ON telegram_dialog (last_seen);

CREATE TABLE telegram_write_receipt (
    key uuid PRIMARY KEY,
    result text NOT NULL,
    sender bigint,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX telegram_receipt_created ON telegram_write_receipt (created_at);

-- Счётчики лимитов запросов. Общие для всех экземпляров сервиса.
CREATE TABLE rate_limit (
    bucket VARCHAR(96) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    hits INTEGER NOT NULL
);

-- Журнал событий безопасности и действий администратора
CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    subject VARCHAR(160),
    ip VARCHAR(64),
    detail VARCHAR(255)
);
CREATE INDEX idx_audit_event_at ON audit_event (at);
