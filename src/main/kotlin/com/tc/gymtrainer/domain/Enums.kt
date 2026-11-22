package com.tc.gymtrainer.domain

/**
 * Цель работы с подопечным. Хранится по имени константы (enumerationByName),
 * RU-подписи вынесены в [label], чтобы доменное ядро не зависело от слоя вывода.
 */
enum class Goal(val label: String) {
    MASS_GAIN("Набор массы"),
    WEIGHT_LOSS("Похудение"),
    REHAB("Реабилитация"),
    MAINTENANCE("Поддержание");

    companion object {
        val available = entries.filter { it != REHAB }
    }
}

/** Статус подопечного в базе тренера. */
enum class ClientStatus(val label: String) {
    ACTIVE("Активен"),
    ARCHIVED("В архиве");
}
