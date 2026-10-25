package com.verdenroz.vpnchain.core.designsystem.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

enum class Edge { Top, Left, Right }

fun Modifier.hairline(edge: Edge, color: Color): Modifier = drawBehind {
    val (start, end) = when (edge) {
        Edge.Top -> Offset.Zero to Offset(size.width, 0f)
        Edge.Left -> Offset.Zero to Offset(0f, size.height)
        Edge.Right -> Offset(size.width, 0f) to Offset(size.width, size.height)
    }
    drawLine(color, start, end, 1f)
}
