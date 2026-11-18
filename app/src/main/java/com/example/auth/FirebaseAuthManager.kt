package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.analytics.AnalyticsTracker
import com.example.analytics.FirebaseAnalyticsHelper
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Clean data model representing authenticated Firebase User details.
 */
data class FirebaseUserInfo(
    val uid: String,
    val email: String?,
    val phoneNumber: String?,
    val displayName: String?,
    val photoUrl: String?,
    val isEmailVerified: Boolean,
    val isAnonymous: Boolean = false
)

/**
 * Generic Auth result wrapper for Firebase operations.
 */
sealed class AuthResult<out T> {
    data class Success<out T>(val data: T) : AuthResult<T>()
    data class Error(val message: String, val code: String? = null, val throwable: Throwable? = null) : AuthResult<Nothing>()
}

sealed class AuthState {
    object Unauthenticated : AuthState()
    object Authenticating : AuthState()
    data class Authenticated(val user: FirebaseUserInfo) : AuthState()
    data class Error(val message: String) : AuthState()
}

/**
 * FirebaseAuthManager
 *
 * Central service for Firebase Authentication in KuoteX messenger.
 * Authenticates users exclusively via Google Sign-In with Jetpack Credential Manager.
 */
object FirebaseAuthManager {

    private const val TAG = "Auth"

    private var firebaseAuthInstance: FirebaseAuth? = null

    private val _currentUser = MutableStateFlow<FirebaseUserInfo?>(null)
    val currentUser: StateFlow<FirebaseUserInfo?> = _currentUser.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    init {
        initFirebaseAuth()
    }

    private fun initFirebaseAuth() {
        try {
            val auth = FirebaseAuth.getInstance()
            firebaseAuthInstance = auth

            val initialUser = auth.currentUser
            if (initialUser != null) {
                val userInfo = mapFirebaseUser(initialUser)
                _currentUser.value = userInfo
                _authState.value = AuthState.Authenticated(userInfo)
            } else {
                _currentUser.value = null
                _authState.value = AuthState.Unauthenticated
            }

            authStateListener = FirebaseAuth.AuthStateListener { fbAuth ->
                val user = fbAuth.currentUser
                if (user != null) {
                    val info = mapFirebaseUser(user)
                    _currentUser.value = info
                    _authState.value = AuthState.Authenticated(info)
                    Log.d(TAG, "FirebaseAuth state changed: Authenticated (${info.email ?: info.uid})")
                } else {
                    _currentUser.value = null
                    _authState.value = AuthState.Unauthenticated
                    Log.d(TAG, "FirebaseAuth state changed: Unauthenticated")
                }
            }
            auth.addAuthStateListener(authStateListener!!)
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseAuth initialization warning: ${e.message}")
        }
    }

    fun getAuth(): FirebaseAuth? {
        if (firebaseAuthInstance == null) {
            try {
                firebaseAuthInstance = FirebaseAuth.getInstance()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get FirebaseAuth instance", e)
            }
        }
        return firebaseAuthInstance
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun resolveWebClientId(context: Context): String? {
        return try {
            context.getString(R.string.default_web_client_id).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In configuration missing: default_web_client_id not found", e)
            null
        }
    }

    /**
     * Silent auto-sign-in attempt on startup using authorized Google accounts only.
     * Uses a dedicated single-option GetCredentialRequest with GetGoogleIdOption.
     */
    suspend fun attemptAutoSignIn(context: Context): AuthResult<FirebaseUserInfo>? {
        val existingUser = getAuth()?.currentUser
        if (existingUser != null) {
            val info = mapFirebaseUser(existingUser)
            _currentUser.value = info
            _authState.value = AuthState.Authenticated(info)
            return AuthResult.Success(info)
        }

        val webClientId = resolveWebClientId(context) ?: return null
        val credentialManager = CredentialManager.create(context)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val credentialContext: Context = context.findActivity() ?: context
        return try {
            val result = credentialManager.getCredential(credentialContext, request)
            completeFirebaseGoogleSignIn(result.credential, method = "google_auto")
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoCredentialException) {
            Log.d(TAG, "No authorized Google credentials available for auto sign-in: ${e.message}")
            null
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Auto Sign-In cancelled or dismissed: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Google Auto Sign-In skipped: ${e.message}")
            null
        }
    }

    /**
     * Interactive Google Sign-In flow triggered by the Sign in with Google button.
     * Uses a single-option GetCredentialRequest with GetSignInWithGoogleOption.
     */
    suspend fun signInWithGoogle(context: Context): AuthResult<FirebaseUserInfo> {
        val webClientId = resolveWebClientId(context)
            ?: return AuthResult.Error("Google Sign-In configuration missing: default_web_client_id not found")

        _authState.value = AuthState.Authenticating
        return try {
            val credentialManager = CredentialManager.create(context)
            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val credentialContext: Context = context.findActivity() ?: context
            val result = credentialManager.getCredential(credentialContext, request)
            val authResult = completeFirebaseGoogleSignIn(result.credential, method = "google")
            if (authResult is AuthResult.Error) {
                _authState.value = AuthState.Error(authResult.message)
            }
            authResult
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            _authState.value = AuthState.Unauthenticated
            AuthResult.Error("Вход через Google отменён пользователем.", code = "CANCELLED", throwable = e)
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google accounts found on device: ${e.message}", e)
            val msg = "На устройстве не найдено аккаунтов Google для входа."
            _authState.value = AuthState.Error(msg)
            AuthResult.Error(msg, code = "NO_CREDENTIAL", throwable = e)
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            val friendlyMsg = mapFirebaseAuthException(e)
            _authState.value = AuthState.Error(friendlyMsg)
            AuthResult.Error(friendlyMsg, throwable = e)
        }
    }

    private suspend fun completeFirebaseGoogleSignIn(
        credential: androidx.credentials.Credential,
        method: String
    ): AuthResult<FirebaseUserInfo> {
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            val auth = getAuth() ?: return AuthResult.Error("Firebase Auth not initialized")

            val authResult = auth.signInWithCredential(firebaseCredential).await()
            val user = authResult.user
            return if (user != null) {
                val userInfo = mapFirebaseUser(user)
                _currentUser.value = userInfo
                _authState.value = AuthState.Authenticated(userInfo)
                FirebaseAnalyticsHelper.logCustomEvent("firebase_login_success", mapOf("method" to method, "uid" to user.uid))
                AnalyticsTracker.logUserLogin(user.uid, "firebase_$method")
                AuthResult.Success(userInfo)
            } else {
                AuthResult.Error("Firebase Auth returned null user after Google Sign-In")
            }
        }
        return AuthResult.Error("Unexpected credential type: ${credential.type}")
    }

    private fun mapFirebaseUser(user: FirebaseUser): FirebaseUserInfo {
        return FirebaseUserInfo(
            uid = user.uid,
            email = user.email,
            phoneNumber = user.phoneNumber,
            displayName = user.displayName ?: user.email?.substringBefore("@") ?: "User_${user.uid.take(5)}",
            photoUrl = user.photoUrl?.toString(),
            isEmailVerified = user.isEmailVerified,
            isAnonymous = user.isAnonymous
        )
    }

    /**
     * Updates user display name and profile picture URL.
     */
    suspend fun updateUserProfile(displayName: String?, photoUrl: String?): AuthResult<Unit> {
        val user = getAuth()?.currentUser ?: return AuthResult.Error("Пользователь не авторизован.")
        val profileUpdates = UserProfileChangeRequest.Builder().apply {
            if (displayName != null) setDisplayName(displayName)
            if (photoUrl != null) setPhotoUri(Uri.parse(photoUrl))
        }.build()

        return try {
            user.updateProfile(profileUpdates).await()
            _currentUser.value = mapFirebaseUser(user)
            AuthResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to update profile", throwable = e)
        }
    }

    /**
     * Signs out current user and clears Credential Manager state when context is provided.
     * Runs inside NonCancellable so UI teardown does not cancel credential cleanup.
     */
    suspend fun signOut(context: Context?) {
        withContext(NonCancellable) {
            try {
                if (context != null) {
                    CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state: ${e.message}")
            } finally {
                signOut()
            }
        }
    }

    /**
     * Immediate FirebaseAuth sign-out without CredentialManager state clearing.
     */
    fun signOut() {
        try {
            getAuth()?.signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Error during signOut: ${e.message}")
        }
        _currentUser.value = null
        _authState.value = AuthState.Unauthenticated
        AnalyticsTracker.logUserLogout()
    }

    /**
     * Translates Firebase Auth exception codes into user-friendly localized messages.
     */
    fun mapFirebaseAuthException(e: Throwable): String {
        if (e is FirebaseAuthException) {
            return when (e.errorCode) {
                "ERROR_INVALID_CUSTOM_TOKEN" -> "Неверный формат токена авторизации."
                "ERROR_CUSTOM_TOKEN_MISMATCH" -> "Токен авторизации не соответствует конфигурации."
                "ERROR_INVALID_CREDENTIAL" -> "Неверные учетные данные Google."
                "ERROR_USER_MISMATCH" -> "Учетные данные не соответствуют текущему пользователю."
                "ERROR_REQUIRES_RECENT_LOGIN" -> "Для выполнения этого действия требуется повторный вход через Google."
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> "Аккаунт с таким email уже существует с другим методом входа."
                "ERROR_CREDENTIAL_ALREADY_IN_USE" -> "Эти учетные данные уже используются другим аккаунтом."
                "ERROR_USER_DISABLED" -> "Этот аккаунт отключен администратором."
                "ERROR_INVALID_USER_TOKEN" -> "Сессия истекла. Пожалуйста, войдите через Google заново."
                "ERROR_OPERATION_NOT_ALLOWED" -> "Вход через Google отключен в консоли Firebase."
                "ERROR_TOO_MANY_REQUESTS" -> "Слишком много попыток входа. Пожалуйста, подождите немного."
                else -> e.localizedMessage ?: "Ошибка авторизации Firebase (${e.errorCode})"
            }
        }
        return e.localizedMessage ?: "Произошла ошибка при аутентификации."
    }
}
