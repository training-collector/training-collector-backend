package com.tc.gymtrainer.service

/** Базовая доменная ошибка сервисного слоя (сообщение уже на RU, годится для показа). */
sealed class ServiceException(message: String) : RuntimeException(message)

/** Запрошенная сущность не найдена. */
class NotFoundException(message: String) : ServiceException(message)

/** Некорректные входные данные (нарушение доменных правил). */
class ValidationException(message: String) : ServiceException(message)

/** Попытка доступа к чужому ресурсу (клиент/тренировка другого тренера). */
class AccessDeniedException(message: String) : ServiceException(message)

class LoginException : ServiceException("Код недействителен или истёк. Проверьте код или запросите новый.")
class ConflictException(message: String) : ServiceException(message)
class BotUnavailableException(message: String = "Бот пока не подключён. Повторите после запуска Telegram-бота.") : ServiceException(message)
