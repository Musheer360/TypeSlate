package com.musheer360.swiftslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.api.ApiClientUtils
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.model.ProviderType
import com.musheer360.swiftslate.provider.GroqConfig
import com.musheer360.swiftslate.ui.components.LocalSlateRhythm
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateItemCard
import com.musheer360.swiftslate.ui.components.SlateTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KeysScreen(keyManager: KeyManager, prefs: SharedPreferences) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    // Deliberately not seeded from keyManager.getKeys(): that decrypts through AndroidKeyStore
    // (and on a legacy store also does a synchronous prefs commit), which ran on the main thread
    // during composition. Loaded in the LaunchedEffect below instead.
    var keys by remember { mutableStateOf<List<String>>(emptyList()) }
    var keyToDelete by remember { mutableStateOf<String?>(null) }
    var newKey by rememberSaveable { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val geminiClient = remember { GeminiClient() }
    val openAIClient = remember { OpenAICompatibleClient() }

    LaunchedEffect(Unit) {
        keys = withContext(Dispatchers.IO) { keyManager.getKeys() }
    }

    val validAddedMsg = stringResource(R.string.keys_valid_added)
    val alreadyAddedMsg = stringResource(R.string.keys_already_added)
    val validationFailedMsg = stringResource(R.string.keys_validation_failed)
    val keystoreErrorMsg = stringResource(R.string.keys_keystore_error)
    val customEndpointRequiredMsg = stringResource(R.string.keys_custom_endpoint_required)
    val signinRequiredMsg = stringResource(R.string.error_provider_auth_required)
    val endpointNeedsV1Msg = stringResource(R.string.keys_endpoint_needs_v1)
    val rhythm = LocalSlateRhythm.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { } // Creates a hardware layer for smooth NavHost slide animations
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV)
    ) {
        ScreenTitle(stringResource(R.string.keys_title))

        if (!keyManager.keystoreAvailable) {
            SlateCard {
                Text(
                    text = keystoreErrorMsg,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = rhythm.bodySize
                )
            }
            Spacer(modifier = Modifier.height(rhythm.cardGap))
        }

        SlateCard {
            SlateTextField(
                value = newKey,
                onValueChange = { if (it.length <= 256) newKey = it },
                placeholder = { Text(stringResource(R.string.keys_api_key_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(rhythm.groupGap))
            Button(
                onClick = {
                    if (newKey.isNotBlank()) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isTesting = true
                        testResult = null
                        scope.launch {
                            val trimmedKey = newKey.trim()
                            if (withContext(Dispatchers.IO) { keyManager.getKeys() }.contains(trimmedKey)) {
                                isTesting = false
                                // Re-adding an existing key means the user is retrying it after a
                                // failure — clear any invalid/rate-limit bench so the service can
                                // use it again immediately instead of waiting out the 15-min TTL.
                                withContext(Dispatchers.IO) { keyManager.clearMarks(trimmedKey) }
                                testResult = alreadyAddedMsg
                                testSuccess = false
                                return@launch
                            }
                            val result = run {
                                val providerType = ProviderType.sanitize(prefs.getString(PrefKeys.PROVIDER_TYPE, null))
                                val customEndpoint = (prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "").trim()
                                when {
                                    providerType == ProviderType.CUSTOM && customEndpoint.isBlank() -> {
                                        isTesting = false
                                        testResult = customEndpointRequiredMsg
                                        testSuccess = false
                                        return@launch
                                    }
                                    providerType == ProviderType.GROQ ->
                                        openAIClient.validateKey(trimmedKey, GroqConfig.ENDPOINT)
                                    providerType == ProviderType.CUSTOM ->
                                        openAIClient.validateKey(trimmedKey, customEndpoint)
                                    else ->
                                        geminiClient.validateKey(trimmedKey)
                                }
                            }
                            isTesting = false
                            if (result.isSuccess) {
                                if (!withContext(Dispatchers.IO) { keyManager.addKey(trimmedKey) }) {
                                    testResult = keystoreErrorMsg
                                    testSuccess = false
                                    return@launch
                                }
                                keys = withContext(Dispatchers.IO) { keyManager.getKeys() }
                                newKey = ""
                                testResult = validAddedMsg
                                testSuccess = true
                                // Clear clipboard to prevent API key leaking via paste history
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                            } else {
                                // redactSecrets: some OpenAI-compatible endpoints echo the
                                // submitted key back in error.message ("Incorrect API key
                                // provided: sk-ab...XYZ"). This is the one path that shows a
                                // raw provider message — the accessibility service maps every
                                // message onto a localized string instead — so it is the one
                                // path that has to strip secrets before displaying it.
                                val raw = result.exceptionOrNull()?.message ?: ""
                                testResult = when {
                                    raw.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER) -> signinRequiredMsg
                                    raw.contains(ApiClientUtils.NEEDS_V1_MARKER) -> endpointNeedsV1Msg
                                    else -> ApiClientUtils.redactSecrets(raw).ifEmpty { validationFailedMsg }
                                }
                                testSuccess = false
                            }
                        }
                    }
                },
                enabled = newKey.isNotBlank() && !isTesting && keyManager.keystoreAvailable,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(if (isTesting) stringResource(R.string.keys_testing) else stringResource(R.string.keys_add_key))
            }
            if (testResult != null) {
                Text(
                    text = testResult!!,
                    color = if (testSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    fontSize = rhythm.bodySize,
                    modifier = Modifier.padding(top = rhythm.formGap)
                )
            }
            val (apiKeyUrl, providerName) = when (prefs.getString(PrefKeys.PROVIDER_TYPE, ProviderType.GEMINI) ?: ProviderType.GEMINI) {
                ProviderType.GROQ -> "https://console.groq.com/keys" to "Groq"
                ProviderType.CUSTOM -> null to null
                else -> "https://aistudio.google.com/api-keys" to "Gemini"
            }
            if (apiKeyUrl != null && providerName != null) {
                Text(
                    text = stringResource(R.string.keys_get_api_key, providerName),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = rhythm.bodySize,
                    modifier = Modifier
                        .clickable(interactionSource = null, indication = null) { uriHandler.openUri(apiKeyUrl) }
                        .padding(top = rhythm.formGap)
                )
            }
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        if (keys.isNotEmpty()) {
            SlateCard(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(rhythm.listGap),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    itemsIndexed(keys, key = { index, k -> "$index-${k.hashCode()}" }) { index, key ->
                        SlateItemCard {
                            Text(
                                text = "••••••••" + key.takeLast(4),
                                fontWeight = FontWeight.Medium,
                                fontSize = rhythm.emphasisSize,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}
                            )
                            Text(
                                text = stringResource(R.string.delete_confirm_button),
                                fontSize = rhythm.bodySize,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable(
                                    interactionSource = null,
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    keyToDelete = key
                                }
                            )
                        }
                    }
                }
            }
        } else {
            SlateCard(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.keys_empty),
                        fontSize = rhythm.bodySize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    keyToDelete?.let { keyValue ->
        AlertDialog(
            onDismissRequest = { keyToDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_key_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    keyToDelete = null
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { keyManager.removeKey(keyValue) }
                        if (removed) {
                            keys = withContext(Dispatchers.IO) { keyManager.getKeys() }
                        } else {
                            testResult = keystoreErrorMsg
                            testSuccess = false
                        }
                    }
                }) {
                    Text(stringResource(R.string.delete_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { keyToDelete = null }) {
                    Text(stringResource(R.string.commands_cancel))
                }
            }
        )
    }
}