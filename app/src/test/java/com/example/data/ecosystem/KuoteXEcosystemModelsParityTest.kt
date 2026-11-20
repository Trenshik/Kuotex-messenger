package com.example.data.ecosystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuoteXEcosystemModelsParityTest {

    @Test
    fun userDocToFirestoreMapMatchesUserProfileRules() {
        val doc = KuoteXUserDoc(
            userId = "7kOEGhwCt4Qfo3C2uAZj3Oq5KXK2",
            username = "",
            displayName = "",
            balance = 1000L,
            role = "user",
            vipStatus = false,
            vipExpiration = 0L,
            availableBoostVotes = 0,
            allocatedBoosts = emptyList(),
            pinnedGiftsCount = 0,
            createdAt = 1700000000000L,
            updatedAt = 1700000000000L
        )
        val map = doc.toFirestoreMap()
        val expectedKeys = setOf(
            "username", "display_name", "balance", "role",
            "vip_status", "vip_expiration", "available_boost_votes",
            "allocated_boosts", "pinned_gifts_count", "created_at", "updated_at"
        )
        assertEquals(expectedKeys, map.keys)
        assertFalse(map.containsKey("user_id"))
        assertFalse(map.containsKey("userId"))
        assertFalse(map.containsKey("vipActive"))
        assertFalse(map.containsKey("parsedRole"))
        assertEquals("user", map["username"])
        assertEquals("User", map["display_name"])
    }

    @Test
    fun catalogGiftToFirestoreMapMatchesCatalogRules() {
        val gift = KuoteXCatalogGiftDoc(
            catalogGiftId = "gift_cyber_dragon_001",
            title = "Cyber Dragon 2026",
            price = 250L,
            totalSupply = 1000L,
            availableSupply = 782L,
            isExclusive = true,
            lottieAssetUrl = "cyber_dragon.json",
            backdropColorHex = "#1E1B4B",
            emojiIcon = "🐉",
            maxUpgradeLevel = 5,
            createdAt = 1700000000000L
        )
        val map = gift.toFirestoreMap()
        val expectedKeys = setOf(
            "title", "price", "total_supply", "available_supply",
            "is_exclusive", "lottie_asset_url", "backdrop_color_hex",
            "emoji_icon", "max_upgrade_level", "created_at"
        )
        assertEquals(expectedKeys, map.keys)
        assertFalse(map.containsKey("catalog_gift_id"))
        assertFalse(map.containsKey("catalogGiftId"))
        assertTrue(map["is_exclusive"] as Boolean)
    }

    @Test
    fun userGiftLedgerChannelAndPollVoteMapsMatchRules() {
        val userGift = KuoteXUserGiftDoc(
            userGiftId = "ug_123",
            catalogGiftId = "gift_cyber_dragon_001",
            senderId = "u1",
            receiverId = "u2",
            isPinnedToHeader = true,
            pinOrderIndex = 0,
            upgradeLevel = 1,
            transferable = false,
            message = "Hi",
            isAnonymous = false,
            acquiredAt = 1700000000000L
        ).toFirestoreMap()
        assertEquals(
            setOf(
                "catalog_gift_id", "sender_id", "receiver_id",
                "is_pinned_to_header", "pin_order_index", "upgrade_level",
                "transferable", "message", "is_anonymous", "acquired_at",
                "cached_title", "cached_emoji", "cached_color_hex",
                "gift_cost_stars"
            ),
            userGift.keys
        )

        val collectibleUserGift = KuoteXUserGiftDoc(
            userGiftId = "ug_skin_123",
            catalogGiftId = "col_mirage_lamp",
            senderId = "u1",
            receiverId = "u2",
            isPinnedToHeader = true,
            pinOrderIndex = 0,
            upgradeLevel = 1,
            transferable = true,
            message = "Skin gift",
            isAnonymous = false,
            acquiredAt = 1700000000000L,
            senderName = "Gubka",
            senderAvatarUrl = "https://example.com/avatar.jpg",
            giftCostStars = 471L,
            isCollectible = true,
            serialNumber = 22770,
            modelName = "Genie Lantern",
            patternName = "Scarab",
            accentColorHex = "#C084FC",
            lottieAssetUrl = "gift_lamp_lottie.json",
            premiumClaimed = true
        ).toFirestoreMap()
        assertEquals(
            setOf(
                "catalog_gift_id", "sender_id", "receiver_id",
                "is_pinned_to_header", "pin_order_index", "upgrade_level",
                "transferable", "message", "is_anonymous", "acquired_at",
                "cached_title", "cached_emoji", "cached_color_hex",
                "sender_name", "sender_avatar_url", "gift_cost_stars",
                "is_collectible", "serial_number", "model_name",
                "pattern_name", "accent_color_hex", "lottie_asset_url",
                "premium_claimed"
            ),
            collectibleUserGift.keys
        )

        val ledger = KuoteXLedgerTxDoc(
            txId = "tx_123",
            idempotencyKey = "idem_1",
            type = LedgerTransactionType.GIFT_PURCHASE.value,
            fromUserId = "u1",
            toUserId = "u2",
            amount = 250L,
            fee = 0L,
            status = LedgerTransactionStatus.COMMITTED.value,
            createdAt = 1700000000000L
        ).toFirestoreMap()
        assertEquals(
            setOf(
                "idempotency_key", "type", "from_user_id", "to_user_id",
                "amount", "fee", "status", "metadata", "created_at"
            ),
            ledger.keys
        )

        val channel = KuoteXChannelDoc(
            channelId = "c_1",
            title = "Channel",
            currentVotes = 5,
            level = 1,
            nextLevelRequiredVotes = 10,
            customColorUnlocked = true,
            updatedAt = 1700000000000L
        ).toFirestoreMap()
        assertEquals(
            setOf(
                "title", "current_votes", "level", "next_level_required_votes",
                "custom_color_unlocked", "status_emoji_unlocked",
                "wallpaper_unlocked", "stories_per_day_limit", "updated_at"
            ),
            channel.keys
        )

        val vote = KuoteXPollVoteDoc(
            voteId = "poll_1_u1",
            pollId = "poll_1",
            chatId = "c_1",
            userId = "u1",
            selectedOptionIds = listOf(0),
            timestamp = 1700000000000L
        ).toFirestoreMap()
        assertEquals(
            setOf("poll_id", "chat_id", "user_id", "selected_option_ids", "timestamp"),
            vote.keys
        )
    }
}
