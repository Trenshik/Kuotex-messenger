package com.example.ui.gifts

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.data.AvatarStorageManager
import com.example.data.EntityType
import com.example.ui.Chat
import com.example.ui.UserAccount
import com.google.firebase.auth.FirebaseAuth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Watermark motifs matching Telegram's patterned collectible gift cards.
 */
enum class GiftPatternMotif(val displayName: String, val glyph: String) {
    STAR("Star", "★"),
    CROWN("Crown", "♛"),
    DIAMOND("Diamond", "◆"),
    CACTUS("Cactus", "🌵"),
    BULB("Lightbulb", "💡"),
    HORSE("Arabian Horse", "♞"),
    SCARAB("Scarab", "🪲"),
    WREATH("Wreath", "🌿"),
    SHIELD("Shield", "🛡"),
    FLORAL("Sakura Flower", "✿"),
    CIRCUIT("Circuit", "◈"),
    SPARKLES("Sparkles", "✦");

    companion object {
        fun fromPatternName(patternName: String): GiftPatternMotif {
            val lower = patternName.lowercase()
            return when {
                lower.contains("cactus") -> CACTUS
                lower.contains("crown") || lower.contains("laurel") -> CROWN
                lower.contains("wreath") -> WREATH
                lower.contains("shield") -> SHIELD
                lower.contains("horse") || lower.contains("arabian") -> HORSE
                lower.contains("scarab") || lower.contains("cicada") -> SCARAB
                lower.contains("bulb") || lower.contains("lamp") -> BULB
                lower.contains("sakura") || lower.contains("floral") || lower.contains("flower") -> FLORAL
                lower.contains("circuit") || lower.contains("hex") || lower.contains("matrix") -> CIRCUIT
                lower.contains("diamond") || lower.contains("gem") -> DIAMOND
                lower.contains("sparkle") || lower.contains("sun") -> SPARKLES
                else -> STAR
            }
        }
    }
}

/**
 * Renders a jewel-toned radial/vertical gradient backdrop with a repeating staggered
 * Canvas watermark pattern for collectible skins and gift preview cards.
 */
@Composable
fun PatternedGiftBackdrop(
    backdropColor: Color,
    accentColor: Color,
    patternName: String,
    modifier: Modifier = Modifier,
    patternAlpha: Float = 0.18f
) {
    val motif = GiftPatternMotif.fromPatternName(patternName)
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.42f),
                    backdropColor,
                    backdropColor.copy(alpha = 0.96f)
                )
            )
        )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellSize = 26.dp.toPx()
            val cols = (size.width / cellSize).toInt() + 2
            val rows = (size.height / cellSize).toInt() + 2
            val watermarkColor = Color.Black.copy(alpha = patternAlpha.coerceIn(0.08f, 0.35f))

            for (r in 0..rows) {
                val xOffset = if (r % 2 == 1) cellSize * 0.5f else 0f
                for (c in 0..cols) {
                    val cx = c * cellSize + xOffset
                    val cy = r * cellSize
                    val angle = if ((r + c) % 2 == 0) -14f else 14f
                    rotate(degrees = angle, pivot = Offset(cx, cy)) {
                        drawMotifGlyph(
                            motif = motif,
                            center = Offset(cx, cy),
                            radius = cellSize * 0.24f,
                            color = watermarkColor
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawMotifGlyph(
    motif: GiftPatternMotif,
    center: Offset,
    radius: Float,
    color: Color
) {
    when (motif) {
        GiftPatternMotif.STAR, GiftPatternMotif.SPARKLES -> {
            val points = if (motif == GiftPatternMotif.STAR) 5 else 4
            val path = Path()
            val innerR = radius * 0.45f
            for (i in 0 until points * 2) {
                val r = if (i % 2 == 0) radius else innerR
                val angleRad = (i * PI / points) - PI / 2.0
                val x = center.x + (r * cos(angleRad)).toFloat()
                val y = center.y + (r * sin(angleRad)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color = color)
        }
        GiftPatternMotif.DIAMOND -> {
            val path = Path().apply {
                moveTo(center.x, center.y - radius)
                lineTo(center.x + radius * 0.85f, center.y)
                lineTo(center.x, center.y + radius)
                lineTo(center.x - radius * 0.85f, center.y)
                close()
            }
            drawPath(path, color = color)
        }
        GiftPatternMotif.CROWN -> {
            val path = Path().apply {
                moveTo(center.x - radius, center.y + radius * 0.5f)
                lineTo(center.x - radius, center.y - radius * 0.4f)
                lineTo(center.x - radius * 0.4f, center.y + radius * 0.05f)
                lineTo(center.x, center.y - radius * 0.65f)
                lineTo(center.x + radius * 0.4f, center.y + radius * 0.05f)
                lineTo(center.x + radius, center.y - radius * 0.4f)
                lineTo(center.x + radius, center.y + radius * 0.5f)
                close()
            }
            drawPath(path, color = color)
        }
        GiftPatternMotif.SHIELD -> {
            val path = Path().apply {
                moveTo(center.x - radius * 0.75f, center.y - radius * 0.7f)
                lineTo(center.x + radius * 0.75f, center.y - radius * 0.7f)
                lineTo(center.x + radius * 0.7f, center.y + radius * 0.1f)
                lineTo(center.x, center.y + radius * 0.9f)
                lineTo(center.x - radius * 0.7f, center.y + radius * 0.1f)
                close()
            }
            drawPath(path, color = color)
        }
        GiftPatternMotif.CACTUS -> {
            drawLine(
                color = color,
                start = Offset(center.x, center.y - radius),
                end = Offset(center.x, center.y + radius),
                strokeWidth = radius * 0.42f
            )
            drawLine(
                color = color,
                start = Offset(center.x - radius * 0.65f, center.y - radius * 0.15f),
                end = Offset(center.x - radius * 0.65f, center.y + radius * 0.35f),
                strokeWidth = radius * 0.3f
            )
            drawLine(
                color = color,
                start = Offset(center.x - radius * 0.65f, center.y + radius * 0.3f),
                end = Offset(center.x, center.y + radius * 0.3f),
                strokeWidth = radius * 0.25f
            )
            drawLine(
                color = color,
                start = Offset(center.x + radius * 0.65f, center.y - radius * 0.4f),
                end = Offset(center.x + radius * 0.65f, center.y + radius * 0.1f),
                strokeWidth = radius * 0.3f
            )
            drawLine(
                color = color,
                start = Offset(center.x, center.y + radius * 0.05f),
                end = Offset(center.x + radius * 0.65f, center.y + radius * 0.05f),
                strokeWidth = radius * 0.25f
            )
        }
        GiftPatternMotif.CIRCUIT -> {
            drawCircle(color = color, radius = radius * 0.45f, center = center)
            drawLine(
                color = color,
                start = Offset(center.x - radius, center.y),
                end = Offset(center.x + radius, center.y),
                strokeWidth = radius * 0.22f
            )
            drawLine(
                color = color,
                start = Offset(center.x, center.y - radius),
                end = Offset(center.x, center.y + radius),
                strokeWidth = radius * 0.22f
            )
        }
        else -> {
            // Floral / Scarab / Wreath / Bulb / Horse clean organic emblem
            for (i in 0 until 4) {
                val angle = (i * PI / 2.0)
                val ox = center.x + (radius * 0.45f * cos(angle)).toFloat()
                val oy = center.y + (radius * 0.45f * sin(angle)).toFloat()
                drawCircle(color = color, radius = radius * 0.38f, center = Offset(ox, oy))
            }
            drawCircle(color = color, radius = radius * 0.32f, center = center)
        }
    }
}

/**
 * Resolves a bundled Lottie JSON asset filename inside assets/lottie_gifts/ for any gift or emoji.
 */
fun resolveGiftLottieAsset(rawAssetUrl: String?, emoji: String = "", title: String = ""): String {
    val clean = rawAssetUrl?.trim().orEmpty()
    if (clean.endsWith(".json") && clean.startsWith("gift_")) {
        return clean
    }
    val lowerTitle = title.lowercase()
    return when {
        clean.contains("lamp") || emoji == "🪔" || emoji == "🏮" || lowerTitle.contains("lamp") -> "gift_lamp_lottie.json"
        clean.contains("heart") || emoji == "💝" || emoji == "❤️" || emoji == "💖" -> "gift_heart_lottie.json"
        clean.contains("diamond") || emoji == "💎" || emoji == "💍" -> "gift_diamond_lottie.json"
        clean.contains("trophy") || clean.contains("crown") || emoji == "🏆" || emoji == "👑" -> "gift_trophy_lottie.json"
        clean.contains("rocket") || emoji == "🚀" || emoji == "🔥" -> "gift_rocket_lottie.json"
        clean.contains("star") || emoji == "⭐" || lowerTitle.contains("premium") -> "gift_star_premium.json"
        clean.contains("sparkle") || emoji == "🔮" || emoji == "✨" -> "gift_sparkle_aura.json"
        else -> "gift_box_lottie.json"
    }
}

/**
 * Renders animated bundled Lottie art layered harmoniously with the crisp expressive gift emblem.
 */
@Composable
fun GiftLottieArt(
    lottieAssetName: String?,
    fallbackEmoji: String,
    modifier: Modifier = Modifier,
    emojiSize: TextUnit = 42.sp,
    accentColor: Color = Color(0xFFFFD54F),
    animateFloatPulse: Boolean = true,
    showForegroundEmoji: Boolean = true
) {
    val resolvedFile = resolveGiftLottieAsset(lottieAssetName, fallbackEmoji)
    val composition by rememberLottieComposition(
        LottieCompositionSpec.Asset("lottie_gifts/$resolvedFile")
    )

    val infiniteTransition = rememberInfiniteTransition(label = "gift_lottie_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gift_float"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Soft ambient radial glow
        Box(
            modifier = Modifier
                .fillMaxSize(0.88f)
                .background(
                    Brush.radialGradient(
                        listOf(
                            accentColor.copy(alpha = 0.34f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                )
        )

        if (composition != null) {
            LottieAnimation(
                composition = composition,
                iterations = LottieConstants.IterateForever,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showForegroundEmoji && fallbackEmoji.isNotBlank()) {
            Text(
                text = fallbackEmoji,
                fontSize = emojiSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    val s = if (animateFloatPulse) pulse else 1f
                    scaleX = s
                    scaleY = s
                }
            )
        }
    }
}

/**
 * Telegram-style angled top-right corner ribbon ("маркет", "#22,770", "-33%").
 * Parent container MUST clip (e.g. `clip(RoundedCornerShape(16.dp))`).
 */
@Composable
fun DiagonalCornerRibbon(
    text: String,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
    fontSize: TextUnit = 9.sp
) {
    Box(
        modifier = modifier
            .width(86.dp)
            .offset(x = 22.dp, y = 10.dp)
            .graphicsLayer { rotationZ = 42f }
            .background(backgroundColor)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

/**
 * Telegram-style dark translucent price pill with star icon and subtle sparkle accent.
 */
@Composable
fun SparkleStarPricePill(
    priceText: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.42f),
    starColor: Color = Color(0xFFFFD54F),
    textColor: Color = Color.White
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(text = "★", fontSize = 11.sp, color = starColor, fontWeight = FontWeight.Bold)
                Text(
                    text = priceText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "✦",
                fontSize = 6.sp,
                color = starColor.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-2).dp)
            )
            Text(
                text = "✦",
                fontSize = 5.sp,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-3).dp, y = 1.dp)
            )
        }
    }
}

/**
 * Circular sender avatar badge or anonymous incognito/ghost badge.
 */
@Composable
fun SenderAvatarBadge(
    senderName: String,
    avatarUrl: String?,
    isAnonymous: Boolean,
    size: Dp = 22.dp,
    borderColor: Color = Color(0xFF0F1014),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (isAnonymous) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFF2B8BE6))
                .border(1.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🕵",
                fontSize = (size.value * 0.56f).sp,
                textAlign = TextAlign.Center
            )
        }
    } else if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .allowHardware(false)
                .data(avatarUrl)
                .crossfade(true)
                .build(),
            contentDescription = senderName,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFF4C1D95))
                .border(1.dp, borderColor, CircleShape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF7C3AED), Color(0xFFEC4899))
                    )
                )
                .border(1.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = senderName.trim().take(1).uppercase().ifBlank { "П" },
                color = Color.White,
                fontSize = (size.value * 0.48f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Resolves human-readable sender nickname & avatar for gifts, masking raw Firebase UIDs
 * cleanly and honoring anonymous flags.
 */
object SenderIdentityResolver {

    fun looksLikeRawUid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val trimmed = value.trim()
        if (trimmed.contains(" ") || trimmed.startsWith("@")) return false
        if (trimmed.length < 20) return false
        val isAlphaNum = trimmed.all { it.isLetterOrDigit() || it == '_' || it == '-' }
        val hasUpper = trimmed.any { it.isUpperCase() }
        val hasLower = trimmed.any { it.isLowerCase() }
        val hasDigit = trimmed.any { it.isDigit() }
        return isAlphaNum && ((hasUpper && hasLower) || (hasDigit && (hasUpper || hasLower)))
    }

    fun resolveSenderName(
        rawSenderId: String,
        storedSenderName: String,
        isAnonymous: Boolean,
        chats: List<Chat> = emptyList(),
        activeAccount: UserAccount? = null
    ): String {
        if (isAnonymous || rawSenderId.equals("anonymous", ignoreCase = true) || storedSenderName == "Аноним") {
            return "Аноним"
        }
        val storedClean = storedSenderName.trim()
        if (storedClean.isNotBlank() && !looksLikeRawUid(storedClean)) {
            return storedClean
        }
        val idClean = rawSenderId.trim()
        val authUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        val authDisplayName = runCatching { FirebaseAuth.getInstance().currentUser?.displayName }.getOrNull()

        if (activeAccount != null && (idClean == activeAccount.id || idClean == authUid || idClean == "me")) {
            val accName = activeAccount.displayName.takeIf { it.isNotBlank() && !looksLikeRawUid(it) }
                ?: activeAccount.username.takeIf { it.isNotBlank() && !looksLikeRawUid(it) }
            if (!accName.isNullOrBlank()) return accName
        }

        if (idClean == authUid && !authDisplayName.isNullOrBlank() && !looksLikeRawUid(authDisplayName)) {
            return authDisplayName
        }

        val matchingChat = chats.find {
            it.id == idClean || (!it.isChannel && !it.isGroup && it.id.contains(idClean))
        }
        if (matchingChat != null && matchingChat.title.isNotBlank() && !looksLikeRawUid(matchingChat.title)) {
            return matchingChat.title
        }

        if (idClean.isNotBlank() && !looksLikeRawUid(idClean) && idClean != "me") {
            return idClean
        }
        return "Пользователь"
    }

    fun resolveSenderAvatar(
        rawSenderId: String,
        storedAvatarUrl: String,
        isAnonymous: Boolean,
        context: Context? = null,
        chats: List<Chat> = emptyList(),
        activeAccount: UserAccount? = null
    ): String? {
        if (isAnonymous || rawSenderId.equals("anonymous", ignoreCase = true)) {
            return null
        }
        if (storedAvatarUrl.isNotBlank()) return storedAvatarUrl

        val idClean = rawSenderId.trim()
        val authUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

        if (activeAccount != null && (idClean == activeAccount.id || idClean == authUid || idClean == "me")) {
            if (activeAccount.profilePicUrl.isNotBlank()) return activeAccount.profilePicUrl
            if (context != null) {
                val localAvatar = AvatarStorageManager.getUserAvatarList(context, activeAccount.id, activeAccount.profilePicUrl).firstOrNull()
                if (!localAvatar.isNullOrBlank() && !localAvatar.contains("unsplash.com")) return localAvatar
            }
        }

        if (context != null && idClean.isNotBlank() && idClean != "me") {
            if (AvatarStorageManager.hasCustomAvatar(context, EntityType.ACCOUNT, idClean)) {
                return AvatarStorageManager.getAvatar(context, EntityType.ACCOUNT, idClean)
            }
            val matchingChat = chats.find { it.id == idClean }
            if (matchingChat != null && AvatarStorageManager.hasCustomAvatar(context, EntityType.ACCOUNT, matchingChat.id)) {
                return AvatarStorageManager.getAvatar(context, EntityType.ACCOUNT, matchingChat.id)
            }
        }
        return null
    }
}
