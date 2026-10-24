package com.laststop.app

/** Durable journey instructions, never a cached claim about the user's current location. */
data class SavedJourney(
    val id: String,
    val sourceId: String,
    val destinationId: String,
    val planId: String,
    val legIndex: Int,
    val soundUri: String,
    val startedElapsedMs: Long,
    val startedWallMs: Long,
    val bootCount: Int,
    val processId: Int,
    val processStartedWallMs: Long,
    val restartCount: Int = 0,
    val alertKind: AlarmKind? = null,
    val alertReason: String? = null,
) {
    fun remainingMs(nowMs: Long, currentBootCount: Int): Long {
        if (bootCount != currentBootCount || nowMs < startedElapsedMs) return 0
        return (MAX_SESSION_MS - (nowMs - startedElapsedMs)).coerceAtLeast(0)
    }

    fun plan(): JourneyPlan? = MumbaiNetwork.plans(sourceId, destinationId)
        .firstOrNull { it.id == planId && legIndex in it.legs.indices }

    fun checkpoint(state: JourneyState): SavedJourney = copy(
        alertKind = state.alarmKind.takeIf { state.status == TrackingStatus.ALARM },
        alertReason = state.alarmReason.takeIf { state.status == TrackingStatus.ALARM },
    )

    fun newEngine(nowMs: Long): JourneyEngine? {
        val plan = plan() ?: return null
        val engine = JourneyEngine(plan, legIndex, false, nowMs, recovering = true)
        // A previously confirmed wake-up alarm remains latched until explicitly ended.
        // A GPS warning instead reacquires location, without restoring stale progress.
        if (alertKind != null && alertKind != AlarmKind.TRACKING && !alertReason.isNullOrBlank())
            engine.restoreLatchedAlert(alertKind, alertReason)
        return engine
    }

    fun fields(): Map<String, String> = mapOf(
        "schema" to "1", "id" to id, "source" to sourceId, "destination" to destinationId,
        "plan" to planId, "leg" to legIndex.toString(), "sound" to soundUri,
        "elapsed" to startedElapsedMs.toString(), "wall" to startedWallMs.toString(),
        "boot" to bootCount.toString(), "pid" to processId.toString(),
        "process_wall" to processStartedWallMs.toString(), "restarts" to restartCount.toString(),
        "alert" to (alertKind?.name ?: ""), "reason" to (alertReason ?: ""),
    )

    companion object {
        const val MAX_SESSION_MS = 6 * 60 * 60 * 1_000L

        fun fromFields(fields: Map<String, String>): SavedJourney? = runCatching {
            require(fields["schema"] == "1")
            fun required(key: String) = fields.getValue(key).also { require(it.isNotBlank()) }
            val alert = fields.getValue("alert").takeIf { it.isNotEmpty() }?.let(AlarmKind::valueOf)
            val reason = fields.getValue("reason").takeIf { it.isNotBlank() }
            require((alert == null) == (reason == null))
            SavedJourney(required("id"), required("source"), required("destination"), required("plan"),
                required("leg").toInt(), fields.getValue("sound"), required("elapsed").toLong(),
                required("wall").toLong(), required("boot").toInt(), required("pid").toInt(),
                required("process_wall").toLong(), required("restarts").toInt(), alert, reason).also {
                require(it.startedElapsedMs >= 0 && it.startedWallMs > 0 && it.bootCount >= 0 &&
                    it.processId > 0 && it.processStartedWallMs > 0 && it.restartCount >= 0)
            }
        }.getOrNull()
    }
}
