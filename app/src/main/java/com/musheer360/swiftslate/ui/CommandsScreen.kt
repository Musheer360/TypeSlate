package com.musheer360.swiftslate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import com.musheer360.swiftslate.ui.components.LocalSlateRhythm
import com.musheer360.swiftslate.ui.components.ScreenTitle
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateItemCard
import com.musheer360.swiftslate.ui.components.SlateTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(commandManager: CommandManager) {
    val haptic = LocalHapticFeedback.current
    var commands by remember { mutableStateOf(commandManager.getCommands()) }
    val displayCommands = remember(commands) {
        val (builtIn, custom) = commands.partition { it.isBuiltIn }
        val (translateCmd, otherBuiltIns) = builtIn.partition { it.trigger.endsWith("translate:xx") }
        otherBuiltIns + translateCmd + custom
    }
    var trigger by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedType by rememberSaveable { mutableStateOf(CommandType.AI) }
    var editingTrigger by rememberSaveable { mutableStateOf<String?>(null) }
    var commandToDelete by remember { mutableStateOf<String?>(null) }
    var isFormExpanded by rememberSaveable { mutableStateOf(false) }
    val prefix = commandManager.getTriggerPrefix()
    val errorPrefixMsg = stringResource(R.string.commands_error_prefix, prefix)
    val errorDuplicateMsg = stringResource(R.string.commands_error_duplicate)
    val errorConflictTemplate = stringResource(R.string.commands_error_conflict, "\u0000")
    val errorEmptyTrigger = stringResource(R.string.commands_error_empty_trigger)
    val collapseLabel = stringResource(R.string.commands_collapse)
    val expandLabel = stringResource(R.string.commands_expand)

    // Search & collapse state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }

    val filteredCommands = remember(displayCommands, searchQuery) {
        if (searchQuery.isBlank()) displayCommands
        else displayCommands.filter { it.trigger.contains(searchQuery, ignoreCase = true) }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isFormExpanded) 0f else 180f,
        animationSpec = tween(250),
        label = "chevron"
    )

    val rhythm = LocalSlateRhythm.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { }
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV)
    ) {
        ScreenTitle(stringResource(R.string.commands_title))

        // Search pill
        if (displayCommands.isNotEmpty()) {
            val searchLabel = stringResource(R.string.commands_search_hint)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap)
                    .semantics { contentDescription = searchLabel },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = rhythm.emphasisSize,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = searchLabel,
                                        fontSize = rhythm.emphasisSize,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.commands_search_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable(interactionSource = null, indication = null) {
                                    searchQuery = ""
                                }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(
                        imageVector = if (expandedIds.isEmpty()) Icons.AutoMirrored.Filled.List else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expandedIds.isEmpty()) expandLabel else collapseLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(interactionSource = null, indication = null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                expandedIds = if (expandedIds.isEmpty()) {
                                    filteredCommands.map { it.trigger }.toSet()
                                } else {
                                    emptySet()
                                }
                            }
                    )
                }
            }

            // Commands list
            SlateCard(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(rhythm.listGap),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    if (filteredCommands.isEmpty() && searchQuery.isNotBlank()) {
                        item {
                            Text(
                                text = stringResource(R.string.commands_search_empty),
                                fontSize = rhythm.bodySize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    items(filteredCommands, key = { it.trigger }) { cmd ->
                        val isExpanded = cmd.trigger in expandedIds
                        SlateItemCard(
                            modifier = Modifier.clickable(
                                interactionSource = null,
                                indication = null,
                                onClickLabel = if (isExpanded) collapseLabel else expandLabel
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                expandedIds = if (isExpanded) expandedIds - cmd.trigger
                                else expandedIds + cmd.trigger
                            }
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = cmd.trigger,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = rhythm.emphasisSize,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (!cmd.isBuiltIn) {
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(
                                            text = stringResource(R.string.commands_edit_command),
                                            fontSize = rhythm.bodySize,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.clickable(
                                                interactionSource = null,
                                                indication = null
                                            ) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                trigger = cmd.trigger
                                                prompt = cmd.prompt
                                                selectedType = cmd.type
                                                editingTrigger = cmd.trigger
                                                errorMessage = null
                                                isFormExpanded = true
                                            }
                                        )
                                        Text(
                                            text = " | ",
                                            fontSize = rhythm.bodySize,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = stringResource(R.string.commands_delete_command),
                                            fontSize = rhythm.bodySize,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.clickable(
                                                interactionSource = null,
                                                indication = null
                                            ) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                commandToDelete = cmd.trigger
                                            }
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(
                                            text = stringResource(R.string.commands_built_in),
                                            fontSize = rhythm.bodySize,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                AnimatedVisibility(
                                    visible = isExpanded,
                                    enter = expandVertically(
                                        animationSpec = tween(250),
                                        expandFrom = Alignment.Top
                                    ) + fadeIn(tween(200)),
                                    exit = shrinkVertically(
                                        animationSpec = tween(250),
                                        shrinkTowards = Alignment.Top
                                    ) + fadeOut(tween(150))
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(rhythm.formGap))
                                        Text(
                                            text = cmd.prompt,
                                            fontSize = rhythm.bodySize,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Collapsible form card — at the bottom
        SlateCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClickLabel = if (isFormExpanded) collapseLabel else expandLabel
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isFormExpanded = !isFormExpanded
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.commands_add_custom_title),
                    fontSize = rhythm.emphasisSize,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
                )
            }

            AnimatedVisibility(
                visible = isFormExpanded,
                enter = expandVertically(
                    animationSpec = tween(250),
                    expandFrom = Alignment.Top
                ) + fadeIn(tween(200)),
                exit = shrinkVertically(
                    animationSpec = tween(250),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(tween(150))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(rhythm.groupGap))
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = selectedType == CommandType.AI,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedType = CommandType.AI
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                activeBorderColor = MaterialTheme.colorScheme.primary,
                                inactiveContainerColor = MaterialTheme.colorScheme.surface,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                inactiveBorderColor = MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Text(stringResource(R.string.commands_type_ai))
                        }
                        SegmentedButton(
                            selected = selectedType == CommandType.TEXT_REPLACER,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedType = CommandType.TEXT_REPLACER
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                activeBorderColor = MaterialTheme.colorScheme.primary,
                                inactiveContainerColor = MaterialTheme.colorScheme.surface,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                inactiveBorderColor = MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Text(stringResource(R.string.commands_type_replacer))
                        }
                    }
                    Spacer(modifier = Modifier.height(rhythm.groupGap))
                    SlateTextField(
                        value = trigger,
                        onValueChange = {
                            // take(), not a conditional guard: rejecting an over-limit value
                            // outright leaves the platform's IME composing state (autocorrect,
                            // predictive text, multi-char composition) pointing at text Compose
                            // never accepted, which some keyboards resync from badly — the next
                            // keystroke lands as a cursor move or a silently dropped edit
                            // instead of a change. Truncating always accepts *some* update, so
                            // Compose and the IME stay in sync. See #129.
                            trigger = it.take(CommandManager.MAX_TRIGGER_LENGTH)
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.commands_trigger_label, prefix)) },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(rhythm.formGap))
                    SlateTextField(
                        value = prompt,
                        onValueChange = {
                            // See the trigger field's onValueChange above for why take() and
                            // not a conditional guard.
                            prompt = it.take(CommandManager.MAX_PROMPT_LENGTH)
                            errorMessage = null
                        },
                        label = { Text(if (selectedType == CommandType.AI) stringResource(R.string.commands_prompt_label) else stringResource(R.string.commands_replacement_label)) },
                        singleLine = false,
                        modifier = Modifier.height(100.dp)
                    )
                    errorMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = rhythm.bodySize,
                            modifier = Modifier.padding(top = rhythm.formGap)
                        )
                    }
                    Spacer(modifier = Modifier.height(rhythm.groupGap))
                    if (editingTrigger != null) {
                        TextButton(
                            onClick = {
                                trigger = ""
                                prompt = ""
                                errorMessage = null
                                editingTrigger = null
                                selectedType = CommandType.AI
                                isFormExpanded = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.commands_cancel))
                        }
                    }
                    Button(
                        onClick = {
                            val trimmedTrigger = trigger.trim()
                            if (trimmedTrigger.isNotBlank() && prompt.isNotBlank()) {
                                if (!trimmedTrigger.startsWith(prefix)) {
                                    errorMessage = errorPrefixMsg
                                    return@Button
                                }
                                if (trimmedTrigger == prefix || trimmedTrigger.length <= prefix.length) {
                                    errorMessage = errorEmptyTrigger
                                    return@Button
                                }
                                if (commands.any { it.trigger == trimmedTrigger && it.trigger != editingTrigger }) {
                                    errorMessage = errorDuplicateMsg
                                    return@Button
                                }
                                val conflicting = commands.firstOrNull {
                                    it.trigger != editingTrigger &&
                                    (it.trigger.startsWith(trimmedTrigger) || trimmedTrigger.startsWith(it.trigger))
                                }
                                if (conflicting != null) {
                                    errorMessage = errorConflictTemplate.replace("\u0000", conflicting.trigger)
                                    return@Button
                                }
                                if (!CommandManager.isValidCommand(trimmedTrigger, prompt.trim(), prefix)) {
                                    errorMessage = errorEmptyTrigger
                                    return@Button
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                // Single atomic write: replaces the command being edited (or the
                                // same trigger, when adding) without a delete-then-add window.
                                val saved = commandManager.saveCustomCommand(
                                    command = Command(trimmedTrigger, prompt.trim(), false, selectedType),
                                    replacing = editingTrigger ?: trimmedTrigger
                                )
                                commands = commandManager.getCommands()
                                if (!saved) return@Button
                                trigger = ""
                                prompt = ""
                                errorMessage = null
                                editingTrigger = null
                                selectedType = CommandType.AI
                                isFormExpanded = false
                            }
                        },
                        enabled = trigger.isNotBlank() && trigger.trim() != prefix && prompt.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(if (editingTrigger != null) stringResource(R.string.commands_save_command) else stringResource(R.string.commands_add_command))
                    }
                }
            }
        }
    }

    commandToDelete?.let { triggerToDelete ->
        AlertDialog(
            onDismissRequest = { commandToDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_command_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    commandManager.removeCustomCommand(triggerToDelete)
                    expandedIds = expandedIds - triggerToDelete
                    if (editingTrigger == triggerToDelete) {
                        trigger = ""
                        prompt = ""
                        errorMessage = null
                        editingTrigger = null
                        selectedType = CommandType.AI
                        isFormExpanded = false
                    }
                    commands = commandManager.getCommands()
                    commandToDelete = null
                }) {
                    Text(stringResource(R.string.delete_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { commandToDelete = null }) {
                    Text(stringResource(R.string.commands_cancel))
                }
            }
        )
    }
}
