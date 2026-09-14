/*
 * Completion helpers shared by the player-body coordinators.
 */
package dev.loom.body.player

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

internal fun <T, R> CompletionStage<T>.thenApplyOn(
    executor: Executor,
    transform: (T) -> R,
): CompletionStage<R> {
    val answer = CompletableFuture<R>()
    whenComplete { value, failure ->
        executor.execute {
            if (failure != null) {
                answer.completeExceptionally(unwrapCompletion(failure) ?: BodyControlFailure.Unavailable())
            } else {
                runCatching { transform(value) }
                    .onSuccess(answer::complete)
                    .onFailure(answer::completeExceptionally)
            }
        }
    }
    return answer
}

internal fun unwrapCompletion(failure: Throwable?): Throwable? =
    if (failure is CompletionException && failure.cause != null) {
        failure.cause
    } else {
        failure
    }
