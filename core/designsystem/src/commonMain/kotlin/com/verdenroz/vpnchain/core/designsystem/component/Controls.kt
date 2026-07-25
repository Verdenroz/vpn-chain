package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.verdenroz.vpnchain.core.designsystem.theme.PanelTheme

/**
 * A miniature of the main throw switch, for the boolean settings. Same
 * vocabulary at a smaller scale — a lamp appears in the half the paddle
 * vacates, so "on" is legible without reading anything.
 */
@Composable
fun PanelToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = PanelTheme.colors
    val travel by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(300, easing = SettleEasing),
        label = "toggle-travel",
    )

    Box(
        modifier
            .size(width = ToggleWidth, height = ToggleHeight)
            .machinedSurface(colors, corner = 2.dp, recessed = true, fill = colors.shellDeep)
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onValueChange = onCheckedChange,
            )
            .padding(2.dp),
    ) {
        // The paddle travels right when on, so the lamp lives in the half it
        // vacates — otherwise the paddle covers the very thing it switches on.
        IndicatorLamp(
            color = colors.lampGreen,
            lit = checked && enabled,
            size = 6.dp,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        BoxWithConstraints {
            Box(
                Modifier
                    .width(maxWidth / 2)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = travel * size.width
                        alpha = if (enabled) 1f else 0.5f
                    }
                    .machinedSurface(colors, corner = 1.dp, fill = colors.shellRaised),
            )
        }
    }
}

/**
 * A machined key. [prominent] is the one that does the thing; everything else
 * is a plain face, because a panel where every key looks primary has no
 * hierarchy at all.
 */
@Composable
fun PanelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = false,
) {
    val colors = PanelTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depress by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(90),
        label = "key-depress",
    )

    Box(
        modifier
            .graphicsLayer {
                scaleY = 1f - depress * 0.05f
                alpha = if (enabled) 1f else 0.45f
            }
            .machinedSurface(
                colors,
                corner = 2.dp,
                fill = if (prominent) colors.shellRaised else colors.shellFace,
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        EngravedText(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (prominent) colors.lampGreen else colors.engraved,
        )
    }
}

/**
 * An input sunk into the panel, with its name engraved above it rather than
 * floating inside it. Values are machine data, so they're set in the mono face.
 */
@Composable
fun PanelField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    numeric: Boolean = false,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    val colors = PanelTheme.colors
    Column(modifier.fillMaxWidth()) {
        EngravedText(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.engraved,
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.readout),
            cursorBrush = SolidColor(colors.lampAmber),
            singleLine = minLines == 1,
            minLines = minLines,
            visualTransformation =
                if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .machinedSurface(colors, corner = 2.dp, recessed = true, fill = colors.shellDeep)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}

/**
 * A multi-position selector: one raised paddle sliding across engraved stops,
 * the same mechanism as the switch rather than a second idiom for the same job.
 */
@Composable
fun PanelSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PanelTheme.colors
    val travel by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(300, easing = SettleEasing),
        label = "selector-travel",
    )

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(SelectorHeight)
            .machinedSurface(colors, corner = 2.dp, recessed = true, fill = colors.shellDeep)
            .padding(2.dp),
    ) {
        val stopWidth = maxWidth / options.size
        Box(
            Modifier
                .width(stopWidth)
                .fillMaxHeight()
                .graphicsLayer { translationX = travel * size.width }
                .machinedSurface(colors, corner = 1.dp, fill = colors.shellRaised),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { index, option ->
                Box(
                    Modifier
                        .width(stopWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (index == selectedIndex) colors.readout else colors.muted,
                    )
                }
            }
        }
    }
}

private val SettleEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val ToggleWidth = 48.dp
private val ToggleHeight = 26.dp
private val SelectorHeight = 38.dp
