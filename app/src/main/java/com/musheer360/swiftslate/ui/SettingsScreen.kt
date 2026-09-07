package com.musheer360.swiftslate.ui

import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.BuildConfig
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.api.ApiClientUtils
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.manager.ProviderModelsCache
import com.musheer360.swiftslate.model.GeminiModels
import com.musheer360.swiftslate.model.GroqModels
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.model.ProviderType
import com.musheer360.swiftslate.provider.EndpointValidator
import com.musheer360.swiftslate.provider.GroqConfig
import com.musheer360.swiftslate.ui.components.LocalSlateRhythm
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateDivider
import com.musheer360.swiftslate.ui.components.SlateTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(commandManager: CommandManager, prefs: SharedPreferences, keyManager: KeyManager) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current

    val scope = rememberCoroutineScope()
    var saveEndpointJob by remember { mutableStateOf<Job?>(null) }
    var saveModelJob by remember { mutableStateOf<Job?>(null) }

    var providerType by remember { mutableStateOf(prefs.getString(PrefKeys.PROVIDER_TYPE, ProviderType.GEMINI) ?: ProviderType.GEMINI) }
    var providerExpanded by remember { mutableStateOf(false) }

    var selectedModel by remember { mutableStateOf(prefs.getString(PrefKeys.GEMINI_MODEL, "") ?: "") }
    var modelExpanded by remember { mutableStateOf(false) }
    var geminiModelList by remember { mutableStateOf(ProviderModelsCache.get(ProviderType.GEMINI)?.models ?: emptyList()) }

    var groqModel by remember { mutableStateOf(prefs.getString(PrefKeys.GROQ_MODEL, "") ?: "") }
    var groqModelExpanded by remember { mutableStateOf(false) }
    var groqModelList by remember { mutableStateOf(ProviderModelsCache.get(ProviderType.GROQ)?.models ?: emptyList()) }

    var customEndpoint by rememberSaveable { mutableStateOf(prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "") }
    var customModel by rememberSaveable { mutableStateOf(prefs.getString(PrefKeys.CUSTOM_MODEL, "") ?: "") }
    var endpointError by remember { mutableStateOf<String?>(null) }
    // Fetched model ids for the Custom provider dropdown. Session state only — refetched on
    // demand, never persisted (the stored pref stays the plain custom_model string).
    var customModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var customModelExpanded by remember { mutableStateOf(false) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchMessage by remember { mutableStateOf<String?>(null) }
    var fetchSuccess by remember { mutableStateOf(false) }
    var isFetchingGeminiModels by remember { mutableStateOf(false) }
    var isFetchingGroqModels by remember { mutableStateOf(false) }
    var apiKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    val openAIClient = remember { OpenAICompatibleClient() }
    val geminiClient = remember { GeminiClient() }

    var triggerPrefix by remember { mutableStateOf(commandManager.getTriggerPrefix()) }
    var prefixError by remember { mutableStateOf<String?>(null) }
    var temperature by remember { mutableStateOf(prefs.getFloat(PrefKeys.TEMPERATURE, 0.5f)) }

    val prefixErrorLength = stringResource(R.string.settings_prefix_error_length)
    val prefixErrorWhitespace = stringResource(R.string.settings_prefix_error_whitespace)
    val prefixErrorAlphanumeric = stringResource(R.string.settings_prefix_error_alphanumeric)
    val endpointErrorScheme = stringResource(R.string.settings_endpoint_error_scheme)
    val endpointErrorSpaces = stringResource(R.string.settings_endpoint_error_spaces)
    val fetchModelsMsg = stringResource(R.string.settings_fetch_models)
    val fetchingModelsMsg = stringResource(R.string.settings_fetch_models_loading)
    val modelsLoadedMsg = stringResource(R.string.settings_fetch_models_success)
    val modelsEmptyMsg = stringResource(R.string.settings_fetch_models_empty)
    val modelsFailedMsg = stringResource(R.string.settings_fetch_models_failed)
    val signinRequiredMsg = stringResource(R.string.error_provider_auth_required)

    // Registered keys are decrypted through the Keystore — load off the main thread, as
    // KeysScreen does. The first key is sent as Bearer when fetching models; keyless local
    // servers get no header at all.
    LaunchedEffect(Unit) {
        apiKeys = withContext(Dispatchers.IO) { keyManager.getKeys() }
    }

    // Fetches one provider's live model list (issue #148). Groq rides the existing
    // OpenAI-compatible prober against its fixed endpoint; Gemini gets a native
    // listing call. Results land in [ProviderModelsCache] plus local state so the
    // dropdown renders without refetching on every visit.
    fun startModelFetch(type: String) {
        val isGemini = type == ProviderType.GEMINI
        if (!isGemini && type != ProviderType.GROQ) return
        if (isGemini && isFetchingGeminiModels) return
        if (!isGemini && isFetchingGroqModels) return

        val key = apiKeys.firstOrNull() ?: return

        if (isGemini) isFetchingGeminiModels = true else isFetchingGroqModels = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (isGemini) {
                    geminiClient.fetchModels(key)
                } else {
                    openAIClient.fetchModels(key, GroqConfig.ENDPOINT).map { ids ->
                        ids.filter { GroqModels.isChatCandidate(it) }
                    }
                }
            }
            val models = result.getOrNull().orEmpty()
            val success = result.isSuccess && models.isNotEmpty()
            val currentModels = if (isGemini) geminiModelList else groqModelList
            val toCache = if (success) models else (ProviderModelsCache.get(type)?.models ?: currentModels)
            ProviderModelsCache.put(type, ProviderModelsCache.Entry(toCache, attempted = true))
            if (isGemini) {
                isFetchingGeminiModels = false
                if (success) {
                    geminiModelList = models
                    if (selectedModel.isBlank() && models.isNotEmpty()) {
                        selectedModel = models.first()
                        prefs.edit().putString(PrefKeys.GEMINI_MODEL, models.first()).apply()
                    }
                }
            } else {
                isFetchingGroqModels = false
                if (success) {
                    groqModelList = models
                    if (groqModel.isBlank() && models.isNotEmpty()) {
                        groqModel = models.first()
                        prefs.edit().putString(PrefKeys.GROQ_MODEL, models.first()).apply()
                    }
                }
            }
        }
    }

    // Auto-fetch once per session per provider (issue #148): fires when Settings shows
    // a Gemini/Groq provider whose list has never been fetched this process — including
    // the no-key case, so it runs automatically once a first key is added.
    LaunchedEffect(providerType, apiKeys) {
        if (providerType == ProviderType.GEMINI || providerType == ProviderType.GROQ) {
            val cached = ProviderModelsCache.get(providerType)
            if (apiKeys.isNotEmpty() && (cached == null || !cached.attempted)) {
                startModelFetch(providerType)
            }
        }
    }

    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupSuccess by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            saveEndpointJob?.cancel()
            saveModelJob?.cancel()
            val editor = prefs.edit()
            var needsWrite = false
            if (customEndpoint != (prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "")) {
                val isValid = customEndpoint.isBlank() ||
                    EndpointValidator.validate(customEndpoint) == EndpointValidator.Error.NONE
                if (isValid) {
                    editor.putString(PrefKeys.CUSTOM_ENDPOINT, customEndpoint)
                    needsWrite = true
                }
            }
            if (customModel != (prefs.getString(PrefKeys.CUSTOM_MODEL, "") ?: "")) {
                editor.putString(PrefKeys.CUSTOM_MODEL, customModel)
                needsWrite = true
            }
            if (needsWrite) editor.apply()
        }
    }
    val exportSuccessMsg = stringResource(R.string.backup_export_success)
    val exportErrorMsg = stringResource(R.string.backup_export_error)
    val importSuccessMsg = stringResource(R.string.backup_import_success)
    val importErrorMsg = stringResource(R.string.backup_import_error)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(it)?.use { os ->
                            os.write(commandManager.exportCommands().toByteArray())
                        }
                    }
                    backupMessage = exportSuccessMsg
                    backupSuccess = true
                } catch (_: Exception) {
                    backupMessage = exportErrorMsg
                    backupSuccess = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                            val text = reader.readText().removePrefix("\uFEFF")
                            if (text.length > 1_000_000) null else text
                        } ?: ""
                    }
                    if (commandManager.importCommands(json)) {
                        backupMessage = importSuccessMsg
                        backupSuccess = true
                    } else {
                        backupMessage = importErrorMsg
                        backupSuccess = false
                    }
                } catch (_: Exception) {
                    backupMessage = importErrorMsg
                    backupSuccess = false
                }
            }
        }
    }

    val rhythm = LocalSlateRhythm.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { }
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV)
    ) {
        ScreenTitle(stringResource(R.string.settings_title))

        // Card 1: Provider + Model
        SlateCard {
            Text(
                text = stringResource(R.string.settings_provider_title),
                fontSize = rhythm.bodySize,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(rhythm.formGap))
            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = !providerExpanded }
            ) {
                SlateTextField(
                    value = when (providerType) {
                        ProviderType.GEMINI -> stringResource(R.string.settings_provider_gemini)
                        ProviderType.GROQ -> stringResource(R.string.settings_provider_groq)
                        else -> stringResource(R.string.settings_provider_custom)
                    },
                    onValueChange = {},
                    readOnly = true,
                    
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_provider_gemini)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = ProviderType.GEMINI
                            prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.GEMINI).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                            providerExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_provider_groq)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = ProviderType.GROQ
                            prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.GROQ).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                            providerExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_provider_custom)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            providerType = ProviderType.CUSTOM
                            prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.CUSTOM).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                            providerExpanded = false
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(rhythm.formGap))
            if (providerType == ProviderType.GEMINI) {
                Text(
                    text = stringResource(R.string.settings_model_title),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(rhythm.formGap))
                DynamicModelDropdown(
                    selectedLabel = if (apiKeys.isEmpty() || selectedModel.isBlank()) "" else GeminiModels.label(selectedModel),
                    enabled = apiKeys.isNotEmpty(),
                    expanded = modelExpanded,
                    onExpandedChange = { isOpening ->
                        modelExpanded = isOpening
                        if (isOpening && apiKeys.isNotEmpty() && !isFetchingGeminiModels) {
                            startModelFetch(ProviderType.GEMINI)
                        }
                    },
                    models = geminiModelList,
                    labelFor = { GeminiModels.label(it) },
                    onSelect = { id ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedModel = id
                        prefs.edit().putString(PrefKeys.GEMINI_MODEL, id).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                        modelExpanded = false
                    },
                    onDismiss = { modelExpanded = false },
                    isFetching = isFetchingGeminiModels,
                    fetchingText = fetchingModelsMsg
                )
            } else if (providerType == ProviderType.GROQ) {
                Text(
                    text = stringResource(R.string.settings_model_title),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(rhythm.formGap))
                DynamicModelDropdown(
                    selectedLabel = if (apiKeys.isEmpty() || groqModel.isBlank()) "" else GroqModels.label(groqModel),
                    enabled = apiKeys.isNotEmpty(),
                    expanded = groqModelExpanded,
                    onExpandedChange = { isOpening ->
                        groqModelExpanded = isOpening
                        if (isOpening && apiKeys.isNotEmpty() && !isFetchingGroqModels) {
                            startModelFetch(ProviderType.GROQ)
                        }
                    },
                    models = groqModelList,
                    labelFor = { GroqModels.label(it) },
                    onSelect = { id ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        groqModel = id
                        prefs.edit().putString(PrefKeys.GROQ_MODEL, id).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                        groqModelExpanded = false
                    },
                    onDismiss = { groqModelExpanded = false },
                    isFetching = isFetchingGroqModels,
                    fetchingText = fetchingModelsMsg
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_endpoint_title),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(rhythm.formGap))
                SlateTextField(
                    value = customEndpoint,
                    onValueChange = {
                        customEndpoint = it
                        // The endpoint changed — a previously fetched model list no longer
                        // describes this server.
                        customModels = emptyList()
                        fetchMessage = null
                        endpointError = when {
                            it.isBlank() -> null
                            it.contains(" ") -> endpointErrorSpaces
                            EndpointValidator.validate(it) == EndpointValidator.Error.NONE -> null
                            else -> endpointErrorScheme
                        }
                        if (endpointError == null) {
                            saveEndpointJob?.cancel()
                            saveEndpointJob = scope.launch {
                                delay(500)
                                prefs.edit().putString(PrefKeys.CUSTOM_ENDPOINT, it).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.settings_endpoint_placeholder)) },
                    
                    isError = endpointError != null
                )
                endpointError?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = rhythm.bodySize,
                        modifier = Modifier.padding(top = rhythm.formGap)
                    )
                }
                Spacer(modifier = Modifier.height(rhythm.formGap))
                Text(
                    text = stringResource(R.string.settings_model_title),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(rhythm.formGap))
                if (customModels.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = customModelExpanded,
                        onExpandedChange = { customModelExpanded = !customModelExpanded }
                    ) {
                        SlateTextField(
                            value = customModel,
                            onValueChange = {
                                customModel = it
                                saveModelJob?.cancel()
                                saveModelJob = scope.launch {
                                    delay(500)
                                    prefs.edit().putString(PrefKeys.CUSTOM_MODEL, it).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                }
                            },
                            placeholder = { Text(stringResource(R.string.settings_model_placeholder)) },
                            
                            // Editable anchor: the fetched list is a convenience, not a
                            // restriction — cloud models and off-list ids stay typeable.
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                        )
                        ExposedDropdownMenu(
                            containerColor = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                            expanded = customModelExpanded,
                            onDismissRequest = { customModelExpanded = false }
                        ) {
                            customModels.forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(id) },
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        customModel = id
                                        // Cancel any pending debounce so a half-typed value
                                        // cannot overwrite the selection 500ms later.
                                        saveModelJob?.cancel()
                                        prefs.edit().putString(PrefKeys.CUSTOM_MODEL, id).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                        customModelExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    SlateTextField(
                        value = customModel,
                        onValueChange = {
                            customModel = it
                            saveModelJob?.cancel()
                            saveModelJob = scope.launch {
                                delay(500)
                                prefs.edit().putString(PrefKeys.CUSTOM_MODEL, it).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                            }
                        },
                        placeholder = { Text(stringResource(R.string.settings_model_placeholder)) },
                        
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isFetchingModels = true
                            fetchMessage = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    openAIClient.fetchModels(apiKeys.firstOrNull(), customEndpoint)
                                }
                                isFetchingModels = false
                                result.onSuccess { ids ->
                                    if (ids.isEmpty()) {
                                        customModels = emptyList()
                                        fetchMessage = modelsEmptyMsg
                                        fetchSuccess = false
                                    } else {
                                        customModels = ids
                                        customModelExpanded = false
                                        fetchMessage = String.format(modelsLoadedMsg, ids.size)
                                        fetchSuccess = true
                                    }
                                }.onFailure { e ->
                                    customModels = emptyList()
                                    val raw = e.message ?: ""
                                    fetchMessage = if (raw.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER)) {
                                        signinRequiredMsg
                                    } else {
                                        modelsFailedMsg
                                    }
                                    fetchSuccess = false
                                }
                            }
                        },
                        enabled = customEndpoint.isNotBlank() && endpointError == null && !isFetchingModels
                    ) {
                        Text(if (isFetchingModels) fetchingModelsMsg else fetchModelsMsg)
                    }
                }
                fetchMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = if (fetchSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = rhythm.bodySize,
                        modifier = Modifier.padding(top = rhythm.tightGap)
                    )
                }
            }
            Spacer(modifier = Modifier.height(rhythm.formGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_temperature_title),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = String.format("%.1f", temperature),
                    fontSize = rhythm.emphasisSize,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(rhythm.sliderGap))
            Slider(
                value = temperature,
                onValueChange = {
                    val newVal = Math.round(it * 10) / 10f
                    if (newVal != temperature) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        temperature = newVal
                    }
                },
                onValueChangeFinished = {
                    prefs.edit().putFloat(PrefKeys.TEMPERATURE, temperature).apply()
                },
                valueRange = 0f..2f,
                steps = 19,
                modifier = Modifier.fillMaxWidth().height(rhythm.sliderHeight),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(modifier = Modifier.height(rhythm.tightGap))
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Card 2: Trigger Prefix
        SlateCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_trigger_prefix_desc, triggerPrefix),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 16.dp)
                )
                SlateTextField(
                    value = triggerPrefix,
                    onValueChange = { input ->
                        val filtered = input.take(1)
                        triggerPrefix = filtered
                        prefixError = when {
                            filtered.length != 1 -> prefixErrorLength
                            filtered[0].isWhitespace() -> prefixErrorWhitespace
                            filtered[0].isLetterOrDigit() -> prefixErrorAlphanumeric
                            else -> {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                commandManager.setTriggerPrefix(filtered)
                                null
                            }
                        }
                    },
                    isError = prefixError != null,
                    modifier = Modifier.width(64.dp)
                )
            }
            prefixError?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = rhythm.bodySize,
                    modifier = Modifier.padding(top = rhythm.formGap)
                )
            }
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Card 3: Backup
        SlateCard {
            Text(
                text = stringResource(R.string.backup_desc),
                fontSize = rhythm.bodySize,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(rhythm.groupGap))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        backupMessage = null
                        exportLauncher.launch("swiftslate-commands.json")
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.backup_export))
                }
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        backupMessage = null
                        showImportConfirm = true
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.backup_import))
                }
            }
            backupMessage?.let { msg ->
                Text(
                    text = msg,
                    color = if (backupSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    fontSize = rhythm.bodySize,
                    modifier = Modifier.padding(top = rhythm.formGap)
                )
            }
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Card 4: About. The only weighted child, so its bottom edge lands flush
        // screenPaddingV above the nav bar exactly like the last card on every
        // other tab, and it absorbs whatever slack the rhythm above did not take.
        //
        // That slack is split rather than pooled. The two text groups each sit in
        // an equally weighted half, so the divider lands on the card's midline and
        // each group is centred within its own half. Pooling it instead — whether
        // as weighted spacers hugging the divider (which opened a ~104 dp void on
        // tall screens) or as one centred block (which left dead space above the
        // version line and below the sponsor line) — is what this replaces.
        SlateCard(
            modifier = Modifier.weight(1f),
            fillHeight = true
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.app_name) + " v" + BuildConfig.VERSION_NAME,
                    fontSize = rhythm.emphasisSize,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(rhythm.tightGap))
                Text(
                    text = stringResource(R.string.settings_check_updates),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        uriHandler.openUri("https://github.com/Musheer360/SwiftSlate/releases/latest")
                    }
                )
            }
            Spacer(modifier = Modifier.height(rhythm.groupGap))
            SlateDivider()
            Spacer(modifier = Modifier.height(rhythm.groupGap))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_made_by),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(rhythm.tightGap))
                Text(
                    text = stringResource(R.string.settings_sponsor),
                    fontSize = rhythm.bodySize,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        uriHandler.openUri("https://github.com/sponsors/Musheer360")
                    }
                )
            }
        }
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text(stringResource(R.string.backup_import)) },
            text = { Text(stringResource(R.string.backup_import_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showImportConfirm = false
                    importLauncher.launch(arrayOf("application/json"))
                }) { Text(stringResource(R.string.backup_import)) }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text(stringResource(R.string.backup_import_cancel))
                }
            }
        )
    }
}

/**
 * Read-only dropdown listing one provider's dynamically fetched model ids
 * (issue #148). Opens immediately showing cached or live models, triggers
 * real-time fetch when opened, and caps its height with vertical scroll
 * so long provider catalogs stay usable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DynamicModelDropdown(
    selectedLabel: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    models: List<String>,
    labelFor: (String) -> String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    isFetching: Boolean,
    fetchingText: String
) {
    val rhythm = LocalSlateRhythm.current
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) onExpandedChange(it) }
    ) {
        SlateTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        if (enabled && expanded) {
            ExposedDropdownMenu(
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                expanded = expanded,
                onDismissRequest = onDismiss,
                // Provider catalogs can exceed a hundred entries — cap and scroll.
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                if (isFetching) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = fetchingText,
                                    fontSize = rhythm.bodySize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {},
                        enabled = false
                    )
                    if (models.isNotEmpty()) {
                        SlateDivider()
                    }
                }
                models.forEach { id ->
                    val displayLabel = labelFor(id)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = displayLabel,
                                color = if (displayLabel == selectedLabel || id == selectedLabel) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        },
                        onClick = { onSelect(id) }
                    )
                }
            }
        }
    }
}
