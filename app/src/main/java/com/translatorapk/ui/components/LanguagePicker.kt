package com.translatorapk.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.translatorapk.model.Language

@Composable
fun LanguagePicker(
    selected: String,
    languages: List<Language>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelPrefix: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val current = languages.firstOrNull { it.code == selected }

    Box(modifier = modifier) {
        AssistChip(
            onClick = { expanded = true },
            label = {
                Text(
                    buildString {
                        if (labelPrefix != null) append(labelPrefix).append(": ")
                        append(current?.label ?: selected)
                    }
                )
            },
            leadingIcon = {
                Text(
                    text = current?.flag ?: "?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { language ->
                DropdownMenuItem(
                    text = {
                        Text("${language.flag}  ${language.label}")
                    },
                    onClick = {
                        expanded = false
                        onSelected(language.code)
                    }
                )
            }
        }
    }
}
