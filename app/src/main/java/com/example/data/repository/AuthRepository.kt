package com.example.data.repository

import android.content.Context
import com.example.auth.AuthResult
import com.example.auth.AuthState
import com.example.auth.FirebaseAuthManager
import com.example.auth.FirebaseUserInfo
import com.example.data.UserDao
import kotlinx.coroutines.flow.StateFlow

/**
 * Clean repository abstraction for Authentication lifecycle and Firebase Auth operations.
 */
interface AuthRepository {
    val authState: StateFlow<AuthState>
    val currentUser: StateFlow<FirebaseUserInfo?>

    suspend fun attemptAutoSignIn(context: Context): AuthResult<FirebaseUserInfo>?
    suspend fun signInWithGoogle(context: Context): AuthResult<FirebaseUserInfo>
    suspend fun signOut(context: Context? = null)
    suspend fun checkPhoneExists(phone: String): Boolean
}

class AuthRepositoryImpl(
    private val userDao: UserDao,
    private val authManager: FirebaseAuthManager = FirebaseAuthManager
) : AuthRepository {

    override val authState: StateFlow<AuthState> = authManager.authState
    override val currentUser: StateFlow<FirebaseUserInfo?> = authManager.currentUser

    override suspend fun attemptAutoSignIn(context: Context): AuthResult<FirebaseUserInfo>? {
        return authManager.attemptAutoSignIn(context)
    }

    override suspend fun signInWithGoogle(context: Context): AuthResult<FirebaseUserInfo> {
        return authManager.signInWithGoogle(context)
    }

    override suspend fun signOut(context: Context?) {
        authManager.signOut(context)
    }

    override suspend fun checkPhoneExists(phone: String): Boolean {
        return userDao.checkPhoneNumberExists(phone)
    }
}
