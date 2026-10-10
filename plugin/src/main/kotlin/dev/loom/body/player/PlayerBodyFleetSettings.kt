/*
 * Deterministic deployment planning for a bounded fleet of Mineflayer bodies.
 */
package dev.loom.body.player

import dev.loom.body.AgentId
import java.nio.file.Path
import java.time.Duration

private const val LOOPBACK_HOST = "127.0.0.1"
private const val DEFAULT_AGENT = "e2e"
private const val DEFAULT_CONTROL_OFFSET = 20
private const val DEFAULT_STARTUP_CONCURRENCY = 4
private const val DEFAULT_IO_WORKERS_PER_AGENT = 4
private const val DEFAULT_STARTUP_ATTEMPTS = 2
private const val DEFAULT_STARTUP_RETRY_MILLIS = 250L
private const val DEFAULT_HEALTH_CHECK_INTERVAL_TICKS = 100L
private const val DEFAULT_HEALTH_FAILURE_LIMIT = 3
internal const val MAX_CONFIGURED_AGENTS = 16
private const val MAX_STARTUP_CONCURRENCY = 8
private const val MAX_IO_WORKERS_PER_AGENT = 8
private const val MAX_STARTUP_ATTEMPTS = 3
private const val MAX_STARTUP_RETRY_MILLIS = 5_000L
private const val MAX_HEALTH_CHECK_INTERVAL_TICKS = 12_000L
private const val MAX_HEALTH_FAILURE_LIMIT = 10
private const val MAX_PORT = 65_535

/** One immutable process allocation in a fleet plan. */
data class PlayerBodyDeployment(
    val identity: PlayerIdentity,
    val settings: PlayerBodySettings,
)

/**
 * The complete bounded body deployment.
 *
 * Agent ids are canonicalized before resources are assigned, so the same set
 * receives the same identity, endpoint, and process directory after a restart
 * regardless of the order used in the environment variable.
 */
data class PlayerBodyFleetSettings(
    val deployments: List<PlayerBodyDeployment>,
    val startupConcurrency: Int,
    val ioWorkersPerAgent: Int,
) {
    init {
        require(deployments.isNotEmpty()) { "at least one player body must be configured" }
        require(deployments.size <= MAX_CONFIGURED_AGENTS) {
            "at most $MAX_CONFIGURED_AGENTS player bodies may be configured"
        }
        require(startupConcurrency in 1..minOf(MAX_STARTUP_CONCURRENCY, deployments.size)) {
            "startupConcurrency must be in 1..${minOf(MAX_STARTUP_CONCURRENCY, deployments.size)}"
        }
        require(ioWorkersPerAgent in 1..MAX_IO_WORKERS_PER_AGENT) {
            "ioWorkersPerAgent must be in 1..$MAX_IO_WORKERS_PER_AGENT"
        }
        requireUnique("agent id", deployments.map { it.identity.agentId.value })
        requireUnique("offline username", deployments.map { it.identity.username })
        requireUnique("offline uuid", deployments.map { it.identity.uuid.toString() })
        requireUnique("control endpoint", deployments.map { it.settings.controlEndpoint.toString() })
        requireUnique(
            "process directory",
            deployments.map { it.settings.processDirectory.normalize().toString() },
        )
    }

    companion object {
        /**
         * Builds one deterministic fleet from bounded environment configuration.
         *
         * `LOOM_BODY_AGENTS` is a comma-separated list. The default keeps the
         * disposable single-agent path runnable, while every configured id is
         * still explicit in the resulting immutable plan.
         */
        fun fromEnvironment(
            serverPort: Int,
            dataDirectory: Path,
            workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
            environment: Map<String, String> = System.getenv(),
        ): PlayerBodyFleetSettings {
            require(dataDirectory.isAbsolute) { "dataDirectory must be absolute" }
            val agentIds = configuredAgents(environment)
            val allocation =
                commonAllocation(
                    serverPort,
                    agentIds.size,
                    dataDirectory,
                    workingDirectory,
                    environment,
                )
            val deployments = deployments(agentIds, allocation)
            val startupConcurrency =
                environment.int(
                    "LOOM_BODY_STARTUP_CONCURRENCY",
                    minOf(DEFAULT_STARTUP_CONCURRENCY, deployments.size),
                    1..minOf(MAX_STARTUP_CONCURRENCY, deployments.size),
                )
            val ioWorkers =
                environment.int(
                    "LOOM_BODY_IO_WORKERS_PER_AGENT",
                    DEFAULT_IO_WORKERS_PER_AGENT,
                    1..MAX_IO_WORKERS_PER_AGENT,
                )
            return PlayerBodyFleetSettings(deployments, startupConcurrency, ioWorkers)
        }
    }
}

private data class CommonAllocation(
    val settings: PlayerBodySettings,
    val processRoot: Path,
)

private data class ProcessBounds(
    val startupAttempts: Int,
    val startupRetryDelay: Duration,
    val healthCheckIntervalTicks: Long,
    val healthFailureLimit: Int,
)

private fun commonAllocation(
    serverPort: Int,
    agentCount: Int,
    dataDirectory: Path,
    workingDirectory: Path,
    environment: Map<String, String>,
): CommonAllocation {
    val bodyDirectory =
        environment.path("LOOM_BODY_DIR", workingDirectory.resolve("body"), workingDirectory)
    val processRoot =
        environment.path(
            "LOOM_BODY_PROCESS_ROOT",
            dataDirectory.resolve("bodies"),
            workingDirectory,
        )
    val minecraftPort = environment.int("LOOM_BODY_MC_PORT", serverPort, 1..MAX_PORT)
    val controlBase =
        environment.int(
            "LOOM_BODY_CONTROL_PORT",
            defaultControlPort(serverPort, agentCount),
            1..MAX_PORT,
        )
    require(controlBase + agentCount - 1 <= MAX_PORT) {
        "LOOM_BODY_CONTROL_PORT leaves no room for $agentCount configured agents"
    }
    require(minecraftPort !in controlBase..(controlBase + agentCount - 1)) {
        "the Minecraft port may not overlap a body-control endpoint"
    }
    val bounds = processBounds(environment)
    return CommonAllocation(
        settings =
            PlayerBodySettings(
                bodyDirectory = bodyDirectory,
                processDirectory = processRoot,
                nodeExecutable =
                    environment["LOOM_BODY_NODE"]?.trim()?.takeIf(String::isNotEmpty) ?: "node",
                minecraftHost = environment["LOOM_BODY_MC_HOST"] ?: LOOPBACK_HOST,
                minecraftPort = minecraftPort,
                minecraftVersion =
                    environment["LOOM_BODY_MC_VERSION"]?.trim()?.takeIf(String::isNotEmpty)
                        ?: "1.21.1",
                controlEndpoint =
                    BodyControlEndpoint(
                        environment["LOOM_BODY_CONTROL_HOST"] ?: LOOPBACK_HOST,
                        controlBase,
                    ),
                startupAttempts = bounds.startupAttempts,
                startupRetryDelay = bounds.startupRetryDelay,
                healthCheckIntervalTicks = bounds.healthCheckIntervalTicks,
                healthFailureLimit = bounds.healthFailureLimit,
            ),
        processRoot = processRoot,
    )
}

private fun processBounds(environment: Map<String, String>): ProcessBounds =
    ProcessBounds(
        startupAttempts =
            environment.int(
                "LOOM_BODY_STARTUP_ATTEMPTS",
                DEFAULT_STARTUP_ATTEMPTS,
                1..MAX_STARTUP_ATTEMPTS,
            ),
        startupRetryDelay =
            Duration.ofMillis(
                environment.long(
                    "LOOM_BODY_STARTUP_RETRY_MS",
                    DEFAULT_STARTUP_RETRY_MILLIS,
                    1L..MAX_STARTUP_RETRY_MILLIS,
                ),
            ),
        healthCheckIntervalTicks =
            environment.long(
                "LOOM_BODY_HEALTH_INTERVAL_TICKS",
                DEFAULT_HEALTH_CHECK_INTERVAL_TICKS,
                1L..MAX_HEALTH_CHECK_INTERVAL_TICKS,
            ),
        healthFailureLimit =
            environment.int(
                "LOOM_BODY_HEALTH_FAILURES",
                DEFAULT_HEALTH_FAILURE_LIMIT,
                1..MAX_HEALTH_FAILURE_LIMIT,
            ),
    )

private fun deployments(
    agentIds: List<AgentId>,
    allocation: CommonAllocation,
): List<PlayerBodyDeployment> =
    agentIds.mapIndexed { index, agentId ->
        val identity = playerIdentityOf(agentId)
        val common = allocation.settings
        PlayerBodyDeployment(
            identity = identity,
            settings =
                common.copy(
                    processDirectory = allocation.processRoot.resolve(identity.username).normalize(),
                    controlEndpoint =
                        BodyControlEndpoint(
                            common.controlEndpoint.host,
                            common.controlEndpoint.port + index,
                        ),
                ),
        )
    }

private fun configuredAgents(environment: Map<String, String>): List<AgentId> {
    val raw = environment["LOOM_BODY_AGENTS"] ?: DEFAULT_AGENT
    val names = raw.split(',').map(String::trim)
    require(names.all(String::isNotEmpty)) {
        "LOOM_BODY_AGENTS must contain non-empty comma-separated agent ids"
    }
    require(names.size <= MAX_CONFIGURED_AGENTS) {
        "LOOM_BODY_AGENTS may contain at most $MAX_CONFIGURED_AGENTS ids"
    }
    require(names.distinct().size == names.size) {
        "LOOM_BODY_AGENTS contains a duplicate agent id"
    }
    return names.map(::AgentId).sortedBy { it.value }
}

private fun defaultControlPort(
    serverPort: Int,
    count: Int,
): Int {
    val above = serverPort + DEFAULT_CONTROL_OFFSET
    return if (above + count - 1 <= MAX_PORT) {
        above
    } else {
        serverPort - DEFAULT_CONTROL_OFFSET - count + 1
    }
}

private fun Map<String, String>.path(
    name: String,
    default: Path,
    workingDirectory: Path,
): Path =
    get(name)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(Path::of)
        ?.let { if (it.isAbsolute) it else workingDirectory.resolve(it) }
        ?.normalize()
        ?: default.toAbsolutePath().normalize()

private fun Map<String, String>.int(
    name: String,
    default: Int,
    range: IntRange,
): Int {
    val raw = get(name)
    val parsed = raw?.toIntOrNull()
    require(raw == null || parsed != null) { "$name must be an integer" }
    val value = parsed ?: default
    require(value in range) { "$name must be in ${range.first}..${range.last}" }
    return value
}

private fun Map<String, String>.long(
    name: String,
    default: Long,
    range: LongRange,
): Long {
    val raw = get(name)
    val parsed = raw?.toLongOrNull()
    require(raw == null || parsed != null) { "$name must be an integer" }
    val value = parsed ?: default
    require(value in range) { "$name must be in ${range.first}..${range.last}" }
    return value
}

private fun requireUnique(
    description: String,
    values: List<String>,
) {
    require(values.distinct().size == values.size) {
        "configured player bodies collide on $description"
    }
}
