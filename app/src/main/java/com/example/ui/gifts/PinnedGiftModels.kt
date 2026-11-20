package com.example.ui.gifts

import androidx.compose.ui.graphics.Color
import com.example.data.ecosystem.KuoteXCatalogGiftDoc
import com.example.data.ecosystem.KuoteXUserGiftDoc

/**
 * PinnedGift - Client-side UI Model for Profile Header Pinned Exclusive Gifts.
 * Seamlessly maps backend gift IDs, upgrade levels, and animation / vector configurations.
 */
data class PinnedGift(
    val id: String,
    val catalogGiftId: String,
    val title: String,
    val senderName: String,
    val receiverId: String,
    val upgradeLevel: Int = 1,
    val maxUpgradeLevel: Int = 5,
    val animationAssetUrl: String = "",
    val vectorConfigString: String = "",
    val backdropColorHex: String = "#1E1B4B",
    val accentGlowHex: String = "#7C3AED",
    val emojiIcon: String = "🎁",
    val isExclusive: Boolean = true,
    val pinOrderIndex: Int = 0,
    val message: String = "",
    val acquiredAt: Long = System.currentTimeMillis(),
    val senderId: String = "",
    val senderAvatarUrl: String = "",
    val isAnonymous: Boolean = false,
    val costStars: Long = 50L,
    val isCollectible: Boolean = false,
    val serialNumber: Int = 0,
    val modelName: String = "",
    val patternName: String = "",
    val accentColorHex: String = ""
) {
    /**
     * Rating / Rarity based on upgrade level and exclusivity.
     */
    val rarityTier: RarityTier
        get() = when {
            upgradeLevel >= 5 -> RarityTier.MYTHIC
            upgradeLevel >= 4 -> RarityTier.LEGENDARY
            upgradeLevel >= 3 -> RarityTier.EPIC
            isExclusive || isCollectible -> RarityTier.EXCLUSIVE
            else -> RarityTier.RARE
        }

    val upgradeStars: String
        get() = "★".repeat(upgradeLevel.coerceIn(1, 5))

    val parsedBackdropColor: Color
        get() = parseHexColor(backdropColorHex, Color(0xFF1E1B4B))

    val parsedAccentColor: Color
        get() = parseHexColor(accentColorHex.ifBlank { accentGlowHex }, Color(0xFF8B5CF6))

    val formattedSerial: String
        get() = if (serialNumber > 0) "#%,d".format(serialNumber) else ""

    fun toUserGiftDoc(targetUserId: String = receiverId): KuoteXUserGiftDoc {
        val anon = isAnonymous || senderName == "Аноним" || senderName == "anonymous"
        return KuoteXUserGiftDoc(
            userGiftId = id,
            catalogGiftId = catalogGiftId,
            senderId = if (anon) "anonymous" else senderId.ifBlank { senderName },
            receiverId = targetUserId,
            isPinnedToHeader = true,
            pinOrderIndex = pinOrderIndex,
            upgradeLevel = upgradeLevel,
            transferable = isCollectible,
            message = message,
            isAnonymous = anon,
            acquiredAt = acquiredAt,
            cachedTitle = title,
            cachedEmoji = emojiIcon,
            cachedColorHex = backdropColorHex,
            senderName = if (anon) "" else senderName,
            senderAvatarUrl = if (anon) "" else senderAvatarUrl,
            giftCostStars = costStars,
            isCollectible = isCollectible,
            serialNumber = serialNumber,
            modelName = modelName,
            patternName = patternName,
            accentColorHex = accentColorHex.ifBlank { accentGlowHex },
            lottieAssetUrl = animationAssetUrl
        )
    }

    companion object {
        private fun parseHexColor(hex: String?, fallback: Color): Color {
            if (hex.isNullOrBlank()) return fallback
            return try {
                val clean = hex.removePrefix("#")
                val colorInt = if (clean.length == 6) {
                    (0xFF000000 or clean.toLong(16)).toInt()
                } else {
                    clean.toLong(16).toInt()
                }
                Color(colorInt)
            } catch (_: Exception) {
                fallback
            }
        }

        /**
         * Seamlessly maps from Firestore KuoteXUserGiftDoc & Catalog metadata.
         */
        fun fromUserGift(
            userGift: KuoteXUserGiftDoc,
            catalog: KuoteXCatalogGiftDoc? = null
        ): PinnedGift {
            val title = catalog?.title?.takeIf { !userGift.isCollectible } ?: userGift.cachedTitle.ifBlank { catalog?.title ?: "Подарок" }
            val emoji = userGift.cachedEmoji.ifBlank { catalog?.emojiIcon ?: "🎁" }
            val backdrop = userGift.cachedColorHex.ifBlank { catalog?.backdropColorHex ?: "#1E1B4B" }
            val isExcl = catalog?.isExclusive ?: userGift.isCollectible
            val lottie = userGift.lottieAssetUrl.ifBlank {
                resolveGiftLottieAsset(catalog?.lottieAssetUrl, emoji, title)
            }

            val accent = userGift.accentColorHex.ifBlank {
                when (userGift.upgradeLevel) {
                    5 -> "#FFD700" // Gold / Mythic
                    4 -> "#F43F5E" // Crimson / Legendary
                    3 -> "#A855F7" // Purple / Epic
                    2 -> "#3B82F6" // Blue / Rare
                    else -> "#10B981" // Emerald
                }
            }

            val anon = userGift.isAnonymous || userGift.senderId.equals("anonymous", ignoreCase = true)
            val resolvedSenderName = SenderIdentityResolver.resolveSenderName(
                rawSenderId = userGift.senderId,
                storedSenderName = userGift.senderName,
                isAnonymous = anon
            )

            val resolvedCost = when {
                userGift.giftCostStars > 0L -> userGift.giftCostStars
                catalog != null && catalog.price > 0L -> catalog.price
                else -> 50L
            }

            return PinnedGift(
                id = userGift.userGiftId,
                catalogGiftId = userGift.catalogGiftId,
                title = title,
                senderName = resolvedSenderName,
                receiverId = userGift.receiverId,
                upgradeLevel = userGift.upgradeLevel,
                maxUpgradeLevel = catalog?.maxUpgradeLevel ?: 5,
                animationAssetUrl = lottie,
                vectorConfigString = "M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5",
                backdropColorHex = backdrop,
                accentGlowHex = accent,
                emojiIcon = emoji,
                isExclusive = isExcl,
                pinOrderIndex = userGift.pinOrderIndex,
                message = userGift.message,
                acquiredAt = userGift.acquiredAt,
                senderId = userGift.senderId,
                senderAvatarUrl = if (anon) "" else userGift.senderAvatarUrl,
                isAnonymous = anon,
                costStars = resolvedCost,
                isCollectible = userGift.isCollectible,
                serialNumber = userGift.serialNumber,
                modelName = userGift.modelName,
                patternName = userGift.patternName,
                accentColorHex = userGift.accentColorHex
            )
        }

        /**
         * Default sample pinned gifts for profile demonstration.
         */
        fun samplePinnedGifts(): List<PinnedGift> = listOf(
            PinnedGift(
                id = "ug_cyber_dragon_sample",
                catalogGiftId = "gift_cyber_dragon_001",
                title = "Cyber Dragon 2026",
                senderName = "KuoteX Official",
                receiverId = "me",
                upgradeLevel = 5,
                maxUpgradeLevel = 5,
                animationAssetUrl = "gift_rocket_lottie.json",
                backdropColorHex = "#1E1B4B",
                accentGlowHex = "#FFD700",
                emojiIcon = "🐉",
                isExclusive = true,
                pinOrderIndex = 0,
                message = "Эксклюзивный подарок основателю KuoteX!",
                acquiredAt = System.currentTimeMillis() - 86400000L * 3,
                costStars = 250L
            ),
            PinnedGift(
                id = "ug_golden_crown_sample",
                catalogGiftId = "gift_golden_crown_002",
                title = "Золотая Корона VIP",
                senderName = "Alexey M.",
                receiverId = "me",
                upgradeLevel = 3,
                maxUpgradeLevel = 5,
                animationAssetUrl = "gift_trophy_lottie.json",
                backdropColorHex = "#281904",
                accentGlowHex = "#F59E0B",
                emojiIcon = "👑",
                isExclusive = true,
                pinOrderIndex = 1,
                message = "С днем рождения!",
                acquiredAt = System.currentTimeMillis() - 86400000L * 10,
                costStars = 150L
            ),
            PinnedGift(
                id = "ug_neon_diamond_sample",
                catalogGiftId = "gift_neon_diamond_003",
                title = "Неоновый Алмаз",
                senderName = "Elena",
                receiverId = "me",
                upgradeLevel = 2,
                maxUpgradeLevel = 3,
                animationAssetUrl = "gift_diamond_lottie.json",
                backdropColorHex = "#062826",
                accentGlowHex = "#06B6D4",
                emojiIcon = "💎",
                isExclusive = false,
                pinOrderIndex = 2,
                message = "За отличную работу над проектом 🚀",
                acquiredAt = System.currentTimeMillis() - 86400000L * 15,
                costStars = 100L
            ),
            PinnedGift(
                id = "ug_cosmic_rocket_sample",
                catalogGiftId = "gift_cosmic_rocket_004",
                title = "Космическая Ракета",
                senderName = "Аноним",
                receiverId = "me",
                upgradeLevel = 1,
                maxUpgradeLevel = 3,
                animationAssetUrl = "gift_rocket_lottie.json",
                backdropColorHex = "#1E1035",
                accentGlowHex = "#EC4899",
                emojiIcon = "🚀",
                isExclusive = false,
                pinOrderIndex = 3,
                message = "Только вперед!",
                acquiredAt = System.currentTimeMillis() - 86400000L * 25,
                isAnonymous = true,
                costStars = 50L
            )
        )
    }
}

enum class RarityTier(val title: String, val colorHex: String) {
    COMMON("Обычный", "#94A3B8"),
    RARE("Редкий", "#3B82F6"),
    EPIC("Эпический", "#A855F7"),
    LEGENDARY("Легендарный", "#F43F5E"),
    MYTHIC("Мифический", "#FFD700"),
    EXCLUSIVE("Эксклюзив", "#F59E0B")
}

/**
 * Currency types for switching payment between Stars, USD, RUB, and EUR.
 */
enum class CurrencyType(val symbol: String, val title: String, val starsConversionRate: Double) {
    STARS("⭐", "Звёзды", 1.0),
    USD("$", "USD", 0.02),      // 1 Star ~ $0.02
    RUB("₽", "RUB", 1.8),       // 1 Star ~ 1.8 ₽
    EUR("€", "EUR", 0.018);     // 1 Star ~ 0.018 €

    fun formatPrice(starsAmount: Long): String {
        return when (this) {
            STARS -> "%,d ⭐".format(starsAmount)
            USD -> {
                val converted = starsAmount * starsConversionRate
                "$%.2f".format(converted)
            }
            RUB -> {
                val converted = (starsAmount * starsConversionRate).toLong()
                "%,d ₽".format(converted)
            }
            EUR -> {
                val converted = starsAmount * starsConversionRate
                "€%.2f".format(converted)
            }
        }
    }
}

/**
 * Collectible unique gifts with serial numbering (#1, #22,770, etc.), pattern/backdrop,
 * model attributes, Lottie animation, and marketplace trading price in Stars.
 */
data class CollectibleGift(
    val id: String,
    val serialNumber: Int,
    val baseTitle: String,
    val category: String,
    val emojiIcon: String,
    val modelName: String,
    val patternName: String,
    val backdropColorHex: String,
    val accentGlowHex: String,
    val priceStars: Long,
    val ownerId: String = "",
    val ownerName: String = "",
    val isForSale: Boolean = true,
    val isPinnedToUsername: Boolean = false,
    val collectionId: String = "",
    val backdropName: String = "",
    val rarityGemTier: Int = 1,
    val lottieAssetUrl: String = "",
    val acceptsStarsOnly: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    val formattedSerialNumber: String
        get() = "#%,d".format(serialNumber)

    val effectiveCollectionId: String
        get() = collectionId.ifBlank { category.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') }

    val effectiveBackdropName: String
        get() = backdropName.ifBlank { "Classic Dark" }

    val effectiveLottieAsset: String
        get() = lottieAssetUrl.ifBlank { resolveGiftLottieAsset("", emojiIcon, baseTitle) }

    val parsedBackdropColor: Color
        get() = try {
            Color(android.graphics.Color.parseColor(backdropColorHex))
        } catch (_: Exception) {
            Color(0xFF1E1B4B)
        }

    val parsedAccentColor: Color
        get() = try {
            Color(android.graphics.Color.parseColor(accentGlowHex))
        } catch (_: Exception) {
            Color(0xFFFFD700)
        }
}

/**
 * Summary card for a Collectible Collection shown in the main GiftMarketplace grid with green "маркет" ribbon.
 */
data class CollectibleCollectionSummary(
    val collectionId: String,
    val title: String,
    val emojiIcon: String,
    val floorPriceStars: Long,
    val offersCount: Int,
    val backdropColorHex: String,
    val accentColorHex: String,
    val lottieAssetUrl: String
) {
    val parsedBackdropColor: Color
        get() = try {
            Color(android.graphics.Color.parseColor(backdropColorHex))
        } catch (_: Exception) {
            Color(0xFF16161C)
        }

    val parsedAccentColor: Color
        get() = try {
            Color(android.graphics.Color.parseColor(accentColorHex))
        } catch (_: Exception) {
            Color(0xFF8B5CF6)
        }
}

