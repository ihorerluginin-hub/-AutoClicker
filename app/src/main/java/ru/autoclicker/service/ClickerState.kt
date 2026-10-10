package ru.autoclicker.service

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Состояние, доступное UI без прямой связи со службой.
 */
object ClickerState {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var recording: Boolean = false
        private set

    @Volatile
    var clicks: Long = 0L
        private set

    @Volatile
    var iterationsDone: Int = 0
        private set

    /** Сколько точек записано на экране. */
    @Volatile
    var recordedPoints: Int = 0
        private set

    /** Сколько сырых событий касания дошло до службы. Диагностика записи. */
    @Volatile
    var rawEvents: Int = 0
        private set

    /** Причина последней остановки. */
    @Volatile
    var reason: String = ""
        private set

    fun addListener(l: () -> Unit) {
        listeners.add(l)
        l()
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    internal fun update(
        running: Boolean,
        recording: Boolean,
        clicks: Long,
        iterationsDone: Int,
        strokes: Int,
        rawEvents: Int,
        reason: String,
    ) {
        this.running = running
        this.recording = recording
        this.clicks = clicks
        this.iterationsDone = iterationsDone
        this.recordedPoints = strokes
        this.rawEvents = rawEvents
        this.reason = reason
        listeners.forEach { listener -> runCatching { listener() } }
    }
}
