package dev.savant.parsers.categorize

import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldPath
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue

/**
 * Deterministic grouping for the chat reply ("found 42 editable values") and the field list.
 *
 * Order of evidence:
 * 1. an explicit category from a plugin or a binary offset mapping always wins
 * 2. an ancestor segment (`inventory.items[3].count` -> inventory)
 * 3. boolean typed values are flags unless an ancestor says otherwise
 * 4. keyword match on the leaf key, then on the whole path
 *
 * No models involved: the same file categorises identically on every device, every time.
 */
object CategoryClassifier {

    private val CURRENCY = setOf(
        "gold", "coins", "coin", "money", "cash", "credits", "credit", "currency", "gems", "gem",
        "diamonds", "diamond", "rupees", "rupee", "gil", "septims", "septim", "caps", "cap",
        "silver", "copper", "funds", "wallet", "bank", "balance", "dollars", "dollar", "bucks",
        "souls", "soul", "tokens", "token", "gold_coins",
    )

    private val STATS = setOf(
        "hp", "mp", "sp", "health", "mana", "stamina", "strength", "str", "dexterity", "dex",
        "intelligence", "int", "wisdom", "wis", "constitution", "con", "charisma", "cha",
        "luck", "lck", "attack", "atk", "defense", "def", "armour", "speed", "agility", "agi",
        "level", "lv", "lvl", "exp", "xp", "skill", "skills", "power", "damage", "dmg",
        "resist", "resistance", "vitality", "vit", "endurance", "energy", "hunger", "thirst",
        "sanity", "reputation", "rep", "honor", "karma", "age", "kills", "deaths", "playtime",
        "stats", "attributes", "crit", "evasion", "accuracy", "magic", "faith", "arcane",
        "weight", "carry", "capacity", "durability", "condition", "temperature", "oxygen",
        "score", "highscore",
    )

    private val INVENTORY = setOf(
        "item", "items", "inventory", "inv", "weapon", "weapons", "armor", "armour", "equip",
        "equipment", "equipped", "slot", "slots", "bag", "bags", "chest", "stash", "storage",
        "count", "quantity", "qty", "amount", "stack", "material", "materials", "resource",
        "resources", "craft", "crafting", "potion", "potions", "consumable", "upgrade",
        "upgrades", "blueprint", "blueprints", "recipe", "recipes", "collectible", "card",
        "cards", "deck", "loadout", "outfit", "mod", "mods", "attachment", "attachments",
        "ammo", "ammunition", "fuel", "food", "supplies", "cargo", "troops", "troop", "units",
        "unit", "party", "companions", "companion", "roster", "heroes", "hero",
    )

    private val FLAGS = setOf(
        "flag", "flags", "quest", "quests", "unlock", "unlocked", "discovered", "seen",
        "complete", "completed", "finished", "achieved", "achievement", "achievements",
        "tutorial", "enabled", "disabled", "active", "visited", "met", "dialogue", "dialog",
        "event", "events", "bit", "bits", "toggle", "isnew", "new", "first", "firsttime",
        "started", "done", "cleared", "beaten", "intro", "cutscene", "trigger", "triggered",
        "spawned", "looted", "read", "played", "dead", "alive", "married", "banished",
    )

    /** Ancestor segments that pull every descendant into a category. */
    private val CONTEXT = mapOf(
        FieldCategory.CURRENCY to setOf("currency", "wallet", "bank", "money", "economy", "purse"),
        FieldCategory.STATS to setOf("stats", "attributes", "skills", "abilities"),
        FieldCategory.INVENTORY to setOf(
            "inventory", "items", "item", "equipment", "gear", "storage", "chest", "chests",
            "bag", "bags", "stash", "loadout", "arsenal", "troops", "roster", "collection",
        ),
        FieldCategory.FLAGS to setOf("flags", "quests", "achievements", "toggles", "events", "progress", "story", "switches"),
    )

    fun classify(
        path: String,
        key: String,
        value: FieldValue?,
        type: FieldType,
        declared: FieldCategory? = null,
    ): FieldCategory {
        if (declared != null) return declared

        val segments = FieldPath.parse(path).segments.mapNotNull { (it as? FieldPath.Segment.Key)?.name }
        val ancestors = segments.dropLast(1).map { it.lowercase() }
        val leaf = key.lowercase()

        for ((category, words) in CONTEXT) {
            if (ancestors.any { ancestor -> words.any { matches(ancestor, it) } }) return category
        }

        if (type == FieldType.BOOL) {
            return if (keywords(leaf).any { it in CURRENCY || it in STATS }) FieldCategory.OTHER else FieldCategory.FLAGS
        }

        // Tokenise the raw key: lowercasing first would hide camelCase boundaries ("maxHp").
        val leafKeywords = keywords(key)
        if (leafKeywords.any { it in CURRENCY } || matchesAny(leaf, CURRENCY)) return FieldCategory.CURRENCY
        if (leafKeywords.any { it in INVENTORY } || matchesAny(leaf, INVENTORY)) return FieldCategory.INVENTORY
        if (leafKeywords.any { it in STATS } || matchesAny(leaf, STATS)) return FieldCategory.STATS
        if (leafKeywords.any { it in FLAGS } || matchesAny(leaf, FLAGS)) return FieldCategory.FLAGS

        // Fall back to the whole path so `data.party[0].hp` still lands in stats.
        val pathText = segments.joinToString("_")
        if (pathText.isNotEmpty()) {
            if (keywords(pathText).any { it in CURRENCY }) return FieldCategory.CURRENCY
            if (keywords(pathText).any { it in INVENTORY }) return FieldCategory.INVENTORY
            if (keywords(pathText).any { it in STATS }) return FieldCategory.STATS
            if (keywords(pathText).any { it in FLAGS }) return FieldCategory.FLAGS
        }

        if (value is FieldValue.Text && type == FieldType.STRING && leaf.endsWith("name")) {
            return FieldCategory.OTHER
        }
        return FieldCategory.OTHER
    }

    /** `max_hp`, `maxHp`, `hp2` -> [max, hp, hp2]. */
    fun keywords(key: String): List<String> = Humanizer.tokens(key).map { it.lowercase() }

    /** Whole-key containment, only for distinctive (long) words so `int` never hits `point`. */
    private fun matchesAny(key: String, words: Set<String>): Boolean =
        words.any { word -> word.length >= 5 && key.contains(word) }

    private fun matches(token: String, word: String): Boolean =
        token == word || (word.length >= 5 && token.contains(word)) || (token.length >= 5 && word.contains(token))
}
