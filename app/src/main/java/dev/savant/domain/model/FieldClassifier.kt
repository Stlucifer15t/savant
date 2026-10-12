package dev.savant.domain.model

private val currencyTerms = setOf(
    "gold", "money", "coin", "coins", "cash", "credit", "credits", "gem", "gems",
    "currency", "gil", "zenny", "rupee", "rupees",
)
private val statTerms = setOf(
    "hp", "health", "mp", "mana", "sp", "stamina", "level", "lvl", "exp", "xp",
    "strength", "str", "defense", "def", "attack", "atk", "agility", "agi", "luck",
    "intelligence", "vitality", "skill", "score",
)
private val inventoryTerms = setOf(
    "inventory", "inventories", "item", "items", "bag", "backpack", "equipment", "weapon",
    "weapons", "armor", "armour", "slot", "slots",
)
private val flagTerms = setOf(
    "flag", "flags", "switch", "switches", "unlocked", "completed", "quest", "quests",
    "achievement", "achievements", "seen", "enabled",
)

fun classifyField(path: String, key: String, type: FieldType): FieldCategory {
    val terms = (path + " " + key)
        .lowercase(LocaleRoot)
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
        .toSet()
    return when {
        terms.any(currencyTerms::contains) -> FieldCategory.CURRENCY
        terms.any(statTerms::contains) -> FieldCategory.STATS
        terms.any(inventoryTerms::contains) -> FieldCategory.INVENTORY
        type == FieldType.BOOL || terms.any(flagTerms::contains) -> FieldCategory.FLAGS
        else -> FieldCategory.OTHER
    }
}

fun displayNameForKey(key: String): String {
    if (key.isBlank() || key == "#text") return "value"
    val spaced = key
        .removePrefix("@")
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return spaced.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(LocaleRoot) else char.toString()
    }
}

private val LocaleRoot = java.util.Locale.ROOT
