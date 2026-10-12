package com.visionnav.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.visionnav.app.ui.kerb.ScreenGutter
import com.visionnav.app.ui.kerb.bleed
import com.visionnav.app.ui.theme.VisionNavTheme

/** Minimum touch target for any control (spec: >= 72 dp). */
val MinTouchTarget: Dp = 72.dp

/** Minimum height of primary full-width actions (spec: >= 96 dp). */
val PrimaryTouchTarget: Dp = 96.dp

enum class ActionStyle { PRIMARY, SECONDARY, DANGER, QUIET }

private data class ActionColors(val container: Color, val content: Color, val pressed: Color, val pressedContent: Color)

/*
 * Street roles:
 *  SECONDARY = zebra bar (road paint on asphalt) — the everyday action.
 *  PRIMARY   = signal amber — the one thing a screen wants you to do next.
 *  QUIET     = kerb stone — navigation to other screens, settings.
 *  DANGER    = signal red — stop / emergency.
 * Pressing any bar flashes it amber, so a press is visible to low-vision users.
 */
@Composable
private fun colorsFor(style: ActionStyle): ActionColors {
    val k = VisionNavTheme.kerb
    return when (style) {
        ActionStyle.SECONDARY -> ActionColors(k.bar, k.onBar, k.amber, k.onAmber)
        ActionStyle.PRIMARY -> ActionColors(k.amber, k.onAmber, k.bar, k.onBar)
        ActionStyle.QUIET -> ActionColors(k.kerb, k.onKerb, k.amber, k.onAmber)
        ActionStyle.DANGER -> ActionColors(k.red, k.onRed, k.amber, k.onAmber)
    }
}

/**
 * Full-width, very large action. The whole surface is one TalkBack node (Role.Button) whose
 * label is [text] followed by [supportingText]. Keyboard / switch-access focus draws a thick
 * contrasting ring so focus is visible to low-vision users.
 */
@Composable
fun BigActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supportingText: String? = null,
    style: ActionStyle = ActionStyle.SECONDARY,
    minHeight: Dp = PrimaryTouchTarget,
    enabled: Boolean = true,
    stateDescription: String? = null,
    onClickLabel: String? = null,
) {
    ActionSurface(
        modifier = modifier,
        style = style,
        minHeight = minHeight,
        enabled = enabled,
        stateDescription = stateDescription,
        clickModifier = { interaction ->
            Modifier.clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
        },
        icon = icon,
        text = text,
        supportingText = supportingText,
    )
}

/** As [BigActionButton] but with a separate long-press action (used for SOS). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LongPressActionButton(
    text: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLongClickLabel: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supportingText: String? = null,
    style: ActionStyle = ActionStyle.DANGER,
    minHeight: Dp = PrimaryTouchTarget,
) {
    ActionSurface(
        modifier = modifier,
        style = style,
        minHeight = minHeight,
        enabled = true,
        stateDescription = null,
        clickModifier = { interaction ->
            Modifier.combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                role = Role.Button,
                onLongClickLabel = onLongClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
        },
        icon = icon,
        text = text,
        supportingText = supportingText,
    )
}

@Composable
private fun ActionSurface(
    modifier: Modifier,
    style: ActionStyle,
    minHeight: Dp,
    enabled: Boolean,
    stateDescription: String?,
    clickModifier: (MutableInteractionSource) -> Modifier,
    icon: ImageVector?,
    text: String,
    supportingText: String?,
) {
    val colors = colorsFor(style)
    val k = VisionNavTheme.kerb
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val large = minHeight >= PrimaryTouchTarget
    // Road paint has square ends; full-size bars run to the screen edge.
    var m = modifier
        // Bars and kerbstones both run edge to edge; only the emergency button is a held object.
        .then(if ((large && style != ActionStyle.DANGER) || style == ActionStyle.QUIET) Modifier.bleed() else Modifier)
        .fillMaxWidth()
        .heightIn(min = minHeight)
        .background(if (pressed) colors.pressed else colors.container)
    if (focused) m = m.border(6.dp, k.amberInk)
    m = m.then(clickModifier(interaction))
    if (stateDescription != null) m = m.semantics { this.stateDescription = stateDescription }
    if (!enabled) m = m.alpha(0.5f)

    CompositionLocalProvider(LocalContentColor provides if (pressed) colors.pressedContent else colors.content) {
        Row(
            modifier = m.padding(horizontal = if (large || style == ActionStyle.QUIET) ScreenGutter + 8.dp else 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Icons only where the shape itself is the warning (danger). Everywhere else the word is the label.
            if (icon != null && style == ActionStyle.DANGER) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = text,
                    style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                )
                if (supportingText != null) {
                    Text(text = supportingText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Square icon-only control with a guaranteed 72 dp target and a spoken label. */
@Composable
fun LargeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    var m = modifier
        .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
        .clip(shape)
    if (focused) m = m.border(5.dp, VisionNavTheme.kerb.amberInk, shape)
    androidx.compose.foundation.layout.Box(
        modifier = m
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(36.dp))
    }
}
