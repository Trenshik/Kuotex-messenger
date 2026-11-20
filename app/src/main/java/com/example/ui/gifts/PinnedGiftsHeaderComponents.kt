package com.example.ui.gifts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import com.example.ui.Chat
import com.example.ui.UserAccount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PinnedGiftsHeader - Profile header Composable that uses a LazyRow to display PinnedGift objects.
 * Encapsulated inside a solid Card container with 20.dp rounded shape and border to match other profile modules.
 */
@Composable
fun PinnedGiftsHeader(
    gifts: List<PinnedGift>,
    modifier: Modifier = Modifier,
    onGiftClick: (PinnedGift) -> Unit = {},
    onAddGiftClick: () -> Unit = {}
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1D23)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            // Header title & count (Removed "Каталог" button to prevent clutter and text wrapping)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Icon(
                    imageVector = Icons.Filled.Stars,
                    contentDescription = null,
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Закрепленные подарки",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFFFFD54F).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${gifts.size}/6",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD54F)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (gifts.isEmpty()) {
                // Friendly Empty State when user has no pinned gifts
                PinnedGiftsEmptyState(onAddGiftClick = onAddGiftClick)
            } else {
                // LazyRow displaying PinnedGift objects
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(gifts, key = { it.id }) { gift ->
                        PinnedGiftCard(
                            gift = gift,
                            onClick = { onGiftClick(gift) },
                            modifier = Modifier.animateItem()
                        )
                    }

                    // "Подарки" Slot to browse and purchase gifts in the marketplace
                    item {
                        AddPinnedGiftSlot(
                            onClick = onAddGiftClick,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

/**
 * PinnedGiftsEmptyState - Friendly Empty State displayed when the user has no pinned gifts.
 * Contains a glowing icon badge, welcoming text, and "Закрепить подарки" action button.
 */
@Composable
fun PinnedGiftsEmptyState(
    onAddGiftClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Glowing Badge with Gift Emoji
        Box(
            modifier = Modifier
                .size(74.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF8B5CF6).copy(alpha = 0.35f),
                            Color(0xFFC084FC).copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(Color(0xFF232035), CircleShape)
                    .border(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎁",
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Пока нет закрепленных подарков",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Закрепляйте редкие и памятные подарки на своей странице, чтобы они всегда были на виду у друзей и гостей профиля",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // "Закрепить подарки" Button
        Button(
            onClick = onAddGiftClick,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(46.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF8B5CF6),
                contentColor = Color.White
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Закрепить подарки",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * PinnedGiftsHeaderRow - Convenience wrapper delegating to PinnedGiftsHeader.
 */
@Composable
fun PinnedGiftsHeaderRow(
    gifts: List<PinnedGift>,
    onGiftClick: (PinnedGift) -> Unit,
    onAddGiftClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    PinnedGiftsHeader(
        gifts = gifts,
        modifier = modifier,
        onGiftClick = onGiftClick,
        onAddGiftClick = onAddGiftClick
    )
}

/**
 * PinnedGiftCard - Rounded container that dynamically clips custom gradient textures,
 * animated pulse glowing border, upgrade stars, and 3D floating emoji/lottie asset.
 */
@Composable
fun PinnedGiftCard(
    gift: PinnedGift,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gift_shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    val bounceScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    // Subtle spring entrance animation when the gift item is added to the list
    val entranceScale = remember { Animatable(0.78f) }
    val entranceAlpha = remember { Animatable(0f) }
    val entranceOffsetY = remember { Animatable(24f) }

    androidx.compose.runtime.LaunchedEffect(gift.id) {
        kotlinx.coroutines.coroutineScope {
            launch {
                entranceScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                entranceAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
            launch {
                entranceOffsetY.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press_scale"
    )

    val baseBackdrop = gift.parsedBackdropColor
    val accentGlow = gift.parsedAccentColor

    Card(
        modifier = modifier
            .width(140.dp)
            .height(182.dp)
            .graphicsLayer {
                val combinedScale = entranceScale.value * pressScale
                scaleX = combinedScale
                scaleY = combinedScale
                alpha = entranceAlpha.value
                translationY = entranceOffsetY.value
            }
            .shadow(
                elevation = if (gift.upgradeLevel >= 3) 8.dp else 4.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = accentGlow
            )
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = baseBackdrop),
        border = BorderStroke(
            width = if (gift.upgradeLevel >= 3) 2.dp else 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    accentGlow.copy(alpha = shimmerAlpha),
                    accentGlow.copy(alpha = 0.3f),
                    Color(0xFF0F172A)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (gift.isCollectible && gift.patternName.isNotBlank()) {
                PatternedGiftBackdrop(
                    backdropColor = baseBackdrop,
                    accentColor = accentGlow,
                    patternName = gift.patternName,
                    modifier = Modifier.fillMaxSize(),
                    patternAlpha = 0.22f
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    accentGlow.copy(alpha = 0.25f),
                                    baseBackdrop,
                                    Color(0xFF0F172A)
                                )
                            )
                        )
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Rarity Pill / Serial + Crown or Avatar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(accentGlow.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (gift.isCollectible && gift.serialNumber > 0) gift.formattedSerial else gift.rarityTier.title,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    if (gift.isExclusive) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(Color(0xFFFFD54F), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "👑", fontSize = 9.sp)
                        }
                    }
                }

                // Center Animated Lottie Gift Art
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .graphicsLayer {
                            scaleX = if (gift.upgradeLevel >= 4) bounceScale else 1f
                            scaleY = if (gift.upgradeLevel >= 4) bounceScale else 1f
                        },
                    contentAlignment = Alignment.Center
                ) {
                    GiftLottieArt(
                        lottieAssetName = gift.animationAssetUrl,
                        fallbackEmoji = gift.emojiIcon,
                        emojiSize = 36.sp,
                        accentColor = accentGlow,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Bottom Titles and Level
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = gift.title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    // Upgrade Stars
                    Text(
                        text = gift.upgradeStars,
                        fontSize = 11.sp,
                        color = Color(0xFFFFD700),
                        letterSpacing = 1.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    // Level Indicator Pill
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1E293B), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LVL ${gift.upgradeLevel}/${gift.maxUpgradeLevel}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentGlow
                        )
                    }
                }
            }
        }
    }
}

/**
 * AddPinnedGiftSlot - Slot in carousel to open the gifts marketplace ("Подарки").
 */
@Composable
fun AddPinnedGiftSlot(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "add_slot_press_scale"
    )

    Card(
        modifier = modifier
            .width(122.dp)
            .height(182.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF22252E)),
        border = BorderStroke(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF8B5CF6), Color(0xFFC084FC))
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Подарки",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Подарки",
                fontSize = 13.sp,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Маркетплейс ➔",
                fontSize = 10.sp,
                color = Color(0xFFC084FC),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * PinnedGiftDetailBottomSheet - Telegram-style "Подарок" modal dialog with Lottie animation,
 * resolved sender avatar & nickname, quick "отправить подарок" pill, info table,
 * pin/upgrade/exchange owner controls, and "OK" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinnedGiftDetailBottomSheet(
    gift: PinnedGift?,
    onDismiss: () -> Unit,
    onUpgradeClick: (PinnedGift) -> Unit,
    onUnpinClick: ((PinnedGift) -> Unit)? = null,
    onExchangeForStarsClick: ((PinnedGift) -> Unit)? = null,
    onSendGiftBack: ((PinnedGift) -> Unit)? = null,
    chats: List<Chat> = emptyList(),
    activeAccount: UserAccount? = null
) {
    if (gift == null) return

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val formattedDate = remember(gift.acquiredAt) {
        val sdf = SimpleDateFormat("dd MMM yyyy 'в' HH:mm", Locale("ru"))
        sdf.format(Date(gift.acquiredAt))
    }

    val resolvedSenderName = remember(gift.senderId, gift.senderName, gift.isAnonymous, chats, activeAccount) {
        SenderIdentityResolver.resolveSenderName(
            rawSenderId = gift.senderId,
            storedSenderName = gift.senderName,
            isAnonymous = gift.isAnonymous,
            chats = chats,
            activeAccount = activeAccount
        )
    }
    val resolvedSenderAvatar = remember(gift.senderId, gift.senderAvatarUrl, gift.isAnonymous, chats, activeAccount) {
        SenderIdentityResolver.resolveSenderAvatar(
            rawSenderId = gift.senderId,
            storedAvatarUrl = gift.senderAvatarUrl,
            isAnonymous = gift.isAnonymous,
            context = context,
            chats = chats,
            activeAccount = activeAccount
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1922),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Big Animated Gift Visual (with Patterned Backdrop if collectible)
            Box(
                modifier = Modifier
                    .size(124.dp)
                    .clip(if (gift.isCollectible) RoundedCornerShape(28.dp) else CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (gift.isCollectible && gift.patternName.isNotBlank()) {
                    PatternedGiftBackdrop(
                        backdropColor = gift.parsedBackdropColor,
                        accentColor = gift.parsedAccentColor,
                        patternName = gift.patternName,
                        modifier = Modifier.fillMaxSize(),
                        patternAlpha = 0.24f
                    )
                }
                GiftLottieArt(
                    lottieAssetName = gift.animationAssetUrl,
                    fallbackEmoji = gift.emojiIcon,
                    emojiSize = 72.sp,
                    accentColor = gift.parsedAccentColor,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (gift.isCollectible) "${gift.title} ${gift.formattedSerial}".trim() else "Подарок",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            if (!gift.isCollectible && gift.title.isNotBlank()) {
                Text(
                    text = "${gift.title} • Ур. ${gift.upgradeLevel}/${gift.maxUpgradeLevel}",
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Telegram-style dark bordered Info Table
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF22202B),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Row 1: "От" with Avatar + Nickname + "отправить подарок" pill
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "От",
                            color = Color(0xFF9CA3AF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(88.dp)
                        )
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SenderAvatarBadge(
                                senderName = resolvedSenderName,
                                avatarUrl = resolvedSenderAvatar,
                                isAnonymous = gift.isAnonymous,
                                size = 24.dp,
                                borderColor = Color(0xFF22202B)
                            )
                            Text(
                                text = resolvedSenderName,
                                color = if (gift.isAnonymous) Color(0xFFCBD5E1) else Color(0xFFC084FC),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (!gift.isAnonymous && onSendGiftBack != null && gift.senderId.isNotBlank() && gift.senderId != activeAccount?.id) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF8B5CF6).copy(alpha = 0.22f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            coroutineScope.launch {
                                                try { sheetState.hide() } catch (_: Exception) {}
                                                onDismiss()
                                                onSendGiftBack(gift)
                                            }
                                        }
                                ) {
                                    Text(
                                        text = "отправить подарок",
                                        color = Color(0xFFD8B4FE),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                    // Row 2: "Дата"
                    TelegramTableRow(label = "Дата", value = formattedDate)

                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                    // Row 3: "Стоимость" with Golden Star
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Стоимость",
                            color = Color(0xFF9CA3AF),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(88.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(text = "★", fontSize = 15.sp, color = Color(0xFFFFB703))
                            Text(
                                text = "%,d".format(gift.costStars),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (gift.isCollectible && gift.modelName.isNotBlank()) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        TelegramTableRow(label = "Модель", value = gift.modelName)
                    }
                    if (gift.isCollectible && gift.patternName.isNotBlank()) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        TelegramTableRow(label = "Узор", value = gift.patternName)
                    }

                    if (gift.message.isNotBlank()) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        Text(
                            text = gift.message,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Keep Upgrade, Pin/Unpin, and Exchange for Stars buttons below info table (confirmed behavior)
            if (gift.upgradeLevel < gift.maxUpgradeLevel && !gift.isCollectible) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            try { sheetState.hide() } catch (_: Exception) {}
                            onUpgradeClick(gift)
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8B4FE))
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Повысить уровень подарка (LVL ${gift.upgradeLevel + 1})",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (onUnpinClick != null) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            try { sheetState.hide() } catch (_: Exception) {}
                            onUnpinClick(gift)
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171))
                ) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFFF87171)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Открепить из витрины профиля",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (onExchangeForStarsClick != null) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            try { sheetState.hide() } catch (_: Exception) {}
                            onExchangeForStarsClick(gift)
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFD54F))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Обменять на звёзды",
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFFFFD54F)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Обменять на звёзды",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Primary Telegram "OK" button
            Button(
                onClick = {
                    coroutineScope.launch {
                        try { sheetState.hide() } catch (_: Exception) {}
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Text(
                    text = "OK",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TelegramTableRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF9CA3AF),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/**
 * Pinned collectible gift icon and serial badge displayed directly next to user nickname/display name.
 */
@Composable
fun PinnedCollectibleBadge(
    gift: CollectibleGift,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = gift.parsedBackdropColor.copy(alpha = 0.85f),
        border = androidx.compose.foundation.BorderStroke(1.dp, gift.parsedAccentColor.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = gift.emojiIcon, fontSize = 14.sp)
            Text(
                text = gift.formattedSerialNumber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = gift.parsedAccentColor
            )
        }
    }
}

/**
 * BottomSheet allowing user to inspect, pin or unpin a collectible gift next to their nickname.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectibleGiftDetailBottomSheet(
    gift: CollectibleGift,
    isOwner: Boolean,
    isPinned: Boolean,
    onPinToggle: (CollectibleGift) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF14121E),
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .border(2.dp, gift.parsedAccentColor, RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center
            ) {
                PatternedGiftBackdrop(
                    backdropColor = gift.parsedBackdropColor,
                    accentColor = gift.parsedAccentColor,
                    patternName = gift.patternName,
                    modifier = Modifier.fillMaxSize(),
                    patternAlpha = 0.24f
                )
                GiftLottieArt(
                    lottieAssetName = gift.effectiveLottieAsset,
                    fallbackEmoji = gift.emojiIcon,
                    emojiSize = 54.sp,
                    accentColor = gift.parsedAccentColor,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = gift.baseTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Surface(
                    color = gift.parsedAccentColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = gift.formattedSerialNumber,
                        color = gift.parsedAccentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Surface(
                    color = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = gift.category,
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1F1D2B),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailRow(label = "Модель", value = gift.modelName)
                    DetailRow(label = "Узор", value = gift.patternName)
                    DetailRow(label = "Владелец", value = if (isOwner) "Вы" else "@${gift.ownerName}")
                    DetailRow(label = "Стоимость", value = "${gift.priceStars} ⭐")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isOwner) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                sheetState.hide()
                            } catch (_: Exception) {}
                            onPinToggle(gift)
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPinned) Color(0xFFEF4444) else Color(0xFF8B5CF6)
                    )
                ) {
                    Icon(
                        if (isPinned) Icons.Filled.Close else Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPinned) "Открепить от никнейма" else "Закрепить у никнейма",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

