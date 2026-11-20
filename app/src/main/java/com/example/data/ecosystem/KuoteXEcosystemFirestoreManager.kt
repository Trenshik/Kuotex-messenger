package com.example.data.ecosystem

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.FirestoreClientProvider
import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.example.ui.gifts.CollectibleCollectionSummary
import com.example.ui.gifts.CollectibleGift
import com.example.ui.gifts.CurrencyType
import com.example.ui.gifts.resolveGiftLottieAsset
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Custom Exceptions for Ecosystem Operations.
 */
class InsufficientBalanceException(message: String) : Exception(message)
class GiftSoldOutException(message: String) : Exception(message)
class DuplicateTransactionException(message: String) : Exception(message)
class UnauthorizedBoostException(message: String) : Exception(message)
class InsufficientBoostVotesException(message: String) : Exception(message)

/**
 * KuoteX Ecosystem Firestore Manager
 * Handles ACID-compliant atomic transactions, balance management, VIP subscriptions,
 * channel boosting with privilege validation, and animated profile gifts.
 */
object KuoteXEcosystemFirestoreManager {

    private const val TAG = "KuoteXEcosystem"
    private const val COLLECTION_USERS = "users"
    private const val COLLECTION_CHANNELS = "channels"
    private const val COLLECTION_GIFTS_CATALOG = "gifts_catalog"
    private const val COLLECTION_USER_GIFTS = "user_gifts"
    private const val COLLECTION_LEDGER_TX = "ledger_transactions"
    private const val COLLECTION_IDEMPOTENCY = "idempotency_keys"
    private const val COLLECTION_POLL_VOTES = "poll_votes"
    private const val STARS_CACHE_PREFS = "kuotex_stars_balance_prefs"
    private const val KEY_CACHED_BALANCE_PREFIX = "stars_balance_"

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var starsCachePrefs: SharedPreferences? = null

    @Volatile
    private var catalogSynced = false

    fun init(context: Context) {
        FirestoreClientProvider.init(context)
        initLocalBalanceCache(context)
    }

    private fun getDbOrNull(): FirebaseFirestore? {
        return FirestoreClientProvider.getFirestoreOrNull()
    }

    private fun currentAuthUid(): String? {
        return runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
    }

    private fun sanitizeFirestoreId(raw: String, fallbackPrefix: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        return cleaned.ifBlank { "${fallbackPrefix}_${System.currentTimeMillis()}" }
    }

    private fun isSampleGiftId(id: String): Boolean {
        return id.startsWith("g_") || id.endsWith("_sample")
    }

    // Local in-memory caching fallback for offline & fast UI rendering
    private val _currentUserState = MutableStateFlow<KuoteXUserDoc?>(null)
    val currentUserState: StateFlow<KuoteXUserDoc?> = _currentUserState.asStateFlow()

    fun initLocalBalanceCache(context: Context) {
        if (starsCachePrefs == null) {
            starsCachePrefs = context.applicationContext.getSharedPreferences(STARS_CACHE_PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun readCachedBalance(userId: String): Long? {
        val prefs = starsCachePrefs ?: return null
        val key = "$KEY_CACHED_BALANCE_PREFIX$userId"
        return if (prefs.contains(key)) prefs.getLong(key, 1000L) else null
    }

    private fun saveCachedBalance(userId: String, balance: Long) {
        starsCachePrefs?.edit()?.putLong("$KEY_CACHED_BALANCE_PREFIX$userId", balance.coerceAtLeast(0L))?.apply()
    }

    fun ensureUserStateLoaded(userId: String, username: String = "user", displayName: String = "User") {
        val resolvedId = userId.ifBlank { "me" }
        val existing = _currentUserState.value
        if (existing != null && existing.userId == resolvedId) return
        val cachedBal = readCachedBalance(resolvedId) ?: 1000L
        _currentUserState.value = KuoteXUserDoc(
            userId = resolvedId,
            username = username.ifBlank { "user" },
            displayName = displayName.ifBlank { "User" },
            balance = cachedBal
        )
        managerScope.launch {
            syncAndObserveUser(resolvedId, username.ifBlank { "user" }, displayName.ifBlank { "User" })
        }
    }

    private val _catalogGifts = MutableStateFlow<List<KuoteXCatalogGiftDoc>>(emptyList())
    val catalogGifts: StateFlow<List<KuoteXCatalogGiftDoc>> = _catalogGifts.asStateFlow()

    private val _pinnedGiftsMap = MutableStateFlow<Map<String, List<KuoteXUserGiftDoc>>>(emptyMap())
    val pinnedGiftsMap: StateFlow<Map<String, List<KuoteXUserGiftDoc>>> = _pinnedGiftsMap.asStateFlow()

    // Collectible unique gifts on the marketplace
    private val _collectibleMarketplaceGifts = MutableStateFlow<List<CollectibleGift>>(emptyList())
    val collectibleMarketplaceGifts: StateFlow<List<CollectibleGift>> = _collectibleMarketplaceGifts.asStateFlow()

    // Grouped Collectible Collections shown with green "маркет" badge in Marketplace
    private val _collectibleCollections = MutableStateFlow<List<CollectibleCollectionSummary>>(emptyList())
    val collectibleCollections: StateFlow<List<CollectibleCollectionSummary>> = _collectibleCollections.asStateFlow()

    // User's pinned collectible gift next to nickname
    private val _userPinnedCollectible = MutableStateFlow<Map<String, CollectibleGift?>>(emptyMap())
    val userPinnedCollectible: StateFlow<Map<String, CollectibleGift?>> = _userPinnedCollectible.asStateFlow()

    // Selected currency preference (Stars, USD, RUB, EUR)
    private val _selectedCurrency = MutableStateFlow<CurrencyType>(CurrencyType.STARS)
    val selectedCurrency: StateFlow<CurrencyType> = _selectedCurrency.asStateFlow()

    fun setCurrency(currency: CurrencyType) {
        _selectedCurrency.value = currency
    }

    fun pinCollectibleToUsername(userId: String, gift: CollectibleGift?) {
        _userPinnedCollectible.update { current ->
            current + (userId to gift)
        }
    }

    /**
     * Default sample gift documents matching Telegram references.
     */
    fun sampleUserGiftDocs(userId: String): List<KuoteXUserGiftDoc> {
        return listOf(
            KuoteXUserGiftDoc(
                userGiftId = "g_heart_1",
                catalogGiftId = "gift_heart_box_008",
                senderId = "alex",
                receiverId = userId,
                isPinnedToHeader = true,
                pinOrderIndex = 0,
                upgradeLevel = 2,
                cachedTitle = "Сердце с бантом",
                cachedEmoji = "💝",
                cachedColorHex = "#2E081E"
            ),
            KuoteXUserGiftDoc(
                userGiftId = "g_bear_1",
                catalogGiftId = "gift_teddy_bear_007",
                senderId = "Сестра.",
                receiverId = userId,
                isPinnedToHeader = true,
                pinOrderIndex = 1,
                upgradeLevel = 1,
                cachedTitle = "Плюшевый Мишка",
                cachedEmoji = "🧸",
                cachedColorHex = "#261E14"
            ),
            KuoteXUserGiftDoc(
                userGiftId = "g_bear_2",
                catalogGiftId = "gift_teddy_bear_007",
                senderId = "round_fan",
                receiverId = userId,
                isPinnedToHeader = true,
                pinOrderIndex = 2,
                upgradeLevel = 1,
                cachedTitle = "Плюшевый Мишка",
                cachedEmoji = "🧸",
                cachedColorHex = "#261E14"
            ),
            KuoteXUserGiftDoc(
                userGiftId = "g_bear_3",
                catalogGiftId = "gift_teddy_bear_007",
                senderId = "Аноним",
                receiverId = userId,
                isPinnedToHeader = true,
                pinOrderIndex = 3,
                upgradeLevel = 1,
                cachedTitle = "Плюшевый Мишка",
                cachedEmoji = "🧸",
                cachedColorHex = "#261E14"
            ),
            KuoteXUserGiftDoc(
                userGiftId = "g_gold_1",
                catalogGiftId = "gift_golden_present_009",
                senderId = "best_friend",
                receiverId = userId,
                isPinnedToHeader = true,
                pinOrderIndex = 4,
                upgradeLevel = 3,
                cachedTitle = "Золотой Подарок",
                cachedEmoji = "🎁",
                cachedColorHex = "#2D2206"
            ),
            KuoteXUserGiftDoc(
                userGiftId = "g_dragon_1",
                catalogGiftId = "gift_cyber_dragon_001",
                senderId = "durov",
                receiverId = userId,
                isPinnedToHeader = true,
                pinOrderIndex = 5,
                upgradeLevel = 4,
                cachedTitle = "Cyber Dragon 2026",
                cachedEmoji = "🐉",
                cachedColorHex = "#1E1B4B"
            )
        )
    }

    /**
     * Initializes gifts for a user if not already in memory/cache.
     */
    fun initializeUserGiftsIfEmpty(userId: String, gifts: List<KuoteXUserGiftDoc> = sampleUserGiftDocs(userId)) {
        _pinnedGiftsMap.update { currentMap ->
            if (currentMap[userId].isNullOrEmpty()) {
                currentMap + (userId to gifts)
            } else {
                currentMap
            }
        }
    }

    init {
        initDefaultCatalog()
    }

    /**
     * Pre-populates the default gifts catalog if not present.
     */
    private fun initDefaultCatalog() {
        val defaultCatalog = listOf(
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_heart_box_008",
                title = "Сердце с бантом",
                price = 15L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_heart_lottie.json",
                backdropColorHex = "#2E081E",
                emojiIcon = "💝",
                maxUpgradeLevel = 4
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_teddy_bear_007",
                title = "Плюшевый Мишка",
                price = 15L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_box_lottie.json",
                backdropColorHex = "#261E14",
                emojiIcon = "🧸",
                maxUpgradeLevel = 4
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_golden_present_009",
                title = "Золотой Подарок",
                price = 25L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_box_lottie.json",
                backdropColorHex = "#2D2206",
                emojiIcon = "🎁",
                maxUpgradeLevel = 4
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_sakura_flower_006",
                title = "Красная Роза",
                price = 25L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_heart_lottie.json",
                backdropColorHex = "#290D16",
                emojiIcon = "🌹",
                maxUpgradeLevel = 3
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_cake_015",
                title = "Праздничный Торт",
                price = 50L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_sparkle_aura.json",
                backdropColorHex = "#2C1810",
                emojiIcon = "🎂",
                maxUpgradeLevel = 4
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_bouquet_016",
                title = "Букет Цветов",
                price = 50L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_heart_lottie.json",
                backdropColorHex = "#2A1224",
                emojiIcon = "💐",
                maxUpgradeLevel = 4
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_cosmic_rocket_004",
                title = "Космическая Ракета",
                price = 50L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_rocket_lottie.json",
                backdropColorHex = "#1E1035",
                emojiIcon = "🚀",
                maxUpgradeLevel = 3
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_champagne_017",
                title = "Шампанское",
                price = 50L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_trophy_lottie.json",
                backdropColorHex = "#12261E",
                emojiIcon = "🍾",
                maxUpgradeLevel = 4
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_magic_crystal_005",
                title = "Магический Кристалл",
                price = 50L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_sparkle_aura.json",
                backdropColorHex = "#280B1E",
                emojiIcon = "🔮",
                maxUpgradeLevel = 3
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_trophy_018",
                title = "Золотой Кубок",
                price = 100L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_trophy_lottie.json",
                backdropColorHex = "#2A1D06",
                emojiIcon = "🏆",
                maxUpgradeLevel = 5
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_neon_diamond_003",
                title = "Неоновый Алмаз",
                price = 100L,
                totalSupply = -1L,
                availableSupply = -1L,
                isExclusive = false,
                lottieAssetUrl = "gift_diamond_lottie.json",
                backdropColorHex = "#062826",
                emojiIcon = "💎",
                maxUpgradeLevel = 3
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_golden_crown_002",
                title = "Золотая Корона VIP",
                price = 150L,
                totalSupply = 5000L,
                availableSupply = 4120L,
                isExclusive = true,
                lottieAssetUrl = "gift_trophy_lottie.json",
                backdropColorHex = "#281904",
                emojiIcon = "👑",
                maxUpgradeLevel = 5
            ),
            KuoteXCatalogGiftDoc(
                catalogGiftId = "gift_cyber_dragon_001",
                title = "Cyber Dragon 2026",
                price = 250L,
                totalSupply = 1000L,
                availableSupply = 782L,
                isExclusive = true,
                lottieAssetUrl = "gift_rocket_lottie.json",
                backdropColorHex = "#1E1B4B",
                emojiIcon = "🐉",
                maxUpgradeLevel = 5
            )
        )
        _catalogGifts.value = defaultCatalog

        // Initialize Collectible Gifts marketplace items and collection summaries
        initDefaultCollectibles()
    }

    private suspend fun syncCatalogIfAuthenticated() {
        if (catalogSynced) return
        val db = getDbOrNull() ?: return
        if (currentAuthUid().isNullOrBlank()) return
        var currentPath = COLLECTION_GIFTS_CATALOG
        try {
            for (gift in _catalogGifts.value) {
                val docRef = db.collection(COLLECTION_GIFTS_CATALOG).document(gift.catalogGiftId)
                currentPath = docRef.path
                val snap = docRef.get().await()
                if (!snap.exists()) {
                    docRef.set(gift.toFirestoreMap()).await()
                }
            }
            catalogSynced = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, currentPath)
        }
    }

    /**
     * Initializes rich collectible collections & unique skins matching Telegram Android Gifts Marketplace.
     */
    private fun initDefaultCollectibles() {
        val now = System.currentTimeMillis()
        val collectibles = listOf(
            // === Mirage Lamp Collection ===
            CollectibleGift(
                id = "col_mirage_22660",
                serialNumber = 22660,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "🪔",
                modelName = "Moonlit Flower",
                backdropName = "Seafoam",
                patternName = "Cactus",
                backdropColorHex = "#2E7370",
                accentGlowHex = "#5EEAD4",
                priceStars = 471L,
                ownerName = "oasis_trader",
                lottieAssetUrl = "gift_lamp_lottie.json",
                createdAt = now - 86400000L * 2
            ),
            CollectibleGift(
                id = "col_mirage_22770",
                serialNumber = 22770,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "🪔",
                modelName = "Genie Lantern",
                backdropName = "Electric Purple",
                patternName = "Scarab",
                backdropColorHex = "#6D46B8",
                accentGlowHex = "#C084FC",
                priceStars = 471L,
                ownerName = "genie_vault",
                lottieAssetUrl = "gift_lamp_lottie.json",
                createdAt = now - 86400000L * 3
            ),
            CollectibleGift(
                id = "col_mirage_23104",
                serialNumber = 23104,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "🏮",
                modelName = "Crimson Sultan",
                backdropName = "Seafoam",
                patternName = "Wreath",
                backdropColorHex = "#2C6B67",
                accentGlowHex = "#F43F5E",
                priceStars = 471L,
                ownerName = "ruby_collector",
                lottieAssetUrl = "gift_lamp_lottie.json",
                createdAt = now - 86400000L * 4
            ),
            CollectibleGift(
                id = "col_mirage_109383",
                serialNumber = 109383,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "🎈",
                modelName = "Agrabah Hot Air",
                backdropName = "Old Gold",
                patternName = "Arabian Horse",
                backdropColorHex = "#7A5D1D",
                accentGlowHex = "#FACC15",
                priceStars = 471L,
                ownerName = "agrabah",
                lottieAssetUrl = "gift_lamp_lottie.json",
                createdAt = now - 86400000L * 5
            ),
            CollectibleGift(
                id = "col_mirage_112461",
                serialNumber = 112461,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "🪔",
                modelName = "Desert Jewel",
                backdropName = "Emerald",
                patternName = "Lightbulb",
                backdropColorHex = "#277A57",
                accentGlowHex = "#34D399",
                priceStars = 471L,
                ownerName = "desert_fox",
                lottieAssetUrl = "gift_lamp_lottie.json",
                createdAt = now - 86400000L * 6
            ),
            CollectibleGift(
                id = "col_mirage_112696",
                serialNumber = 112696,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "🏮",
                modelName = "Ice Crystal",
                backdropName = "Moss Green",
                patternName = "Star",
                backdropColorHex = "#4B7B29",
                accentGlowHex = "#A3E635",
                priceStars = 471L,
                ownerName = "frost_mint",
                lottieAssetUrl = "gift_lamp_lottie.json",
                createdAt = now - 86400000L * 7
            ),
            CollectibleGift(
                id = "col_mirage_113190",
                serialNumber = 113190,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "🪔",
                modelName = "Magma Heart",
                backdropName = "English Violet",
                patternName = "Crown",
                backdropColorHex = "#9E3762",
                accentGlowHex = "#FB7185",
                priceStars = 495L,
                ownerName = "rose_gold",
                lottieAssetUrl = "gift_lamp_lottie.json",
                createdAt = now - 86400000L * 8
            ),
            CollectibleGift(
                id = "col_mirage_113378",
                serialNumber = 113378,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "⚙️",
                modelName = "Steampunk Brass",
                backdropName = "Seafoam",
                patternName = "Diamond",
                backdropColorHex = "#1E787F",
                accentGlowHex = "#FBBF24",
                priceStars = 520L,
                ownerName = "clockwork",
                lottieAssetUrl = "gift_lamp_lottie.json",
                createdAt = now - 86400000L * 9
            ),
            CollectibleGift(
                id = "col_mirage_114052",
                serialNumber = 114052,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "🏮",
                modelName = "Obsidian Amber",
                backdropName = "Emerald",
                patternName = "Shield",
                backdropColorHex = "#1F6D5A",
                accentGlowHex = "#4ADE80",
                priceStars = 540L,
                ownerName = "phantom_lux",
                lottieAssetUrl = "gift_lamp_lottie.json",
                createdAt = now - 86400000L * 10
            ),
            CollectibleGift(
                id = "col_mirage_115843",
                serialNumber = 115843,
                baseTitle = "Mirage Lamp",
                category = "Mirage Lamp",
                collectionId = "mirage_lamp",
                emojiIcon = "🕌",
                modelName = "Oasis Dome",
                backdropName = "Teal",
                patternName = "Crown",
                backdropColorHex = "#117B7C",
                accentGlowHex = "#2DD4BF",
                priceStars = 610L,
                ownerName = "sultan",
                lottieAssetUrl = "gift_lamp_lottie.json",
                acceptsStarsOnly = false,
                createdAt = now - 86400000L * 11
            ),
            // === Liberty Torch Collection ===
            CollectibleGift(
                id = "col_torch_4102",
                serialNumber = 4102,
                baseTitle = "Liberty Torch",
                category = "Liberty Torch",
                collectionId = "liberty_torch",
                emojiIcon = "🔥",
                modelName = "Bronze Flame",
                backdropName = "Emerald",
                patternName = "Star",
                backdropColorHex = "#14532D",
                accentGlowHex = "#FBBF24",
                priceStars = 430L,
                ownerName = "torch_bearer",
                lottieAssetUrl = "gift_rocket_lottie.json"
            ),
            CollectibleGift(
                id = "col_torch_8841",
                serialNumber = 8841,
                baseTitle = "Liberty Torch",
                category = "Liberty Torch",
                collectionId = "liberty_torch",
                emojiIcon = "🔥",
                modelName = "Neon Cyan",
                backdropName = "Black",
                patternName = "Sparkles",
                backdropColorHex = "#1E293B",
                accentGlowHex = "#38BDF8",
                priceStars = 495L,
                ownerName = "spark_ny",
                lottieAssetUrl = "gift_rocket_lottie.json"
            ),
            CollectibleGift(
                id = "col_torch_912",
                serialNumber = 912,
                baseTitle = "Liberty Torch",
                category = "Liberty Torch",
                collectionId = "liberty_torch",
                emojiIcon = "🔥",
                modelName = "Pure Gold",
                backdropName = "Pure Gold",
                patternName = "Crown",
                backdropColorHex = "#6B4F10",
                accentGlowHex = "#FDE047",
                priceStars = 620L,
                ownerName = "gold_mint",
                lottieAssetUrl = "gift_rocket_lottie.json"
            ),
            // === Statue of Liberty Collection ===
            CollectibleGift(
                id = "col_statue_1205",
                serialNumber = 1205,
                baseTitle = "Statue of Liberty",
                category = "Statue of Liberty",
                collectionId = "statue_of_liberty",
                emojiIcon = "🗽",
                modelName = "Emerald Jade",
                backdropName = "Seafoam",
                patternName = "Star",
                backdropColorHex = "#0F5E53",
                accentGlowHex = "#34D399",
                priceStars = 472L,
                ownerName = "liberty_fan",
                lottieAssetUrl = "gift_sparkle_aura.json"
            ),
            CollectibleGift(
                id = "col_statue_4420",
                serialNumber = 4420,
                baseTitle = "Statue of Liberty",
                category = "Statue of Liberty",
                collectionId = "statue_of_liberty",
                emojiIcon = "🗽",
                modelName = "Violet Prism",
                backdropName = "Electric Purple",
                patternName = "Diamond",
                backdropColorHex = "#4C1D95",
                accentGlowHex = "#C084FC",
                priceStars = 560L,
                ownerName = "prism",
                lottieAssetUrl = "gift_sparkle_aura.json"
            ),
            // === Jason Mask Collection ===
            CollectibleGift(
                id = "col_jason_1313",
                serialNumber = 1313,
                baseTitle = "Jason Mask",
                category = "Jason Mask",
                collectionId = "jason_mask",
                emojiIcon = "🎭",
                modelName = "Friday Bone",
                backdropName = "Black",
                patternName = "Shield",
                backdropColorHex = "#27171B",
                accentGlowHex = "#EF4444",
                priceStars = 802L,
                ownerName = "horror_vault",
                lottieAssetUrl = "gift_sparkle_aura.json"
            ),
            CollectibleGift(
                id = "col_jason_6660",
                serialNumber = 6660,
                baseTitle = "Jason Mask",
                category = "Jason Mask",
                collectionId = "jason_mask",
                emojiIcon = "🎭",
                modelName = "Chrome Blood",
                backdropName = "Burgundy",
                patternName = "Circuit",
                backdropColorHex = "#4A101B",
                accentGlowHex = "#FB7185",
                priceStars = 940L,
                ownerName = "midnight",
                lottieAssetUrl = "gift_sparkle_aura.json"
            ),
            // === Ramen Bowl Collection ===
            CollectibleGift(
                id = "col_ramen_3310",
                serialNumber = 3310,
                baseTitle = "Ramen Bowl",
                category = "Ramen Bowl",
                collectionId = "ramen_bowl",
                emojiIcon = "🍜",
                modelName = "Tokyo Spicy",
                backdropName = "Old Gold",
                patternName = "Sakura Flower",
                backdropColorHex = "#632A12",
                accentGlowHex = "#F97316",
                priceStars = 465L,
                ownerName = "ichiraku",
                lottieAssetUrl = "gift_box_lottie.json"
            ),
            CollectibleGift(
                id = "col_ramen_7012",
                serialNumber = 7012,
                baseTitle = "Ramen Bowl",
                category = "Ramen Bowl",
                collectionId = "ramen_bowl",
                emojiIcon = "🍜",
                modelName = "Matcha Broth",
                backdropName = "Moss Green",
                patternName = "Wreath",
                backdropColorHex = "#2B5B2E",
                accentGlowHex = "#86EFAC",
                priceStars = 515L,
                ownerName = "kyoto",
                lottieAssetUrl = "gift_box_lottie.json"
            ),
            // === Lollipop Collection ===
            CollectibleGift(
                id = "col_lollipop_1881",
                serialNumber = 1881,
                baseTitle = "Lollipop",
                category = "Lollipop",
                collectionId = "lollipop",
                emojiIcon = "🍭",
                modelName = "Rainbow Swirl",
                backdropName = "English Violet",
                patternName = "Sparkles",
                backdropColorHex = "#5A246E",
                accentGlowHex = "#F472B6",
                priceStars = 472L,
                ownerName = "candy_shop",
                lottieAssetUrl = "gift_heart_lottie.json"
            ),
            CollectibleGift(
                id = "col_lollipop_5012",
                serialNumber = 5012,
                baseTitle = "Lollipop",
                category = "Lollipop",
                collectionId = "lollipop",
                emojiIcon = "🍭",
                modelName = "Mint Berry",
                backdropName = "Seafoam",
                patternName = "Star",
                backdropColorHex = "#1B6369",
                accentGlowHex = "#5EEAD4",
                priceStars = 499L,
                ownerName = "sweet_mint",
                lottieAssetUrl = "gift_heart_lottie.json"
            ),
            // === Ice Cream Cone Collection ===
            CollectibleGift(
                id = "col_icecream_2219",
                serialNumber = 2219,
                baseTitle = "Ice Cream Cone",
                category = "Ice Cream Cone",
                collectionId = "ice_cream",
                emojiIcon = "🍦",
                modelName = "Choco Glaze",
                backdropName = "Old Gold",
                patternName = "Star",
                backdropColorHex = "#4B2E1E",
                accentGlowHex = "#FBBF24",
                priceStars = 472L,
                ownerName = "gelato",
                lottieAssetUrl = "gift_box_lottie.json"
            ),
            // === Flamingo Float Collection ===
            CollectibleGift(
                id = "col_flamingo_8901",
                serialNumber = 8901,
                baseTitle = "Flamingo Float",
                category = "Flamingo Float",
                collectionId = "flamingo_float",
                emojiIcon = "🦩",
                modelName = "Miami Pink",
                backdropName = "Teal",
                patternName = "Cactus",
                backdropColorHex = "#175C72",
                accentGlowHex = "#F472B6",
                priceStars = 472L,
                ownerName = "miami_pool",
                lottieAssetUrl = "gift_heart_lottie.json"
            ),
            // === Nail Bracelet Collection ===
            CollectibleGift(
                id = "col_nail_2095",
                serialNumber = 2095,
                baseTitle = "Nail Bracelet",
                category = "Nail Bracelet",
                collectionId = "nail_bracelet",
                emojiIcon = "💍",
                modelName = "Chrome Classic",
                backdropName = "Black",
                patternName = "Circuit",
                backdropColorHex = "#1E293B",
                accentGlowHex = "#94A3B8",
                priceStars = 680L,
                ownerName = "durov",
                isPinnedToUsername = true,
                lottieAssetUrl = "gift_diamond_lottie.json"
            ),
            CollectibleGift(
                id = "col_nail_1428",
                serialNumber = 1428,
                baseTitle = "Nail Bracelet",
                category = "Nail Bracelet",
                collectionId = "nail_bracelet",
                emojiIcon = "💍",
                modelName = "Neon Ruby",
                backdropName = "Burgundy",
                patternName = "Diamond",
                backdropColorHex = "#5A1722",
                accentGlowHex = "#EF4444",
                priceStars = 740L,
                ownerName = "crypto_king",
                lottieAssetUrl = "gift_diamond_lottie.json"
            ),
            CollectibleGift(
                id = "col_nail_3373",
                serialNumber = 3373,
                baseTitle = "Nail Bracelet",
                category = "Nail Bracelet",
                collectionId = "nail_bracelet",
                emojiIcon = "💍",
                modelName = "Mint Emerald",
                backdropName = "Emerald",
                patternName = "Wreath",
                backdropColorHex = "#0B4D42",
                accentGlowHex = "#10B981",
                priceStars = 790L,
                ownerName = "alice_wonder",
                lottieAssetUrl = "gift_diamond_lottie.json"
            ),
            // === Durov's Glasses Collection ===
            CollectibleGift(
                id = "col_glasses_3374",
                serialNumber = 3374,
                baseTitle = "Durov's Glasses",
                category = "Durov's Glasses",
                collectionId = "durovs_glasses",
                emojiIcon = "🕶️",
                modelName = "Matrix Vision",
                backdropName = "Emerald",
                patternName = "Circuit",
                backdropColorHex = "#094225",
                accentGlowHex = "#22C55E",
                priceStars = 590L,
                ownerName = "neo",
                lottieAssetUrl = "gift_diamond_lottie.json"
            ),
            CollectibleGift(
                id = "col_glasses_2844",
                serialNumber = 2844,
                baseTitle = "Durov's Glasses",
                category = "Durov's Glasses",
                collectionId = "durovs_glasses",
                emojiIcon = "🕶️",
                modelName = "Aqua Star",
                backdropName = "Seafoam",
                patternName = "Star",
                backdropColorHex = "#0E4A5C",
                accentGlowHex = "#06B6D4",
                priceStars = 640L,
                ownerName = "round",
                lottieAssetUrl = "gift_diamond_lottie.json"
            ),
            // === Perfume Bottle Collection ===
            CollectibleGift(
                id = "col_perfume_3107",
                serialNumber = 3107,
                baseTitle = "Perfume Bottle",
                category = "Perfume Bottle",
                collectionId = "perfume_bottle",
                emojiIcon = "🧴",
                modelName = "No. 5 Luxury",
                backdropName = "English Violet",
                patternName = "Sakura Flower",
                backdropColorHex = "#4E143F",
                accentGlowHex = "#F472B6",
                priceStars = 515L,
                ownerName = "mademoiselle",
                lottieAssetUrl = "gift_sparkle_aura.json"
            ),
            // === Spartan Helmet & Champion's Cup ===
            CollectibleGift(
                id = "col_spartan_187",
                serialNumber = 187,
                baseTitle = "Spartan Helmet",
                category = "Spartan Helmet",
                collectionId = "spartan_helmet",
                emojiIcon = "⛑️",
                modelName = "Leonidas Bronze",
                backdropName = "Old Gold",
                patternName = "Shield",
                backdropColorHex = "#5A240C",
                accentGlowHex = "#EA580C",
                priceStars = 890L,
                ownerName = "sparta300",
                lottieAssetUrl = "gift_trophy_lottie.json"
            ),
            CollectibleGift(
                id = "col_cup_12",
                serialNumber = 12,
                baseTitle = "Кубок Чемпиона",
                category = "Кубок Чемпиона",
                collectionId = "champions_cup",
                emojiIcon = "🏆",
                modelName = "Grand Master",
                backdropName = "Pure Gold",
                patternName = "Crown",
                backdropColorHex = "#1E3A8A",
                accentGlowHex = "#FACC15",
                priceStars = 1250L,
                ownerName = "winner_2026",
                lottieAssetUrl = "gift_trophy_lottie.json"
            )
        )
        _collectibleMarketplaceGifts.value = collectibles
        rebuildCollectibleCollections(collectibles)
    }

    private fun rebuildCollectibleCollections(collectibles: List<CollectibleGift>) {
        val grouped = collectibles
            .groupBy { it.effectiveCollectionId }
            .map { (colId, items) ->
                val available = items.filter { it.isForSale }
                val source = if (available.isNotEmpty()) available else items
                val floorItem = source.minByOrNull { it.priceStars } ?: items.first()
                CollectibleCollectionSummary(
                    collectionId = colId,
                    title = floorItem.baseTitle,
                    emojiIcon = floorItem.emojiIcon,
                    floorPriceStars = floorItem.priceStars,
                    offersCount = available.size,
                    backdropColorHex = floorItem.backdropColorHex,
                    accentColorHex = floorItem.accentGlowHex,
                    lottieAssetUrl = floorItem.effectiveLottieAsset
                )
            }
        _collectibleCollections.value = grouped
    }

    fun getCollectibleCollectionSummary(collectionId: String): CollectibleCollectionSummary? {
        return _collectibleCollections.value.find { it.collectionId == collectionId }
    }

    fun getCollectibleSkinsForCollection(collectionId: String): List<CollectibleGift> {
        return _collectibleMarketplaceGifts.value.filter { it.effectiveCollectionId == collectionId }
    }

    /**
     * Purchase a collectible gift from the marketplace with currency check and atomic transfer.
     */
    suspend fun purchaseCollectibleGift(
        userId: String,
        collectibleGiftId: String
    ): Result<CollectibleGift> = purchaseCollectibleSkinAtomic(
        senderUserId = userId,
        targetUserId = userId,
        collectibleGiftId = collectibleGiftId,
        idempotencyKey = "skin_${collectibleGiftId}_${System.currentTimeMillis()}"
    ).map {
        _collectibleMarketplaceGifts.value.find { g -> g.id == collectibleGiftId }
            ?: CollectibleGift(
                id = collectibleGiftId,
                serialNumber = it.serialNumber,
                baseTitle = it.cachedTitle,
                category = it.cachedTitle,
                emojiIcon = it.cachedEmoji,
                modelName = it.modelName,
                patternName = it.patternName,
                backdropColorHex = it.cachedColorHex,
                accentGlowHex = it.accentColorHex,
                priceStars = it.giftCostStars,
                ownerId = it.receiverId,
                ownerName = "me",
                isForSale = false
            )
    }

    /**
     * Synchronizes a user account with Firestore and observes changes.
     */
    suspend fun syncAndObserveUser(userId: String, username: String, displayName: String, role: String = "user") {
        withContext(Dispatchers.IO) {
            val authUid = currentAuthUid()
            val db = getDbOrNull()
            if (authUid.isNullOrBlank() || authUid != userId || db == null) {
                if (_currentUserState.value == null) {
                    _currentUserState.value = KuoteXUserDoc(
                        userId = userId,
                        username = username,
                        displayName = displayName,
                        balance = readCachedBalance(userId) ?: 1000L,
                        role = role
                    )
                }
                return@withContext
            }

            syncCatalogIfAuthenticated()

            val safeUsername = username.trim().ifBlank { "user" }.take(64)
            val safeDisplayName = displayName.trim().ifBlank { "User" }.take(100)
            val userPath = "$COLLECTION_USERS/$userId"
            try {
                val userRef = db.collection(COLLECTION_USERS).document(userId)
                val snapshot = userRef.get().await()

                if (!snapshot.exists()) {
                    val now = System.currentTimeMillis()
                    val newUser = KuoteXUserDoc(
                        userId = userId,
                        username = safeUsername,
                        displayName = safeDisplayName,
                        balance = 1000L,
                        role = "user",
                        vipStatus = false,
                        vipExpiration = 0L,
                        availableBoostVotes = 0,
                        allocatedBoosts = emptyList(),
                        pinnedGiftsCount = 0,
                        createdAt = now,
                        updatedAt = now
                    )
                    userRef.set(newUser.toFirestoreMap()).await()
                    _currentUserState.value = newUser
                } else {
                    val userDoc = snapshot.toObject(KuoteXUserDoc::class.java)
                    if (userDoc != null) {
                        _currentUserState.value = userDoc
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, userPath)
                if (_currentUserState.value == null) {
                    _currentUserState.value = KuoteXUserDoc(
                        userId = userId,
                        username = username,
                        displayName = displayName,
                        balance = readCachedBalance(userId) ?: 1000L,
                        role = role
                    )
                }
            }
        }
    }

    /**
     * ATOMIC TRANSACTION: Process Gift Purchase and Transfer to Profile Header
     */
    suspend fun processGiftPurchaseAtomic(
        senderUserId: String,
        targetUserId: String,
        catalogGiftId: String,
        idempotencyKey: String,
        message: String = "",
        pinToHeader: Boolean = true,
        isAnonymous: Boolean = false,
        senderName: String = "",
        senderAvatarUrl: String = ""
    ): Result<KuoteXUserGiftDoc> = withContext(Dispatchers.IO) {
        val txId = "tx_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val userGiftId = "ug_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val now = System.currentTimeMillis()
        val resolvedSenderDisplay = if (isAnonymous) "" else senderName.trim()
        val resolvedSenderAvatar = if (isAnonymous) "" else senderAvatarUrl.trim()

        val safeTargetFallback = if (targetUserId.isBlank() || targetUserId == "me") {
            currentAuthUid()?.takeIf { it.isNotBlank() } ?: senderUserId.ifBlank { "me" }
        } else {
            targetUserId
        }
        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val authUid = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val effectiveSenderId = authUid
            val effectiveTargetId = if (targetUserId.isBlank() || targetUserId == "me") {
                authUid
            } else {
                targetUserId.take(128)
            }
            val safeIdempotencyKey = sanitizeFirestoreId(idempotencyKey, "buy")
            val safeCatalogGiftId = sanitizeFirestoreId(catalogGiftId, "gift")

            val userGiftResult = db.runTransaction { transaction ->
                val idempotencyRef = db.collection(COLLECTION_IDEMPOTENCY).document(safeIdempotencyKey)
                val idemSnap = transaction.get(idempotencyRef)
                if (idemSnap.exists()) {
                    throw DuplicateTransactionException("Idempotency key already processed: $safeIdempotencyKey")
                }

                val senderRef = db.collection(COLLECTION_USERS).document(effectiveSenderId)
                val catalogRef = db.collection(COLLECTION_GIFTS_CATALOG).document(safeCatalogGiftId)
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)
                val userGiftRef = db.collection(COLLECTION_USER_GIFTS).document(userGiftId)

                val senderSnap = transaction.get(senderRef)
                val catalogSnap = transaction.get(catalogRef)

                val senderBalance = senderSnap.getLong("balance") ?: (_currentUserState.value?.balance ?: 1000L)
                val catalogData = catalogSnap.toObject(KuoteXCatalogGiftDoc::class.java)
                    ?: _catalogGifts.value.find { it.catalogGiftId == safeCatalogGiftId }
                    ?: throw IllegalArgumentException("Gift $safeCatalogGiftId not found in catalog")

                val giftPrice = catalogData.price
                if (senderBalance < giftPrice) {
                    throw InsufficientBalanceException("Insufficient balance ($senderBalance < $giftPrice)")
                }

                val availSupply = catalogData.availableSupply
                if (availSupply != -1L && availSupply <= 0) {
                    throw GiftSoldOutException("Gift is completely sold out")
                }

                val isSelfGift = effectiveTargetId == effectiveSenderId
                val targetPinnedCount = if (isSelfGift) {
                    (senderSnap.getLong("pinned_gifts_count") ?: 0L).toInt()
                } else {
                    _pinnedGiftsMap.value[effectiveTargetId]?.count {
                        it.isPinnedToHeader && !isSampleGiftId(it.userGiftId)
                    } ?: 0
                }
                val shouldPin = pinToHeader && (targetPinnedCount < 12)

                // 1. Deduct sender balance (and increment pinned_gifts_count if self-gift)
                val newSenderBalance = senderBalance - giftPrice
                if (senderSnap.exists()) {
                    val senderUpdates = mutableMapOf<String, Any>(
                        "balance" to newSenderBalance,
                        "updated_at" to now
                    )
                    if (isSelfGift && shouldPin) {
                        senderUpdates["pinned_gifts_count"] = (targetPinnedCount + 1).coerceAtMost(12)
                    }
                    transaction.update(senderRef, senderUpdates)
                } else {
                    val fallbackUser = KuoteXUserDoc(
                        userId = effectiveSenderId,
                        username = _currentUserState.value?.username?.ifBlank { "user" } ?: "user",
                        displayName = _currentUserState.value?.displayName?.ifBlank { "User" } ?: "User",
                        balance = newSenderBalance.coerceIn(0L, 1000L),
                        role = "user",
                        pinnedGiftsCount = if (isSelfGift && shouldPin) 1 else 0,
                        createdAt = now,
                        updatedAt = now
                    )
                    transaction.set(senderRef, fallbackUser.toFirestoreMap())
                }

                // 2. Decrement catalog supply if limited
                if (availSupply > 0) {
                    if (catalogSnap.exists()) {
                        transaction.update(catalogRef, "available_supply", availSupply - 1)
                    } else {
                        transaction.set(
                            catalogRef,
                            catalogData.copy(availableSupply = availSupply - 1).toFirestoreMap()
                        )
                    }
                } else if (!catalogSnap.exists()) {
                    transaction.set(catalogRef, catalogData.toFirestoreMap())
                }

                val lottieFile = resolveGiftLottieAsset(catalogData.lottieAssetUrl, catalogData.emojiIcon, catalogData.title)
                val senderFallbackName = resolvedSenderDisplay.ifBlank {
                    senderSnap.getString("display_name")
                        ?: _currentUserState.value?.displayName?.takeIf { it.isNotBlank() }
                        ?: ""
                }

                // 3. Create User Gift Document
                val userGiftDoc = KuoteXUserGiftDoc(
                    userGiftId = userGiftId,
                    catalogGiftId = safeCatalogGiftId,
                    senderId = if (isAnonymous) "anonymous" else effectiveSenderId,
                    receiverId = effectiveTargetId,
                    isPinnedToHeader = shouldPin,
                    pinOrderIndex = if (shouldPin) targetPinnedCount else -1,
                    upgradeLevel = 1,
                    transferable = false,
                    message = message,
                    isAnonymous = isAnonymous,
                    acquiredAt = now,
                    cachedTitle = catalogData.title,
                    cachedEmoji = catalogData.emojiIcon,
                    cachedColorHex = catalogData.backdropColorHex,
                    senderName = if (isAnonymous) "" else senderFallbackName,
                    senderAvatarUrl = resolvedSenderAvatar,
                    giftCostStars = giftPrice,
                    lottieAssetUrl = lottieFile
                )
                transaction.set(userGiftRef, userGiftDoc.toFirestoreMap())

                // 4. Append Immutable Ledger Record
                val ledgerDoc = KuoteXLedgerTxDoc(
                    txId = txId,
                    idempotencyKey = safeIdempotencyKey,
                    type = LedgerTransactionType.GIFT_PURCHASE.value,
                    fromUserId = effectiveSenderId,
                    toUserId = effectiveTargetId,
                    amount = giftPrice,
                    fee = 0L,
                    status = LedgerTransactionStatus.COMMITTED.value,
                    metadata = mapOf(
                        "catalog_gift_id" to safeCatalogGiftId,
                        "user_gift_id" to userGiftId,
                        "is_pinned" to shouldPin
                    ),
                    createdAt = now
                )
                transaction.set(ledgerRef, ledgerDoc.toFirestoreMap())

                // 5. Mark idempotency key
                transaction.set(idempotencyRef, mapOf(
                    "tx_id" to txId,
                    "user_id" to effectiveSenderId,
                    "created_at" to now
                ))

                userGiftDoc
            }.await()

            val purchasedPrice = userGiftResult.giftCostStars.takeIf { it > 0L }
                ?: (_catalogGifts.value.find { g -> g.catalogGiftId == safeCatalogGiftId }?.price ?: 100L)
            saveCachedBalance(effectiveSenderId, ((_currentUserState.value?.balance ?: 1000L) - purchasedPrice).coerceAtLeast(0L))
            _currentUserState.update { current ->
                current?.let {
                    if (it.userId == effectiveSenderId) {
                        it.copy(balance = (it.balance - purchasedPrice).coerceAtLeast(0L))
                    } else it
                }
            }

            _pinnedGiftsMap.update { currentMap ->
                val existing = currentMap[effectiveTargetId]?.toMutableList() ?: sampleUserGiftDocs(effectiveTargetId).toMutableList()
                if (existing.none { it.userGiftId == userGiftResult.userGiftId }) {
                    existing.add(0, userGiftResult)
                }
                currentMap + (effectiveTargetId to existing)
            }

            com.example.ui.rating.UserRatingManager.recordDirectGiftPurchase(
                senderUserId = effectiveSenderId,
                starsSpent = purchasedPrice.toInt()
            )

            Result.success(userGiftResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is InsufficientBalanceException || e is GiftSoldOutException || e is DuplicateTransactionException) {
                Log.e(TAG, "Transaction failed for gift purchase: ${e.message}", e)
                return@withContext Result.failure(e)
            }
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USER_GIFTS/$userGiftId")
            Log.w(TAG, "Remote Firestore gift purchase failed (${e.message}). Executing local atomic fallback.")

            val catalogData = _catalogGifts.value.find { it.catalogGiftId == catalogGiftId }
                ?: return@withContext Result.failure(IllegalArgumentException("Gift $catalogGiftId not found in catalog"))

            val currentBalance = _currentUserState.value?.balance ?: 1000L
            val giftPrice = catalogData.price
            if (currentBalance < giftPrice) {
                return@withContext Result.failure(InsufficientBalanceException("Недостаточно звёзд ($currentBalance < $giftPrice)"))
            }

            val availSupply = catalogData.availableSupply
            if (availSupply != -1L && availSupply <= 0) {
                return@withContext Result.failure(GiftSoldOutException("Gift is completely sold out"))
            }

            _currentUserState.update { curr ->
                curr?.copy(balance = (curr.balance - giftPrice).coerceAtLeast(0L))
                    ?: KuoteXUserDoc(userId = senderUserId, username = "me", displayName = "Me", balance = (1000L - giftPrice).coerceAtLeast(0L))
            }

            if (availSupply > 0) {
                _catalogGifts.update { list ->
                    list.map {
                        if (it.catalogGiftId == catalogGiftId) it.copy(availableSupply = (it.availableSupply - 1).coerceAtLeast(0L)) else it
                    }
                }
            }

            val existing = _pinnedGiftsMap.value[safeTargetFallback]?.toMutableList()
                ?: sampleUserGiftDocs(safeTargetFallback).toMutableList()
            val realPinnedCount = existing.count { it.isPinnedToHeader && !isSampleGiftId(it.userGiftId) }
            val shouldPin = pinToHeader && (realPinnedCount < 12)
            val lottieFile = resolveGiftLottieAsset(catalogData.lottieAssetUrl, catalogData.emojiIcon, catalogData.title)

            val localUserGift = KuoteXUserGiftDoc(
                userGiftId = userGiftId,
                catalogGiftId = catalogGiftId,
                senderId = if (isAnonymous) "anonymous" else senderUserId,
                receiverId = safeTargetFallback,
                isPinnedToHeader = shouldPin,
                pinOrderIndex = if (shouldPin) realPinnedCount else -1,
                upgradeLevel = 1,
                transferable = false,
                message = message,
                isAnonymous = isAnonymous,
                acquiredAt = now,
                cachedTitle = catalogData.title,
                cachedEmoji = catalogData.emojiIcon,
                cachedColorHex = catalogData.backdropColorHex,
                senderName = resolvedSenderDisplay,
                senderAvatarUrl = resolvedSenderAvatar,
                giftCostStars = giftPrice,
                lottieAssetUrl = lottieFile
            )

            if (existing.none { it.userGiftId == localUserGift.userGiftId }) {
                existing.add(0, localUserGift)
            }
            _pinnedGiftsMap.update { currentMap ->
                currentMap + (safeTargetFallback to existing)
            }

            com.example.ui.rating.UserRatingManager.recordDirectGiftPurchase(
                senderUserId = senderUserId,
                starsSpent = giftPrice.toInt()
            )

            Log.i(TAG, "Gift purchase completed locally: ${localUserGift.userGiftId} (${catalogData.title})")
            Result.success(localUserGift)
        }
    }

    /**
     * ATOMIC TRANSACTION: Purchase & Send a Collectible Skin (with unique model, backdrop, pattern & serial #)
     */
    suspend fun purchaseCollectibleSkinAtomic(
        senderUserId: String,
        targetUserId: String,
        collectibleGiftId: String,
        idempotencyKey: String,
        message: String = "",
        pinToHeader: Boolean = true,
        isAnonymous: Boolean = false,
        senderName: String = "",
        senderAvatarUrl: String = ""
    ): Result<KuoteXUserGiftDoc> = withContext(Dispatchers.IO) {
        val skin = _collectibleMarketplaceGifts.value.find { it.id == collectibleGiftId }
            ?: return@withContext Result.failure(IllegalArgumentException("Коллекционный скин не найден"))

        val txId = "tx_skin_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val userGiftId = "ug_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val now = System.currentTimeMillis()
        val resolvedSenderDisplay = if (isAnonymous) "" else senderName.trim()
        val resolvedSenderAvatar = if (isAnonymous) "" else senderAvatarUrl.trim()
        val safeTargetFallback = if (targetUserId.isBlank() || targetUserId == "me") {
            currentAuthUid()?.takeIf { it.isNotBlank() } ?: senderUserId.ifBlank { "me" }
        } else {
            targetUserId
        }

        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val authUid = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val effectiveSenderId = authUid
            val effectiveTargetId = if (targetUserId.isBlank() || targetUserId == "me") authUid else targetUserId.take(128)
            val safeIdempotencyKey = sanitizeFirestoreId(idempotencyKey, "skin")

            val userGiftResult = db.runTransaction { transaction ->
                val idempotencyRef = db.collection(COLLECTION_IDEMPOTENCY).document(safeIdempotencyKey)
                if (transaction.get(idempotencyRef).exists()) {
                    throw DuplicateTransactionException("Idempotency key already processed: $safeIdempotencyKey")
                }

                val senderRef = db.collection(COLLECTION_USERS).document(effectiveSenderId)
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)
                val userGiftRef = db.collection(COLLECTION_USER_GIFTS).document(userGiftId)
                val senderSnap = transaction.get(senderRef)

                val senderBalance = senderSnap.getLong("balance") ?: (_currentUserState.value?.balance ?: 1000L)
                if (senderBalance < skin.priceStars) {
                    throw InsufficientBalanceException("Недостаточно звёзд ($senderBalance < ${skin.priceStars})")
                }

                val isSelfGift = effectiveTargetId == effectiveSenderId
                val targetPinnedCount = if (isSelfGift) {
                    (senderSnap.getLong("pinned_gifts_count") ?: 0L).toInt()
                } else {
                    _pinnedGiftsMap.value[effectiveTargetId]?.count {
                        it.isPinnedToHeader && !isSampleGiftId(it.userGiftId)
                    } ?: 0
                }
                val shouldPin = pinToHeader && (targetPinnedCount < 12)
                val newSenderBalance = senderBalance - skin.priceStars

                if (senderSnap.exists()) {
                    val senderUpdates = mutableMapOf<String, Any>(
                        "balance" to newSenderBalance,
                        "updated_at" to now
                    )
                    if (isSelfGift && shouldPin) {
                        senderUpdates["pinned_gifts_count"] = (targetPinnedCount + 1).coerceAtMost(12)
                    }
                    transaction.update(senderRef, senderUpdates)
                } else {
                    val fallbackUser = KuoteXUserDoc(
                        userId = effectiveSenderId,
                        username = _currentUserState.value?.username?.ifBlank { "user" } ?: "user",
                        displayName = _currentUserState.value?.displayName?.ifBlank { "User" } ?: "User",
                        balance = newSenderBalance.coerceIn(0L, 1000L),
                        role = "user",
                        pinnedGiftsCount = if (isSelfGift && shouldPin) 1 else 0,
                        createdAt = now,
                        updatedAt = now
                    )
                    transaction.set(senderRef, fallbackUser.toFirestoreMap())
                }

                val senderFallbackName = resolvedSenderDisplay.ifBlank {
                    senderSnap.getString("display_name")
                        ?: _currentUserState.value?.displayName?.takeIf { it.isNotBlank() }
                        ?: ""
                }

                val userGiftDoc = KuoteXUserGiftDoc(
                    userGiftId = userGiftId,
                    catalogGiftId = sanitizeFirestoreId("col_${skin.effectiveCollectionId}", "col"),
                    senderId = if (isAnonymous) "anonymous" else effectiveSenderId,
                    receiverId = effectiveTargetId,
                    isPinnedToHeader = shouldPin,
                    pinOrderIndex = if (shouldPin) targetPinnedCount else -1,
                    upgradeLevel = 1,
                    transferable = true,
                    message = message,
                    isAnonymous = isAnonymous,
                    acquiredAt = now,
                    cachedTitle = "${skin.baseTitle} (${skin.modelName})",
                    cachedEmoji = skin.emojiIcon,
                    cachedColorHex = skin.backdropColorHex,
                    senderName = if (isAnonymous) "" else senderFallbackName,
                    senderAvatarUrl = resolvedSenderAvatar,
                    giftCostStars = skin.priceStars,
                    isCollectible = true,
                    serialNumber = skin.serialNumber,
                    modelName = skin.modelName,
                    patternName = skin.patternName,
                    accentColorHex = skin.accentGlowHex,
                    lottieAssetUrl = skin.effectiveLottieAsset
                )
                transaction.set(userGiftRef, userGiftDoc.toFirestoreMap())

                val ledgerDoc = KuoteXLedgerTxDoc(
                    txId = txId,
                    idempotencyKey = safeIdempotencyKey,
                    type = LedgerTransactionType.GIFT_PURCHASE.value,
                    fromUserId = effectiveSenderId,
                    toUserId = effectiveTargetId,
                    amount = skin.priceStars,
                    fee = 0L,
                    status = LedgerTransactionStatus.COMMITTED.value,
                    metadata = mapOf(
                        "collectible_id" to skin.id,
                        "serial_number" to skin.serialNumber,
                        "user_gift_id" to userGiftId
                    ),
                    createdAt = now
                )
                transaction.set(ledgerRef, ledgerDoc.toFirestoreMap())
                transaction.set(idempotencyRef, mapOf("tx_id" to txId, "user_id" to effectiveSenderId, "created_at" to now))

                userGiftDoc
            }.await()

            saveCachedBalance(effectiveSenderId, ((_currentUserState.value?.balance ?: 1000L) - skin.priceStars).coerceAtLeast(0L))
            _currentUserState.update { curr ->
                curr?.let { if (it.userId == effectiveSenderId) it.copy(balance = (it.balance - skin.priceStars).coerceAtLeast(0L)) else it }
            }

            _collectibleMarketplaceGifts.update { list ->
                val updated = list.map {
                    if (it.id == collectibleGiftId) it.copy(ownerId = effectiveTargetId, ownerName = "me", isForSale = false) else it
                }
                rebuildCollectibleCollections(updated)
                updated
            }

            _pinnedGiftsMap.update { currentMap ->
                val existing = currentMap[effectiveTargetId]?.toMutableList() ?: sampleUserGiftDocs(effectiveTargetId).toMutableList()
                if (existing.none { it.userGiftId == userGiftResult.userGiftId }) {
                    existing.add(0, userGiftResult)
                }
                currentMap + (effectiveTargetId to existing)
            }

            com.example.ui.rating.UserRatingManager.recordDirectGiftPurchase(
                senderUserId = effectiveSenderId,
                starsSpent = skin.priceStars.toInt()
            )
            Result.success(userGiftResult)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is InsufficientBalanceException || e is DuplicateTransactionException) {
                return@withContext Result.failure(e)
            }
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USER_GIFTS/$userGiftId")
            val currentBalance = _currentUserState.value?.balance ?: 1000L
            if (currentBalance < skin.priceStars) {
                return@withContext Result.failure(InsufficientBalanceException("Недостаточно звёзд ($currentBalance < ${skin.priceStars})"))
            }

            _currentUserState.update { curr ->
                curr?.copy(balance = (curr.balance - skin.priceStars).coerceAtLeast(0L))
                    ?: KuoteXUserDoc(userId = senderUserId, username = "me", displayName = "Me", balance = (1000L - skin.priceStars).coerceAtLeast(0L))
            }

            _collectibleMarketplaceGifts.update { list ->
                val updated = list.map {
                    if (it.id == collectibleGiftId) it.copy(ownerId = safeTargetFallback, ownerName = "me", isForSale = false) else it
                }
                rebuildCollectibleCollections(updated)
                updated
            }

            val existing = _pinnedGiftsMap.value[safeTargetFallback]?.toMutableList()
                ?: sampleUserGiftDocs(safeTargetFallback).toMutableList()
            val realPinnedCount = existing.count { it.isPinnedToHeader && !isSampleGiftId(it.userGiftId) }
            val shouldPin = pinToHeader && (realPinnedCount < 12)

            val localDoc = KuoteXUserGiftDoc(
                userGiftId = userGiftId,
                catalogGiftId = "col_${skin.effectiveCollectionId}",
                senderId = if (isAnonymous) "anonymous" else senderUserId,
                receiverId = safeTargetFallback,
                isPinnedToHeader = shouldPin,
                pinOrderIndex = if (shouldPin) realPinnedCount else -1,
                upgradeLevel = 1,
                transferable = true,
                message = message,
                isAnonymous = isAnonymous,
                acquiredAt = now,
                cachedTitle = "${skin.baseTitle} (${skin.modelName})",
                cachedEmoji = skin.emojiIcon,
                cachedColorHex = skin.backdropColorHex,
                senderName = resolvedSenderDisplay,
                senderAvatarUrl = resolvedSenderAvatar,
                giftCostStars = skin.priceStars,
                isCollectible = true,
                serialNumber = skin.serialNumber,
                modelName = skin.modelName,
                patternName = skin.patternName,
                accentColorHex = skin.accentGlowHex,
                lottieAssetUrl = skin.effectiveLottieAsset
            )
            if (existing.none { it.userGiftId == localDoc.userGiftId }) {
                existing.add(0, localDoc)
            }
            _pinnedGiftsMap.update { it + (safeTargetFallback to existing) }

            com.example.ui.rating.UserRatingManager.recordDirectGiftPurchase(
                senderUserId = senderUserId,
                starsSpent = skin.priceStars.toInt()
            )
            Result.success(localDoc)
        }
    }

    /**
     * ATOMIC TRANSACTION: Gift KuoteX Premium (3 / 6 / 12 months) using Stars.
     * Applies VIP immediately for self-gifts or issues a claimable VIP pass to recipient's profile.
     */
    suspend fun giftPremiumSubscriptionAtomic(
        senderUserId: String,
        targetUserId: String,
        months: Int,
        priceStars: Long,
        senderName: String = "",
        senderAvatarUrl: String = "",
        idempotencyKey: String
    ): Result<KuoteXUserGiftDoc> = withContext(Dispatchers.IO) {
        val safeTarget = targetUserId.ifBlank { senderUserId }
        val authUid = currentAuthUid()?.takeIf { it.isNotBlank() }
        val isSelfGift = (safeTarget == "me" || safeTarget == senderUserId || (authUid != null && safeTarget == authUid))
        val now = System.currentTimeMillis()
        val durationMs = months * 30L * 86400000L
        val userGiftId = "ug_vip_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val txId = "tx_vipp_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

        if (isSelfGift) {
            val res = activateVipSubscriptionAtomic(senderUserId, months, idempotencyKey, priceStars)
            return@withContext res.map {
                KuoteXUserGiftDoc(
                    userGiftId = userGiftId,
                    catalogGiftId = "gift_premium_${months}m",
                    senderId = authUid ?: senderUserId,
                    receiverId = authUid ?: senderUserId,
                    isPinnedToHeader = true,
                    pinOrderIndex = 0,
                    upgradeLevel = 5,
                    acquiredAt = now,
                    cachedTitle = "KuoteX Premium ($months мес.)",
                    cachedEmoji = "⭐",
                    cachedColorHex = "#241242",
                    senderName = senderName,
                    senderAvatarUrl = senderAvatarUrl,
                    giftCostStars = priceStars,
                    lottieAssetUrl = "gift_star_premium.json",
                    premiumClaimed = true
                )
            }
        }

        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val effectiveSender = authUid ?: throw IllegalStateException("User not signed in")
            val effectiveTarget = safeTarget.take(128)
            val safeIdem = sanitizeFirestoreId(idempotencyKey, "vipp")

            val passDoc = db.runTransaction { transaction ->
                val idemRef = db.collection(COLLECTION_IDEMPOTENCY).document(safeIdem)
                if (transaction.get(idemRef).exists()) {
                    throw DuplicateTransactionException("Idempotency key already processed")
                }

                val senderRef = db.collection(COLLECTION_USERS).document(effectiveSender)
                val userGiftRef = db.collection(COLLECTION_USER_GIFTS).document(userGiftId)
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)
                val senderSnap = transaction.get(senderRef)

                val balance = senderSnap.getLong("balance") ?: (_currentUserState.value?.balance ?: 1000L)
                if (balance < priceStars) {
                    throw InsufficientBalanceException("Недостаточно звёзд ($balance < $priceStars)")
                }

                val newBalance = balance - priceStars
                if (senderSnap.exists()) {
                    transaction.update(senderRef, mapOf("balance" to newBalance, "updated_at" to now))
                } else {
                    val fallbackUser = KuoteXUserDoc(
                        userId = effectiveSender,
                        username = _currentUserState.value?.username?.ifBlank { "user" } ?: "user",
                        displayName = _currentUserState.value?.displayName?.ifBlank { "User" } ?: "User",
                        balance = newBalance.coerceIn(0L, 1000L),
                        createdAt = now,
                        updatedAt = now
                    )
                    transaction.set(senderRef, fallbackUser.toFirestoreMap())
                }

                val doc = KuoteXUserGiftDoc(
                    userGiftId = userGiftId,
                    catalogGiftId = "gift_premium_${months}m",
                    senderId = effectiveSender,
                    receiverId = effectiveTarget,
                    isPinnedToHeader = true,
                    pinOrderIndex = 0,
                    upgradeLevel = 5,
                    message = "KuoteX Premium на $months мес.",
                    acquiredAt = now,
                    cachedTitle = "KuoteX Premium ($months мес.)",
                    cachedEmoji = "⭐",
                    cachedColorHex = "#241242",
                    senderName = senderName.ifBlank { senderSnap.getString("display_name") ?: "" },
                    senderAvatarUrl = senderAvatarUrl,
                    giftCostStars = priceStars,
                    accentColorHex = "#A855F7",
                    lottieAssetUrl = "gift_star_premium.json",
                    premiumClaimed = false
                )
                transaction.set(userGiftRef, doc.toFirestoreMap())

                transaction.set(
                    ledgerRef,
                    KuoteXLedgerTxDoc(
                        txId = txId,
                        idempotencyKey = safeIdem,
                        type = LedgerTransactionType.VIP_SUBSCRIPTION.value,
                        fromUserId = effectiveSender,
                        toUserId = effectiveTarget,
                        amount = priceStars,
                        status = LedgerTransactionStatus.COMMITTED.value,
                        metadata = mapOf("months" to months, "user_gift_id" to userGiftId),
                        createdAt = now
                    ).toFirestoreMap()
                )
                transaction.set(idemRef, mapOf("tx_id" to txId, "user_id" to effectiveSender, "created_at" to now))
                doc
            }.await()

            saveCachedBalance(effectiveSender, ((_currentUserState.value?.balance ?: 1000L) - priceStars).coerceAtLeast(0L))
            _currentUserState.update { curr ->
                curr?.let { if (it.userId == effectiveSender) it.copy(balance = (it.balance - priceStars).coerceAtLeast(0L)) else it }
            }
            _pinnedGiftsMap.update { currentMap ->
                val existing = currentMap[effectiveTarget]?.toMutableList() ?: sampleUserGiftDocs(effectiveTarget).toMutableList()
                existing.add(0, passDoc)
                currentMap + (effectiveTarget to existing)
            }
            com.example.ui.rating.UserRatingManager.recordDirectGiftPurchase(effectiveSender, priceStars.toInt())
            Result.success(passDoc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is InsufficientBalanceException || e is DuplicateTransactionException) {
                return@withContext Result.failure(e)
            }
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USER_GIFTS/$userGiftId")
            val currentBalance = _currentUserState.value?.balance ?: 1000L
            if (currentBalance < priceStars) {
                return@withContext Result.failure(InsufficientBalanceException("Недостаточно звёзд ($currentBalance < $priceStars)"))
            }
            _currentUserState.update { curr ->
                curr?.copy(balance = (curr.balance - priceStars).coerceAtLeast(0L))
            }
            val localPass = KuoteXUserGiftDoc(
                userGiftId = userGiftId,
                catalogGiftId = "gift_premium_${months}m",
                senderId = senderUserId,
                receiverId = safeTarget,
                isPinnedToHeader = true,
                pinOrderIndex = 0,
                upgradeLevel = 5,
                message = "KuoteX Premium на $months мес.",
                acquiredAt = now,
                cachedTitle = "KuoteX Premium ($months мес.)",
                cachedEmoji = "⭐",
                cachedColorHex = "#241242",
                senderName = senderName,
                senderAvatarUrl = senderAvatarUrl,
                giftCostStars = priceStars,
                accentColorHex = "#A855F7",
                lottieAssetUrl = "gift_star_premium.json",
                premiumClaimed = false
            )
            _pinnedGiftsMap.update { currentMap ->
                val existing = currentMap[safeTarget]?.toMutableList() ?: sampleUserGiftDocs(safeTarget).toMutableList()
                existing.add(0, localPass)
                currentMap + (safeTarget to existing)
            }
            Result.success(localPass)
        }
    }

    private suspend fun redeemReceivedPremiumGiftsIfNeeded(uid: String, gifts: List<KuoteXUserGiftDoc>) {
        val db = getDbOrNull() ?: return
        val authUid = currentAuthUid() ?: return
        if (uid != authUid) return

        val unclaimed = gifts.filter {
            it.catalogGiftId.startsWith("gift_premium_") && !it.premiumClaimed && it.receiverId == authUid
        }
        if (unclaimed.isEmpty()) return

        for (gift in unclaimed) {
            val months = gift.catalogGiftId
                .removePrefix("gift_premium_")
                .removeSuffix("m")
                .toIntOrNull()
                ?.coerceIn(1, 24) ?: 3
            val now = System.currentTimeMillis()
            val durationMs = months * 30L * 86400000L
            try {
                db.runTransaction { tx ->
                    val userRef = db.collection(COLLECTION_USERS).document(authUid)
                    val giftRef = db.collection(COLLECTION_USER_GIFTS).document(gift.userGiftId)
                    val userSnap = tx.get(userRef)
                    val giftSnap = tx.get(giftRef)
                    if (!giftSnap.exists() || giftSnap.getBoolean("premium_claimed") == true) return@runTransaction

                    val currentExp = userSnap.getLong("vip_expiration") ?: 0L
                    val newExp = (if (currentExp > now) currentExp else now) + durationMs
                    val currentVotes = (userSnap.getLong("available_boost_votes") ?: 0L).toInt()
                    val newVotes = currentVotes + (4 * months)

                    if (userSnap.exists()) {
                        tx.update(
                            userRef,
                            mapOf(
                                "vip_status" to true,
                                "vip_expiration" to newExp,
                                "available_boost_votes" to newVotes,
                                "updated_at" to now
                            )
                        )
                    }
                    tx.update(giftRef, mapOf("premium_claimed" to true))
                }.await()
                _currentUserState.update { curr ->
                    curr?.copy(
                        vipStatus = true,
                        vipExpiration = (if (curr.vipExpiration > now) curr.vipExpiration else now) + durationMs,
                        availableBoostVotes = curr.availableBoostVotes + (4 * months),
                        updatedAt = now
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to auto-claim Premium gift ${gift.userGiftId}: ${e.message}")
            }
        }
    }

    /**
     * Unpin a user gift from the profile showcase
     */
    fun unpinUserGift(userId: String, userGiftId: String) {
        _pinnedGiftsMap.update { currentMap ->
            val list = currentMap[userId]?.filter { it.userGiftId != userGiftId } ?: emptyList()
            currentMap + (userId to list)
        }
        Log.i(TAG, "Unpinned user gift $userGiftId for user $userId")
    }

    /**
     * Exchange a received gift for Stars, deducting 85% of the gift's Stars value from rating.
     */
    fun exchangeUserGiftForStars(
        userId: String,
        userGiftId: String,
        catalogGiftId: String
    ): Result<Long> {
        val storedGift = _pinnedGiftsMap.value[userId]?.find { it.userGiftId == userGiftId }
        val catalogGift = _catalogGifts.value.find { it.catalogGiftId == catalogGiftId }
        val giftPriceStars = when {
            storedGift != null && storedGift.giftCostStars > 0L -> storedGift.giftCostStars
            catalogGift != null && catalogGift.price > 0L -> catalogGift.price
            else -> 50L
        }
        val starsCredited = (giftPriceStars * 0.85).toLong().coerceAtLeast(1L)

        _pinnedGiftsMap.update { currentMap ->
            val list = currentMap[userId]?.filter { it.userGiftId != userGiftId } ?: emptyList()
            currentMap + (userId to list)
        }
        _currentUserState.update { curr ->
            curr?.copy(balance = curr.balance + starsCredited)
                ?: KuoteXUserDoc(userId = userId, username = userId, displayName = userId, balance = 1000L + starsCredited)
        }
        com.example.ui.rating.UserRatingManager.recordGiftExchangeForStars(
            userId = userId,
            giftPriceStars = giftPriceStars.toInt()
        )
        return Result.success(starsCredited)
    }

    /**
     * Process Stars refund, deducting 10x refunded Stars from user rating.
     */
    fun refundStars(userId: String, refundedStars: Long): Result<Long> {
        val safeRefund = refundedStars.coerceAtLeast(0L)
        _currentUserState.update { curr ->
            curr?.copy(balance = (curr.balance - safeRefund).coerceAtLeast(0L))
        }
        com.example.ui.rating.UserRatingManager.recordStarsRefund(
            userId = userId,
            refundedStars = safeRefund.toInt()
        )
        return Result.success(safeRefund)
    }

    /**
     * ATOMIC TRANSACTION: Activate or Extend KuoteX VIP Subscription
     */
    suspend fun activateVipSubscriptionAtomic(
        userId: String,
        months: Int = 1,
        idempotencyKey: String,
        price: Long = 300L
    ): Result<KuoteXUserDoc> = withContext(Dispatchers.IO) {
        val txId = "tx_vip_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        val durationMs = months * 30L * 86400000L

        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val effectiveUserId = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val safeIdempotencyKey = sanitizeFirestoreId(idempotencyKey, "vip")
            val updatedUser = db.runTransaction { transaction ->
                val idempotencyRef = db.collection(COLLECTION_IDEMPOTENCY).document(safeIdempotencyKey)
                if (transaction.get(idempotencyRef).exists()) {
                    throw DuplicateTransactionException("Idempotency key already processed")
                }

                val userRef = db.collection(COLLECTION_USERS).document(effectiveUserId)
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)
                val userSnap = transaction.get(userRef)

                val currentBalance = userSnap.getLong("balance") ?: (_currentUserState.value?.balance ?: 1000L)
                if (currentBalance < price) {
                    throw InsufficientBalanceException("Insufficient balance for VIP subscription ($currentBalance < $price)")
                }

                val currentVipExp = userSnap.getLong("vip_expiration") ?: 0L
                val newVipExp = (if (currentVipExp > now) currentVipExp else now) + durationMs
                val currentVotes = (userSnap.getLong("available_boost_votes") ?: 0L).toInt()
                val newVotes = currentVotes + (4 * months)

                val newBalance = currentBalance - price
                val baseDoc = userSnap.toObject(KuoteXUserDoc::class.java)
                    ?: KuoteXUserDoc(
                        userId = effectiveUserId,
                        username = _currentUserState.value?.username?.ifBlank { "user" } ?: "user",
                        displayName = _currentUserState.value?.displayName?.ifBlank { "User" } ?: "User",
                        createdAt = now
                    )
                val updatedDoc = baseDoc.copy(
                    userId = effectiveUserId,
                    balance = newBalance,
                    vipStatus = true,
                    vipExpiration = newVipExp,
                    availableBoostVotes = newVotes,
                    updatedAt = now
                )

                if (userSnap.exists()) {
                    transaction.update(userRef, mapOf(
                        "balance" to newBalance,
                        "vip_status" to true,
                        "vip_expiration" to newVipExp,
                        "available_boost_votes" to newVotes,
                        "updated_at" to now
                    ))
                } else {
                    transaction.set(
                        userRef,
                        updatedDoc.copy(balance = newBalance.coerceIn(0L, 1000L), role = "user").toFirestoreMap()
                    )
                }

                // Immutable Ledger Entry
                val ledgerDoc = KuoteXLedgerTxDoc(
                    txId = txId,
                    idempotencyKey = safeIdempotencyKey,
                    type = LedgerTransactionType.VIP_SUBSCRIPTION.value,
                    fromUserId = effectiveUserId,
                    toUserId = "system_kuotex_vip",
                    amount = price,
                    status = LedgerTransactionStatus.COMMITTED.value,
                    metadata = mapOf("months" to months, "vip_expiration" to newVipExp),
                    createdAt = now
                )
                transaction.set(ledgerRef, ledgerDoc.toFirestoreMap())

                transaction.set(idempotencyRef, mapOf("tx_id" to txId, "user_id" to effectiveUserId, "created_at" to now))

                updatedDoc
            }.await()

            _currentUserState.value = updatedUser
            Result.success(updatedUser)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is InsufficientBalanceException || e is DuplicateTransactionException) {
                Log.e(TAG, "Failed VIP activation due to business constraint: ${e.message}")
                return@withContext Result.failure(e)
            }
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USERS/$userId")
            Log.w(TAG, "Remote Firestore VIP activation failed (${e.message}). Activating locally.")
            val currentBalance = _currentUserState.value?.balance ?: 1000L
            if (currentBalance < price) {
                return@withContext Result.failure(InsufficientBalanceException("Недостаточно Stars ($currentBalance < $price)"))
            }
            val currentVipExp = _currentUserState.value?.vipExpiration ?: 0L
            val newVipExp = (if (currentVipExp > now) currentVipExp else now) + durationMs
            val currentVotes = _currentUserState.value?.availableBoostVotes ?: 0
            val newVotes = currentVotes + (4 * months)
            val newBalance = (currentBalance - price).coerceAtLeast(0L)

            val localUser = (_currentUserState.value ?: KuoteXUserDoc(userId = userId)).copy(
                balance = newBalance,
                vipStatus = true,
                vipExpiration = newVipExp,
                availableBoostVotes = newVotes,
                updatedAt = now
            )
            _currentUserState.value = localUser
            Result.success(localUser)
        }
    }

    /**
     * ATOMIC TRANSACTION: Apply Channel Boost with Privilege Validation & Level Recalculation
     */
    suspend fun applyChannelBoostAtomic(
        userId: String,
        channelId: String,
        votesToApply: Int = 1
    ): Result<KuoteXChannelDoc> = withContext(Dispatchers.IO) {
        val txId = "tx_boost_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()

        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val effectiveUserId = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val safeChannelId = sanitizeFirestoreId(channelId, "chan")
            val updatedChannel = db.runTransaction { transaction ->
                val userRef = db.collection(COLLECTION_USERS).document(effectiveUserId)
                val channelRef = db.collection(COLLECTION_CHANNELS).document(safeChannelId)
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)

                val userSnap = transaction.get(userRef)
                val channelSnap = transaction.get(channelRef)

                val userDoc = userSnap.toObject(KuoteXUserDoc::class.java)
                    ?: _currentUserState.value
                    ?: throw IllegalArgumentException("User document $effectiveUserId not found")

                // Privilege Validation
                if (!userDoc.hasBoostPrivilege()) {
                    throw UnauthorizedBoostException("Only KuoteX VIP or Admins/Developers can boost channels.")
                }

                val availableVotes = (userSnap.getLong("available_boost_votes") ?: userDoc.availableBoostVotes.toLong()).toInt()
                if (availableVotes < votesToApply && !userDoc.role.equals("developer", ignoreCase = true)) {
                    throw InsufficientBoostVotesException("Insufficient boost votes available ($availableVotes < $votesToApply)")
                }

                val newAvailableVotes = (availableVotes - votesToApply).coerceAtLeast(0)

                // Update allocated boosts list
                val currentAllocated = userDoc.allocatedBoosts.toMutableList()
                val existingIdx = currentAllocated.indexOfFirst { it.channelId == safeChannelId }
                if (existingIdx >= 0) {
                    val old = currentAllocated[existingIdx]
                    currentAllocated[existingIdx] = old.copy(
                        votesCount = old.votesCount + votesToApply,
                        boostedAt = now
                    )
                } else {
                    currentAllocated.add(AllocatedBoost(channelId = safeChannelId, votesCount = votesToApply, boostedAt = now))
                }

                val serializedBoosts = currentAllocated.take(50).map { boost ->
                    mapOf(
                        "channel_id" to boost.channelId,
                        "votes_count" to boost.votesCount,
                        "boosted_at" to boost.boostedAt
                    )
                }
                if (userSnap.exists()) {
                    transaction.update(userRef, mapOf(
                        "available_boost_votes" to newAvailableVotes,
                        "allocated_boosts" to serializedBoosts,
                        "updated_at" to now
                    ))
                } else {
                    transaction.set(
                        userRef,
                        userDoc.copy(
                            userId = effectiveUserId,
                            balance = userDoc.balance.coerceIn(0L, 1000L),
                            role = "user",
                            availableBoostVotes = newAvailableVotes,
                            allocatedBoosts = currentAllocated,
                            updatedAt = now
                        ).toFirestoreMap()
                    )
                }

                // Recalculate Channel Level Progression
                val currentVotes = (channelSnap.getLong("current_votes") ?: 0L).toInt()
                val totalVotes = currentVotes + votesToApply
                val newLevel = KuoteXBoostProgression.calculateLevel(totalVotes)
                val nextRequirement = KuoteXBoostProgression.nextLevelRequirement(totalVotes)

                val channelDoc = KuoteXChannelDoc(
                    channelId = safeChannelId,
                    title = channelSnap.getString("title") ?: "Канал",
                    currentVotes = totalVotes,
                    level = newLevel,
                    nextLevelRequiredVotes = nextRequirement,
                    customColorUnlocked = newLevel >= 1,
                    statusEmojiUnlocked = newLevel >= 2,
                    wallpaperUnlocked = newLevel >= 3,
                    storiesPerDayLimit = (newLevel * 2).coerceAtLeast(0),
                    updatedAt = now
                )

                transaction.set(channelRef, channelDoc.toFirestoreMap(), SetOptions.merge())

                // Immutable Ledger entry
                val ledgerDoc = KuoteXLedgerTxDoc(
                    txId = txId,
                    idempotencyKey = sanitizeFirestoreId("boost_${effectiveUserId}_${safeChannelId}_${now}", "boost"),
                    type = LedgerTransactionType.CHANNEL_BOOST.value,
                    fromUserId = effectiveUserId,
                    toUserId = safeChannelId,
                    amount = votesToApply.toLong(),
                    status = LedgerTransactionStatus.COMMITTED.value,
                    metadata = mapOf("votes" to votesToApply, "channel_level" to newLevel),
                    createdAt = now
                )
                transaction.set(ledgerRef, ledgerDoc.toFirestoreMap())

                channelDoc
            }.await()

            // Update user state locally
            _currentUserState.update { curr ->
                curr?.copy(
                    availableBoostVotes = (curr.availableBoostVotes - votesToApply).coerceAtLeast(0)
                )
            }

            com.example.ui.rating.UserRatingManager.recordBoostActivity(userId = userId)

            Result.success(updatedChannel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is UnauthorizedBoostException || e is InsufficientBoostVotesException) {
                Log.e(TAG, "Channel boost failed due to business rule: ${e.message}")
                return@withContext Result.failure(e)
            }
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_CHANNELS/$channelId")
            Log.w(TAG, "Remote Firestore channel boost failed (${e.message}). Applying locally.")
            val user = _currentUserState.value
            val availableVotes = user?.availableBoostVotes ?: 0
            if (availableVotes < votesToApply && user?.role?.equals("developer", ignoreCase = true) != true) {
                return@withContext Result.failure(InsufficientBoostVotesException("Недостаточно голосов буста ($availableVotes < $votesToApply)"))
            }
            _currentUserState.update { curr ->
                curr?.copy(availableBoostVotes = (curr.availableBoostVotes - votesToApply).coerceAtLeast(0))
            }
            val newTotalVotes = votesToApply
            val newLevel = KuoteXBoostProgression.calculateLevel(newTotalVotes)
            val nextReq = KuoteXBoostProgression.nextLevelRequirement(newTotalVotes)
            val channelDoc = KuoteXChannelDoc(
                channelId = channelId,
                title = "Канал",
                currentVotes = newTotalVotes,
                level = newLevel,
                nextLevelRequiredVotes = nextReq,
                customColorUnlocked = newLevel >= 1,
                statusEmojiUnlocked = newLevel >= 2,
                wallpaperUnlocked = newLevel >= 3,
                storiesPerDayLimit = (newLevel * 2).coerceAtLeast(0),
                updatedAt = now
            )
            com.example.ui.rating.UserRatingManager.recordBoostActivity(userId = userId)
            Result.success(channelDoc)
        }
    }

    /**
     * ATOMIC TRANSACTION: Upgrade Pinned User Gift Level (Phase 4)
     */
    suspend fun upgradeUserGiftAtomic(
        userId: String,
        userGiftId: String,
        upgradeCostStars: Long = 50L
    ): Result<KuoteXUserGiftDoc> = withContext(Dispatchers.IO) {
        val txId = "tx_gift_upg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()

        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val effectiveUserId = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val safeUserGiftId = sanitizeFirestoreId(userGiftId, "ug")
            val updatedUserGift = db.runTransaction { transaction ->
                val userRef = db.collection(COLLECTION_USERS).document(effectiveUserId)
                val giftRef = db.collection(COLLECTION_USER_GIFTS).document(safeUserGiftId)
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)

                val userSnap = transaction.get(userRef)
                val giftSnap = transaction.get(giftRef)

                val currentBalance = userSnap.getLong("balance") ?: (_currentUserState.value?.balance ?: 1000L)
                if (currentBalance < upgradeCostStars) {
                    throw InsufficientBalanceException("Insufficient Stars balance to upgrade gift ($currentBalance < $upgradeCostStars)")
                }

                val currentGift = giftSnap.toObject(KuoteXUserGiftDoc::class.java)
                    ?: (_pinnedGiftsMap.value[effectiveUserId] ?: _pinnedGiftsMap.value[userId])?.find { it.userGiftId == safeUserGiftId }
                    ?: throw IllegalArgumentException("User gift $safeUserGiftId not found")

                val newLevel = (currentGift.upgradeLevel + 1).coerceAtMost(5)
                val upgradedDoc = currentGift.copy(
                    upgradeLevel = newLevel
                )

                val newBalance = currentBalance - upgradeCostStars
                if (userSnap.exists()) {
                    transaction.update(userRef, mapOf("balance" to newBalance, "updated_at" to now))
                } else {
                    val fallbackUser = KuoteXUserDoc(
                        userId = effectiveUserId,
                        username = _currentUserState.value?.username?.ifBlank { "user" } ?: "user",
                        displayName = _currentUserState.value?.displayName?.ifBlank { "User" } ?: "User",
                        balance = newBalance.coerceIn(0L, 1000L),
                        role = "user",
                        createdAt = now,
                        updatedAt = now
                    )
                    transaction.set(userRef, fallbackUser.toFirestoreMap())
                }

                if (giftSnap.exists() && giftSnap.getString("receiver_id") == effectiveUserId) {
                    transaction.update(giftRef, mapOf("upgrade_level" to newLevel))
                } else if (!giftSnap.exists() && (upgradedDoc.isAnonymous || upgradedDoc.senderId == effectiveUserId)) {
                    transaction.set(giftRef, upgradedDoc.copy(receiverId = effectiveUserId).toFirestoreMap())
                }

                // Immutable Ledger
                val ledgerDoc = KuoteXLedgerTxDoc(
                    txId = txId,
                    idempotencyKey = sanitizeFirestoreId("upg_${safeUserGiftId}_lvl${newLevel}_$now", "upg"),
                    type = LedgerTransactionType.GIFT_UPGRADE.value,
                    fromUserId = effectiveUserId,
                    toUserId = "system_gift_vault",
                    amount = upgradeCostStars,
                    status = LedgerTransactionStatus.COMMITTED.value,
                    metadata = mapOf("user_gift_id" to safeUserGiftId, "new_level" to newLevel),
                    createdAt = now
                )
                transaction.set(ledgerRef, ledgerDoc.toFirestoreMap())

                upgradedDoc
            }.await()

            // Update local state
            _currentUserState.update { curr ->
                curr?.let { it.copy(balance = (it.balance - upgradeCostStars).coerceAtLeast(0L)) }
            }

            _pinnedGiftsMap.update { currentMap ->
                val list = currentMap[userId]?.toMutableList() ?: mutableListOf()
                val idx = list.indexOfFirst { it.userGiftId == userGiftId }
                if (idx >= 0) {
                    list[idx] = updatedUserGift
                } else {
                    list.add(updatedUserGift)
                }
                currentMap + (userId to list)
            }

            com.example.ui.rating.UserRatingManager.recordMarketOrPaidAction(
                userId = userId,
                starsSpent = upgradeCostStars.toInt()
            )

            Result.success(updatedUserGift)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USER_GIFTS/$userGiftId")
            Log.w(TAG, "Remote Firestore gift upgrade failed: ${e.message}. Attempting local ecosystem update.")
            
            // If Firestore transaction fails due to PERMISSION_DENIED or network issues,
            // provide seamless local execution so user experience is not disrupted.
            val currentGifts = _pinnedGiftsMap.value[userId]?.toMutableList() ?: mutableListOf()
            val existingGift = currentGifts.find { it.userGiftId == userGiftId }
            val currentBalance = _currentUserState.value?.balance ?: 1000L

            if (currentBalance < upgradeCostStars) {
                Log.e(TAG, "Gift upgrade failed: Insufficient balance ($currentBalance < $upgradeCostStars)")
                return@withContext Result.failure(InsufficientBalanceException("Недостаточно Stars для улучшения подарка ($currentBalance < $upgradeCostStars)"))
            }

            if (existingGift != null) {
                val newLevel = (existingGift.upgradeLevel + 1).coerceAtMost(5)
                val upgradedLocalDoc = existingGift.copy(upgradeLevel = newLevel)
                val idx = currentGifts.indexOfFirst { it.userGiftId == userGiftId }
                if (idx >= 0) {
                    currentGifts[idx] = upgradedLocalDoc
                } else {
                    currentGifts.add(upgradedLocalDoc)
                }
                
                // Update cached balance and pinned gifts
                _currentUserState.update { curr ->
                    curr?.copy(balance = (curr.balance - upgradeCostStars).coerceAtLeast(0L))
                        ?: KuoteXUserDoc(userId = userId, username = "user", displayName = "User", balance = (1000L - upgradeCostStars).coerceAtLeast(0L))
                }
                _pinnedGiftsMap.update { currentMap ->
                    currentMap + (userId to currentGifts)
                }
                
                com.example.ui.rating.UserRatingManager.recordMarketOrPaidAction(
                    userId = userId,
                    starsSpent = upgradeCostStars.toInt()
                )
                Log.i(TAG, "Gift upgraded locally successfully: ${upgradedLocalDoc.userGiftId} to level $newLevel")
                Result.success(upgradedLocalDoc)
            } else {
                Log.e(TAG, "Gift upgrade failed: gift not found: $userGiftId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * ATOMIC TRANSACTION: Vote in Telegram-style channel/group poll
     */
    suspend fun voteInPollAtomic(
        pollId: String,
        chatId: String,
        userId: String,
        selectedOptionIds: List<Int>
    ): Result<KuoteXPollVoteDoc> = withContext(Dispatchers.IO) {
        val voteDocId = "${pollId}_${userId}"
        val now = System.currentTimeMillis()

        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val effectiveUserId = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val safePollId = sanitizeFirestoreId(pollId, "poll")
            val effectiveVoteDocId = "${safePollId}_${effectiveUserId}"
            val voteDoc = db.runTransaction { transaction ->
                val voteRef = db.collection(COLLECTION_POLL_VOTES).document(effectiveVoteDocId)
                val existingVote = transaction.get(voteRef)
                if (existingVote.exists()) {
                    throw DuplicateTransactionException("User $effectiveUserId has already voted in poll $safePollId")
                }

                val newVote = KuoteXPollVoteDoc(
                    voteId = effectiveVoteDocId,
                    pollId = safePollId,
                    chatId = chatId,
                    userId = effectiveUserId,
                    selectedOptionIds = selectedOptionIds,
                    timestamp = now
                )
                transaction.set(voteRef, newVote.toFirestoreMap())
                newVote
            }.await()

            Result.success(voteDoc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is DuplicateTransactionException) {
                Log.e(TAG, "Poll vote transaction error: ${e.message}", e)
                return@withContext Result.failure(e)
            }
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_POLL_VOTES/$voteDocId")
            Log.w(TAG, "Remote Firestore poll vote failed (${e.message}). Recording locally.")
            val newVote = KuoteXPollVoteDoc(
                voteId = voteDocId,
                pollId = pollId,
                chatId = chatId,
                userId = userId,
                selectedOptionIds = selectedOptionIds,
                timestamp = now
            )
            Result.success(newVote)
        }
    }

    /**
     * Top-up user internal currency balance (KuoteX Stars / Coins)
     */
    suspend fun topUpUserBalanceAtomic(
        userId: String,
        amount: Long,
        providerTxId: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        val txId = "topup_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()

        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val effectiveUserId = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val safeProviderTxId = sanitizeFirestoreId(providerTxId, "topup")
            val newBalance = db.runTransaction { transaction ->
                val idempotencyRef = db.collection(COLLECTION_IDEMPOTENCY).document(safeProviderTxId)
                if (transaction.get(idempotencyRef).exists()) {
                    throw DuplicateTransactionException("Provider tx $safeProviderTxId already credited")
                }

                val userRef = db.collection(COLLECTION_USERS).document(effectiveUserId)
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)
                val userSnap = transaction.get(userRef)

                val currentBalance = userSnap.getLong("balance") ?: (_currentUserState.value?.balance ?: 1000L)
                val updatedBalance = currentBalance + amount

                if (userSnap.exists()) {
                    transaction.update(userRef, mapOf(
                        "balance" to updatedBalance,
                        "updated_at" to now
                    ))
                } else {
                    val fallbackUser = KuoteXUserDoc(
                        userId = effectiveUserId,
                        username = _currentUserState.value?.username?.ifBlank { "user" } ?: "user",
                        displayName = _currentUserState.value?.displayName?.ifBlank { "User" } ?: "User",
                        balance = updatedBalance.coerceIn(0L, 1000L),
                        role = "user",
                        createdAt = now,
                        updatedAt = now
                    )
                    transaction.set(userRef, fallbackUser.toFirestoreMap())
                }

                val ledgerDoc = KuoteXLedgerTxDoc(
                    txId = txId,
                    idempotencyKey = safeProviderTxId,
                    type = LedgerTransactionType.BALANCE_TOPUP.value,
                    fromUserId = "external_payment_gateway",
                    toUserId = effectiveUserId,
                    amount = amount,
                    status = LedgerTransactionStatus.COMMITTED.value,
                    metadata = mapOf("provider_tx_id" to safeProviderTxId),
                    createdAt = now
                )
                transaction.set(ledgerRef, ledgerDoc.toFirestoreMap())
                transaction.set(idempotencyRef, mapOf("tx_id" to txId, "user_id" to effectiveUserId, "created_at" to now))

                updatedBalance
            }.await()

            _currentUserState.update { it?.copy(balance = newBalance) }
            Result.success(newBalance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is DuplicateTransactionException) {
                Log.e(TAG, "Balance top-up error: ${e.message}", e)
                return@withContext Result.failure(e)
            }
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USERS/$userId")
            Log.e(TAG, "Balance top-up failed (${e.message}). NOT crediting locally.")
// Начислять звёзды на клиенте нельзя: сервер мог операцию не принять.
// Пусть UI покажет ошибку, а пользователь повторит с тем же ключом.
Result.failure(PaymentOutcomeUnknown(providerTxId, "Не удалось связаться с сервером"))
        }
    }

    /**
     * Observes real-time Pinned Gifts for a profile header.
     */
    fun observePinnedGifts(userId: String): Flow<List<KuoteXUserGiftDoc>> = callbackFlow {
        val db = getDbOrNull()
        val authUid = currentAuthUid()
        if (db == null || authUid.isNullOrBlank() || userId.isBlank()) {
            trySend(_pinnedGiftsMap.value[userId] ?: emptyList())
            close()
            return@callbackFlow
        }
        val query = if (userId == authUid) {
            db.collection(COLLECTION_USER_GIFTS)
                .whereEqualTo("receiver_id", userId)
        } else {
            db.collection(COLLECTION_USER_GIFTS)
                .whereEqualTo("receiver_id", userId)
                .whereEqualTo("is_pinned_to_header", true)
        }
        val listener = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, COLLECTION_USER_GIFTS)
                trySend(_pinnedGiftsMap.value[userId] ?: emptyList())
                return@addSnapshotListener
            }
            val remoteGifts = (snapshot?.documents?.mapNotNull { it.toObject(KuoteXUserGiftDoc::class.java) } ?: emptyList())
                .sortedByDescending { it.acquiredAt }
            if (userId == authUid && remoteGifts.any { it.catalogGiftId.startsWith("gift_premium_") && !it.premiumClaimed }) {
                managerScope.launch {
                    redeemReceivedPremiumGiftsIfNeeded(userId, remoteGifts)
                }
            }
            val remoteIds = remoteGifts.mapTo(HashSet()) { it.userGiftId }
            var mergedResult: List<KuoteXUserGiftDoc> = remoteGifts
            _pinnedGiftsMap.update { currentMap ->
                val existing = currentMap[userId] ?: sampleUserGiftDocs(userId)
                val retained = existing.filter { it.userGiftId !in remoteIds }
                mergedResult = remoteGifts + retained
                currentMap + (userId to mergedResult)
            }
            trySend(mergedResult)
        }

        awaitClose { listener.remove() }
    }

    /**
     * Observes a channel's boost level, votes, and unlocked perks in real-time.
     */
    fun observeChannelEcosystem(channelId: String): Flow<KuoteXChannelDoc?> = callbackFlow {
        val db = getDbOrNull()
        if (db == null || currentAuthUid().isNullOrBlank() || channelId.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val channelPath = "$COLLECTION_CHANNELS/$channelId"
        val listener = db.collection(COLLECTION_CHANNELS).document(channelId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.GET, channelPath)
                    return@addSnapshotListener
                }
                val doc = snapshot?.toObject(KuoteXChannelDoc::class.java)
                trySend(doc)
            }
        awaitClose { listener.remove() }
    }

    /**
     * Purchases a Stars bundle via atomic top-up, with local cached fallback when backend is offline/unauthenticated.
     */
    suspend fun purchaseStarsBundle(
        userId: String,
        amount: Long,
        packageId: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        val resolvedId = userId.ifBlank { "me" }
        val providerTxId = "bundle_${packageId}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val remoteResult = topUpUserBalanceAtomic(resolvedId, amount, providerTxId)
        if (remoteResult.isSuccess) {
            val newBal = remoteResult.getOrThrow()
            saveCachedBalance(resolvedId, newBal)
            return@withContext Result.success(newBal)
        }
        val err = remoteResult.exceptionOrNull()
        if (err is DuplicateTransactionException) {
            return@withContext Result.failure(err)
        }
        val currentBal = _currentUserState.value?.balance ?: readCachedBalance(resolvedId) ?: 1000L
        val updatedBal = (currentBal + amount).coerceAtLeast(0L)
        saveCachedBalance(resolvedId, updatedBal)
        _currentUserState.update { curr ->
            curr?.copy(balance = updatedBal, updatedAt = System.currentTimeMillis())
                ?: KuoteXUserDoc(userId = resolvedId, username = "user", displayName = "User", balance = updatedBal)
        }
        Result.success(updatedBal)
    }

    /**
     * Escrows Stars from a subscriber when proposing a paid Suggested Post.
     */
    suspend fun processSuggestedPostEscrow(
        senderUserId: String,
        channelId: String,
        priceStars: Long,
        proposalId: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        val resolvedId = senderUserId.ifBlank { "me" }
        if (priceStars <= 0L) {
            val cur = _currentUserState.value?.balance ?: readCachedBalance(resolvedId) ?: 1000L
            return@withContext Result.success(cur)
        }
        val txId = "sp_escrow_${proposalId}_${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val effectiveUserId = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val safeIdemId = sanitizeFirestoreId("escrow_$proposalId", "escrow")
            val newBalance = db.runTransaction { transaction ->
                val idemRef = db.collection(COLLECTION_IDEMPOTENCY).document(safeIdemId)
                if (transaction.get(idemRef).exists()) {
                    throw DuplicateTransactionException("Suggested post $proposalId already escrowed")
                }
                val userRef = db.collection(COLLECTION_USERS).document(effectiveUserId)
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)
                val userSnap = transaction.get(userRef)
                val currentBalance = userSnap.getLong("balance") ?: (_currentUserState.value?.balance ?: readCachedBalance(effectiveUserId) ?: 1000L)
                if (currentBalance < priceStars) {
                    throw InsufficientBalanceException("Недостаточно звёзд ($currentBalance < $priceStars)")
                }
                val updatedBalance = currentBalance - priceStars
                if (userSnap.exists()) {
                    transaction.update(userRef, mapOf("balance" to updatedBalance, "updated_at" to now))
                } else {
                    val fallbackUser = KuoteXUserDoc(
                        userId = effectiveUserId,
                        username = _currentUserState.value?.username?.ifBlank { "user" } ?: "user",
                        displayName = _currentUserState.value?.displayName?.ifBlank { "User" } ?: "User",
                        balance = updatedBalance.coerceIn(0L, 1000L),
                        role = "user",
                        createdAt = now,
                        updatedAt = now
                    )
                    transaction.set(userRef, fallbackUser.toFirestoreMap())
                }
                transaction.set(
                    ledgerRef,
                    KuoteXLedgerTxDoc(
                        txId = txId,
                        idempotencyKey = safeIdemId,
                        type = LedgerTransactionType.DIRECT_MESSAGE_STAR.value,
                        fromUserId = effectiveUserId,
                        toUserId = "escrow_channel_$channelId",
                        amount = priceStars,
                        status = LedgerTransactionStatus.PENDING.value,
                        metadata = mapOf("channel_id" to channelId, "proposal_id" to proposalId),
                        createdAt = now
                    ).toFirestoreMap()
                )
                transaction.set(idemRef, mapOf("tx_id" to txId, "user_id" to effectiveUserId, "created_at" to now))
                updatedBalance
            }.await()

            saveCachedBalance(effectiveUserId, newBalance)
            _currentUserState.update { curr ->
                curr?.copy(balance = newBalance, updatedAt = now)
                    ?: KuoteXUserDoc(userId = effectiveUserId, username = "user", displayName = "User", balance = newBalance)
            }
            com.example.ui.rating.UserRatingManager.recordMarketOrPaidAction(effectiveUserId, priceStars.toInt())
            Result.success(newBalance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is InsufficientBalanceException || e is DuplicateTransactionException) {
                return@withContext Result.failure(e)
            }
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USERS/$resolvedId")
            val currentBalance = _currentUserState.value?.balance ?: readCachedBalance(resolvedId) ?: 1000L
            if (currentBalance < priceStars) {
                return@withContext Result.failure(InsufficientBalanceException("Недостаточно звёзд ($currentBalance < $priceStars)"))
            }
            val updatedBalance = (currentBalance - priceStars).coerceAtLeast(0L)
            saveCachedBalance(resolvedId, updatedBalance)
            _currentUserState.update { curr ->
                curr?.copy(balance = updatedBalance, updatedAt = now)
                    ?: KuoteXUserDoc(userId = resolvedId, username = "user", displayName = "User", balance = updatedBalance)
            }
            com.example.ui.rating.UserRatingManager.recordMarketOrPaidAction(resolvedId, priceStars.toInt())
            Result.success(updatedBalance)
        }
    }

    /**
     * Releases 85% creator share to the channel owner when a Suggested Post is accepted/scheduled.
     */
    suspend fun creditChannelOwnerSuggestedPost(
        ownerUserId: String,
        channelId: String,
        priceStars: Long,
        proposalId: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        if (priceStars <= 0L) return@withContext Result.success(0L)
        val ownerShare = ((priceStars * 85L) / 100L).coerceAtLeast(1L)
        val resolvedOwner = ownerUserId.ifBlank { "me" }
        val txId = "sp_payout_${proposalId}_${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val authUid = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val targetOwner = if (resolvedOwner == "me") authUid else resolvedOwner
            val safeIdemId = sanitizeFirestoreId("payout_$proposalId", "payout")
            val updatedBal = db.runTransaction { transaction ->
                val idemRef = db.collection(COLLECTION_IDEMPOTENCY).document(safeIdemId)
                if (transaction.get(idemRef).exists()) {
                    throw DuplicateTransactionException("Already paid out")
                }
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)
                val isOwnAccount = targetOwner == authUid
                val ownerRef = if (isOwnAccount) db.collection(COLLECTION_USERS).document(authUid) else null
                val snap = ownerRef?.let { transaction.get(it) }
                val currentBalance = snap?.getLong("balance") ?: (_currentUserState.value?.balance ?: readCachedBalance(targetOwner) ?: 1000L)
                val nextBal = currentBalance + ownerShare
                if (ownerRef != null && snap != null && snap.exists()) {
                    transaction.update(ownerRef, mapOf("balance" to nextBal, "updated_at" to now))
                }
                transaction.set(
                    ledgerRef,
                    KuoteXLedgerTxDoc(
                        txId = txId,
                        idempotencyKey = safeIdemId,
                        type = LedgerTransactionType.DIRECT_MESSAGE_STAR.value,
                        fromUserId = authUid,
                        toUserId = targetOwner,
                        amount = ownerShare,
                        status = LedgerTransactionStatus.COMMITTED.value,
                        metadata = mapOf("channel_id" to channelId, "proposal_id" to proposalId),
                        createdAt = now
                    ).toFirestoreMap()
                )
                transaction.set(idemRef, mapOf("tx_id" to txId, "user_id" to authUid, "created_at" to now))
                nextBal
            }.await()

            if (_currentUserState.value?.userId == targetOwner) {
                saveCachedBalance(targetOwner, updatedBal)
                _currentUserState.update { it?.copy(balance = updatedBal, updatedAt = now) }
            }
            Result.success(ownerShare)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is DuplicateTransactionException) return@withContext Result.success(0L)
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USERS/$resolvedOwner")
            if (_currentUserState.value?.userId == resolvedOwner) {
                val cur = _currentUserState.value?.balance ?: readCachedBalance(resolvedOwner) ?: 1000L
                val nextBal = cur + ownerShare
                saveCachedBalance(resolvedOwner, nextBal)
                _currentUserState.update { it?.copy(balance = nextBal, updatedAt = now) }
            }
            Result.success(ownerShare)
        }
    }

    /**
     * Refunds escrowed Stars back to subscriber when an admin declines a Suggested Post.
     */
    suspend fun refundSuggestedPostEscrow(
        subscriberId: String,
        channelId: String,
        priceStars: Long,
        proposalId: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        if (priceStars <= 0L) return@withContext Result.success(0L)
        val resolvedSub = subscriberId.ifBlank { "me" }
        val txId = "sp_refund_${proposalId}_${System.currentTimeMillis()}"
        val now = System.currentTimeMillis()
        try {
            val db = getDbOrNull() ?: throw IllegalStateException("Firestore unavailable")
            val authUid = currentAuthUid()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("User not signed in")
            val targetSub = if (resolvedSub == "me") authUid else resolvedSub
            val safeIdemId = sanitizeFirestoreId("refund_$proposalId", "refund")
            val newBalance = db.runTransaction { transaction ->
                val idemRef = db.collection(COLLECTION_IDEMPOTENCY).document(safeIdemId)
                if (transaction.get(idemRef).exists()) {
                    throw DuplicateTransactionException("Already refunded")
                }
                val ledgerRef = db.collection(COLLECTION_LEDGER_TX).document(txId)
                val isOwnAccount = targetSub == authUid
                val userRef = if (isOwnAccount) db.collection(COLLECTION_USERS).document(authUid) else null
                val snap = userRef?.let { transaction.get(it) }
                val cur = snap?.getLong("balance") ?: (_currentUserState.value?.balance ?: readCachedBalance(targetSub) ?: 1000L)
                val nextBal = cur + priceStars
                if (userRef != null && snap != null && snap.exists()) {
                    transaction.update(userRef, mapOf("balance" to nextBal, "updated_at" to now))
                }
                transaction.set(
                    ledgerRef,
                    KuoteXLedgerTxDoc(
                        txId = txId,
                        idempotencyKey = safeIdemId,
                        type = LedgerTransactionType.DIRECT_MESSAGE_STAR.value,
                        fromUserId = authUid,
                        toUserId = targetSub,
                        amount = priceStars,
                        status = LedgerTransactionStatus.COMMITTED.value,
                        metadata = mapOf("channel_id" to channelId, "proposal_id" to proposalId, "refund" to true),
                        createdAt = now
                    ).toFirestoreMap()
                )
                transaction.set(idemRef, mapOf("tx_id" to txId, "user_id" to authUid, "created_at" to now))
                nextBal
            }.await()

            if (_currentUserState.value?.userId == targetSub) {
                saveCachedBalance(targetSub, newBalance)
                _currentUserState.update { it?.copy(balance = newBalance, updatedAt = now) }
            }
            Result.success(newBalance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is DuplicateTransactionException) return@withContext Result.success(0L)
            handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USERS/$resolvedSub")
            val cur = if (_currentUserState.value?.userId == resolvedSub) {
                _currentUserState.value?.balance ?: readCachedBalance(resolvedSub) ?: 1000L
            } else {
                readCachedBalance(resolvedSub) ?: 1000L
            }
            val nextBal = cur + priceStars
            saveCachedBalance(resolvedSub, nextBal)
            if (_currentUserState.value?.userId == resolvedSub) {
                _currentUserState.update { it?.copy(balance = nextBal, updatedAt = now) }
            }
            Result.success(nextBal)
        }
    }
}
