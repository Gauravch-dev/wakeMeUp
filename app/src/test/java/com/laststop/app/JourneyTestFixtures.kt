package com.laststop.app

/** Two distinct, plausible fixes five seconds apart, as received on a journey. */
fun JourneyEngine.confirmAt(fix: Fix): JourneyState {
    accept(fix, fix.elapsedMs)
    val next = fix.copy(elapsedMs = fix.elapsedMs + 5000)
    return accept(next, next.elapsedMs)
}
