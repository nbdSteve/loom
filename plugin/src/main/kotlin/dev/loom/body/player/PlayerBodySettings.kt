/*
 * Supervisor configuration for the persistent Mineflayer body process.
 */
package dev.loom.body.player

import java.nio.file.Path
import java.time.Duration

private const val LOOPBACK = "127.0.0.1"
private const val MAX_PORT = 65_535
private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 500L
private const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 3L
private const val DEFAULT_MOVEMENT_TIMEOUT_SECONDS = 21L
private const val DEFAULT_STARTUP_TIMEOUT_SECONDS = 45L
private const val DEFAULT_SHUTDOWN_TIMEOUT_SECONDS = 5L
private const val DEFAULT_STARTUP_ATTEMPTS = 2
private const val DEFAULT_STARTUP_RETRY_MILLIS = 250L
private const val DEFAULT_HEALTH_CHECK_INTERVAL_TICKS = 100L
private const val DEFAULT_HEALTH_FAILURE_LIMIT = 3

data class PlayerBodySettings(
    val bodyDirectory: Path,
    val processDirectory: Path = bodyDirectory,
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
    val startupAttempts: Int = DEFAULT_STARTUP_ATTEMPTS,
    val startupRetryDelay: Duration = Duration.ofMillis(DEFAULT_STARTUP_RETRY_MILLIS),
    val healthCheckIntervalTicks: Long = DEFAULT_HEALTH_CHECK_INTERVAL_TICKS,
    val healthFailureLimit: Int = DEFAULT_HEALTH_FAILURE_LIMIT,
) {
    init {
        require(bodyDirectory.isAbsolute) { "bodyDirectory must be absolute" }
        require(processDirectory.isAbsolute) { "processDirectory must be absolute" }
        require(nodeExecutable.isNotBlank()) { "nodeExecutable must not be blank" }
        require(minecraftHost == LOOPBACK || minecraftHost == "::1") {
            "the Mineflayer server must be a loopback literal"
        }
        require(minecraftPort in 1..MAX_PORT) { "minecraftPort must be in 1..65535" }
        require(minecraftVersion.isNotBlank()) { "minecraftVersion must not be blank" }
        listOf(connectTimeout, requestTimeout, movementTimeout, startupTimeout, shutdownTimeout).forEach {
            require(!it.isNegative && !it.isZero) { "body process timeouts must be positive" }
        }
        require(startupAttempts > 0) { "startupAttempts must be positive" }
        require(!startupRetryDelay.isNegative && !startupRetryDelay.isZero) {
            "startupRetryDelay must be positive"
        }
        require(healthCheckIntervalTicks > 0) { "healthCheckIntervalTicks must be positive" }
        require(healthFailureLimit > 0) { "healthFailureLimit must be positive" }
    }
}
