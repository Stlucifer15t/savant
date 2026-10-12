package dev.savant.domain

import dev.savant.domain.model.EditResult
import dev.savant.domain.model.FieldEditor
import dev.savant.domain.model.FieldNode
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class FieldEditorTest {
    @Test
    fun `integer edit enforces declared bounds`() {
        val field = FieldNode(
            path = "$.hp",
            displayName = "HP",
            type = FieldType.INT,
            value = FieldValue.IntegerValue(20),
            min = 0.0,
            max = 100.0,
            editable = true,
        )

        assertEquals(
            FieldValue.IntegerValue(99),
            assertIs<EditResult.Valid>(FieldEditor.parse(field, "99")).value,
        )
        assertEquals(
            "maximum is 100",
            assertIs<EditResult.Invalid>(FieldEditor.parse(field, "101")).reason,
        )
    }

    @Test
    fun `boolean parser accepts explicit save conventions`() {
        val field = FieldNode(
            path = "$.flag",
            displayName = "Flag",
            type = FieldType.BOOL,
            value = FieldValue.BooleanValue(false),
            editable = true,
        )

        assertEquals(
            FieldValue.BooleanValue(true),
            assertIs<EditResult.Valid>(FieldEditor.parse(field, "on")).value,
        )
    }
}
