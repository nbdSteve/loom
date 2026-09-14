/*
 * One operator, watching one agent, reading only the research record.
 *
 * WHY THIS IS READ-ONLY BY CONSTRUCTION. The observatory holds no `BodyPort`, no
 * cognition bridge, and no world handle - it could not disturb a run if it tried. Its
 * only input is the `BodyEventRecord` stream that Loom already writes, offered to it by
 * `ObservedEventLog` *after* the real log has taken the record. So attaching a watcher
 * adds no perception, no decision, no tick work, and no record: the display is a
 * consumer of the run and never a participant in it, and every acceptance test of that
 * property is a test about types rather than about timing.
 *
 * WHY NO LINE REACHES AN OPERATOR EXCEPT FROM [refresh]. `saw` can be called from
 * whichever thread appended a record, and Bukkit is a main-thread API. So `saw` only
 * accumulates, and every line an operator reads is delivered by `refresh`, which the plugin
 * calls on its own server timer. That also gives the display its noise bound for free:
 * however many records a tick produces, a watcher hears at most [maxPendingNotes] lines per
 * refresh.
 *
 * The sink itself is touched from three more places - [watch] displaces a surface, and
 * [stop] and [shutDown] take one down - so those three must be called on the main thread.
 * Nothing here enforces that; it holds because each is reached only from
 * `WatchCommand.onCommand` or `LoomPlugin.onDisable`, and it is stated on each of them
 * because a caller who believed only `refresh` touched the sink would have no reason to
 * think about it.
 *
 * ONE WATCH AT A TIME. This is the one-agent demo path: a second `watch` replaces the
 * first and tells the operator it displaced so, rather than quietly fanning out into the
 * multi-agent display this deliberately is not. "Somebody else" is read from
 * `ObserverSink.observer` and never from the sink's identity, because the command builds a
 * fresh surface every time it is typed: an operator re-running it is one watcher changing
 * their mind, not two watchers competing.
 *
 * A WATCH BELONGS TO AN OBSERVER, and every question about one is asked as an observer.
 * [Observatory.standing] and [Observatory.stop] both take who is asking and answer as
 * [WatchStanding], so ending a watch and reporting one obey the rule `watch` already does.
 * Only [Observatory.shutDown] ignores it, because that is Loom itself going away rather
 * than anybody asking.
 */
package dev.loom.observe

import dev.loom.body.AgentId
import dev.loom.body.BodyEventRecord

/**
 * The standing watch as it looks to the observer who asked about it.
 *
 * Three cases rather than a nullable agent id, because "yours, on this agent", "somebody
 * else's" and "none at all" are three different answers and a caller that could not tell
 * them apart would have to guess: an operator who typed `/loomwatch off` while another
 * operator was watching used to be told they had stopped a watch that was never theirs,
 * while that watcher's display came down with nothing said to them.
 *
 * [NotMine] deliberately names no agent. Which agent somebody else is watching is that
 * observer's business, and the display exists to show a run rather than to report on who
 * is looking at it.
 */
sealed interface WatchStanding {
    /** The asking observer's own watch, on [agentId]. */
    data class Mine(
        val agentId: AgentId,
    ) : WatchStanding

    /** A watch is standing, and it belongs to somebody else. */
    data object NotMine : WatchStanding

    /** Nobody is watching anything. */
    data object Nobody : WatchStanding
}

/**
 * Whether attaching a watch left the display with something to show.
 *
 * Answered by [Observatory.watch] rather than worked out by whoever called it, because only
 * the observatory can see what the display was already showing: a caller that guessed would
 * need its own copy of the continuity rule, and the two would drift. The one thing that turns
 * on it is what an operator is told, and the two cases want opposite sentences - a bar that
 * already has a reading on it makes the advice to attach before the spawn not merely useless
 * but false.
 *
 * It measures what the asking operator can *see*, which is why [Observatory] asks the view
 * rather than reading the state: a surviving state and a display with something on it differ
 * over exactly the windows an operator meets. A watch attached before the spawn and attached
 * again keeps a state that says nothing, and a watch attached mid-walk keeps one whose only
 * content is the tick of a step, which no line renders.
 */
enum class WatchAttachment {
    /**
     * The asking operator's own watch went on showing what it was showing.
     *
     * Two cases, and both are that operator's own: they retyped the command on an agent the run
     * had told them something about, or their surface had to be replaced and the bar was
     * repainted with it. What is known follows the agent, so in both nothing they were looking
     * at was lost.
     */
    KEPT,

    /**
     * This operator's display has nothing on it yet, and fills in from the next records.
     *
     * A first watch, a watch on a different agent, one attached after a stop or a dropped
     * observer, one re-attached while nothing the line renders has been recorded yet, and a
     * watch taken over from somebody else - the cases where the record is the only thing that
     * can fill the display in, and where attaching before the spawn catches the first reading.
     *
     * A takeover belongs here even though it inherits the displaced watch's state, and that is
     * about disclosure rather than about tidiness: the operator taking over has lost nothing,
     * never having watched anything, and [WatchStanding.NotMine] deliberately does not say
     * which agent somebody else is watching - an answer of [KEPT] on a first-ever watch would
     * say it, by implying the displaced operator had been watching this very agent.
     */
    FRESH,
}

/**
 * A live operator view of one agent, driven entirely by the append-only record.
 *
 * @param view how records and state become bounded lines.
 * @param maxPendingNotes the most lines one [refresh] will deliver. A cognition cycle
 *   produces three, so this leaves room for a burst without ever becoming a firehose.
 */
class Observatory(
    private val view: ObserverView = ObserverView(),
    private val maxPendingNotes: Int = DEFAULT_MAX_PENDING_NOTES,
) {
    init {
        require(maxPendingNotes > 0) { "maxPendingNotes must be positive, was $maxPendingNotes" }
    }

    private val lock = Any()
    private var watch: Watch? = null

    /**
     * What [observer] may be told about the watch that is standing.
     *
     * Asked as an observer rather than read globally, so there is no way to learn what
     * somebody else is watching: the answer to a question about a watch depends on whose
     * watch it is, and a getter that ignored that is how the report told a non-watcher
     * which agent another operator had on their screen.
     */
    fun standing(observer: String): WatchStanding = synchronized(lock) { standingFor(observer) }

    /**
     * Points [sink] at [agentId], replacing any watch already running.
     *
     * A watch on an agent nothing was watching knows nothing: it starts at
     * [AgentActivity.UNSEEN] and fills in from the next records the run produces, because
     * the record is the only thing it may read. That is why the demo procedure attaches the
     * watch *before* spawning the agent - the spawn record then carries the first energy
     * reading.
     *
     * WHAT IS KNOWN FOLLOWS THE AGENT, NOT THE SURFACE. A watch already standing on
     * [agentId] keeps its [WatchState], because that state is a projection of the append-only
     * record rather than anything about who was looking: rebuilt blank, an operator who
     * retyped the command they were already running lost the food reading until the next
     * vitals-carrying record and the activity until the next goal review, up to a whole
     * cadence, while the gauge sat empty on an agent with a full food bar. Retyping it
     * against the same surface changes nothing at all, down to the lines
     * not yet drained. Carried onto a *new* surface it is painted there from scratch, since
     * that surface has no bar up yet. Only naming a different agent starts blank, and so does
     * a watch that was stopped: the bar came down, and nothing here keeps history after that.
     *
     * An operator who is already watching keeps their own surface, and is told nothing:
     * the standing watch is only displaced when [sink] belongs to somebody else. Their
     * existing sink is kept rather than swapped for the fresh one, because a surface can
     * only be taken down by the sink that put it up - releasing the old one and painting
     * on the new one would flicker an operator's display, and skipping the release
     * without keeping the old sink would leave a second bar behind that nothing owns.
     *
     * Displacing somebody speaks to their sink, so this must be called on the server main
     * thread. Unlike [saw], which is deliberately free of that constraint.
     *
     * @return which of the two it was, as [WatchAttachment], answered about the *asking*
     *   observer's own display: [WatchAttachment.KEPT] needs both that the standing watch is
     *   theirs - by [ObserverSink.observer] and not by sink identity or presence, so a replaced
     *   surface is still their own - and that [view] renders this state differently from a blank
     *   one for the same agent. A state that survived without reaching the line is kept exactly
     *   as it is and answered [WatchAttachment.FRESH], because that is what the bar shows, and
     *   so is a takeover, which has displaced a watch rather than kept one. Asking the view
     *   keeps the question total as [WatchState] grows: a field the line does not render cannot
     *   answer for a display it never reached, and a field it does render needs no mention here.
     */
    fun watch(
        agentId: AgentId,
        sink: ObserverSink,
    ): WatchAttachment {
        synchronized(lock) {
            val standing = watch
            val kept = standing?.sink?.takeIf { it.observer == sink.observer && it.isPresent }
            if (standing != null && kept == null) {
                displace(standing)
            }
            val blank = WatchState(agentId = agentId.value)
            val known = standing?.state?.takeIf { it.agentId == agentId.value }
            watch =
                if (kept != null && known != null) {
                    standing
                } else {
                    Watch(kept ?: sink, known ?: blank)
                }
            val mine = standing?.sink?.observer == sink.observer
            val shows = mine && known != null && view.render(known) != view.render(blank)
            return if (shows) WatchAttachment.KEPT else WatchAttachment.FRESH
        }
    }

    /** Tells a displaced watcher and takes their surface down. Callers hold [lock]. */
    private fun displace(standing: Watch) {
        if (standing.sink.isPresent) {
            standing.sink.note(Note("no longer watching ${standing.state.agentId}", ObserverLevel.CALM))
        }
        standing.sink.release()
    }

    /**
     * Ends [observer]'s own watch, and says what was standing when they asked.
     *
     * Only their own: a watch belongs to an observer, so somebody else's is refused with
     * [WatchStanding.NotMine] and left exactly as it was. Releasing it instead would take
     * a boss bar off a watcher's screen mid-run with nothing said to them, and the caller
     * asking to stop cannot say anything to them either - the sink is one-way and there is
     * no second surface to reach. Refusing needs no such message, because nothing happened.
     *
     * Idempotent: stopping when nobody is watching is [WatchStanding.Nobody] rather than an
     * error, and stopping a watch already stopped says the same.
     *
     * Takes a surface down, so it must be called on the server main thread.
     */
    fun stop(observer: String): WatchStanding {
        synchronized(lock) {
            val ending = standingFor(observer)
            if (ending is WatchStanding.Mine) {
                watch?.sink?.release()
                watch = null
            }
            return ending
        }
    }

    /**
     * Ends whatever watch is standing, whoever it belongs to, because Loom is going away.
     *
     * The one unconditional way out, for the plugin's own disable path: a boss bar left up
     * after Loom unloaded would go on stating an agent's energy that nothing is watching
     * any more. Nothing is said to the watcher, unlike a displacement: the server is
     * stopping, so a chat line may never reach them, and the bar has to come down either
     * way.
     *
     * Takes a surface down, so it must be called on the server main thread. `onDisable`
     * runs there.
     */
    fun shutDown() {
        synchronized(lock) {
            watch?.sink?.release()
            watch = null
        }
    }

    /** The standing watch from [observer]'s point of view. Callers hold [lock]. */
    private fun standingFor(observer: String): WatchStanding {
        val current = watch ?: return WatchStanding.Nobody
        return if (current.sink.observer == observer) {
            WatchStanding.Mine(AgentId(current.state.agentId))
        } else {
            WatchStanding.NotMine
        }
    }

    /**
     * Offers one record to whoever is watching.
     *
     * Cheap and total: with no watcher it does nothing, and with a watcher it accepts
     * only records about that agent.
     */
    fun saw(record: BodyEventRecord) {
        synchronized(lock) {
            val current = watch ?: return
            if (record.agentId != current.state.agentId) return
            view.noteFor(record)?.let { current.offer(it, maxPendingNotes) }
            current.state = advanced(current.state, record)
        }
    }

    /**
     * Delivers what has accumulated, and the standing line if it has changed.
     *
     * The overlay is pushed only on change, so a resting agent costs one comparison per
     * refresh and nothing else. A watcher that has gone away - a player who
     * disconnected - is dropped here, which is the only place that can be noticed.
     */
    fun refresh(tick: Long) {
        synchronized(lock) {
            val current = watch ?: return
            if (!current.sink.isPresent) {
                watch = null
                // Released even though nobody is there: a surface can outlive its observer -
                // a boss bar survives a reconnect - so the display is taken down rather than
                // left for whoever comes back.
                current.sink.release()
                return
            }
            current.state = decayed(current.state, tick)
            current.drainTo(current.sink)
            val overlay = view.render(current.state)
            if (overlay != current.shown) {
                current.shown = overlay
                current.sink.show(overlay)
            }
        }
    }

    /** One operator's attachment to one agent, and what has not been shown to them yet. */
    private class Watch(
        val sink: ObserverSink,
        var state: WatchState,
    ) {
        private val pending = ArrayDeque<Note>()
        private var skipped = 0
        private var said: Note? = null
        var shown: Overlay? = null

        /**
         * Queues [note] unless it repeats the last one, dropping the oldest if full.
         *
         * The oldest goes rather than the newest: an operator watching live wants what
         * just happened, and a queue that dropped the arrivals instead would show a
         * stale burst forever. What was dropped is counted and said out loud, because a
         * display that silently skipped lines is worse than one that admits it.
         *
         * A note identical to the one before it is not queued at all, and is not counted
         * as skipped either, because nothing was lost: the operator is already looking at
         * that line. Some events recur every tick for as long as a condition lasts - a
         * world hold that keeps being refused is recorded on every follow - and without
         * this the queue fills with one repeated sentence and evicts the `decided`, `died`
         * and `intentApplied` lines that sentence exists to qualify. Counting the repeats
         * as skipped would only trade the duplicates for a running "(N lines skipped)".
         *
         * The comparison is against the last note *taken*, not the queue's tail, so
         * emptying the queue at a refresh does not turn a standing condition back into one
         * line per refresh. Anything else arriving in between ends the run of duplicates,
         * so a condition that recurs after real news is stated again.
         */
        fun offer(
            note: Note,
            limit: Int,
        ) {
            if (note == said) {
                return
            }
            said = note
            while (pending.size >= limit) {
                pending.removeFirst()
                skipped++
            }
            pending.addLast(note)
        }

        /** Hands everything queued to [sink], newest last, saying what was skipped. */
        fun drainTo(sink: ObserverSink) {
            if (skipped > 0) {
                sink.note(Note("($skipped earlier line(s) skipped)", ObserverLevel.CONCERN))
                skipped = 0
            }
            while (pending.isNotEmpty()) {
                sink.note(pending.removeFirst())
            }
        }
    }

    companion object {
        /** Three lines is one cognition cycle; eight leaves room for a burst. */
        const val DEFAULT_MAX_PENDING_NOTES: Int = 8
    }
}
