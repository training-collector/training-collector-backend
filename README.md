# Training Collector Backend

HTTP API на Ktor. Платформа хранит данные в PostgreSQL 16 и применяет миграции Flyway при запуске.

## Запуск

Нужны JDK 25 и PostgreSQL. Скопируйте `.env.example` в `.env` и заполните `DB_NAME`, `DB_USER` и `DB_PASSWORD`. По умолчанию платформа подключается к `jdbc:postgresql://localhost:5433/training`. Для внешней БД задайте `DB_JDBC_URL`, `DB_USER` и `DB_PASSWORD`. Пул настраивается через `DB_MAX_POOL_SIZE`.

Для ботов сгенерируйте два независимых ключа командой `openssl rand -hex 32`. Значение `AUTH_SERVICE_KEY` укажите также в конфигурации бота авторизации, `TRAINING_SERVICE_KEY` — в конфигурации бота тренировок. Если ключи пусты, соответствующие служебные маршруты закрыты.

```sh
./gradlew run
curl http://localhost:8080/health
```

Для Docker выполните `docker build -t training-collector-backend .` и `docker run --env-file .env -p 8080:8080 training-collector-backend`. Внутри контейнера в `DB_JDBC_URL` укажите хост PostgreSQL, доступный из его сети. HTTP слушает `SERVER_HOST` и `SERVER_PORT`. Telegram-токены задаются только в соответствующих ботах.

## Vercel

Импортируйте этот репозиторий как отдельный проект. Vercel соберёт корневой Dockerfile с пресетом Container. Задайте переменные окружения перед первым деплоем.

| Переменная | Значение |
|---|---|
| `PORT` | `8080`. Vercel направляет HTTP на этот порт, Ktor читает его при старте. Локально по-прежнему работает `SERVER_PORT`. |
| `DB_JDBC_URL` | JDBC-адрес внешней PostgreSQL с TLS, например `jdbc:postgresql://db.example.com:5432/training?sslmode=verify-full`. Пользователю БД нужны права на создание и обновление схемы для Flyway. |
| `DB_USER`, `DB_PASSWORD` | Учётные данные этой базы. |
| `AUTH_SERVICE_KEY`, `TRAINING_SERVICE_KEY` | Два разных секрета. Каждый бот получает свой ключ. |

Учитывайте лимит подключений PostgreSQL при настройке `DB_MAX_POOL_SIZE` (по умолчанию 5 на экземпляр). Выберите регион выполнения рядом с БД. После деплоя откройте `/health` по постоянному HTTPS-домену Production. Этот адрес должен быть доступен фронтенду и ботам без интерактивного входа в Vercel. Используйте отдельную базу и отдельные ключи для Preview.

## Администрирование

Задайте `ADMIN_LOGIN` и `ADMIN_PASSWORD` в окружении бэкенда. Пустое значение закрывает вход. `POST /admin/v1/login` принимает `login` и `password`, возвращает токен на 8 часов. Для последующих запросов используйте Bearer. `POST /admin/v1/logout` отзывает токен.

- `GET /admin/v1/trainers` показывает привязавших Telegram тренеров. `POST /admin/v1/trainers/{id}/approve` одобряет заявку и создаёт код. Повторное одобрение активного тренера безопасно.
- `POST /admin/v1/trainers/{id}/deactivate` закрывает кабинет и обоих ботов, отзывает сессии, API-ключ, ссылки и коды. `DELETE /admin/v1/trainers/{id}` удаляет также подопечных и их историю.
- `/admin/v1/exercises` и `/admin/v1/goals` поддерживают GET, POST и по идентификатору PUT и DELETE. Поле `hidden` управляет публикацией. Старые личные упражнения переходят в скрытые, история тренировок сохраняет исходные названия. Действующие тренеры сохраняют прежнее одобрение.

## Тесты

`./gradlew build` запускает проверки сервисов, миграций и HTTP API с Testcontainers. Нужен Docker. Сквозные проверки обоих Telegram-диалогов поднимают временную базу и настоящий HTTP-сервер. Они запускаются отдельно при наличии исходников обоих ботов в любых каталогах:

```sh
./gradlew -p integration test -PtrainingBotDir=/path/to/training-collector-bot -PauthBotDir=/path/to/training-collector-auth-bot
```

## Записать первую тренировку

Пример для `bash` и `jq`. После входа по коду передайте токен веб-сессии из ответа `POST /api/v1/auth/login/verify` в переменной окружения `TRAINER_TOKEN`.

```sh
api=http://localhost:8080/api/v1
auth="Authorization: Bearer ${TRAINER_TOKEN:?Укажите токен сессии}"

client=$(curl -fsS "$api/clients" -H "$auth" -H 'Content-Type: application/json' \
  -d '{"fullName":"Ирина"}' | jq -er .id)
# Сначала опубликуйте оба упражнения в админке.
exercises=$(curl -fsS "$api/catalog/exercises" -H "$auth")
crunch=$(jq -er '.[] | select(.name == "Скручивания") | .id' <<< "$exercises")
plank=$(jq -er '.[] | select(.name == "Планка") | .id' <<< "$exercises")

curl -fsS "$api/clients/$client/workouts" -H "$auth" -H 'Content-Type: application/json' -d "{
  \"performedAt\":\"2026-11-21T10:00:00+04:00\",
  \"durationMin\":45,
  \"exercises\":[
    {\"exerciseCatalogId\":$crunch,\"sets\":[{\"reps\":12,\"weightKg\":5},{\"reps\":10}]},
    {\"exerciseCatalogId\":$plank,\"sets\":[{\"durationSec\":45}]}
  ]
}"
```

Тренер определяется по токену, подопечный — по адресу запроса. Каждый подход содержит ровно один положительный целочисленный результат — `reps` или `durationSec`. Можно добавить `weightKg` в килограммах, `restSec` в секундах и `rpe` от 0 до 10. Каждое упражнение содержит хотя бы один подход. Тренировка сохраняется целиком в одной транзакции. В ответе `session` содержит дату и идентификаторы тренера, подопечного и тренировки, а `exercises` — упорядоченные пары `exercise` и `sets`.

## Замеры и история

Обхваты рук, предплечий, бёдер и голеней вводятся отдельно справа и слева. Ширину спины измеряют горизонтально по лопаткам между задними краями подмышек. Замеры прежнего формата сохраняются с пометкой «без указания стороны». Каждый POST добавляет запись на указанную дату. Отправляйте только измеренные показатели.

```sh
curl -fsS "$api/clients/$client/measurements" -H "$auth" -H 'Content-Type: application/json' \
  -d '{"measuredOn":"2026-11-01","heightCm":175,"weightKg":72.5,"waistCm":81}'
curl -fsS "$api/clients/$client/measurements" -H "$auth" -H 'Content-Type: application/json' \
  -d '{"measuredOn":"2026-11-21","weightKg":71.8,"calfCm":36}'
curl -fsS "$api/clients/$client/measurements" -H "$auth"
curl -fsS "$api/clients/$client/measurements/current" -H "$auth"
```

История возвращает обе записи с исходными датами, от новых к старым. В `current` у каждого показателя свои `value` и `measuredOn`. В примере актуальный вес равен 71,8 кг на 21 ноября, а последнее измерение талии — 81 см на 1 ноября. При нескольких записях одного показателя за день приоритет у последней добавленной.

| Поле | Показатель |
|---|---|
| `heightCm`, `weightKg`, `bodyFatPct` | Рост, масса тела, процент жира |
| `bicepsCm`, `waistCm`, `chestCm` | Обхваты руки, талии и груди |
| `hipsCm`, `thighCm`, `calfCm` | Обхваты по ягодицам, одного бедра и икры |
| `rightBicepsCm`, `leftBicepsCm` | Обхват правой и левой руки |
| `rightForearmCm`, `leftForearmCm` | Обхват правого и левого предплечья |
| `rightThighCm`, `leftThighCm`, `rightCalfCm`, `leftCalfCm` | Обхват каждого бедра и голени |
| `backWidthCm` | Ширина спины, см |
| `note` | Комментарий к записи |

## HTTP API

Префикс маршрутов — `/api/v1`. Для защищённых маршрутов передайте `Authorization: Bearer <token>`. При регистрации выдаётся ограниченный токен для привязки Telegram на 24 часа. Доступ к данным кабинета открывает веб-сессия, полученная после проверки одноразового кода. Пока привязка не завершена, заявку можно отменить с сайта.

| Метод и путь | Назначение |
|---|---|
| `POST /trainers` | Создать заявку по `username`, `firstName`, `lastName`. Ответ содержит `trainer` и ограниченный `token` |
| `POST /trainers/me/telegram-link` | Получить `url` для Auth Bot, `challenge` и `expiresAt` для ввода первого кода. Ссылка действует до пяти минут. После `/start` заявка поступает администратору. Одобрение ставит первый код в очередь доставки. После одобрения запросите ссылку снова для получения актуального challenge |
| `GET /trainers/me` | Профиль с `telegramLinkedAt`, `approvedAt` и `trainingBotLinkedAt` |
| `POST /trainers/me/training-bot-link` | После входа по коду получить персональную ссылку на бота для тренировок на 10 минут. Подтверждается командой `/start` с того же Telegram-аккаунта |
| `POST /auth/login` | Запросить код по `username`. Ответ содержит `challenge`, `botUrl`, `expiresAt`. Одобренному профилю код доставляет бот авторизации после активации чата |
| `POST /auth/login/verify` | Обменять `challenge` и строку `code` из шести цифр на веб-сессию. Просроченный, неверный или уже использованный код даёт 401 |
| `POST /auth/logout` | Завершить текущую сессию или отменить ещё не привязанную заявку |
| `POST /trainers/me/api-key` | Выпустить ключ для прямой интеграции с API по действующей веб-сессии |
| `POST /trainers/me/token` | Заменить текущий ключ API. Передайте этот ключ как Bearer и `newToken` из 32 криптографически случайных байт в base64url без дополнения. Ответ 204, старый ключ сразу отозван |
| `POST /clients`, `GET /clients` | Создать и получить список своих подопечных. Для списка с архивом — `?includeArchived=true` |
| `GET /clients/{id}`, `PUT /clients/{id}` | Прочитать или заменить поля карточки — `fullName`, `phone`, `goal`, `notes` |
| `GET /trainers/me/bot-links` | Адреса чатов бота авторизации и подключённого тренировочного бота для профиля тренера |
| `GET /catalog/muscle-groups` | Справочник групп для прежних версий API |
| `GET /catalog/exercises`, `GET /catalog/muscle-groups/{id}/exercises` | Опубликованные общие упражнения целиком или по исторической группе |
| `POST /clients/{id}/workouts`, `GET /clients/{id}/workouts` | Записать тренировку или получить историю. Фильтры — `from` включительно, `to` исключительно (ISO 8601 с часовым поясом), `exercise` по части названия |
| `GET /workouts/{id}` | Получить тренировку с упражнениями и результатами подходов |
| `PUT /sets/{id}` | Заменить результат подхода. Тело того же формата, что при создании. Успешный ответ — 204 |
| `POST /clients/{id}/measurements`, `GET /clients/{id}/measurements` | Добавить датированный замер или прочитать историю |
| `GET /clients/{id}/measurements/current` | Прочитать последнее значение и дату измерения каждого показателя |

Операции редактирования и удаления доступны владельцу записи:

| Метод и путь | Действие |
|---|---|
| `POST /clients/{id}/archive`, `POST /clients/{id}/restore`, `DELETE /clients/{id}` | Архивировать, вернуть или удалить карточку вместе с историей |
| `PUT /workouts/{id}`, `DELETE /workouts/{id}` | Изменить дату, длительность и заметку или удалить тренировку |
| `POST /workouts/{id}/exercises`, `DELETE /exercises/{id}` | Добавить упражнение с подходами или удалить его |
| `POST /exercises/{id}/sets`, `DELETE /sets/{id}` | Добавить или удалить подход |
| `PUT /measurements/{id}`, `DELETE /measurements/{id}` | Исправить датированную запись целиком или удалить её |

Для записи передайте `exerciseCatalogId` из общего справочника и `sets`. Старые клиенты могут передать имя опубликованного упражнения. Сервер возвращает 404 для неизвестного или скрытого упражнения. Доступные цели возвращает `GET /catalog/goals`. В карточке подопечного поле `goal` содержит название.

При создании тренера, подопечного, тренировки, упражнения в тренировке, подхода или замера сервер возвращает 201. Для некорректного ввода API возвращает 400, для отсутствующего или недействительного токена — 401, для чужой тренировки или карточки — 403, для отсутствующей записи — 404.
