package app.krewd.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.krewd.constants.ShellDimensions
import io.github.zainbits.cohesion.GlassBackdrop
import io.github.zainbits.cohesion.GlassStyle
import io.github.zainbits.cohesion.LocalGlassStyle
import io.github.zainbits.cohesion.glassPullContent
import io.github.zainbits.cohesion.glassPullGesture
import io.github.zainbits.cohesion.rememberGlassPullState
import io.github.zainbits.vitreous.GlassGroup
import io.github.zainbits.vitreous.GlassIconButton
import io.github.zainbits.vitreous.GlassShape
import io.github.zainbits.vitreous.GlassSurface

/*
 * Krewd's floating pills and buttons, in one place, so every screen has the same liquid feel (Cohesion through
 * Vitreous). Each one draws one of three ways:
 *  - Glass: the glass switch is on, the caller asked for glass and the screen has content behind it
 *    ([LocalKrewdBackdrop]): liquid glass that stretches under the finger and merges with its neighbours.
 *  - Flat: the switch is on but there is nothing worth seeing through (the browser's bars, a coloured button):
 *    a solid pill that still stretches and merges, with no offscreen pass.
 *  - Plain: the switch is off, or battery saver or heat turned it off: the plain Material pill, no stretch.
 */

/** How strongly a [KrewdPill]'s glassTint colours its glass (a selected browser tab). */
private const val GLASS_TINT_ALPHA = 0.55f

/** The content glass on this screen shows (the tab screens, a routine's page, a picture); null = nothing. */
val LocalKrewdBackdrop = compositionLocalOf<GlassBackdrop?> { null }

private enum class Look { Glass, Flat, Plain }

/** Set inside a [LiquidRow], whose look its pieces share. */
private val LocalRowLook = compositionLocalOf<Look?> { null }

@Composable
private fun look(glass: Boolean): Look {
    LocalRowLook.current?.let { return it }
    return when {
        !LocalKrewdGlass.current.on -> Look.Plain
        glass && LocalKrewdBackdrop.current != null -> Look.Glass
        else -> Look.Flat
    }
}

@Composable
private fun flatStyle(color: Color = Color.Unspecified): GlassStyle {
    val fill = if (color.isSpecified) color else MaterialTheme.colorScheme.surfaceContainerHigh
    return remember(fill) { GlassStyle(solidColor = fill) }
}

@Composable
private fun PlainPill(
    modifier: Modifier,
    onClick: (() -> Unit)?,
    enabled: Boolean,
    color: Color,
    contentColor: Color,
    shadow: Dp,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val fill = if (color.isSpecified) color else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = if (contentColor.isSpecified) contentColor else MaterialTheme.colorScheme.onSurface
    // The default floating pill has its hairline and shadow; a coloured button (Send) has neither.
    val border = if (color.isSpecified) null else BorderStroke(ShellDimensions.PILL_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant)
    val lift = if (color.isSpecified) 0.dp else shadow
    if (onLongClick != null) {
        // The Surface clips its content, so the press ripple follows the capsule.
        Surface(modifier = modifier, shape = CircleShape, color = fill, contentColor = ink, border = border, shadowElevation = lift) {
            Box(Modifier.combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick ?: {}), propagateMinConstraints = true) { content() }
        }
    } else if (onClick != null) {
        Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = CircleShape, color = fill, contentColor = ink, border = border, shadowElevation = lift, content = content)
    } else {
        Surface(modifier = modifier, shape = CircleShape, color = fill, contentColor = ink, border = border, shadowElevation = lift, content = content)
    }
}

/**
 * Pills side by side that act as one liquid bar: in glass or flat they draw in one pass, and pulling one into
 * its neighbour (or pieces closer than half of [merge]) flows them together, like the chat's back button and
 * title. Put [KrewdPill]s and [KrewdIconPill]s inside.
 */
@Composable
fun LiquidRow(
    modifier: Modifier = Modifier,
    glass: Boolean = true,
    merge: Dp = 10.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val look = look(glass)
    if (look == Look.Plain) {
        Row(modifier, horizontalArrangement, verticalAlignment, content)
        return
    }
    val style = if (look == Look.Glass) LocalGlassStyle.current else flatStyle()
    GlassGroup(
        backdrop = if (look == Look.Glass) LocalKrewdBackdrop.current else null,
        modifier = modifier,
        style = style,
        merge = merge,
    ) { group ->
        CompositionLocalProvider(LocalRowLook provides look) {
            Row(group, horizontalArrangement, verticalAlignment, content)
        }
    }
}

/**
 * A floating capsule (a page title, a status chip, a URL bar, a browser tab). It stretches under the finger in
 * glass and flat; with [onClick] a press released over it is a click, with [onLongClick] a press held still.
 * In a scrolling row give its [scrollAxis], so a drag along the row scrolls it. Content colour: the theme's
 * (the glass tint keeps it readable over anything).
 */
@Composable
fun KrewdPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    glass: Boolean = true,
    height: Dp = ShellDimensions.TITLE_PILL_HEIGHT,
    color: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    contentAlignment: Alignment = Alignment.CenterStart,
    onLongClick: (() -> Unit)? = null,
    scrollAxis: Orientation? = null,
    glassTint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    when (val look = look(glass)) {
        Look.Plain -> PlainPill(modifier.height(height), onClick, enabled, color, contentColor, if (height < 44.dp) 1.dp else 2.dp, onLongClick) {
            Box(contentAlignment = contentAlignment, content = content)
        }
        else -> {
            val ink = if (contentColor.isSpecified) contentColor else MaterialTheme.colorScheme.onSurface
            CompositionLocalProvider(LocalContentColor provides ink) {
                GlassSurface(
                    backdrop = if (look == Look.Glass) LocalKrewdBackdrop.current else null,
                    modifier = modifier.height(height),
                    shape = GlassShape.Capsule,
                    style = if (look != Look.Glass) flatStyle(color)
                    else if (glassTint.isSpecified) LocalGlassStyle.current.let { it.copy(tint = glassTint, tintAlpha = maxOf(it.tintAlpha, GLASS_TINT_ALPHA)) }
                    else LocalGlassStyle.current,
                    contentAlignment = contentAlignment,
                    elastic = true,
                    onClick = onClick,
                    enabled = enabled,
                    onLongClick = onLongClick,
                    scrollAxis = scrollAxis,
                    content = content,
                )
            }
        }
    }
}

/** A round floating button with an icon: glass or flat it swells, stretches toward the finger and wobbles back. */
@Composable
fun KrewdIconPill(
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glass: Boolean = true,
    size: Dp = ShellDimensions.TITLE_PILL_HEIGHT,
    iconSize: Dp = 24.dp,
    color: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val look = look(glass)
    val iconContent: @Composable () -> Unit = {
        val ink = if (contentColor.isSpecified) contentColor else LocalContentColor.current
        Icon(painterResource(icon), contentDescription = null, tint = if (enabled) ink else ink.copy(alpha = 0.38f), modifier = Modifier.size(iconSize))
    }
    when (look) {
        Look.Plain -> PlainPill(
            modifier.size(size).semantics { if (contentDescription != null) this.contentDescription = contentDescription },
            onClick, enabled, color, contentColor, if (size < 44.dp) 1.dp else 2.dp,
        ) { Box(contentAlignment = Alignment.Center) { iconContent() } }
        else -> {
            val ink = if (contentColor.isSpecified) contentColor else MaterialTheme.colorScheme.onSurface
            CompositionLocalProvider(LocalContentColor provides ink) {
                GlassIconButton(
                    onClick = onClick,
                    backdrop = if (look == Look.Glass) LocalKrewdBackdrop.current else null,
                    contentDescription = contentDescription,
                    modifier = modifier,
                    size = size,
                    enabled = enabled,
                    style = if (look == Look.Glass) LocalGlassStyle.current else flatStyle(color),
                    icon = iconContent,
                )
            }
        }
    }
}

/**
 * An icon button with no shape of its own (the composer's attach button): with the glass switch on, the icon
 * itself swells and stretches toward the finger like the glass pieces; off, a plain [IconButton].
 */
@Composable
fun StretchIconButton(
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Drawn in the accent colour (a mode that is on, such as voice mode). */
    active: Boolean = false,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current
    if (!LocalKrewdGlass.current.on) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(painterResource(icon), contentDescription = contentDescription, tint = tint)
        }
        return
    }
    val pull = rememberGlassPullState()
    Box(
        modifier
            .size(48.dp)
            .glassPullGesture(pull, enabled, onTap = onClick)
            .semantics {
                role = Role.Button
                if (contentDescription != null) this.contentDescription = contentDescription
                if (enabled) this.onClick(label = null) { onClick(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.matchParentSize().glassPullContent(pull), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = tint)
        }
    }
}
