package com.droiddeck.launcher.session

/**
 * The battery reading the session's sampler and its report share.
 *
 * `BatteryComponent` writes the attributes the guest sees from the sticky power broadcast, but a
 * session that slows down needs the same numbers beside the clocks. A peltier on the back can keep
 * the pack at 34 C while the AP die is at 69 C, and only the current and power say whether the
 * cooler is drawing from the phone's own port. The fields are null until the first write.
 */
object BatterySnapshot {
    @Volatile var currentMa: Int? = null
    @Volatile var powerMw: Int? = null
    @Volatile var tempC: Double? = null
    @Volatile var plugged: Boolean? = null
}
