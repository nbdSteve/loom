/*
 * The Bukkit answer to what a recipe for an item is made from.
 */
package dev.loom.plugin

import dev.loom.mind.RecipeInputs
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe

/**
 * The direct inputs of every server recipe for an item, as item names, read from Paper's own
 * recipe registry the first time an item is asked about and kept, at most [MAX_CACHED] items,
 * the oldest forgotten first. Main thread only, like the registry it reads.
 */
class BukkitRecipeInputs(
    private val server: Server,
) : RecipeInputs {
    private val known =
        object : LinkedHashMap<String, Set<String>>(MAX_CACHED, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean =
                size > MAX_CACHED
        }

    override fun inputsOf(item: String): Set<String> = known.getOrPut(item) { read(item) }

    /** Every sole-input recipe's result, by its one input, read once from the whole registry. */
    private val soleInput: Map<String, List<String>> by lazy {
        val made = mutableMapOf<String, MutableMap<String, Int>>()
        server.recipeIterator().forEach { recipe ->
            val slots =
                when (recipe) {
                    is ShapedRecipe -> recipe.choiceMap.values.filterNotNull()
                    is ShapelessRecipe -> recipe.choiceList
                    else -> emptyList()
                }
            val result = recipe.result.type
            // Sole input: every slot accepts the same materials, so one item fills the recipe.
            val accepted = slots.map { materialsOf(it).toSet() }.distinct()
            if (slots.isEmpty() || accepted.size != 1 || result.isAir) return@forEach
            for (material in accepted.single().filter { it != result && !it.isAir }) {
                val outputs = made.getOrPut(itemNameOf(material)) { mutableMapOf() }
                val name = itemNameOf(result)
                outputs[name] = minOf(outputs[name] ?: Int.MAX_VALUE, slots.size)
            }
        }
        made.mapValues {
                (_, outputs) ->
            outputs.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }
        }
    }

    override fun madeFrom(input: String): List<String> = soleInput[input].orEmpty()

    private fun read(item: String): Set<String> {
        val material = materialOf(item) ?: return emptySet()
        return server
            .getRecipesFor(ItemStack(material))
            .flatMap { recipe ->
                when (recipe) {
                    is ShapedRecipe -> recipe.choiceMap.values.filterNotNull()
                    is ShapelessRecipe -> recipe.choiceList
                    else -> emptyList()
                }
            }.flatMap(::materialsOf)
            .filter { it != material && !it.isAir }
            .map(::itemNameOf)
            .toSet()
    }

    private companion object {
        const val MAX_CACHED = 256
        const val LOAD_FACTOR = 0.75f
    }
}

/** The material an inventory item name (`item.minecraft.oak_planks`) names, if any. */
internal fun materialOf(item: String): Material? =
    item.removePrefix("item.").replaceFirst('.', ':').let(Material::matchMaterial)

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
