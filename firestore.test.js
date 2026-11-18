const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function directChatId(a, b) {
  return a < b ? `direct_${a}_${b}` : `direct_${b}_${a}`;
}

function validUserPayload(overrides = {}) {
  const now = Date.now() - 1000;
  return {
    username: "alice",
    display_name: "Alice KuoteX",
    balance: 1000,
    role: "user",
    vip_status: false,
    vip_expiration: 0,
    available_boost_votes: 0,
    allocated_boosts: [],
    pinned_gifts_count: 0,
    created_at: now,
    updated_at: now,
    ...overrides,
  };
}

function validChatPayload(overrides = {}) {
  return {
    id: directChatId(ALICE_UID, BOB_UID),
    participants: [ALICE_UID, BOB_UID],
    participantNames: {
      [ALICE_UID]: "Alice",
      [BOB_UID]: "Bob",
    },
    lastMessage: "",
    lastMessageTimestamp: Date.now() - 1000,
    lastSenderId: "",
    unreadCounts: {
      [ALICE_UID]: 0,
      [BOB_UID]: 0,
    },
    createdAt: new Date(Date.now() - 2000),
    isDirect: true,
    ...overrides,
  };
}

function validMessagePayload(chatId, senderId, overrides = {}) {
  return {
    id: "msg_1",
    chatId,
    senderId,
    receiverId: BOB_UID,
    text: "Hello KuoteX!",
    timestamp: Date.now() - 1000,
    isDelivered: true,
    isRead: false,
    createdAt: new Date(Date.now() - 1000),
    ...overrides,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// =========================================================================
// 1. UNAUTHENTICATED DENIALS ACROSS ALL COLLECTIONS
// =========================================================================

test("Unauthenticated user: denied across all collections and subcollections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const chatId = directChatId(ALICE_UID, BOB_UID);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(validUserPayload()));
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("presence").doc("status").get()
  );
  await assertFails(
    unauthDb.collection("chats").where("participants", "array-contains", ALICE_UID).get()
  );
  await assertFails(unauthDb.collection("chats").doc(chatId).set(validChatPayload()));
  await assertFails(
    unauthDb.collection("chats").doc(chatId).collection("messages").doc("msg_1").get()
  );
  await assertFails(unauthDb.collection("channels").doc("chan_1").get());
  await assertFails(unauthDb.collection("gifts_catalog").doc("gift_1").get());
  await assertFails(
    unauthDb
      .collection("user_gifts")
      .where("receiver_id", "==", ALICE_UID)
      .where("is_pinned_to_header", "==", true)
      .get()
  );
  await assertFails(
    unauthDb.collection("ledger_transactions").where("from_user_id", "==", ALICE_UID).get()
  );
  await assertFails(unauthDb.collection("idempotency_keys").doc("key_1").get());
  await assertFails(
    unauthDb.collection("poll_votes").where("user_id", "==", ALICE_UID).get()
  );
});

// =========================================================================
// 2. USER PROFILES (/users/{userId}) & PRESENCE (/users/{userId}/presence/status)
// =========================================================================

test("Users collection: owner create, partial updates, privilege guard & cross-user isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Create mirrors KuoteXUserDoc (@DocumentId userId stripped on write)
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validUserPayload()));
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());

  // Partial updates matching KuoteXEcosystemFirestoreManager
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      balance: 700,
      vip_status: true,
      vip_expiration: Date.now() + 2592000000,
      available_boost_votes: 4,
      updated_at: Date.now(),
    })
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      allocated_boosts: [{ channel_id: "chan_1", votes_count: 1, boosted_at: Date.now() }],
      available_boost_votes: 3,
      updated_at: Date.now(),
    })
  );

  // Reject role escalation
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      role: "admin",
      updated_at: Date.now(),
    })
  );

  // Reject creating initial account with role != 'user' or balance > 1000
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set(validUserPayload({ role: "developer" }))
  );
  await assertFails(
    bobDb.collection("users").doc(BOB_UID).set(validUserPayload({ balance: 50000 }))
  );

  // Reject immutable created_at mutation and unexpected fields
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      created_at: 123456,
      updated_at: Date.now(),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      user_id: ALICE_UID,
      updated_at: Date.now(),
    })
  );

  // Cross-user read & write isolation
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).update({ balance: 0 }));
});

test("Presence subcollection: owner write, peer get, list denial, and future timestamp rejection", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  const alicePresenceRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("presence")
    .doc("status");

  // Mirrors PresenceWorker statusMap
  await assertSucceeds(
    alicePresenceRef.set({
      isOnline: true,
      lastActive: Date.now() - 500,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Peer (Bob) can read Alice's presence status doc
  await assertSucceeds(
    bobDb.collection("users").doc(ALICE_UID).collection("presence").doc("status").get()
  );

  // Peer cannot list Alice's presence subcollection or overwrite it
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("presence").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("presence").doc("status").set({
      isOnline: false,
      lastActive: Date.now(),
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject future updatedAt timestamp
  await assertFails(
    alicePresenceRef.set({
      isOnline: false,
      lastActive: Date.now(),
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

// =========================================================================
// 3. CHATS, MESSAGES & TYPING (/chats/{chatId}, messages, typing)
// =========================================================================

test("Chats & Messages: participant queries, batch create, reactions, read receipts, typing, and isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  const chatId = directChatId(ALICE_UID, BOB_UID);

  // 1. Direct chat creation (mirrors getOrCreateDirectChat)
  await assertSucceeds(
    aliceDb.collection("chats").doc(chatId).set(validChatPayload(), { merge: true })
  );

  // Outsider cannot create chat excluding themselves
  await assertFails(
    charlieDb
      .collection("chats")
      .doc(directChatId("u1", "u2"))
      .set(validChatPayload({ id: directChatId("u1", "u2"), participants: ["u1", "u2"] }))
  );

  // 2. Mirrors observeUserChatsRealtime query
  await assertSucceeds(
    aliceDb
      .collection("chats")
      .where("participants", "array-contains", ALICE_UID)
      .orderBy("lastMessageTimestamp", "desc")
      .get()
  );
  // Unfiltered list on /chats must fail
  await assertFails(aliceDb.collection("chats").get());

  // 3. Batch create of chat + first message (mirrors sendMessage on fresh or existing chat)
  const freshChatId = directChatId(ALICE_UID, CHARLIE_UID);
  const batch = aliceDb.batch();
  batch.set(
    aliceDb.collection("chats").doc(freshChatId),
    validChatPayload({ id: freshChatId, participants: [ALICE_UID, CHARLIE_UID] }),
    { merge: true }
  );
  batch.set(
    aliceDb.collection("chats").doc(freshChatId).collection("messages").doc("msg_first"),
    validMessagePayload(freshChatId, ALICE_UID, { id: "msg_first", receiverId: CHARLIE_UID })
  );
  await assertSucceeds(batch.commit());

  // 4. Send message in existing chat + update unreadCounts dot-path (mirrors sendMessage)
  const sendBatch = aliceDb.batch();
  sendBatch.set(
    aliceDb.collection("chats").doc(chatId).collection("messages").doc("msg_1"),
    validMessagePayload(chatId, ALICE_UID)
  );
  sendBatch.set(
    aliceDb.collection("chats").doc(chatId),
    {
      lastMessage: "Hello KuoteX!",
      lastMessageTimestamp: Date.now() - 500,
      lastSenderId: ALICE_UID,
      unreadCounts: { [ALICE_UID]: 0, [BOB_UID]: 1 },
    },
    { merge: true }
  );
  await assertSucceeds(sendBatch.commit());

  // Spoofed senderId rejected
  await assertFails(
    bobDb
      .collection("chats")
      .doc(chatId)
      .collection("messages")
      .doc("msg_spoof")
      .set(validMessagePayload(chatId, ALICE_UID, { id: "msg_spoof" }))
  );

  // Outsider cannot read messages or query them
  await assertFails(
    charlieDb
      .collection("chats")
      .doc(chatId)
      .collection("messages")
      .orderBy("timestamp", "asc")
      .get()
  );

  // 5. Mirrors observeMessagesRealtime query
  await assertSucceeds(
    bobDb
      .collection("chats")
      .doc(chatId)
      .collection("messages")
      .orderBy("timestamp", "asc")
      .get()
  );

  // 6. Participant updates: Bob marks message as read and adds reaction
  await assertSucceeds(
    bobDb
      .collection("chats")
      .doc(chatId)
      .collection("messages")
      .doc("msg_1")
      .update({ isRead: true })
  );
  await assertSucceeds(
    bobDb
      .collection("chats")
      .doc(chatId)
      .collection("messages")
      .doc("msg_1")
      .update({ reaction: "🔥" })
  );

  // Bob cannot rewrite Alice's message text
  await assertFails(
    bobDb
      .collection("chats")
      .doc(chatId)
      .collection("messages")
      .doc("msg_1")
      .update({ text: "Tampered by Bob" })
  );

  // Bob cannot delete Alice's message, Alice can delete her own message
  await assertFails(
    bobDb.collection("chats").doc(chatId).collection("messages").doc("msg_1").delete()
  );
  await assertSucceeds(
    aliceDb.collection("chats").doc(chatId).collection("messages").doc("msg_1").delete()
  );

  // 7. Typing status (mirrors setTypingStatus & observeTypingStatus)
  await assertSucceeds(
    aliceDb.collection("chats").doc(chatId).collection("typing").doc(ALICE_UID).set(
      {
        isTyping: true,
        timestamp: new Date(Date.now() - 500),
      },
      { merge: true }
    )
  );
  await assertSucceeds(
    bobDb.collection("chats").doc(chatId).collection("typing").doc(ALICE_UID).get()
  );
  await assertFails(
    charlieDb.collection("chats").doc(chatId).collection("typing").doc(ALICE_UID).get()
  );
  await assertFails(
    bobDb.collection("chats").doc(chatId).collection("typing").doc(ALICE_UID).set({
      isTyping: false,
      timestamp: new Date(Date.now() - 500),
    })
  );
});

// =========================================================================
// 4. CHANNELS, POSTS & COMMENTS (/channels/{channelId}/posts/{postId}/comments)
// =========================================================================

test("Channels, Posts & Comments: discussion sync, comments query, boost update & ownership", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const channelId = "chan_neon_1";
  const groupId = "grp_discuss_1";
  const postId = "post_101";

  // 1. Mirrors ChannelDiscussionFirestoreSync.syncChannelDiscussionLink
  const channelPayload = {
    id: channelId,
    title: "Neon Updates",
    isChannel: true,
    linked_discussion_group_id: groupId,
    linked_discussion_group_title: "Neon Chat",
    linkedDiscussionGroupId: groupId,
    linkedDiscussionGroupTitle: "Neon Chat",
    discussion_only_members_can_send: false,
    discussion_join_requests_required: false,
    updated_at: new Date(Date.now() - 1000),
  };

  await assertSucceeds(
    aliceDb.collection("channels").doc(channelId).set(channelPayload, { merge: true })
  );
  await assertSucceeds(
    aliceDb.collection("chats").doc(channelId).set(channelPayload, { merge: true })
  );
  await assertSucceeds(
    aliceDb.collection("chats").doc(groupId).set(
      {
        id: groupId,
        title: "Neon Chat",
        isGroup: true,
        isChannel: false,
        linkedChannelId: channelId,
        linkedChannelTitle: "Neon Updates",
        updated_at: new Date(Date.now() - 1000),
      },
      { merge: true }
    )
  );

  // 2. Mirrors Channel Boost update in KuoteXEcosystemFirestoreManager (numeric updated_at)
  await assertSucceeds(
    aliceDb.collection("channels").doc(channelId).set(
      {
        title: "Neon Updates",
        current_votes: 5,
        level: 1,
        next_level_required_votes: 10,
        custom_color_unlocked: true,
        status_emoji_unlocked: false,
        wallpaper_unlocked: false,
        stories_per_day_limit: 2,
        updated_at: Date.now(),
      },
      { merge: true }
    )
  );

  // Unfiltered channel list denied
  await assertFails(aliceDb.collection("channels").get());

  // 3. Mirrors syncPostComment (post metadata + comment doc)
  const postRef = aliceDb
    .collection("channels")
    .doc(channelId)
    .collection("posts")
    .doc(postId);
  await assertSucceeds(
    postRef.set(
      {
        postId,
        channelId,
        commentCount: 1,
        mirroredGroupId: groupId,
        mirroredGroupMessageId: "gmsg_1",
        updatedAt: new Date(Date.now() - 1000),
      },
      { merge: true }
    )
  );

  await assertSucceeds(
    postRef.collection("comments").doc("c_1").set(
      {
        id: "c_1",
        channelId,
        postId,
        senderId: ALICE_UID,
        senderName: "Alice",
        isChannelIdentity: false,
        text: "First comment!",
        timestamp: Date.now() - 500,
        createdAt: new Date(Date.now() - 500),
      },
      { merge: true }
    )
  );

  // 4. Mirrors observePostComments query
  await assertSucceeds(
    bobDb
      .collection("channels")
      .doc(channelId)
      .collection("posts")
      .doc(postId)
      .collection("comments")
      .orderBy("timestamp", "asc")
      .get()
  );

  // Bob cannot spoof Alice's senderId or delete Alice's comment
  await assertFails(
    bobDb
      .collection("channels")
      .doc(channelId)
      .collection("posts")
      .doc(postId)
      .collection("comments")
      .doc("c_spoof")
      .set({
        id: "c_spoof",
        channelId,
        postId,
        senderId: ALICE_UID,
        senderName: "Alice",
        text: "Spoof",
        timestamp: Date.now(),
        createdAt: new Date(Date.now() - 500),
      })
  );
  await assertFails(
    bobDb
      .collection("channels")
      .doc(channelId)
      .collection("posts")
      .doc(postId)
      .collection("comments")
      .doc("c_1")
      .delete()
  );
});

// =========================================================================
// 5. GIFTS CATALOG, USER GIFTS, LEDGER, IDEMPOTENCY & POLL VOTES
// =========================================================================

test("Gifts catalog: create, supply decrement by 1, price lock, and list denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const giftRef = aliceDb.collection("gifts_catalog").doc("gift_cyber_dragon_001");

  await assertSucceeds(
    giftRef.set({
      title: "Cyber Dragon 2026",
      price: 250,
      total_supply: 1000,
      available_supply: 782,
      is_exclusive: true,
      lottie_asset_url: "cyber_dragon.json",
      backdrop_color_hex: "#1E1B4B",
      emoji_icon: "🐉",
      max_upgrade_level: 5,
      created_at: Date.now() - 5000,
    })
  );

  // Valid atomic supply decrement by exactly 1
  await assertSucceeds(giftRef.update({ available_supply: 781 }));

  // Price tampering or invalid decrement rejected
  await assertFails(giftRef.update({ price: 1 }));
  await assertFails(giftRef.update({ available_supply: 500 }));
  await assertFails(aliceDb.collection("gifts_catalog").get());
});

test("User gifts: pinned header query, anonymous gift rules, receiver-only upgrade & unfiltered denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();

  // Alice buys a pinned gift for Bob (mirrors KuoteXUserGiftDoc)
  await assertSucceeds(
    aliceDb.collection("user_gifts").doc("ug_1").set({
      catalog_gift_id: "gift_cyber_dragon_001",
      sender_id: ALICE_UID,
      receiver_id: BOB_UID,
      is_pinned_to_header: true,
      pin_order_index: 0,
      upgrade_level: 1,
      transferable: false,
      message: "Happy Launch!",
      is_anonymous: false,
      acquired_at: Date.now() - 1000,
      cached_title: "Cyber Dragon 2026",
      cached_emoji: "🐉",
      cached_color_hex: "#1E1B4B",
    })
  );

  // Anonymous gift requires sender_id == 'anonymous'
  await assertSucceeds(
    aliceDb.collection("user_gifts").doc("ug_anon").set({
      catalog_gift_id: "gift_teddy_bear_007",
      sender_id: "anonymous",
      receiver_id: BOB_UID,
      is_pinned_to_header: false,
      pin_order_index: -1,
      upgrade_level: 1,
      transferable: false,
      message: "Secret",
      is_anonymous: true,
      acquired_at: Date.now() - 1000,
    })
  );

  // Unpinned private gift cannot be read by unrelated third party Charlie
  await assertFails(charlieDb.collection("user_gifts").doc("ug_anon").get());

  // Mirrors observePinnedGifts query
  await assertSucceeds(
    charlieDb
      .collection("user_gifts")
      .where("receiver_id", "==", BOB_UID)
      .where("is_pinned_to_header", "==", true)
      .get()
  );

  // Unfiltered list on user_gifts rejected
  await assertFails(charlieDb.collection("user_gifts").get());

  // Receiver (Bob) upgrades gift; sender (Alice) cannot modify Bob's gift
  await assertSucceeds(
    bobDb.collection("user_gifts").doc("ug_1").update({ upgrade_level: 2 })
  );
  await assertFails(
    aliceDb.collection("user_gifts").doc("ug_1").update({ upgrade_level: 5 })
  );
  await assertFails(bobDb.collection("user_gifts").doc("ug_1").delete());
});

test("Ledger transactions, Idempotency keys & Poll votes: immutability, ownership & deterministic ID", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // 1. Ledger transaction create & immutability
  await assertSucceeds(
    aliceDb.collection("ledger_transactions").doc("tx_1").set({
      idempotency_key: "idem_1",
      type: "VIP_SUBSCRIPTION",
      from_user_id: ALICE_UID,
      to_user_id: "system_kuotex_vip",
      amount: 300,
      fee: 0,
      status: "COMMITTED",
      metadata: { months: 1 },
      created_at: Date.now() - 1000,
    })
  );

  await assertSucceeds(
    aliceDb.collection("ledger_transactions").where("from_user_id", "==", ALICE_UID).get()
  );
  await assertFails(aliceDb.collection("ledger_transactions").doc("tx_1").update({ amount: 0 }));
  await assertFails(aliceDb.collection("ledger_transactions").doc("tx_1").delete());
  await assertFails(bobDb.collection("ledger_transactions").doc("tx_1").get());

  // Reject invalid ledger type enum
  await assertFails(
    aliceDb.collection("ledger_transactions").doc("tx_bad").set({
      idempotency_key: "idem_bad",
      type: "HACK_MINT",
      from_user_id: ALICE_UID,
      to_user_id: ALICE_UID,
      amount: 99999,
      status: "COMMITTED",
      created_at: Date.now() - 1000,
    })
  );

  // 2. Idempotency key create, owner read, immutability & list denial
  await assertSucceeds(
    aliceDb.collection("idempotency_keys").doc("idem_1").set({
      tx_id: "tx_1",
      user_id: ALICE_UID,
      created_at: Date.now() - 1000,
    })
  );
  await assertSucceeds(aliceDb.collection("idempotency_keys").doc("idem_1").get());
  await assertFails(bobDb.collection("idempotency_keys").doc("idem_1").get());
  await assertFails(aliceDb.collection("idempotency_keys").get());
  await assertFails(aliceDb.collection("idempotency_keys").doc("idem_1").delete());

  // 3. Poll vote deterministic doc ID (${poll_id}_${user_id}) & single-vote immutability
  const voteId = `poll_99_${ALICE_UID}`;
  await assertSucceeds(
    aliceDb.collection("poll_votes").doc(voteId).set({
      poll_id: "poll_99",
      chat_id: "chan_neon_1",
      user_id: ALICE_UID,
      selected_option_ids: [1],
      timestamp: Date.now() - 1000,
    })
  );
  // Non-deterministic vote ID rejected
  await assertFails(
    aliceDb.collection("poll_votes").doc("arbitrary_id").set({
      poll_id: "poll_99",
      chat_id: "chan_neon_1",
      user_id: ALICE_UID,
      selected_option_ids: [2],
      timestamp: Date.now() - 1000,
    })
  );
  // Vote overwrite/delete rejected
  await assertFails(
    aliceDb.collection("poll_votes").doc(voteId).update({ selected_option_ids: [0] })
  );
  await assertFails(aliceDb.collection("poll_votes").doc(voteId).delete());
});
