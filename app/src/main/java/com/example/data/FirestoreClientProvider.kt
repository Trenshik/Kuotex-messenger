package com.example.data

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write")
}

object FirestoreClientProvider {
    private const val TAG = "FirestoreClientProvider"

    @Volatile
    private var cachedDatabaseId: String? = null

    fun init(context: Context) {
        val dbId = runCatching {
            context.applicationContext.getString(R.string.firestore_database_id)
        }.getOrNull()
        if (!dbId.isNullOrBlank()) {
            cachedDatabaseId = dbId
        }
    }

    fun getDatabaseId(context: Context? = null): String? {
        if (context != null) {
            init(context)
        }
        return cachedDatabaseId
    }

    fun getFirestore(context: Context? = null): FirebaseFirestore {
        val dbId = getDatabaseId(context)
            ?: throw IllegalStateException(
                "Firestore database ID is not initialized. Ensure FirestoreClientProvider.init(context) is called or pass a Context."
            )
        return FirebaseFirestore.getInstance(dbId)
    }

    fun getFirestoreOrNull(context: Context? = null): FirebaseFirestore? {
        return try {
            getFirestore(context)
        } catch (t: Throwable) {
            Log.w(TAG, "Firestore unavailable: ${t.message}")
            null
        }
    }
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?
): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val currentUser = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}
