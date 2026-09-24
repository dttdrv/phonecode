package dev.phonecode.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import dev.phonecode.app.R

/** Brand marks for known services and providers; bitmap marks already carry their own tile. */
private val glyphs = mapOf(
    "github" to R.drawable.brand_github,
    "stripe" to R.drawable.brand_stripe,
    "cloudflare-docs" to R.drawable.brand_cloudflare,
    "cloudflare-agents-docs" to R.drawable.brand_cloudflare,
    "openai" to R.drawable.brand_openai,
    "codex" to R.drawable.brand_openai,
    "anthropic" to R.drawable.brand_anthropic,
    "openrouter" to R.drawable.brand_openrouter,
    "google" to R.drawable.brand_googlegemini,
    "mistral" to R.drawable.brand_mistralai,
    "xai" to R.drawable.brand_x,
)
private val bitmaps = mapOf(
    "deepwiki" to R.drawable.brand_deepwiki,
    "microsoft-learn" to R.drawable.brand_microsoft,
    "aws-knowledge" to R.drawable.brand_aws,
    "deepseek" to R.drawable.brand_deepseek,
    "opencode-zen" to R.drawable.brand_opencode,
    "opencode-go" to R.drawable.brand_opencode,
)

/** Brands whose mark is monochrome: drawn in the ink color so they survive dark mode. */
private val inkGlyphs = setOf("github", "openai", "codex", "anthropic", "xai", "openrouter")

internal fun hasAppIcon(key: String) = key in glyphs || key in bitmaps

/**
 * A service's app icon on a rounded tile. Unknown services get a neutral [fallback] glyph -
 * never initials.
 */
@Composable
fun AppIcon(key: String, size: Dp, modifier: Modifier = Modifier, fallback: ImageVector = Icons.Outlined.Extension) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val shape = RoundedCornerShape(size * 0.26f)
    val tile = modifier.size(size).clip(shape).clearAndSetSemantics {}
        .then(if (dark) Modifier else Modifier.border(1.dp, colors.outlineVariant.copy(alpha = 0.8f), shape))
    val bitmap = bitmaps[key]
    if (bitmap != null) {
        Image(painterResource(bitmap), null, contentScale = ContentScale.Crop, modifier = tile)
        return
    }
    Box(
        tile.background(if (dark) colors.surfaceContainerHighest else Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val glyph: Int? = glyphs[key]
        if (glyph != null) {
            Icon(
                painterResource(glyph),
                null,
                tint = if (key in inkGlyphs) colors.onSurface else Color.Unspecified,
                modifier = Modifier.size(size * 0.56f),
            )
        } else {
            Icon(fallback, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(size * 0.5f))
        }
    }
}

