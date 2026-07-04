package `fun`.kirari.hanako.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ========== Public API ==========

/** Direction for version switching animation. */
internal enum class AnswerSwitchDirection {
    PREVIOUS, NEXT, NONE
}

@Composable
internal fun AnswerActionBar(
    versionCount: Int,
    currentVersionIndex: Int,
    canRegenerate: Boolean,
    regenerating: Boolean,
    onPreviousVersion: () -> Unit,
    onNextVersion: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (versionCount > 1) {
            VersionSwitcher(
                versionCount = versionCount,
                currentIndex = currentVersionIndex,
                onPrevious = onPreviousVersion,
                onNext = onNextVersion,
                enabled = true
            )
        }

        AnswerActionButtons(
            regenerating = regenerating,
            canRegenerate = canRegenerate,
            onCopy = onCopy,
            onRegenerate = onRegenerate
        )
    }
}

@Composable
internal fun AnimatedAnswerVersionContent(
    text: String,
    direction: AnswerSwitchDirection,
    content: @Composable (String) -> Unit
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { answerSwitchAnimationSpec(direction) },
        label = "answer-version-switch"
    ) { currentText ->
        content(currentText)
    }
}

// ========== Version Switcher ==========

@Composable
private fun VersionSwitcher(
    versionCount: Int,
    currentIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        VersionNavButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onPrevious,
            enabled = enabled && currentIndex > 0
        )

        VersionDots(
            versionCount = versionCount,
            currentIndex = currentIndex,
            modifier = Modifier.padding(horizontal = 6.dp)
        )

        VersionNavButton(
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            onClick = onNext,
            enabled = enabled && currentIndex < versionCount - 1
        )
    }
}

@Composable
private fun VersionNavButton(
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(28.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (enabled) onSurfaceVariant else onSurfaceVariant.copy(alpha = 0.38f)
            )
        }
    }
}

@Composable
private fun VersionDots(
    versionCount: Int,
    currentIndex: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(versionCount) { index ->
            VersionDot(selected = index == currentIndex)
        }
    }
}

@Composable
private fun VersionDot(selected: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val width by animateDpAsState(
        targetValue = if (selected) 16.dp else 5.dp,
        animationSpec = tween(durationMillis = 280, easing = EmphasizedEasing),
        label = "version-dot-width"
    )
    val color by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        animationSpec = tween(durationMillis = 280, easing = EmphasizedEasing),
        label = "version-dot-color"
    )

    Box(
        modifier = Modifier
            .size(width = width, height = 5.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

// ========== Action Buttons ==========

@Composable
private fun AnswerActionButtons(
    regenerating: Boolean,
    canRegenerate: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CopyActionButton(
            copied = copied,
            enabled = true,
            onClick = {
                onCopy()
                copied = true
            }
        )

        if (canRegenerate) {
            RegenerateActionButton(
                regenerating = regenerating,
                onClick = onRegenerate
            )
        }
    }
}

@Composable
private fun CopyActionButton(
    copied: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = colorScheme.surfaceContainerHighest,
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            CopyIcon(copied = copied)
            AnimatedVisibility(
                visible = copied,
                enter = expandHorizontally(
                    expandFrom = Alignment.Start,
                    animationSpec = tween(280, easing = EmphasizedDecelerate)
                ) + fadeIn(tween(200, easing = EmphasizedDecelerate)),
                exit = shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    animationSpec = tween(200, easing = EmphasizedAccelerate)
                ) + fadeOut(tween(120, easing = EmphasizedAccelerate))
            ) {
                Text(
                    text = "已复制",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun RegenerateActionButton(
    regenerating: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = !regenerating,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            RegenerateIcon(regenerating = regenerating)
        }
    }
}

@Composable
private fun CopyIcon(copied: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val scale = remember { Animatable(1f) }
    LaunchedEffect(copied) {
        if (copied) {
            scale.snapTo(0.6f)
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = {
                fadeIn(tween(180, easing = EmphasizedDecelerate))
                    .togetherWith(fadeOut(tween(120, easing = EmphasizedAccelerate)))
            },
            label = "copy-icon-morph"
        ) { isCopied ->
            Icon(
                imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isCopied) colorScheme.primary else colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RegenerateIcon(regenerating: Boolean) {
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    if (regenerating) {
        val infiniteTransition = rememberInfiniteTransition(label = "regen-spin")
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "regen-angle"
        )
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = angle },
            tint = onSurfaceVariant
        )
    } else {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = onSurfaceVariant
        )
    }
}

// ========== Animation specs (Material 3 motion) ==========

private fun answerSwitchAnimationSpec(direction: AnswerSwitchDirection): ContentTransform {
    val enterFade = fadeIn(tween(300, delayMillis = 40, easing = EmphasizedDecelerate))
    val exitFade = fadeOut(tween(160, easing = EmphasizedAccelerate))

    return when (direction) {
        AnswerSwitchDirection.PREVIOUS ->
            (slideInHorizontally(tween(360, easing = EmphasizedDecelerate)) { it / 5 } + enterFade)
                .togetherWith(slideOutHorizontally(tween(200, easing = EmphasizedAccelerate)) { -it / 5 } + exitFade)

        AnswerSwitchDirection.NEXT ->
            (slideInHorizontally(tween(360, easing = EmphasizedDecelerate)) { -it / 5 } + enterFade)
                .togetherWith(slideOutHorizontally(tween(200, easing = EmphasizedAccelerate)) { it / 5 } + exitFade)

        AnswerSwitchDirection.NONE ->
            fadeIn(tween(220, easing = EmphasizedDecelerate))
                .togetherWith(fadeOut(tween(140, easing = EmphasizedAccelerate)))
    }
}

/** Material 3 emphasized easing tokens. */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
