package dev.loom.plugin

import dev.loom.contract.LoomJson
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

/** `closed` admits only managed identities and observers; any other value, or none, admits every login. */
const val ADMISSION_ENV: String = "LOOM_ADMISSION"

/** Comma-separated observer usernames. */
const val OBSERVERS_ENV: String = "LOOM_OBSERVERS"

/** Matches the research config's own bound. */
const val MAX_OBSERVERS: Int = 4

const val OBSERVER_JOINED: String = "observerJoined"
const val OBSERVER_LEFT: String = "observerLeft"

private val OBSERVER_USERNAME = Regex("^[A-Za-z0-9_]{3,16}$")
private val OBSERVER_COMMANDS = setOf("loomwatch", "loom:loomwatch")

/**
 * Who a run admits beside its agents, and what an observer may do once in.
 *
 * An observer is a spectator only; a managed identity is always an agent, even if an observer
 * entry names it.
 */
class Spectators(
    val closed: Boolean,
    observers: Collection<String>,
) {
    private val keys: Set<String> = observers.map(::observerKeyOf).toSet()

    init {
        require(observers.size <= MAX_OBSERVERS) { "$OBSERVERS_ENV may name at most $MAX_OBSERVERS observers" }
        require(observers.all { OBSERVER_USERNAME.matches(it) }) {
            "$OBSERVERS_ENV must name Minecraft usernames of 3..16 letters, digits or underscores"
        }
        require(keys.size == observers.size) { "$OBSERVERS_ENV names an observer twice" }
    }

    val hasObservers: Boolean get() = keys.isNotEmpty()

    /** Whether [username] is an observer's login, given whether it matched a managed identity. */
    fun isObserver(
        username: String,
        managed: Boolean,
    ): Boolean = !managed && observerKeyOf(username) in keys

    /** Whether a login may reach the witness at all. */
    fun admits(
        username: String,
        managed: Boolean,
    ): Boolean = !closed || managed || isObserver(username, managed)

    /** Whether an observer may send [message], the command line as the client typed it. */
    fun mayRun(message: String): Boolean {
        val label = message.removePrefix("/").substringBefore(' ').lowercase(Locale.ROOT)
        return label in OBSERVER_COMMANDS
    }

    companion object {
        const val REFUSAL: String = "This Loom run admits only its agents and configured observers."
        const val COMMAND_REFUSAL: String = "Observers may use only /loomwatch."

        fun fromEnvironment(environment: Map<String, String>): Spectators =
            Spectators(
                closed = environment[ADMISSION_ENV] == "closed",
                observers =
                    environment[OBSERVERS_ENV]
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        .orEmpty(),
            )
    }
}

private fun observerKeyOf(username: String): String = username.lowercase(Locale.ROOT)

/** One observer arrival or departure, kept apart from the agents' event log. */
@Serializable
data class ObserverRecord(
    val event: String,
    val tick: Long,
    val username: String,
    val uuid: String,
)

/** Appends observer records as JSON lines; a failed write is reported and never thrown. */
class ObserverJournal(
    private val file: Path,
    private val onFailure: (Exception) -> Unit,
) {
    fun append(record: ObserverRecord) {
        try {
            Files.createDirectories(file.parent)
            Files.writeString(
                file,
                LoomJson.encodeToString(ObserverRecord.serializer(), record) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.SYNC,
            )
        } catch (failure: IOException) {
            onFailure(failure)
        }
    }
}
