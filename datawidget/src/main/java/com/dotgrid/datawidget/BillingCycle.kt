package com.dotgrid.datawidget

import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * The rollover date, turned into a window to query.
 *
 * @param startMillis midnight local time on the day the current cycle opened
 * @param endMillis   midnight local time on the day it closes - i.e. the next
 *   rollover. Exclusive: the byte that arrives at 00:00 on rollover day belongs
 *   to the new cycle.
 * @param daysLeft whole days from now until [endMillis], floored at 0.
 */
data class BillingCycle(
    val startMillis: Long,
    val endMillis: Long,
    val daysLeft: Int
)

object CycleMath {

    private val DAY_MS = TimeUnit.DAYS.toMillis(1)

    /**
     * @param cycleDay the day of the month the plan rolls over, 1..31.
     *
     * Short months are the whole reason this is not one line. A plan that rolls
     * over on the 31st has no 31st in February, so the rollover is **clamped to
     * the last day of whichever month it lands in** - 28 Feb, then 31 Mar. The
     * alternative reading, spilling into 1 March, would silently give February a
     * 29-day cycle and make the tile disagree with the bill.
     */
    fun current(nowMillis: Long, cycleDay: Int): BillingCycle {
        val day = cycleDay.coerceIn(1, 31)

        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }

        val thisMonth = rolloverIn(now, day)
        val opened = nowMillis >= thisMonth

        val start = if (opened) thisMonth else rolloverIn(shiftMonths(now, -1), day)
        val end = if (opened) rolloverIn(shiftMonths(now, 1), day) else thisMonth

        val daysLeft = ((end - nowMillis + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(0)
        return BillingCycle(start, end, daysLeft)
    }

    /** Midnight on [day] of the month [reference] falls in, clamped to that month's length. */
    private fun rolloverIn(reference: Calendar, day: Int): Long {
        val c = reference.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)))
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * Calendar.add(MONTH) on the 31st of a month lands on the 30th or 28th of
     * the next one and keeps that shortened day, so the clamp above would then
     * be reading an already-clamped date. Parking on the 1st before the shift
     * keeps the day number the caller asked for the only one in play.
     */
    private fun shiftMonths(reference: Calendar, months: Int): Calendar {
        val c = reference.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.add(Calendar.MONTH, months)
        return c
    }
}
