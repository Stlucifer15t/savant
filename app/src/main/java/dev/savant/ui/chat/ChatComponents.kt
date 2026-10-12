package dev.savant.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.using
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.savant.domain.model.FieldCategory
import dev.savant.domain.model.FieldChange
import dev.savant.domain.model.FieldEditor
import dev.savant.domain.model.FieldNode
import dev.savant.domain.model.FieldType
import dev.savant.domain.model.FieldValue
import dev.savant.domain.model.asDisplayString
import dev.savant.ui.components.SavantIcon
import dev.savant.ui.components.SavantIconName
import dev.savant.ui.theme.JetBrainsMono
import dev.savant.ui.theme.LocalReducedMotion
import dev.savant.ui.theme.LocalSavantColors
import kotlinx.coroutines.delay

@Composable
fun AssistantBlock(
    text: String,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(if (content == null) 42.dp else 58.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            content?.invoke()
        }
    }
}

@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = LocalSavantColors.current.userBubble,
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LocalSavantColors.current.border),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun DropSaveZone(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val border = LocalSavantColors.current.border
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = border,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 7.dp.toPx())),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
            }
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        SavantIcon(
            SavantIconName.ATTACH,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = LocalSavantColors.current.muted,
        )
        Text(
            "drop a save file",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "or tap to browse · up to 50 MB",
            style = MaterialTheme.typography.bodySmall,
            color = LocalSavantColors.current.muted,
        )
    }
}

@Composable
fun FileChip(
    source: SourceFileUi,
    formatName: String?,
    confidence: Int?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalSavantColors.current.border),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HexThumbnail(source.thumbnailSeed)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatBytes(source.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalSavantColors.current.muted,
                )
            }
            if (formatName != null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TypeTag(formatName.uppercase())
                    confidence?.let {
                        Text(
                            "$it% match",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalSavantColors.current.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HexThumbnail(seed: Int) {
    val border = LocalSavantColors.current.border
    val muted = LocalSavantColors.current.muted
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(LocalSavantColors.current.raisedSurface, RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize().padding(4.dp)) {
            val spacing = size.width / 4f
            for (column in -1..4) {
                for (row in -1..4) {
                    val x = column * spacing + if (row % 2 == 0) 0f else spacing / 2f
                    val y = row * spacing * .86f
                    drawCircle(
                        color = muted.copy(alpha = if ((seed + column * 7 + row) % 3 == 0) .42f else .16f),
                        radius = 1.15.dp.toPx(),
                        center = Offset(x, y),
                    )
                }
            }
        }
        Text(
            "%02X".format(seed and 0xFF),
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun ParseProgress(logs: List<String>, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalSavantColors.current.border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            logs.forEachIndexed { index, line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (index == logs.lastIndex && active) ">" else "·",
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        color = if (index == logs.lastIndex && active) MaterialTheme.colorScheme.primary else LocalSavantColors.current.muted,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == logs.lastIndex) MaterialTheme.colorScheme.onSurface else LocalSavantColors.current.muted,
                    )
                }
            }
        }
    }
}

@Composable
fun FieldSearch(
    query: String,
    category: FieldCategory?,
    onQueryChange: (String) -> Unit,
    onCategoryChange: (FieldCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = {
                Text("search name, path, or value", style = MaterialTheme.typography.bodyMedium)
            },
            leadingIcon = {
                SavantIcon(
                    SavantIconName.SEARCH,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = LocalSavantColors.current.muted,
                )
            },
            shape = RoundedCornerShape(6.dp),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CategoryChip("all", category == null) { onCategoryChange(null) }
            FieldCategory.entries.forEach { item ->
                CategoryChip(item.label.lowercase(), category == item) { onCategoryChange(item) }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick),
        color = if (selected) LocalSavantColors.current.raisedSurface else MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalSavantColors.current.border),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else LocalSavantColors.current.muted,
        )
    }
}

@Composable
fun FieldGroupHeader(
    category: FieldCategory,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            category.label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Surface(
            color = LocalSavantColors.current.raisedSurface,
            shape = RoundedCornerShape(3.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, LocalSavantColors.current.border),
        ) {
            Text(
                count.toString(),
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = LocalSavantColors.current.muted,
            )
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Box(
            Modifier.size(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            SavantIcon(
                SavantIconName.CHEVRON_DOWN,
                contentDescription = if (expanded) "collapse ${category.label}" else "expand ${category.label}",
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 0f else -90f),
                tint = LocalSavantColors.current.muted,
            )
        }
    }
    HorizontalDivider(color = LocalSavantColors.current.border)
}

@Composable
fun FieldRow(
    node: FieldNode,
    rowIndex: Int,
    onProposeEdit: (String, String) -> String?,
    modifier: Modifier = Modifier,
) {
    var editing by remember(node.path) { mutableStateOf(false) }
    var input by remember(node.path, editing) { mutableStateOf(node.value?.asDisplayString().orEmpty()) }
    var error by remember(node.path) { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val extras = LocalSavantColors.current
    val reducedMotion = LocalReducedMotion.current
    val flash = remember(node.path) { Animatable(0f) }
    var previousValue by remember(node.path) { mutableStateOf(node.value) }

    LaunchedEffect(node.value) {
        if (previousValue != node.value) {
            previousValue = node.value
            if (!reducedMotion) {
                flash.snapTo(1f)
                flash.animateTo(0f, tween(400))
            }
        }
    }
    LaunchedEffect(editing) {
        if (editing) {
            delay(40)
            focusRequester.requestFocus()
        }
    }

    val commit = {
        val result = onProposeEdit(node.path, input)
        error = result
        if (result == null) {
            editing = false
            focusManager.clearFocus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                lerp(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary, flash.value * .12f),
            )
            .clickable(enabled = node.editable && !editing) { editing = true }
            .padding(vertical = 11.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        node.displayName,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    node.aiGuess?.let {
                        Spacer(Modifier.width(6.dp))
                        Text("likely", style = MaterialTheme.typography.labelSmall, color = extras.muted)
                    }
                }
                Text(
                    node.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (!editing) {
                AnimatedContent(
                    targetState = node.value?.asDisplayString().orEmpty(),
                    transitionSpec = {
                        if (reducedMotion || node.type !in setOf(FieldType.INT, FieldType.FLOAT)) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            (slideInVertically(tween(170)) { it / 2 } + fadeIn(tween(170))) togetherWith
                                (slideOutVertically(tween(170)) { -it / 2 } + fadeOut(tween(120))
                        }
                    },
                    label = "field value",
                ) { value ->
                    Text(
                        value.ifEmpty { "empty" },
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TypeTag(node.type.tag)
            }
        }

        if (editing) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; error = null },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = node.type != FieldType.STRING,
                textStyle = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (node.type) {
                        FieldType.INT -> KeyboardType.Number
                        FieldType.FLOAT -> KeyboardType.Decimal
                        else -> KeyboardType.Text
                    },
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                shape = RoundedCornerShape(6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (node.type == FieldType.INT || node.type == FieldType.FLOAT) {
                    QuickValueChip("MAX", FieldEditor.maximumValue(node) != null) {
                        input = FieldEditor.maximumValue(node).orEmpty()
                    }
                    QuickValueChip("0", true) { input = "0" }
                    QuickValueChip("×2", FieldEditor.doubledValue(node) != null) {
                        input = FieldEditor.doubledValue(node).orEmpty()
                    }
                } else if (node.type == FieldType.BOOL) {
                    QuickValueChip("true", true) { input = "true" }
                    QuickValueChip("false", true) { input = "false" }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "cancel",
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { editing = false }.padding(7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = extras.muted,
                )
                Text(
                    "preview",
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { commit() }
                        .padding(7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    HorizontalDivider(color = extras.border)
}

@Composable
private fun QuickValueChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalSavantColors.current.border),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else LocalSavantColors.current.muted.copy(alpha = .45f),
        )
    }
}

@Composable
fun TypeTag(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = LocalSavantColors.current.raisedSurface,
        shape = RoundedCornerShape(3.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalSavantColors.current.border),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = LocalSavantColors.current.muted,
        )
    }
}

@Composable
fun ExportCard(
    export: ExportUi,
    onShare: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalSavantColors.current.border),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        export.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${formatBytes(export.size)} · checksum ${export.checksumStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalSavantColors.current.muted,
                    )
                }
                SavantIcon(
                    SavantIconName.CHECK,
                    contentDescription = "valid export",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    SavantIcon(
                        SavantIconName.SHARE,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("share")
                }
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                    SavantIcon(
                        SavantIconName.SAVE,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = LocalContentColor.current,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("save")
                }
            }
        }
    }
}

@Composable
fun ChatComposer(
    enabled: Boolean,
    onAttach: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    val border = LocalSavantColors.current.border
    val send = {
        if (text.isNotBlank() && enabled) {
            onSend(text)
            text = ""
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind { drawLine(border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        IconButton(onClick = onAttach, enabled = enabled, modifier = Modifier.size(38.dp)) {
            SavantIcon(
                SavantIconName.ATTACH,
                contentDescription = "attach save file",
                modifier = Modifier.size(20.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onBackground else LocalSavantColors.current.muted,
            )
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text(
                            "describe an edit",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalSavantColors.current.muted,
                        )
                    }
                    inner()
                }
            },
        )
        IconButton(
            onClick = send,
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (enabled && text.isNotBlank()) MaterialTheme.colorScheme.primary
                    else LocalSavantColors.current.raisedSurface,
                    RoundedCornerShape(6.dp),
                ),
        ) {
            SavantIcon(
                SavantIconName.SEND,
                contentDescription = "send command",
                modifier = Modifier.size(18.dp),
                tint = if (enabled && text.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                else LocalSavantColors.current.muted,
            )
        }
    }
}

@Composable
fun DiffRow(
    change: FieldChange,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    change.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TypeTag(change.type.tag)
            }
            Text(
                change.path,
                style = MaterialTheme.typography.labelSmall,
                color = LocalSavantColors.current.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    change.oldValue.asDisplayString(),
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                    color = LocalSavantColors.current.muted,
                    textDecoration = TextDecoration.LineThrough,
                )
                Text("→", color = LocalSavantColors.current.muted)
                Text(
                    change.newValue.asDisplayString(),
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = LocalSavantColors.current.accentText,
                )
            }
        }
        IconButton(onClick = onRevert) {
            SavantIcon(
                SavantIconName.REVERT,
                contentDescription = "revert ${change.displayName}",
                modifier = Modifier.size(18.dp),
                tint = LocalSavantColors.current.muted,
            )
        }
    }
}

@Composable
fun ApplyChangesButton(
    count: Int,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = count > 0 && !loading,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("apply $count ${if (count == 1) "change" else "changes"}")
        }
    }
}
