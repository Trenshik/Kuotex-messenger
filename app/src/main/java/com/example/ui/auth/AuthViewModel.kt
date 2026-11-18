package com.example.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.analytics.AnalyticsTracker
import com.example.auth.AuthResult
import com.example.auth.AuthState
import com.example.auth.FirebaseUserInfo
import com.example.data.repository.AuthRepository
import com.example.data.repository.UserDataRepository
import com.example.ui.UserAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI State representation for Authentication flows.
 */
sealed class AuthUiState {
    object Idle : AuthUiState()
    data class Loading(val message: String = "Processing...") : AuthUiState()
    data class Success(val user: FirebaseUserInfo, val accountId: String) : AuthUiState()
    data class Error(val message: String, val code: String? = null) : AuthUiState()
    data class Requires2FA(val accountId: String) : AuthUiState()
}

/**
 * Dedicated AuthViewModel responsible for Google Sign-In authentication sessions.
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val userDataRepository: UserDataRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val authState: StateFlow<AuthState> = authRepository.authState
    val currentUser: StateFlow<FirebaseUserInfo?> = authRepository.currentUser
    val registeredAccounts = userDataRepository.getAllAccounts()

    fun resetState() {
        _uiState.value = AuthUiState.Idle
    }

    fun attemptAutoSignIn(context: Context) {
        viewModelScope.launch {
            val result = authRepository.attemptAutoSignIn(context)
            if (result is AuthResult.Success) {
                val user = result.data
                upsertGoogleAccount(user)
                _uiState.value = AuthUiState.Success(user, user.uid)
                AnalyticsTracker.logButtonClick("auto_signin_success", "auth", "login_screen")
            }
        }
    }

    fun signInWithGoogle(context: Context) {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading("Connecting to Google...")
            AnalyticsTracker.logButtonClick("google_signin_attempt", "auth", "login_screen")
            when (val result = authRepository.signInWithGoogle(context)) {
                is AuthResult.Success -> {
                    val user = result.data
                    upsertGoogleAccount(user)
                    _uiState.value = AuthUiState.Success(user, user.uid)
                    AnalyticsTracker.logButtonClick("google_signin_success", "auth", "login_screen")
                }
                is AuthResult.Error -> {
                    if (result.code == "CANCELLED") {
                        _uiState.value = AuthUiState.Idle
                    } else {
                        _uiState.value = AuthUiState.Error(result.message, result.code)
                    }
                    AnalyticsTracker.logButtonClick("google_signin_failure", "auth", "login_screen")
                }
            }
        }
    }

    fun signOut(context: Context? = null) {
        viewModelScope.launch {
            authRepository.signOut(context)
            _uiState.value = AuthUiState.Idle
        }
    }

    private suspend fun upsertGoogleAccount(user: FirebaseUserInfo) {
        val cleanHandle = (user.email?.substringBefore("@") ?: user.displayName ?: "user_${user.uid.take(6)}")
            .replace(" ", "")
            .lowercase()
        val newAccount = UserAccount(
            id = user.uid,
            username = "@$cleanHandle",
            displayName = user.displayName ?: cleanHandle,
            phoneNumber = user.phoneNumber ?: "",
            profilePicUrl = user.photoUrl ?: "",
            isActive = true,
            customStatus = "Verified Google User",
            is2FAEnabled = false
        )
        userDataRepository.insertAccount(newAccount)
    }

    suspend fun checkPhoneExists(phone: String): Boolean {
        return authRepository.checkPhoneExists(phone)
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value = AuthUiState.Idle
    }
}
