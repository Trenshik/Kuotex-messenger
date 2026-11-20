package com.example.ui.gifts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.AvatarStorageManager
import com.example.data.ecosystem.KuoteXCatalogGiftDoc
import com.example.data.ecosystem.KuoteXEcosystemFirestoreManager
import com.example.ui.AppViewModel
import com.example.ui.LocalActiveAccount
import com.example.ui.navigation.AppDestinations
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

private data class PremiumPromoTier(
    val months: Int,
    val title: String,
    val priceStars: Long,
    val discountLabel: String?,
    val gradientColors: List<Color>,
    val ribbonColor: Color
)

private val premiumTiers = listOf(
    PremiumPromoTier(
        months = 3,
        title = "3 месяца",
        priceStars = 1000L,
        discountLabel = null,
        gradientColors = listOf(Color(0xFF1D243B), Color(0xFF141828)),
        ribbonColor = Color(0xFF6366F1)
    ),
    PremiumPromoTier(
        months = 6,
        title = "6 месяцев",
        priceStars = 1500L,
        discountLabel = "-33%",
        gradientColors = listOf(Color(0xFF331B4B), Color(0xFF1D132B)),
        ribbonColor = Color(0xFFA855F7)
    ),
    PremiumPromoTier(
        months = 12,
        title = "12 месяцев",
        priceStars = 2500L,
        discountLabel = "-39%",
        gradientColors = listOf(Color(0xFF471C3A), Color(0xFF221226)),
        ribbonColor = Color(0xFFEC4899)
    )
)

/**
 * GiftMarketplaceScreen
 *
 * Telegram-styled Gift Marketplace with animated hero header, "Подарить Premium"
 * 3/6/12 month tiers, "Отправить подарок" grid with "Все" / "Коллекционные" tabs,
 * Lottie-animated catalog gifts, green-ribbon "маркет" collection cards linking to
 * separate CollectibleCollectionScreen windows, and full sender identity resolution.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GiftMarketplaceScreen(
    viewModel: AppViewModel,
    navController: NavController,
    ecosystemManager: KuoteXEcosystemFirestoreManager = KuoteXEcosystemFirestoreManager,
    targetUserId: String? = null,
    targetUserName: String? = null
) {
    val context = LocalContext.current
    val catalogGifts by ecosystemManager.catalogGifts.collectAsState()
    val collectibleCollections by ecosystemManager.collectibleCollections.collectAsState()
    val collectibleGifts by ecosystemManager.collectibleMarketplaceGifts.collectAsState()
    val currentUser by ecosystemManager.currentUserState.collectAsState()
    val activeAccount = LocalActiveAccount.current
    val chats by viewModel.chats.collectAsState()

    val resolvedActiveUserId = FirebaseAuth.getInstance().currentUser?.uid
        ?.takeIf { it.isNotBlank() }
        ?: activeAccount?.id?.takeIf { it.isNotBlank() && it != "me" }
        ?: currentUser?.userId?.takeIf { it.isNotBlank() && it != "me" }
        ?: "me"
    val activeCurrency by ecosystemManager.selectedCurrency.collectAsState()

    val decodedTargetName = remember(targetUserName) {
        targetUserName?.takeIf { it.isNotBlank() }?.let {
            runCatching { android.net.Uri.decode(it) }.getOrDefault(it)
        }
    }
    val effectiveRecipientId = targetUserId?.takeIf { it.isNotBlank() && it != "me" } ?: resolvedActiveUserId
    val recipientDisplayName = decodedTargetName
        ?: chats.find { it.id == effectiveRecipientId }?.title
        ?: activeAccount?.displayName?.takeIf { effectiveRecipientId == resolvedActiveUserId }
        ?: "Себе в профиль"

    val recipientAvatarUrl = remember(effectiveRecipientId, activeAccount, chats) {
        SenderIdentityResolver.resolveSenderAvatar(
            rawSenderId = effectiveRecipientId,
            storedAvatarUrl = if (effectiveRecipientId == resolvedActiveUserId) activeAccount?.profilePicUrl.orEmpty() else "",
            isAnonymous = false,
            context = context,
            chats = chats,
            activeAccount = activeAccount
        ) ?: AvatarStorageManager.getUserAvatarList(
            context = context,
            userId = effectiveRecipientId,
            currentAvatar = if (effectiveRecipientId == resolvedActiveUserId) activeAccount?.profilePicUrl else null
        ).firstOrNull()
    }

    val senderDisplayName = remember(activeAccount, currentUser, resolvedActiveUserId) {
        SenderIdentityResolver.resolveSenderName(
            rawSenderId = resolvedActiveUserId,
            storedSenderName = activeAccount?.displayName ?: currentUser?.displayName.orEmpty(),
            isAnonymous = false,
            chats = chats,
            activeAccount = activeAccount
        )
    }
    val senderAvatarUrl = remember(activeAccount, resolvedActiveUserId) {
        SenderIdentityResolver.resolveSenderAvatar(
            rawSenderId = resolvedActiveUserId,
            storedAvatarUrl = activeAccount?.profilePicUrl.orEmpty(),
            isAnonymous = false,
            context = context,
            chats = chats,
            activeAccount = activeAccount
        ).orEmpty()
    }

    var selectedFilterTab by remember { mutableIntStateOf(0) } // 0: Все, 1: Коллекционные
    var showCurrencyMenu by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selectedCatalogGiftForBuy by remember { mutableStateOf<KuoteXCatalogGiftDoc?>(null) }
    var selectedPremiumTierForBuy by remember { mutableStateOf<PremiumPromoTier?>(null) }

    val userBalance = currentUser?.balance ?: 1000L

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF0A0A0F)
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 0.dp,
                bottom = 28.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            // 1. Hero Header with Back, Currency/Balance Pill, Recipient Avatar & Sparkles
            item(span = { GridItemSpan(3) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 6.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(40.dp)
                                .background(Color(0xFF191922), CircleShape)
                                .testTag("marketplace_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = Color.White
                            )
                        }

                        // Balance + Currency Switcher Pill
                        Box {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0xFF191922),
                                border = BorderStroke(1.dp, Color(0xFFFFD54F).copy(alpha = 0.28f)),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { showCurrencyMenu = true }
                                    .testTag("marketplace_currency_balance_pill")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Баланс",
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = activeCurrency.formatPrice(userBalance),
                                        color = Color(0xFFFFD54F),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showCurrencyMenu,
                                onDismissRequest = { showCurrencyMenu = false },
                                modifier = Modifier.background(Color(0xFF1D1B28))
                            ) {
                                CurrencyType.entries.forEach { currency ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("${currency.symbol}  ${currency.title}", color = Color.White)
                                                if (currency == activeCurrency) {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        contentDescription = null,
                                                        tint = Color(0xFF8B5CF6)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            ecosystemManager.setCurrency(currency)
                                            showCurrencyMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Recipient Avatar with animated gold sparkles burst
                    Box(
                        modifier = Modifier.size(110.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        GiftLottieArt(
                            lottieAssetName = "gift_sparkle_aura.json",
                            fallbackEmoji = "",
                            accentColor = Color(0xFFFFB703),
                            showForegroundEmoji = false,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (!recipientAvatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .allowHardware(false)
                                    .data(recipientAvatarUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = recipientDisplayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .border(2.dp, Color(0xFF8B5CF6).copy(alpha = 0.65f), CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFEC4899)))
                                    )
                                    .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = recipientDisplayName.trim().take(1).uppercase().ifBlank { "П" },
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text(
                        text = if (!decodedTargetName.isNullOrBlank()) {
                            "Подарок для $decodedTargetName"
                        } else {
                            "Магазин подарков"
                        },
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Text(
                        text = "Выберите подарок или редкий коллекционный скин",
                        color = Color(0xFF9CA3AF),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 3.dp, start = 16.dp, end = 16.dp)
                    )
                }
            }

            // 2. "Подарить Premium" Section Header & 3 Cards
            item(span = { GridItemSpan(3) }) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Подарить Premium",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                    Text(
                        text = "Доступ ко всем эксклюзивным функциям и звёздным бустам",
                        color = Color(0xFF9CA3AF),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
            }

            items(premiumTiers, key = { "premium_${it.months}" }) { tier ->
                PremiumPromoCard(
                    tier = tier,
                    activeCurrency = activeCurrency,
                    onClick = { selectedPremiumTierForBuy = tier }
                )
            }

            // 3. "Отправить подарок" Section Header & "Все" / "Коллекционные" Pills
            item(span = { GridItemSpan(3) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = "Отправить подарок",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Получатель сможет закрепить подарок в профиле или обменять на звёзды",
                        color = Color(0xFF9CA3AF),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Все", "Коллекционные").forEachIndexed { idx, label ->
                            val selected = selectedFilterTab == idx
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (selected) Color(0xFF28223C) else Color(0xFF17171F),
                                border = BorderStroke(
                                    1.dp,
                                    if (selected) Color(0xFF8B5CF6) else Color.White.copy(alpha = 0.08f)
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { selectedFilterTab = idx }
                                    .testTag(if (idx == 0) "tab_gifts_all" else "tab_gifts_collectibles")
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) Color(0xFFE9D5FF) else Color(0xFF9CA3AF),
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 4. Unified 3-Column Gift Grid
            if (selectedFilterTab == 0) {
                // Regular Star Catalog Gifts
                items(catalogGifts, key = { "cat_${it.catalogGiftId}" }) { gift ->
                    CatalogGiftTile(
                        gift = gift,
                        activeCurrency = activeCurrency,
                        onClick = { selectedCatalogGiftForBuy = gift }
                    )
                }

                // Collectible Collection "маркет" Cards
                items(collectibleCollections, key = { "col_${it.collectionId}" }) { col ->
                    CollectibleCollectionTile(
                        collection = col,
                        activeCurrency = activeCurrency,
                        onClick = {
                            navController.navigate(
                                AppDestinations.collectibleCollection(
                                    collectionId = col.collectionId,
                                    userId = targetUserId,
                                    userName = targetUserName
                                )
                            )
                        }
                    )
                }
            } else {
                // All Collectible Collection "маркет" Cards
                items(collectibleCollections, key = { "col_only_${it.collectionId}" }) { col ->
                    CollectibleCollectionTile(
                        collection = col,
                        activeCurrency = activeCurrency,
                        onClick = {
                            navController.navigate(
                                AppDestinations.collectibleCollection(
                                    collectionId = col.collectionId,
                                    userId = targetUserId,
                                    userName = targetUserName
                                )
                            )
                        }
                    )
                }

                // Individual Featured Collectible Skins Preview Section
                if (collectibleGifts.isNotEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        Text(
                            text = "Скины в наличии (нажмите для обзора коллекции)",
                            color = Color(0xFF9CA3AF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }

                    items(collectibleGifts.take(12), key = { "skin_preview_${it.id}" }) { skin ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.84f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    navController.navigate(
                                        AppDestinations.collectibleCollection(
                                            collectionId = skin.effectiveCollectionId,
                                            userId = targetUserId,
                                            userName = targetUserName
                                        )
                                    )
                                }
                        ) {
                            PatternedGiftBackdrop(
                                backdropColor = skin.parsedBackdropColor,
                                accentColor = skin.parsedAccentColor,
                                patternName = skin.patternName,
                                modifier = Modifier.fillMaxSize(),
                                patternAlpha = 0.22f
                            )
                            DiagonalCornerRibbon(
                                text = skin.formattedSerialNumber,
                                backgroundColor = Color.Black.copy(alpha = 0.30f),
                                fontSize = 8.sp,
                                modifier = Modifier.align(Alignment.TopEnd)
                            )
                            GiftLottieArt(
                                lottieAssetName = skin.effectiveLottieAsset,
                                fallbackEmoji = skin.emojiIcon,
                                emojiSize = 38.sp,
                                accentColor = skin.parsedAccentColor,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(bottom = 12.dp)
                                    .size(64.dp)
                            )
                            SparkleStarPricePill(
                                priceText = if (activeCurrency == CurrencyType.STARS) {
                                    "%,d".format(skin.priceStars)
                                } else {
                                    activeCurrency.formatPrice(skin.priceStars)
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 7.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet for Purchasing / Sending Regular Catalog Gift (Telegram design)
    selectedCatalogGiftForBuy?.let { gift ->
        PurchaseGiftBottomSheet(
            gift = gift,
            activeCurrency = activeCurrency,
            userBalance = userBalance,
            targetUserName = recipientDisplayName,
            senderDisplayName = senderDisplayName,
            senderAvatarUrl = senderAvatarUrl,
            onDismiss = { selectedCatalogGiftForBuy = null },
            onConfirmPurchase = { message, isAnonymous, pinToHeader ->
                selectedCatalogGiftForBuy = null
                scope.launch {
                    val activeUser = resolvedActiveUserId
                    val receiver = targetUserId?.takeIf { it.isNotBlank() && it != "me" } ?: activeUser
                    val idempotencyKey = "buy_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                    val result = ecosystemManager.processGiftPurchaseAtomic(
                        senderUserId = activeUser,
                        targetUserId = receiver,
                        catalogGiftId = gift.catalogGiftId,
                        idempotencyKey = idempotencyKey,
                        message = message,
                        pinToHeader = pinToHeader,
                        isAnonymous = isAnonymous,
                        senderName = senderDisplayName,
                        senderAvatarUrl = senderAvatarUrl
                    )
                    if (result.isSuccess) {
                        snackbarHostState.showSnackbar("Подарок \"${gift.title}\" успешно отправлен!")
                    } else {
                        snackbarHostState.showSnackbar("Ошибка: ${result.exceptionOrNull()?.message}")
                    }
                }
            }
        )
    }

    // Modal Sheet for Gifting Real KuoteX Premium Subscription (3 / 6 / 12 months)
    selectedPremiumTierForBuy?.let { tier ->
        GiftPremiumBottomSheet(
            tier = tier,
            activeCurrency = activeCurrency,
            userBalance = userBalance,
            recipientDisplayName = recipientDisplayName,
            senderDisplayName = senderDisplayName,
            senderAvatarUrl = senderAvatarUrl,
            onDismiss = { selectedPremiumTierForBuy = null },
            onConfirmPurchase = {
                selectedPremiumTierForBuy = null
                scope.launch {
                    val receiver = targetUserId?.takeIf { it.isNotBlank() && it != "me" } ?: resolvedActiveUserId
                    val idemKey = "vipp_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                    val res = ecosystemManager.giftPremiumSubscriptionAtomic(
                        senderUserId = resolvedActiveUserId,
                        targetUserId = receiver,
                        months = tier.months,
                        priceStars = tier.priceStars,
                        senderName = senderDisplayName,
                        senderAvatarUrl = senderAvatarUrl,
                        idempotencyKey = idemKey
                    )
                    if (res.isSuccess) {
                        snackbarHostState.showSnackbar("KuoteX Premium (${tier.title}) успешно подарен!")
                    } else {
                        snackbarHostState.showSnackbar("Ошибка: ${res.exceptionOrNull()?.message}")
                    }
                }
            }
        )
    }
}

@Composable
private fun PremiumPromoCard(
    tier: PremiumPromoTier,
    activeCurrency: CurrencyType,
    onClick: () -> Unit
) {
    val priceDisplay = if (activeCurrency == CurrencyType.STARS) {
        "%,d".format(tier.priceStars)
    } else {
        activeCurrency.formatPrice(tier.priceStars)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.84f)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(tier.gradientColors))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag("premium_tier_${tier.months}m")
    ) {
        if (!tier.discountLabel.isNullOrBlank()) {
            DiagonalCornerRibbon(
                text = tier.discountLabel,
                backgroundColor = tier.ribbonColor,
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            GiftLottieArt(
                lottieAssetName = "gift_star_premium.json",
                fallbackEmoji = "⭐",
                emojiSize = 36.sp,
                accentColor = tier.ribbonColor,
                modifier = Modifier.size(56.dp)
            )

            Text(
                text = tier.title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            SparkleStarPricePill(
                priceText = priceDisplay,
                containerColor = Color.Black.copy(alpha = 0.36f)
            )
        }
    }
}

@Composable
private fun CatalogGiftTile(
    gift: KuoteXCatalogGiftDoc,
    activeCurrency: CurrencyType,
    onClick: () -> Unit
) {
    val entranceScale = remember { Animatable(0.88f) }
    val entranceAlpha = remember { Animatable(0f) }

    LaunchedEffect(gift.catalogGiftId) {
        coroutineScope {
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
        }
    }

    val priceDisplay = if (activeCurrency == CurrencyType.STARS) {
        "%,d".format(gift.price)
    } else {
        activeCurrency.formatPrice(gift.price)
    }

    val accent = runCatching { Color(android.graphics.Color.parseColor(gift.backdropColorHex)) }
        .getOrDefault(Color(0xFF1E1B4B))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.86f)
            .graphicsLayer {
                scaleX = entranceScale.value
                scaleY = entranceScale.value
                alpha = entranceAlpha.value
            }
            .clickable { onClick() }
            .testTag("catalog_gift_${gift.catalogGiftId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF17171F)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (gift.isExclusive) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF8B5CF6).copy(alpha = 0.25f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    Text(
                        text = "VIP",
                        color = Color(0xFFD8B4FE),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }

            GiftLottieArt(
                lottieAssetName = gift.lottieAssetUrl,
                fallbackEmoji = gift.emojiIcon,
                emojiSize = 40.sp,
                accentColor = accent,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 14.dp)
                    .size(66.dp)
            )

            SparkleStarPricePill(
                priceText = priceDisplay,
                containerColor = Color.White.copy(alpha = 0.08f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun CollectibleCollectionTile(
    collection: CollectibleCollectionSummary,
    activeCurrency: CurrencyType,
    onClick: () -> Unit
) {
    val entranceScale = remember { Animatable(0.88f) }
    val entranceAlpha = remember { Animatable(0f) }

    LaunchedEffect(collection.collectionId) {
        coroutineScope {
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
        }
    }

    val floorDisplay = if (activeCurrency == CurrencyType.STARS) {
        "%,d+".format(collection.floorPriceStars)
    } else {
        "${activeCurrency.formatPrice(collection.floorPriceStars)}+"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.86f)
            .graphicsLayer {
                scaleX = entranceScale.value
                scaleY = entranceScale.value
                alpha = entranceAlpha.value
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF17171F))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag("collection_tile_${collection.collectionId}")
    ) {
        GiftLottieArt(
            lottieAssetName = collection.lottieAssetUrl,
            fallbackEmoji = collection.emojiIcon,
            emojiSize = 42.sp,
            accentColor = collection.parsedAccentColor,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 14.dp)
                .size(68.dp)
        )

        // Green angled "маркет" ribbon in top-right (Telegram Marketplace signature)
        DiagonalCornerRibbon(
            text = "маркет",
            backgroundColor = Color(0xFF22C55E),
            textColor = Color.White,
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.TopEnd)
        )

        SparkleStarPricePill(
            priceText = floorDisplay,
            containerColor = Color(0xFF2B2214).copy(alpha = 0.85f),
            starColor = Color(0xFFFFB703),
            textColor = Color(0xFFFFE082),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

/**
 * Telegram-style "Отправить подарок" preview bottom sheet matching reference Screenshot 11.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseGiftBottomSheet(
    gift: KuoteXCatalogGiftDoc,
    activeCurrency: CurrencyType,
    userBalance: Long,
    targetUserName: String,
    senderDisplayName: String,
    senderAvatarUrl: String,
    onDismiss: () -> Unit,
    onConfirmPurchase: (message: String, isAnonymous: Boolean, pinToHeader: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var customMessage by remember { mutableStateOf("") }
    var isAnonymous by remember { mutableStateOf(false) }
    var pinToHeader by remember { mutableStateOf(true) }

    val density = LocalDensity.current
    val isKeyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val scrollState = rememberScrollState()

    LaunchedEffect(isKeyboardOpen) {
        if (isKeyboardOpen) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val previewSender = if (isAnonymous) "Аноним" else senderDisplayName
    val previewAccent = runCatching { Color(android.graphics.Color.parseColor(gift.backdropColorHex)) }
        .getOrDefault(Color(0xFF5A1832))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF181722),
        scrimColor = Color.Black.copy(alpha = 0.72f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Sheet Title
            Text(
                text = "Отправить подарок",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Получатель: $targetUserName",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            // Live Telegram-Style Gradient Gift Preview Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                previewAccent.copy(alpha = 0.9f),
                                Color(0xFF281322)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Black.copy(alpha = 0.3f)
                    ) {
                        Text(
                            text = "$previewSender отправил(а) Вам подарок стоимостью ${gift.price} звёзд",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    GiftLottieArt(
                        lottieAssetName = gift.lottieAssetUrl,
                        fallbackEmoji = gift.emojiIcon,
                        emojiSize = if (isKeyboardOpen) 40.sp else 58.sp,
                        accentColor = Color(0xFFFFD54F),
                        modifier = Modifier.size(if (isKeyboardOpen) 64.dp else 94.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Подарок от",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        SenderAvatarBadge(
                            senderName = previewSender,
                            avatarUrl = if (isAnonymous) null else senderAvatarUrl,
                            isAnonymous = isAnonymous,
                            size = 20.dp
                        )
                        Text(
                            text = previewSender,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = if (customMessage.isNotBlank()) customMessage else "Вы можете добавить этот подарок в профиль.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "Посмотреть",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = customMessage,
                onValueChange = { customMessage = it },
                label = { Text("Ваше сообщение") },
                placeholder = { Text("Напишите тёплые слова...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Sentences
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF8B5CF6),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedLabelColor = Color(0xFFC084FC),
                    unfocusedLabelColor = Color(0xFF9CA3AF),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isAnonymous = !isAnonymous }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Скрыть моё имя", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                    Text(
                        "Другие пользователи не увидят Ваше имя под подарком",
                        fontSize = 11.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }
                Switch(checked = isAnonymous, onCheckedChange = { isAnonymous = it })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pinToHeader = !pinToHeader }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text("Закрепить в профиле", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 14.sp)
                    Text("Подарок сразу появится в витрине профиля", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
                Switch(checked = pinToHeader, onCheckedChange = { pinToHeader = it })
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        try { sheetState.hide() } catch (_: Exception) {}
                        onConfirmPurchase(customMessage, isAnonymous, pinToHeader)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("confirm_gift_purchase_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Отправить подарок за ${activeCurrency.formatPrice(gift.price)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Text(
                        text = "У Вас %,d звёзд".format(userBalance),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GiftPremiumBottomSheet(
    tier: PremiumPromoTier,
    activeCurrency: CurrencyType,
    userBalance: Long,
    recipientDisplayName: String,
    senderDisplayName: String,
    senderAvatarUrl: String,
    onDismiss: () -> Unit,
    onConfirmPurchase: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF181722),
        scrimColor = Color.Black.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(tier.ribbonColor.copy(alpha = 0.35f), Color.Transparent))),
                contentAlignment = Alignment.Center
            ) {
                GiftLottieArt(
                    lottieAssetName = "gift_star_premium.json",
                    fallbackEmoji = "⭐",
                    emojiSize = 54.sp,
                    accentColor = tier.ribbonColor,
                    modifier = Modifier.size(92.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Подарить KuoteX Premium",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${tier.title} • Получатель: $recipientDisplayName",
                color = Color(0xFF9CA3AF),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF21202D),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Отправитель", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SenderAvatarBadge(
                                senderName = senderDisplayName,
                                avatarUrl = senderAvatarUrl,
                                isAnonymous = false,
                                size = 20.dp
                            )
                            Text(senderDisplayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Срок VIP", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                        Text(tier.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Бонус голосов буста", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                        Text("+${4 * tier.months} голосов", color = Color(0xFF34D399), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Стоимость", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                        Text(
                            activeCurrency.formatPrice(tier.priceStars),
                            color = Color(0xFFFFD54F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        try { sheetState.hide() } catch (_: Exception) {}
                        onConfirmPurchase()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("confirm_premium_gift_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = tier.ribbonColor)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Подарить Premium за ${activeCurrency.formatPrice(tier.priceStars)}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "У Вас %,d звёзд".format(userBalance),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}
