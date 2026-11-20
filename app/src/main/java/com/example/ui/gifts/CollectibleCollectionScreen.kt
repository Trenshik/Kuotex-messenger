package com.example.ui.gifts

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.example.data.ecosystem.KuoteXEcosystemFirestoreManager
import com.example.ui.AppViewModel
import com.example.ui.LocalActiveAccount
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.util.UUID

enum class CollectionSortOption(val chipLabel: String, val menuLabel: String) {
    PRICE("Цена", "Сортировать по цене"),
    DATE("Дата", "Сортировать по дате"),
    NUMBER("Номер", "Сортировать по номеру")
}

/**
 * CollectibleCollectionScreen
 *
 * Dedicated Telegram-style browser window for a specific collectible collection (e.g., Mirage Lamp),
 * featuring patterned jewel-toned skin cards, Lottie gift art, searchable multi-select filters
 * (Модель, Фон, Узор), sort options, Stars-only filter pill, and live atomic skin purchase sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectibleCollectionScreen(
    viewModel: AppViewModel,
    navController: NavController,
    collectionId: String,
    targetUserId: String? = null,
    targetUserName: String? = null,
    ecosystemManager: KuoteXEcosystemFirestoreManager = KuoteXEcosystemFirestoreManager
) {
    val context = LocalContext.current
    val allCollectibles by ecosystemManager.collectibleMarketplaceGifts.collectAsState()
    val currentUser by ecosystemManager.currentUserState.collectAsState()
    val activeCurrency by ecosystemManager.selectedCurrency.collectAsState()
    val activeAccount = LocalActiveAccount.current
    val chats by viewModel.chats.collectAsState()

    val collectionSkins = remember(allCollectibles, collectionId) {
        allCollectibles.filter { it.effectiveCollectionId == collectionId }
    }
    val collectionSummary = remember(collectionSkins, collectionId) {
        ecosystemManager.getCollectibleCollectionSummary(collectionId)
            ?: collectionSkins.firstOrNull()?.let {
                CollectibleCollectionSummary(
                    collectionId = collectionId,
                    title = it.baseTitle,
                    emojiIcon = it.emojiIcon,
                    floorPriceStars = it.priceStars,
                    offersCount = collectionSkins.count { s -> s.isForSale },
                    backdropColorHex = it.backdropColorHex,
                    accentColorHex = it.accentGlowHex,
                    lottieAssetUrl = it.effectiveLottieAsset
                )
            }
    }

    var sortOption by remember { mutableStateOf(CollectionSortOption.PRICE) }
    var onlyStarsFilter by remember { mutableStateOf(false) }
    var selectedModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedBackdrops by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedPatterns by remember { mutableStateOf<Set<String>>(emptySet()) }

    var showSortMenu by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    var showBackdropMenu by remember { mutableStateOf(false) }
    var showPatternMenu by remember { mutableStateOf(false) }

    var modelSearch by remember { mutableStateOf("") }
    var backdropSearch by remember { mutableStateOf("") }
    var patternSearch by remember { mutableStateOf("") }

    var selectedSkinForPurchase by remember { mutableStateOf<CollectibleGift?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val modelCounts = remember(collectionSkins) {
        collectionSkins.groupingBy { it.modelName }.eachCount()
            .entries.sortedByDescending { it.value }
    }
    val backdropCounts = remember(collectionSkins) {
        collectionSkins.groupBy { it.effectiveBackdropName }.map { (name, items) ->
            Triple(name, items.size, items.first().parsedBackdropColor)
        }.sortedByDescending { it.second }
    }
    val patternCounts = remember(collectionSkins) {
        collectionSkins.groupingBy { it.patternName }.eachCount()
            .entries.sortedByDescending { it.value }
    }

    val filteredSkins by remember(
        collectionSkins,
        sortOption,
        onlyStarsFilter,
        selectedModels,
        selectedBackdrops,
        selectedPatterns
    ) {
        derivedStateOf {
            collectionSkins
                .filter { skin ->
                    (!onlyStarsFilter || skin.acceptsStarsOnly) &&
                        (selectedModels.isEmpty() || skin.modelName in selectedModels) &&
                        (selectedBackdrops.isEmpty() || skin.effectiveBackdropName in selectedBackdrops) &&
                        (selectedPatterns.isEmpty() || skin.patternName in selectedPatterns)
                }
                .let { list ->
                    when (sortOption) {
                        CollectionSortOption.PRICE -> list.sortedWith(compareBy<CollectibleGift> { !it.isForSale }.thenBy { it.priceStars }.thenBy { it.serialNumber })
                        CollectionSortOption.DATE -> list.sortedWith(compareBy<CollectibleGift> { !it.isForSale }.thenByDescending { it.createdAt })
                        CollectionSortOption.NUMBER -> list.sortedWith(compareBy<CollectibleGift> { !it.isForSale }.thenBy { it.serialNumber })
                    }
                }
        }
    }

    val userBalance = currentUser?.balance ?: 1000L
    val resolvedActiveUserId = FirebaseAuth.getInstance().currentUser?.uid
        ?.takeIf { it.isNotBlank() }
        ?: activeAccount?.id?.takeIf { it.isNotBlank() && it != "me" }
        ?: currentUser?.userId?.takeIf { it.isNotBlank() && it != "me" }
        ?: "me"

    val senderDisplayName = remember(activeAccount, currentUser) {
        SenderIdentityResolver.resolveSenderName(
            rawSenderId = resolvedActiveUserId,
            storedSenderName = activeAccount?.displayName ?: currentUser?.displayName.orEmpty(),
            isAnonymous = false,
            chats = chats,
            activeAccount = activeAccount
        )
    }
    val senderAvatarUrl = remember(activeAccount) {
        SenderIdentityResolver.resolveSenderAvatar(
            rawSenderId = resolvedActiveUserId,
            storedAvatarUrl = activeAccount?.profilePicUrl.orEmpty(),
            isAnonymous = false,
            context = context,
            chats = chats,
            activeAccount = activeAccount
        ).orEmpty()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF0B0B10)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header Bar (matching Telegram collectible collection header)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(40.dp)
                            .background(Color(0xFF1A1B22), CircleShape)
                            .testTag("collection_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = Color.White
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = collectionSummary?.title ?: "Коллекция",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${filteredSkins.size} предложений",
                            color = Color(0xFF9CA3AF),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF1A1B22),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(text = "★", color = Color(0xFFFFB703), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = "%,d".format(userBalance),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Filter Chips Bar: Sort, Модель, Фон, Узор
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Sort Chip + Menu
                    item {
                        Box {
                            FilterChipPill(
                                label = sortOption.chipLabel,
                                isActive = true,
                                leadingIcon = {
                                    Icon(
                                        imageVector = when (sortOption) {
                                            CollectionSortOption.PRICE -> Icons.Filled.AttachMoney
                                            CollectionSortOption.DATE -> Icons.Filled.CalendarToday
                                            CollectionSortOption.NUMBER -> Icons.Filled.Tag
                                        },
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                showChevron = false,
                                testTag = "filter_sort_chip",
                                onClick = { showSortMenu = true }
                            )

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier
                                    .width(240.dp)
                                    .background(Color(0xFF1E1D28))
                            ) {
                                CollectionSortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = option.menuLabel,
                                                color = Color.White,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = when (option) {
                                                    CollectionSortOption.PRICE -> Icons.Filled.AttachMoney
                                                    CollectionSortOption.DATE -> Icons.Filled.CalendarToday
                                                    CollectionSortOption.NUMBER -> Icons.Filled.Tag
                                                },
                                                contentDescription = null,
                                                tint = if (sortOption == option) Color(0xFF8B5CF6) else Color(0xFF9CA3AF)
                                            )
                                        },
                                        trailingIcon = {
                                            if (sortOption == option) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF8B5CF6))
                                            }
                                        },
                                        onClick = {
                                            sortOption = option
                                            showSortMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                                DropdownMenuItem(
                                    text = { Text("Все предложения", color = Color.White, fontSize = 14.sp) },
                                    trailingIcon = {
                                        if (!onlyStarsFilter) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF8B5CF6))
                                        }
                                    },
                                    onClick = {
                                        onlyStarsFilter = false
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Только за звёзды", color = Color.White, fontSize = 14.sp) },
                                    trailingIcon = {
                                        if (onlyStarsFilter) {
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF8B5CF6))
                                        }
                                    },
                                    onClick = {
                                        onlyStarsFilter = true
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // 2. Модель Dropdown
                    item {
                        Box {
                            FilterChipPill(
                                label = if (selectedModels.isEmpty()) "Модель" else "Модель (${selectedModels.size})",
                                isActive = selectedModels.isNotEmpty(),
                                showChevron = true,
                                testTag = "filter_model_chip",
                                onClick = { showModelMenu = true }
                            )

                            SearchableFilterDropdown(
                                expanded = showModelMenu,
                                onDismissRequest = {
                                    showModelMenu = false
                                    modelSearch = ""
                                },
                                searchQuery = modelSearch,
                                onSearchChange = { modelSearch = it },
                                onReset = if (selectedModels.isNotEmpty()) {
                                    { selectedModels = emptySet() }
                                } else null
                            ) {
                                val filtered = modelCounts.filter {
                                    modelSearch.isBlank() || it.key.contains(modelSearch, ignoreCase = true)
                                }
                                filtered.forEach { (model, count) ->
                                    val checked = model in selectedModels
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = collectionSummary?.emojiIcon ?: "🎁",
                                                    fontSize = 16.sp
                                                )
                                                Text(
                                                    text = model,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Text(
                                                    text = "$count",
                                                    color = Color(0xFF9CA3AF),
                                                    fontSize = 13.sp
                                                )
                                            }
                                        },
                                        trailingIcon = {
                                            if (checked || selectedModels.isEmpty()) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = if (checked) Color(0xFF8B5CF6) else Color.White.copy(alpha = 0.7f)
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedModels = if (checked) selectedModels - model else selectedModels + model
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 3. Фон Dropdown
                    item {
                        Box {
                            FilterChipPill(
                                label = if (selectedBackdrops.isEmpty()) "Фон" else "Фон (${selectedBackdrops.size})",
                                isActive = selectedBackdrops.isNotEmpty(),
                                showChevron = true,
                                testTag = "filter_backdrop_chip",
                                onClick = { showBackdropMenu = true }
                            )

                            SearchableFilterDropdown(
                                expanded = showBackdropMenu,
                                onDismissRequest = {
                                    showBackdropMenu = false
                                    backdropSearch = ""
                                },
                                searchQuery = backdropSearch,
                                onSearchChange = { backdropSearch = it },
                                onReset = if (selectedBackdrops.isNotEmpty()) {
                                    { selectedBackdrops = emptySet() }
                                } else null
                            ) {
                                val filtered = backdropCounts.filter {
                                    backdropSearch.isBlank() || it.first.contains(backdropSearch, ignoreCase = true)
                                }
                                filtered.forEach { (name, count, color) ->
                                    val checked = name in selectedBackdrops
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .clip(CircleShape)
                                                        .background(color)
                                                        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                                                )
                                                Text(
                                                    text = name,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Text(
                                                    text = "$count",
                                                    color = Color(0xFF9CA3AF),
                                                    fontSize = 13.sp
                                                )
                                            }
                                        },
                                        trailingIcon = {
                                            if (checked || selectedBackdrops.isEmpty()) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = if (checked) Color(0xFF8B5CF6) else Color.White.copy(alpha = 0.7f)
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedBackdrops = if (checked) selectedBackdrops - name else selectedBackdrops + name
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 4. Узор Dropdown
                    item {
                        Box {
                            FilterChipPill(
                                label = if (selectedPatterns.isEmpty()) "Узор" else "Узор (${selectedPatterns.size})",
                                isActive = selectedPatterns.isNotEmpty(),
                                showChevron = true,
                                testTag = "filter_pattern_chip",
                                onClick = { showPatternMenu = true }
                            )

                            SearchableFilterDropdown(
                                expanded = showPatternMenu,
                                onDismissRequest = {
                                    showPatternMenu = false
                                    patternSearch = ""
                                },
                                searchQuery = patternSearch,
                                onSearchChange = { patternSearch = it },
                                onReset = if (selectedPatterns.isNotEmpty()) {
                                    { selectedPatterns = emptySet() }
                                } else null
                            ) {
                                val filtered = patternCounts.filter {
                                    patternSearch.isBlank() || it.key.contains(patternSearch, ignoreCase = true)
                                }
                                filtered.forEach { (pattern, count) ->
                                    val checked = pattern in selectedPatterns
                                    val motif = GiftPatternMotif.fromPatternName(pattern)
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Text(text = motif.glyph, fontSize = 15.sp, color = Color.White)
                                                Text(
                                                    text = pattern,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Text(
                                                    text = "$count",
                                                    color = Color(0xFF9CA3AF),
                                                    fontSize = 13.sp
                                                )
                                            }
                                        },
                                        trailingIcon = {
                                            if (checked || selectedPatterns.isEmpty()) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = if (checked) Color(0xFF8B5CF6) else Color.White.copy(alpha = 0.7f)
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedPatterns = if (checked) selectedPatterns - pattern else selectedPatterns + pattern
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3-Column Patterned Skin Grid
                if (filteredSkins.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "🔍", fontSize = 40.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Нет предложений по выбранным фильтрам",
                                color = Color(0xFF9CA3AF),
                                fontSize = 15.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 92.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredSkins, key = { it.id }) { skin ->
                            CollectibleSkinCard(
                                skin = skin,
                                activeCurrency = activeCurrency,
                                onClick = {
                                    if (skin.isForSale) {
                                        selectedSkinForPurchase = skin
                                    } else {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Этот скин уже продан")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Floating Bottom Toggle Pill: "Только предложения за звёзды"
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF1F1E28).copy(alpha = 0.94f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onlyStarsFilter = !onlyStarsFilter }
                    .testTag("stars_only_floating_toggle")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (onlyStarsFilter) Color(0xFF8B5CF6) else Color.Transparent)
                            .border(
                                width = 1.8.dp,
                                color = if (onlyStarsFilter) Color(0xFFC084FC) else Color(0xFF6B7280),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (onlyStarsFilter) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    Text(
                        text = "Только предложения за звёзды",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }

    selectedSkinForPurchase?.let { skin ->
        PurchaseCollectibleSkinSheet(
            skin = skin,
            activeCurrency = activeCurrency,
            userBalance = userBalance,
            targetUserName = targetUserName?.takeIf { it.isNotBlank() } ?: "Себе в профиль",
            senderName = senderDisplayName,
            senderAvatarUrl = senderAvatarUrl,
            onDismiss = { selectedSkinForPurchase = null },
            onConfirmPurchase = { message, isAnonymous, pinToHeader ->
                val toBuy = skin
                selectedSkinForPurchase = null
                scope.launch {
                    val receiver = targetUserId?.takeIf { it.isNotBlank() && it != "me" } ?: resolvedActiveUserId
                    val idemKey = "skin_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
                    val res = ecosystemManager.purchaseCollectibleSkinAtomic(
                        senderUserId = resolvedActiveUserId,
                        targetUserId = receiver,
                        collectibleGiftId = toBuy.id,
                        idempotencyKey = idemKey,
                        message = message,
                        pinToHeader = pinToHeader,
                        isAnonymous = isAnonymous,
                        senderName = senderDisplayName,
                        senderAvatarUrl = senderAvatarUrl
                    )
                    if (res.isSuccess) {
                        snackbarHostState.showSnackbar("Скин ${toBuy.baseTitle} ${toBuy.formattedSerialNumber} отправлен!")
                    } else {
                        snackbarHostState.showSnackbar("Ошибка: ${res.exceptionOrNull()?.message}")
                    }
                }
            }
        )
    }
}

@Composable
private fun CollectibleSkinCard(
    skin: CollectibleGift,
    activeCurrency: CurrencyType,
    onClick: () -> Unit
) {
    val priceDisplay = if (activeCurrency == CurrencyType.STARS) {
        "%,d".format(skin.priceStars)
    } else {
        activeCurrency.formatPrice(skin.priceStars)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.76f)
            .alpha(if (skin.isForSale) 1f else 0.52f)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .testTag("collectible_skin_${skin.id}")
    ) {
        PatternedGiftBackdrop(
            backdropColor = skin.parsedBackdropColor,
            accentColor = skin.parsedAccentColor,
            patternName = skin.patternName,
            modifier = Modifier.fillMaxSize(),
            patternAlpha = 0.22f
        )

        // Top-left Rarity Gem Badge (matching screenshot)
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(7.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "◆",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Top-right angled serial number ribbon (#22,770)
        DiagonalCornerRibbon(
            text = skin.formattedSerialNumber,
            backgroundColor = Color.Black.copy(alpha = 0.28f),
            textColor = Color.White,
            fontSize = 8.sp,
            modifier = Modifier.align(Alignment.TopEnd)
        )

        // Center Animated Lottie Skin Artwork
        GiftLottieArt(
            lottieAssetName = skin.effectiveLottieAsset,
            fallbackEmoji = skin.emojiIcon,
            emojiSize = 44.sp,
            accentColor = skin.parsedAccentColor,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 14.dp)
                .size(74.dp)
        )

        // Bottom Price Pill or Sold indicator
        if (skin.isForSale) {
            SparkleStarPricePill(
                priceText = priceDisplay,
                containerColor = Color.Black.copy(alpha = 0.34f),
                starColor = Color.White,
                textColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        } else {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.65f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = "Продано",
                    color = Color(0xFFD1D5DB),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    isActive: Boolean,
    showChevron: Boolean,
    testTag: String,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (isActive) Color(0xFF282636) else Color(0xFF1A1B22),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) Color(0xFF8B5CF6).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)
        ),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            leadingIcon?.invoke()
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
            if (showChevron) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchableFilterDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onReset: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier
            .width(248.dp)
            .heightIn(max = 360.dp)
            .background(Color(0xFF1E1D28))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Поиск",
                tint = Color(0xFF9CA3AF),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Поиск...", color = Color(0xFF6B7280), fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }
        if (onReset != null) {
            DropdownMenuItem(
                text = { Text("Сбросить выбор", color = Color(0xFFC084FC), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                onClick = onReset
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PurchaseCollectibleSkinSheet(
    skin: CollectibleGift,
    activeCurrency: CurrencyType,
    userBalance: Long,
    targetUserName: String,
    senderName: String,
    senderAvatarUrl: String,
    onDismiss: () -> Unit,
    onConfirmPurchase: (message: String, isAnonymous: Boolean, pinToHeader: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var message by remember { mutableStateOf("") }
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161520),
        scrimColor = Color.Black.copy(alpha = 0.72f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Patterned Skin Hero Card Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isKeyboardOpen) 136.dp else 196.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .border(1.5.dp, skin.parsedAccentColor.copy(alpha = 0.65f), RoundedCornerShape(22.dp))
            ) {
                PatternedGiftBackdrop(
                    backdropColor = skin.parsedBackdropColor,
                    accentColor = skin.parsedAccentColor,
                    patternName = skin.patternName,
                    modifier = Modifier.fillMaxSize(),
                    patternAlpha = 0.24f
                )

                DiagonalCornerRibbon(
                    text = skin.formattedSerialNumber,
                    backgroundColor = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.align(Alignment.TopEnd)
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    GiftLottieArt(
                        lottieAssetName = skin.effectiveLottieAsset,
                        fallbackEmoji = skin.emojiIcon,
                        emojiSize = if (isKeyboardOpen) 44.sp else 62.sp,
                        accentColor = skin.parsedAccentColor,
                        modifier = Modifier.size(if (isKeyboardOpen) 72.dp else 96.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${skin.baseTitle} ${skin.formattedSerialNumber}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        SenderAvatarBadge(
                            senderName = if (isAnonymous) "Аноним" else senderName,
                            avatarUrl = if (isAnonymous) null else senderAvatarUrl,
                            isAnonymous = isAnonymous,
                            size = 20.dp
                        )
                        Text(
                            text = if (isAnonymous) "От: Аноним" else "От: $senderName",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Skin Metadata Table (Модель, Фон, Узор, Владелец)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF21202D),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkinMetaRow("Получатель", targetUserName)
                    SkinMetaRow("Модель", skin.modelName)
                    SkinMetaRow("Фон", skin.effectiveBackdropName)
                    SkinMetaRow("Узор", skin.patternName)
                    SkinMetaRow("Продавец", "@${skin.ownerName.ifBlank { "market" }}")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Ваше сообщение") },
                placeholder = { Text("Добавьте поздравление...") },
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

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isAnonymous = !isAnonymous }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Скрыть моё имя", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Получатель не узнает, кто подарил скин", color = Color(0xFF9CA3AF), fontSize = 11.sp)
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("Закрепить в профиле", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Скин появится в витрине профиля получателя", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                }
                Switch(checked = pinToHeader, onCheckedChange = { pinToHeader = it })
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        try { sheetState.hide() } catch (_: Exception) {}
                        onConfirmPurchase(message, isAnonymous, pinToHeader)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("confirm_skin_purchase_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Отправить подарок за ${activeCurrency.formatPrice(skin.priceStars)}",
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

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SkinMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = Color(0xFF9CA3AF), fontSize = 13.sp)
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
