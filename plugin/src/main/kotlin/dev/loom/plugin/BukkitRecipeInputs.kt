/*
 * The Bukkit answer to what a recipe for an item is made from.
 */
package dev.loom.plugin

import dev.loom.mind.RecipeInputs
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe

/**
 * The direct inputs of every server recipe for an item, and every sole-input recipe's result by its
 * one input, as item names, read from Paper's own recipe registry in one pass when the plugin is
 * enabled and immutable after: no goal review scans the registry on the server's main thread, and
 * any thread may read the answer. [recipes] and [buildMillis] state what the one pass read and
 * how long it took, which the plugin logs.
 */
class BukkitRecipeInputs(
    server: Server,
    clock: () -> Long = System::nanoTime,
) : RecipeInputs {
    private val inputsByResult: Map<String, Set<String>>
    private val soleInput: Map<String, List<String>>

    /** How many server recipes the one pass read. */
    val recipes: Int

    /** How long the one pass took, in milliseconds. */
    val buildMillis: Long

    init {
        val started = clock()
        val inputs = mutableMapOf<String, MutableSet<String>>()
        val made = mutableMapOf<String, MutableMap<String, Int>>()
        var read = 0
        server.recipeIterator().forEach { recipe ->
            read += 1
            val slots =
                when (recipe) {
                    is ShapedRecipe -> recipe.choiceMap.values.filterNotNull()
                    is ShapelessRecipe -> recipe.choiceList
                    else -> emptyList()
                }
            val result = recipe.result.type
            if (result.isAir) return@forEach
            val resultName = itemNameOf(result)
            inputs.getOrPut(resultName) { mutableSetOf() } +=
                slots.flatMap(::materialsOf).filter { it != result && !it.isAir }.map(::itemNameOf)
            // Sole input: every slot accepts the same materials, so one item fills the recipe.
            val accepted = slots.map { materialsOf(it).toSet() }.distinct()
            if (slots.isEmpty() || accepted.size != 1) return@forEach
            for (material in accepted.single().filter { it != result && !it.isAir }) {
                val outputs = made.getOrPut(itemNameOf(material)) { mutableMapOf() }
                outputs[resultName] = minOf(outputs[resultName] ?: Int.MAX_VALUE, slots.size)
            }
        }
        inputsByResult = inputs.mapValues { (_, names) -> names.toSet() }
        soleInput =
            made.mapValues {
                    (_, outputs) ->
                outputs.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }
            }
        recipes = read
        buildMillis = (clock() - started) / NANOS_PER_MILLI
    }

    override fun inputsOf(item: String): Set<String> = inputsByResult[item].orEmpty()

    override fun madeFrom(input: String): List<String> = soleInput[input].orEmpty()

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** The materials one recipe slot accepts. */
private fun materialsOf(choice: RecipeChoice): List<Material> =
    when (choice) {
        is RecipeChoice.MaterialChoice -> choice.choices
        is RecipeChoice.ExactChoice -> choice.choices.map { it.type }
        else -> emptyList()
    }

/** The inventory item name of [material], as a stack names it. */
private fun itemNameOf(material: Material): String =
    "item.${material.key.namespace}.${material.key.key.replace('/', '.')}"
