package com.example.ui

import android.content.Context
import androidx.work.*
import com.example.data.FirestoreClientProvider
import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.TimeUnit

object PresenceManager {
    fun updatePresence(context: Context, userId: String, isOnline: Boolean) {
        val currentUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (currentUid.isNullOrBlank() || userId.isBlank() || currentUid != userId) {
            return
        }

        val inputData = Data.Builder()
            .putString("USER_ID", userId)
            .putBoolean("IS_ONLINE", isOnline)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<PresenceWorker>()
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "presence_update_$userId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun observePresence(userId: String): Flow<UserPresence?> = callbackFlow {
        val db = FirestoreClientProvider.getFirestoreOrNull()
        if (db == null || userId.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val presencePath = "users/$userId/presence/status"
        val listener = db.collection("users").document(userId).collection("presence").document("status")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.GET, presencePath)
                    trySend(null)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val isOnline = snapshot.getBoolean("isOnline") ?: false
                    val lastActive = snapshot.getLong("lastActive") ?: 0L
                    trySend(UserPresence(userId, isOnline, lastActive))
                } else {
                    trySend(null)
                }
            }

        awaitClose { listener.remove() }
    }
}
