/*
 * The Bukkit answer to what a recipe for an item is made from.
 */
package dev.loom.plugin

import dev.loom.mind.RecipeInputs
import io.papermc.paper.event.server.ServerResourcesReloadedEvent
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import java.util.logging.Logger

/**
 * The direct inputs of every server recipe for an item, and every sole-input recipe's result by its
 * one input, as item names, read from Paper's own recipe registry in one pass when the plugin is
 * enabled, and again whenever the server reloads its datapacks ([reread], [RecipeIndexReload]):
 * no goal review scans the registry on the server's main thread, and each pass's answer is an
 * immutable snapshot any thread may read. [recipes] and [buildMillis] state what the latest pass
 * read and how long it took, which the plugin logs.
 */
class BukkitRecipeInputs(
    private val server: Server,
    private val clock: () -> Long = System::nanoTime,
) : RecipeInputs {
    @Volatile
    private var index = RecipeIndex.read(server, clock)

    /** How many server recipes the latest pass read. */
    val recipes: Int get() = index.recipes

    /** How long the latest pass took, in milliseconds. */
    val buildMillis: Long get() = index.buildMillis

    override fun inputsOf(item: String): Set<String> = index.inputsByResult[item].orEmpty()

    override fun madeFrom(input: String): List<String> = index.soleInput[input].orEmpty()

    /** Reads the registry again, as the server holds it after a datapack reload. */
    fun reread() {
        index = RecipeIndex.read(server, clock)
    }
}

/** One pass's reading of the recipe registry. */
private class RecipeIndex(
    val inputsByResult: Map<String, Set<String>>,
    val soleInput: Map<String, List<String>>,
    val recipes: Int,
    val buildMillis: Long,
) {
    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        fun read(
            server: Server,
            clock: () -> Long,
        ): RecipeIndex {
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
            return RecipeIndex(
                inputsByResult = inputs.mapValues { (_, names) -> names.toSet() },
                soleInput =
                    made.mapValues {
                            (_, outputs) ->
                        outputs.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }
                    },
                recipes = read,
                buildMillis = (clock() - started) / NANOS_PER_MILLI,
            )
        }
    }
}

/** Reads [recipes] again whenever the server reloads its datapacks, and says so. */
internal class RecipeIndexReload(
    private val recipes: BukkitRecipeInputs,
    private val logger: Logger,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun reloaded(event: ServerResourcesReloadedEvent) {
        recipes.reread()
        logger.info(
            "Loom recipe index reread after ${event.cause}: " +
                "${recipes.recipes} server recipes in ${recipes.buildMillis} ms",
        )
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
