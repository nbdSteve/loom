/*
 * Supervisor configuration for the persistent Mineflayer body process.
 */
package dev.loom.body.player

import java.nio.file.Path
import java.time.Duration

private const val LOOPBACK = "127.0.0.1"
private const val DEFAULT_CONTROL_OFFSET = 20
private const val MAX_PORT = 65_535
private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 500L
private const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 3L
private const val DEFAULT_MOVEMENT_TIMEOUT_SECONDS = 21L
private const val DEFAULT_STARTUP_TIMEOUT_SECONDS = 45L
private const val DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 5L

data class PlayerBodySettings(
    val bodyDirectory: Path,
    val nodeExecutable: String = "node",
    val minecraftHost: String = LOOPBACK,
    val minecraftPort: Int = 25_565,
    val minecraftVersion: String = "1.21.1",
    val controlEndpoint: BodyControlEndpoint,
    val connectTimeout: Duration = Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MILLIS),
    val requestTimeout: Duration = Duration.ofSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS),
    val movementTimeout: Duration = Duration.ofSeconds(DEFAULT_MOVEMENT_TIMEOUT_SECONDS),
    val startupTimeout: Duration = Duration.ofSeconds(DEFAULT_STARTUP_TIMEOUT_SECONDS),
    val shutdownTimeout: Duration = Duration.ofSeconds(DEFAULT_SHUTDOWN_TIMEOUT_SECONDS),
) {
    init {
        require(bodyDirectory.isAbsolute) { "bodyDirectory must be absolute" }
        require(nodeExecutable.isNotBlank()) { "nodeExecutable must not be blank" }
        require(minecraftHost == LOOPBACK || minecraftHost == "::1") {
            "the Mineflayer server must be a loopback literal"
        }
        require(minecraftPort in 1..MAX_PORT) { "minecraftPort must be in 1..65535" }
        require(minecraftVersion.isNotBlank()) { "minecraftVersion must not be blank" }
        listOf(connectTimeout, requestTimeout, movementTimeout, startupTimeout, shutdownTimeout).forEach {
            require(!it.isNegative && !it.isZero) { "body process timeouts must be positive" }
        }
    }

    companion object {
        /**
         * Reads process-location overrides while deriving network defaults from Paper.
         *
         * The body package is an installation artifact rather than part of the plugin
         * jar, so its directory is explicit in production and defaults to `body/` only
         * for a repository checkout or disposable acceptance server.
         */
        fun fromEnvironment(
            serverPort: Int,
            workingDirectory: Path = Path.of("").toAbsolutePath().normalize(),
            environment: Map<String, String> = System.getenv(),
        ): PlayerBodySettings {
            val directory =
                environment["LOOM_BODY_DIR"]
                    ?.let(Path::of)
                    ?.let { if (it.isAbsolute) it else workingDirectory.resolve(it) }
                    ?.normalize()
                    ?: workingDirectory.resolve("body").normalize()
            val controlPort =
                environment["LOOM_BODY_CONTROL_PORT"]?.toIntOrNull()
                    ?: (serverPort + DEFAULT_CONTROL_OFFSET).takeIf { it <= MAX_PORT }
                    ?: (serverPort - DEFAULT_CONTROL_OFFSET)
            return PlayerBodySettings(
                bodyDirectory = directory,
                nodeExecutable = environment["LOOM_BODY_NODE"]?.takeIf(String::isNotBlank) ?: "node",
                minecraftHost = environment["LOOM_BODY_MC_HOST"] ?: LOOPBACK,
                minecraftPort = environment["LOOM_BODY_MC_PORT"]?.toIntOrNull() ?: serverPort,
                minecraftVersion =
                    environment["LOOM_BODY_MC_VERSION"]?.trim()?.takeIf(String::isNotBlank)
                        ?: "1.21.1",
                controlEndpoint =
                    BodyControlEndpoint(
                        environment["LOOM_BODY_CONTROL_HOST"] ?: LOOPBACK,
                        controlPort,
                    ),
            )
        }
    }
}
