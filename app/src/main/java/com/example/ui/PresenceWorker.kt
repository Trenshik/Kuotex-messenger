package com.example.ui

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.FirestoreClientProvider
import com.example.data.OperationType
import com.example.data.handleFirestoreError
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

class PresenceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val userId = inputData.getString("USER_ID") ?: return Result.failure()
        val currentUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (currentUid.isNullOrBlank() || currentUid != userId) {
            return Result.success()
        }

        val presencePath = "users/$userId/presence/status"
        return try {
            val db = FirestoreClientProvider.getFirestore(applicationContext)
            val isOnline = inputData.getBoolean("IS_ONLINE", true)

            val statusMap = hashMapOf<String, Any>(
                "isOnline" to isOnline,
                "lastActive" to System.currentTimeMillis(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            db.collection("users").document(userId).collection("presence").document("status")
                .set(statusMap)
                .await()

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, presencePath)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
