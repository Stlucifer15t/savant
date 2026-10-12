package dev.savant.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paths, values, validation and tree indexing — the parts every parser relies on. */
class FieldModelTest {

    // ---- FieldPath ----------------------------------------------------------------

    @Test
    fun pathsEncodeAndParseBack() {
        val cases = listOf(
            FieldPath.of("player", "gold"),
            FieldPath.of("party", 0, "hp"),
            FieldPath.of("save", "@version"),
            FieldPath.of("note", "#text"),
            FieldPath.of("weird.key"),
            FieldPath.of("with space"),
            FieldPath.of("0leading"),
            FieldPath.of("a", 1, "b", 2, "c"),
        )
        for (path in cases) {
            val encoded = path.encode()
            assertEquals("$encoded did not survive a round trip", path, FieldPath.parse(encoded))
        }
        assertEquals("player.gold", FieldPath.of("player", "gold").encode())
        assertEquals("party[0].hp", FieldPath.of("party", 0, "hp").encode())
        assertEquals("`weird.key`", FieldPath.of("weird.key").encode())
    }

    @Test
    fun parentAndLeafHelpers() {
        assertEquals("player", FieldPath.parentOf("player.gold"))
        assertEquals("", FieldPath.parentOf("gold"))
        assertEquals("party[0]", FieldPath.parentOf("party[0].hp"))
        assertEquals("hp", FieldPath.parse("party[0].hp").leafName)
        assertEquals("[2]", FieldPath.parse("items[2]").leafName)
    }

    // ---- FieldValue ---------------------------------------------------------------

    @Test
    fun parsesTypedInput() {
        assertEquals(FieldValue.Int64(999999), FieldValue.parse("999999"))
        assertEquals(FieldValue.Int64(-5), FieldValue.parse("-5"))
        assertEquals(FieldValue.Real(1.5), FieldValue.parse("1.5"))
        assertEquals(FieldValue.Int64(31), FieldValue.parse("0x1F"))
        assertEquals(FieldValue.Real(12000.0), FieldValue.parse("12k"))
        assertEquals(FieldValue.Bool(true), FieldValue.parse("true"))
        assertEquals(FieldValue.Bool(false), FieldValue.parse("off"))
        assertEquals(FieldValue.Null, FieldValue.parse("null"))
        assertEquals(FieldValue.Text("Aric"), FieldValue.parse("Aric"))
        assertEquals(FieldValue.Text(""), FieldValue.parse("   "))
    }

    @Test
    fun parseHonoursTheTargetType() {
        assertEquals(FieldValue.Real(5.0), FieldValue.parse("5", FieldType.DOUBLE))
        assertEquals(FieldValue.Int64(5), FieldValue.parse("5", FieldType.INT32))
        assertEquals(FieldValue.Int64(5), FieldValue.parse("5.0", FieldType.INT16))
    }

    @Test
    fun displayTextIsStable() {
        assertEquals("999999", FieldValue.Int64(999999).displayText())
        assertEquals("null", FieldValue.Null.displayText())
        assertEquals("true", FieldValue.Bool(true).displayText())
        assertEquals("\"Aric\"", FieldValue.Text("Aric").displayText())
        assertEquals("1.5", FieldValue.Real(1.5).displayText())
        assertEquals("0.5", FieldValue.Real(0.5).displayText())
        assertEquals("-0.25", FieldValue.Real(-0.25).displayText())
        assertEquals("0.000001", FieldValue.Real(0.000001).displayText())
        assertEquals("100000", FieldValue.Real(100000.0).displayText())
        assertEquals("NaN", FieldValue.Real(Double.NaN).displayText())
    }

    // ---- validation ---------------------------------------------------------------

    private fun node(
        type: FieldType,
        value: FieldValue,
        min: Double? = null,
        max: Double? = null,
        fixedWidth: Boolean = false,
        options: List<String> = emptyList(),
    ) = Node(
        path = "field",
        displayName = "Field",
        type = type,
        value = value,
        min = min,
        max = max,
        enumOptions = options,
        fixedWidth = fixedWidth,
    )

    @Test
    fun validationAcceptsSaneEdits() {
        assertTrue(EditValidator.validate(node(FieldType.INT32, FieldValue.Int64(5)), FieldValue.Int64(999999)).ok)
        assertTrue(EditValidator.validate(node(FieldType.STRING, FieldValue.Text("a")), FieldValue.Text("b")).ok)
        assertTrue(EditValidator.validate(node(FieldType.BOOL, FieldValue.Bool(false)), FieldValue.Bool(true)).ok)
        assertTrue(EditValidator.validate(node(FieldType.DOUBLE, FieldValue.Real(1.0)), FieldValue.Real(1.25)).ok)
        assertTrue(EditValidator.validate(node(FieldType.NULL, FieldValue.Null), FieldValue.Int64(3)).ok)
    }

    @Test
    fun validationRejectsBadEdits() {
        assertFalse(EditValidator.validate(node(FieldType.INT32, FieldValue.Int64(1)), FieldValue.Text("abc")).ok)
        assertFalse(EditValidator.validate(node(FieldType.INT32, FieldValue.Int64(1)), FieldValue.Real(1.5)).ok)
        assertFalse(EditValidator.validate(node(FieldType.BOOL, FieldValue.Bool(true)), FieldValue.Int64(1)).ok)
        assertFalse(EditValidator.validate(node(FieldType.UINT8, FieldValue.Int64(1), fixedWidth = true), FieldValue.Int64(300)).ok)
        assertFalse(EditValidator.validate(node(FieldType.UINT8, FieldValue.Int64(1), fixedWidth = true), FieldValue.Int64(-1)).ok)
        assertFalse(EditValidator.validate(node(FieldType.INT32, FieldValue.Int64(1), max = 10.0), FieldValue.Int64(11)).ok)
        assertFalse(EditValidator.validate(node(FieldType.INT32, FieldValue.Int64(1), min = 3.0), FieldValue.Int64(2)).ok)
        val enumNode = node(FieldType.ENUM, FieldValue.Text("a"), options = listOf("a", "b"))
        assertFalse(EditValidator.validate(enumNode, FieldValue.Text("c")).ok)
        assertTrue(EditValidator.validate(enumNode, FieldValue.Text("b")).ok)
        val container = Node("obj", "Obj", FieldType.OBJECT, null, editable = false)
        assertFalse(EditValidator.validate(container, FieldValue.Int64(1)).ok)
    }

    @Test
    fun coercionFollowsTheFieldType() {
        assertEquals(FieldValue.Int64(12), EditValidator.coerce(node(FieldType.INT32, FieldValue.Int64(1)), "12"))
        assertEquals(FieldValue.Real(1.25), EditValidator.coerce(node(FieldType.DOUBLE, FieldValue.Real(1.0)), "1.25"))
        assertEquals(FieldValue.Int64(3), EditValidator.coerce(node(FieldType.INT64, FieldValue.Int64(1)), "3.0"))
        assertEquals(FieldValue.Bool(true), EditValidator.coerce(node(FieldType.BOOL, FieldValue.Bool(false)), "1"))
        assertEquals(FieldValue.Text("5"), EditValidator.coerce(node(FieldType.STRING, FieldValue.Text("a")), "5"))
        assertEquals(FieldValue.Text("a"), EditValidator.coerce(node(FieldType.STRING, FieldValue.Text("x")), "\"a\""))
    }

    // ---- tree ---------------------------------------------------------------------

    private fun tree() = FieldTree(
        format = SaveFormat.JSON,
        roots = listOf(
            Node(
                path = "player",
                displayName = "Player",
                type = FieldType.OBJECT,
                value = null,
                editable = false,
                children = listOf(
                    Node("player.gold", "Gold", FieldType.INT32, FieldValue.Int64(5000), FieldCategory.CURRENCY),
                    Node("player.maxHp", "Max HP", FieldType.INT32, FieldValue.Int64(40), FieldCategory.STATS),
                    Node("player.name", "Name", FieldType.STRING, FieldValue.Text("Aric")),
                ),
            ),
        ),
    )

    @Test
    fun treeIndexesAndCounts() {
        val fieldTree = tree()
        assertEquals(4, fieldTree.totalCount)
        assertEquals(3, fieldTree.editableCount)
        assertNotNull(fieldTree.node("player.gold"))
        assertEquals(5000L, (fieldTree.node("player.gold")!!.value as FieldValue.Int64).value)
        assertEquals(3, fieldTree.groups().sumOf { it.count })
    }

    @Test
    fun treeWithEditsReturnsANewTree() {
        val before = tree()
        val edit = FieldEdit.of(before.node("player.gold")!!, FieldValue.Int64(1))
        val after = before.withEdits(listOf(edit))
        assertEquals(5000L, (before.node("player.gold")!!.value as FieldValue.Int64).value)
        assertEquals(1L, (after.node("player.gold")!!.value as FieldValue.Int64).value)
        assertEquals(before.totalCount, after.totalCount)
        assertEquals(before.node("player.maxHp"), after.node("player.maxHp"))
    }

    @Test
    fun treeSearchIsTolerant() {
        val fieldTree = tree()
        assertTrue(fieldTree.search("gold").isNotEmpty())
        assertTrue(fieldTree.search("PLAYERGOLD").isNotEmpty())
        assertTrue(fieldTree.search("aric").any { it.path == "player.name" })
        assertTrue(fieldTree.search("category:stats").all { it.category == FieldCategory.STATS })
        assertTrue(fieldTree.search("type:str").any { it.path == "player.name" })
        assertTrue(fieldTree.search("nothingmatches").isEmpty())
    }

    @Test
    fun readOnlyTreesSayWhy() {
        val fieldTree = FieldTree(SaveFormat.JSON, emptyList(), readOnlyReason = "gzip payload")
        assertTrue(fieldTree.readOnly)
        assertEquals("gzip payload", fieldTree.readOnlyReason)
    }
}
