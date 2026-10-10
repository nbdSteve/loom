/*
 * Position-shaped field validation for the body-control wire.
 */
package dev.loom.bodycontrol

/**
 * Refuses a position that is not three coordinates inside the world's own limits.
 *
 * The axis count is checked here rather than by a fixed-arity type, because the wire
 * carries an array and a frame naming four coordinates is a peer describing a world this
 * one does not have - which is a refusal and not a value to interpret.
 */
internal fun requireWorldPosition(
    field: String,
    value: List<Double>,
) {
    require(value.size == WORLD_POSITION_AXES) { "$field must be $WORLD_POSITION_AXES coordinates" }
    require(value.all { it >= -MAX_WORLD_COORDINATE && it <= MAX_WORLD_COORDINATE }) {
        "$field must be within $MAX_WORLD_COORDINATE of the origin"
    }
}

/** Refuses a block position that is not three integral coordinates inside the world. */
internal fun requireBlockPosition(
    field: String,
    value: List<Int>,
) {
    require(value.size == WORLD_POSITION_AXES) { "$field must be $WORLD_POSITION_AXES coordinates" }
    require(value.all { it in -MAX_WORLD_COORDINATE..MAX_WORLD_COORDINATE }) {
        "$field must be within $MAX_WORLD_COORDINATE of the origin"
    }
}
