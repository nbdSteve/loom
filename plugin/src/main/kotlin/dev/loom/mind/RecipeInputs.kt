/*
 * Which items a recipe for an item is made from, as the server's own recipe registry states it.
 */
package dev.loom.mind

/**
 * The items any one recipe for an item takes directly, by item name: oak planks are made from
 * oak logs, sticks from planks. Engine-free; the server's registry answers it in the plugin.
 *
 * A goal's step advances it only when the inventory then holds more of the goal's own item, or of
 * one of these - a typed step toward it - and never for any other item the step happened to give
 * (r19: a sapling dug for a stick goal, and a stick and an apple for a sapling goal, each reset the
 * stall count). A goal whose item is made from an earlier met goal's item is chained to it.
 */
fun interface RecipeInputs {
    fun inputsOf(item: String): Set<String>

    companion object {
        /** No registry to ask: nothing is known to be made from anything. */
        val NONE: RecipeInputs = RecipeInputs { emptySet() }
    }
}
