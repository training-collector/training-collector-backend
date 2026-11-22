package com.tc.gymtrainer.service

import java.math.BigDecimal

internal fun BigDecimal.fitsDecimal(precision: Int, scale: Int): Boolean {
    val value = stripTrailingZeros()
    return value.scale() <= scale && value.abs() < BigDecimal.TEN.pow(precision - scale)
}
