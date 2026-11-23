package com.tc.gymtrainer.service

/** Базовая доменная ошибка сервисного слоя (сообщение уже на RU, годится для показа). */
sealed class ServiceException(message: String) : RuntimeException(message)

/** Запрошенная сущность не найдена или принадлежит другому тренеру: ответ в обоих случаях одинаковый. */
class NotFoundException(message: String) : ServiceException(message)

/** Некорректные входные данные (нарушение доменных правил). */
class ValidationException(message: String) : ServiceException(message)

/** Действие недоступно для этого вида доступа: незавершённая регистрация, ключ вместо сессии. */
class AccessDeniedException(message: String) : ServiceException(message)

class ConflictException(message: String) : ServiceException(message)
class BotUnavailableException(message: String = "Бот пока не подключён. Повторите после запуска Telegram-бота.") : ServiceException(message)
class TooManyRequestsException(val retryAfterSeconds: Long) : ServiceException("Слишком много запросов. Повторите позже.")
