package com.visionnav.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.visionnav.app.R
import com.visionnav.app.ui.aura.ScreenGutter
import com.visionnav.app.ui.theme.VisionNavTheme

/*
 * Kerb controls. Settings rows are kerbstones: square, edge to edge, joined by thin joints.
 * State is always a written word ("On", "Off", "Selected"); colour only repeats it. Pressing
 * any row flashes it amber, like the zebra bars, so a press is visible to low-vision users.
 */

/** Divider between rows of a group (the group's background shows through the gap). */
private val Divider = 1.dp

private val GroupShape = RoundedCornerShape(12.dp)

/** AURA list group: one outlined surface, rows separated by dividers (borders over boxes, §07). */
@Composable
private fun Modifier.listGroup(): Modifier {
    val aura = VisionNavTheme.aura
    return this
        .fillMaxWidth()
        .clip(GroupShape)
        .background(aura.border)
        .border(1.5.dp, aura.borderStrong, GroupShape)
}

/** True inside a [ListGroup]: quiet rows then draw as plain rows and let the group draw the outline. */
val LocalInListGroup = staticCompositionLocalOf { false }

/** Rows grouped in one outlined list. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalInListGroup provides true) {
        Column(modifier.listGroup(), verticalArrangement = Arrangement.spacedBy(Divider), content = content)
    }
}

/** One row of information (not an action) inside a [ListGroup]. */
@Composable
fun ListRow(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val aura = VisionNavTheme.aura
    CompositionLocalProvider(LocalContentColor provides aura.text) {
        Column(
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .background(aura.surface)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            content = content,
        )
    }
}

/**
 * Whole-row switch (Role.Switch) as a kerbstone. The state is a word in a block at the end of
 * the row: "On" painted amber, "Off" left as bare ground, so the word, the fill and the
 * TalkBack state all agree.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val aura = VisionNavTheme.aura
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val stateText = stringResource(if (checked) R.string.state_on else R.string.state_off)
    val fg = aura.text
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .background(if (pressed) aura.surfaceMuted else aura.surface)
            .then(if (focused) Modifier.border(3.dp, aura.info) else Modifier)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .semantics { stateDescription = stateText }
            .then(if (enabled) Modifier else Modifier.alpha(0.5f))
            .padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = fg)
        }
        // Visual duplicate of the state; TalkBack already has it from stateDescription.
        Box(
            Modifier
                .widthIn(min = 76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (checked) aura.primary else aura.surfaceMuted)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Text(stateText, style = MaterialTheme.typography.labelLarge, color = if (checked) aura.onPrimary else aura.text)
        }
    }
}

data class RadioOption<T>(val value: T, val label: String, val description: String? = null)

/**
 * Single choice as a run of kerbstones. The chosen stone is painted (like a zebra bar) and
 * carries the word "Selected"; TalkBack hears it as a radio button in a group.
 */
@Composable
fun <T> RadioGroup(
    options: List<RadioOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = VisionNavTheme.aura
    Column(modifier.listGroup().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Divider)) {
        options.forEach { option ->
            val isSelected = option.value == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val focused by interaction.collectIsFocusedAsState()
            val bg = when {
                pressed -> aura.surfaceMuted
                isSelected -> aura.surfaceMuted
                else -> aura.surface
            }
            val fg = aura.text
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .background(bg)
                    .drawBehind { if (isSelected) drawRect(aura.primary, size = Size(5.dp.toPx(), size.height)) }
                    .then(if (focused) Modifier.border(3.dp, aura.info) else Modifier)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(option.value) },
                    )
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = fg,
                    )
                    if (option.description != null) {
                        Text(option.description, style = MaterialTheme.typography.bodyMedium, color = fg)
                    }
                }
                if (isSelected) {
                    Text(
                        stringResource(R.string.state_selected),
                        style = MaterialTheme.typography.labelLarge,
                        color = fg,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/**
 * Accessible stepper (preferred over a slider for screen-reader users): the current value on a
 * kerbstone, then two word buttons such as "Slower" and "Faster". The value is a polite live
 * region, so each press is announced.
 */
@Composable
fun StepperRow(
    title: String,
    valueText: String,
    decreaseText: String,
    increaseText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
) {
    ListGroup(modifier) {
        ListRow(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueText, style = MaterialTheme.typography.headlineSmall)
        }
        BigActionButton(
            text = decreaseText,
            onClick = onDecrease,
            style = ActionStyle.QUIET,
            minHeight = MinTouchTarget,
            enabled = canDecrease,
        )
        BigActionButton(
            text = increaseText,
            onClick = onIncrease,
            style = ActionStyle.QUIET,
            minHeight = MinTouchTarget,
            enabled = canIncrease,
        )
    }
}

/**
 * Text field as a held object: rounded, raised, with its label inside so TalkBack reads label
 * and value together. Focus draws a thick amber edge. Errors are words below the field, also
 * exposed through semantics, and turn the edge amber.
 */
@Composable
fun AuraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    errorText: String? = null,
    large: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val aura = VisionNavTheme.aura
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val textStyle = (if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
        .copy(color = aura.text, fontWeight = FontWeight.SemiBold)
    val edge = when {
        focused -> aura.attention
        errorText != null -> aura.attention
        else -> aura.textSecondary
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(aura.attention),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (errorText != null) error(errorText) },
            decorationBox = { inner ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (large) PrimaryTouchTarget else MinTouchTarget)
                        .clip(shape)
                        .background(aura.surface)
                        .border(if (focused || errorText != null) 4.dp else 2.dp, edge, shape)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = aura.text)
                    Box {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = textStyle.copy(color = aura.textSecondary, fontWeight = FontWeight.Normal))
                        }
                        inner()
                    }
                }
            },
        )
        if (errorText != null) {
            Text(
                errorText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = aura.attention,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * A small action set into a kerbstone (e.g. "Remove" beside a contact): a ground-coloured
 * notch with a word, at least 72 dp, with a spoken label that names its target.
 */
@Composable
fun InsetAction(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aura = VisionNavTheme.aura
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .heightIn(min = MinTouchTarget)
            .widthIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) aura.surfaceMuted else aura.surface)
            .border(1.5.dp, aura.borderStrong, RoundedCornerShape(8.dp))
            .then(if (focused) Modifier.border(3.dp, aura.info, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = aura.text)
    }
}
