package com.magichandy.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.magichandy.mobile.ui.theme.MagicHandyTheme

/**
 * A labelled single-line text field. A secret field hides its text and turns off suggestions, so
 * keys and tokens never reach the keyboard's history. [isVerbatim] turns off autocorrect for text
 * that must stay exactly as typed, such as a model ID.
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isSecret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    isVerbatim: Boolean = false,
) {
    val colors = MagicHandyTheme.colors
    val shape = MagicHandyTheme.shapes.control
    Column(modifier.fillMaxWidth()) {
        Text(
            label,
            style = MagicHandyTheme.type.small,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = MagicHandyTheme.dimens.snug),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MagicHandyTheme.type.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation =
                if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = if (isSecret) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = !isSecret && !isVerbatim,
                    capitalization = KeyboardCapitalization.None,
                ),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = MagicHandyTheme.dimens.control)
                    .clip(shape)
                    .background(colors.well)
                    .border(MagicHandyTheme.dimens.hairline, colors.fieldLine, shape)
                    .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier.padding(
                        horizontal = MagicHandyTheme.dimens.m,
                        vertical = MagicHandyTheme.dimens.m,
                    ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = MagicHandyTheme.type.body, color = colors.faint)
                    }
                    inner()
                }
            },
        )
    }
}
