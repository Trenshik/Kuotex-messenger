package com.example.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.auth.AuthResult
import com.example.auth.FirebaseAuthManager
import kotlinx.coroutines.launch

val languages = listOf(
    Triple("en", "English", "🇺🇸"),
    Triple("ru", "Русский", "🇷🇺"),
    Triple("uk", "Українська", "🇺🇦"),
    Triple("es", "Español", "🇪🇸"),
    Triple("de", "Deutsch", "🇩🇪"),
    Triple("fr", "Français", "🇫🇷"),
    Triple("kz", "Қазақша", "🇰🇿")
)

fun getCountryFlagForPhoneNumber(phone: String): String {
    if (phone.startsWith("+79") || phone.startsWith("+78") || phone.startsWith("+73") || phone.startsWith("+74") || phone.startsWith("+75")) return "🇷🇺"
    if (phone.startsWith("+77") || phone.startsWith("+76") || phone.startsWith("+70") || phone.startsWith("+71") || phone.startsWith("+72")) return "🇰🇿"
    if (phone.startsWith("+1")) return "🇺🇸"
    if (phone.startsWith("+380")) return "🇺🇦"
    if (phone.startsWith("+44")) return "🇬🇧"
    if (phone.startsWith("+49")) return "🇩🇪"
    if (phone.startsWith("+33")) return "🇫🇷"
    if (phone.startsWith("+34")) return "🇪🇸"
    return "🌍"
}

@Composable
fun LanguageSelector() {
    var selectedLanguage by remember { mutableStateOf(languages[1]) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("Select Language", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(languages) { lang ->
                    val isSelected = lang == selectedLanguage
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent)
                            .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { selectedLanguage = lang }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text("${lang.third} ${lang.second}", style = MaterialTheme.typography.bodySmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
fun FirebaseAuthBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF00E676), CircleShape)
            )
            Text(
                text = "Firebase Auth + Google Credential Manager",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun TelegramModerationBlockedDialog(
    user: com.example.data.RegisteredUserRole,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF1744).copy(alpha = 0.2f))
                        .border(1.dp, Color(0xFFFF1744), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Block,
                        contentDescription = "Blocked",
                        tint = Color(0xFFFF1744),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Аккаунт заблокирован",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5252)
                    )
                    Text(
                        text = "KuoteX Moderation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Доступ к аккаунту ${user.displayName} (${user.username}) приостановлен администрацией в соответствии с правилами сообщества.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFF1744).copy(alpha = 0.1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF1744).copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Причина блокировки:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF5252)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = user.blockReason ?: "Нарушение правил сообщества",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (user.blockedBy != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Модератор: ${user.blockedBy}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
            ) {
                Text("Понятно", color = Color.White)
            }
        }
    )
}

@Composable
fun LoginScreen(
    context: Context = LocalContext.current,
    accounts: List<UserAccount>,
    onLoginSuccess: (String) -> Unit,
    viewModel: AppViewModel? = null,
    forceManualLogin: Boolean = false
) {
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var blockedUserRecord by remember { mutableStateOf<com.example.data.RegisteredUserRole?>(null) }
    val scope = rememberCoroutineScope()

    // Attempt silent auto sign-in for previously authorized Google accounts on startup
    LaunchedEffect(forceManualLogin) {
        if (!forceManualLogin) {
            isLoading = true
            val result = if (viewModel != null) {
                viewModel.attemptAutoSignIn(context)
            } else {
                FirebaseAuthManager.attemptAutoSignIn(context)
            }
            isLoading = false
            if (result is AuthResult.Success) {
                val blockInfo = com.example.data.FirestoreUserRoleManager.getUserBlockInfo(result.data.uid)
                    ?: result.data.email?.let { com.example.data.FirestoreUserRoleManager.getUserBlockInfo(it) }
                if (blockInfo != null && blockInfo.isBlocked) {
                    FirebaseAuthManager.signOut(context)
                    blockedUserRecord = blockInfo
                } else {
                    onLoginSuccess(result.data.uid)
                }
            }
        }
    }

    if (blockedUserRecord != null) {
        TelegramModerationBlockedDialog(
            user = blockedUserRecord!!,
            onDismiss = { blockedUserRecord = null }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            NeonLoadingSpinner(size = 80.dp, color = MaterialTheme.colorScheme.primary)
        } else {
            Card(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FirebaseAuthBadge(modifier = Modifier.padding(bottom = 16.dp))

                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.AccountCircle,
                            contentDescription = "KuoteX Sign In",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    LanguageSelector()

                    Text(
                        text = if (forceManualLogin) "Добавить аккаунт Google" else "Добро пожаловать в KuoteX",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Войдите через Google, чтобы синхронизировать чаты, каналы и профиль в защищённом облаке Firebase.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(20.dp))

                    if (errorMessage != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Text(
                                    text = errorMessage!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                val result = if (viewModel != null) {
                                    viewModel.signInWithGoogle(context)
                                } else {
                                    FirebaseAuthManager.signInWithGoogle(context)
                                }
                                isLoading = false
                                when (result) {
                                    is AuthResult.Success -> {
                                        val blockInfo = com.example.data.FirestoreUserRoleManager.getUserBlockInfo(result.data.uid)
                                            ?: result.data.email?.let { com.example.data.FirestoreUserRoleManager.getUserBlockInfo(it) }
                                        if (blockInfo != null && blockInfo.isBlocked) {
                                            FirebaseAuthManager.signOut(context)
                                            blockedUserRecord = blockInfo
                                        } else {
                                            onLoginSuccess(result.data.uid)
                                        }
                                    }
                                    is AuthResult.Error -> {
                                        if (result.code != "CANCELLED") {
                                            errorMessage = result.message
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("google_sign_in_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            Icons.Filled.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Войти через Google",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Защита данных с помощью Firebase Security Rules",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (forceManualLogin && accounts.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = { viewModel?.clearAddingAccount() },
                            modifier = Modifier.testTag("cancel_add_account_button")
                        ) {
                            Text("Назад")
                        }
                    }
                }
            }
        }
    }
}
