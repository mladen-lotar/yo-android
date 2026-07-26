package com.example.yo.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.yo.domain.model.AuthFailure
import com.example.yo.ui.theme.YoLabel
import com.example.yo.ui.theme.YoPalette
import com.example.yo.ui.theme.YoRowText
import com.example.yo.ui.theme.YoTagline
import com.example.yo.ui.theme.YoWordmark

private val ROW_HEIGHT = 89.dp

/**
 * The sign-in gate. Two 89dp bands over the Amethyst field, in the same chromeless idiom as the
 * main screen: no app bar, no card, no outlined text fields.
 *
 * Yo asked for a username and a password and nothing else - no email, no phone verification - and
 * that is exactly what this asks for.
 */
@Composable
fun AuthScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val submitSignUp = { viewModel.signUp(username, password) }
    val submitLogIn = { viewModel.logIn(username, password) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(YoPalette.Amethyst)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "Yo", style = YoWordmark)
            Text(text = "IT'S THAT SIMPLE.", style = YoTagline)

            CredentialField(
                value = username,
                onValueChange = { username = it.uppercase() },
                placeholder = "USERNAME",
                capitalize = true,
                isPassword = false,
                imeAction = ImeAction.Next,
                onSubmit = submitSignUp,
                modifier = Modifier.padding(top = 40.dp),
            )
            CredentialField(
                value = password,
                onValueChange = { password = it },
                placeholder = "PASSWORD",
                capitalize = false,
                isPassword = true,
                imeAction = ImeAction.Done,
                onSubmit = submitSignUp,
                modifier = Modifier.padding(top = 8.dp),
            )

            val message = state.message
            if (message != null) {
                Text(
                    text = message,
                    style = YoLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }

        ActionBand(
            label = if (state.busy) "..." else "SIGN UP",
            color = YoPalette.Rows[0],
            enabled = !state.busy,
            onClick = submitSignUp,
        )
        ActionBand(
            label = "LOG IN",
            color = YoPalette.Rows[2],
            enabled = !state.busy,
            onClick = submitLogIn,
        )
    }
}

@Composable
private fun ActionBand(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(color)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = YoRowText)
    }
}

/**
 * A field with no box, no underline and no label - just centred white type on the purple, so the
 * screen keeps the original's total absence of chrome.
 */
@Composable
private fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    capitalize: Boolean,
    isPassword: Boolean,
    imeAction: ImeAction,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (value.isEmpty()) {
            Text(text = placeholder, style = YoLabel)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = YoLabel.copy(textAlign = TextAlign.Center),
            cursorBrush = SolidColor(YoPalette.OnColor),
            visualTransformation =
                if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization =
                    if (capitalize) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Human wording for each failure, so the screen never shows a bare status code. */
fun AuthFailure.message(): String = when (this) {
    AuthFailure.UsernameTaken -> "THAT NAME IS TAKEN"
    AuthFailure.InvalidCredentials -> "WRONG USERNAME OR PASSWORD"
    AuthFailure.Rejected -> "2-32 LETTERS OR DIGITS, PASSWORD 8+"
    AuthFailure.RateLimited -> "TOO MANY TRIES — WAIT A MINUTE"
    AuthFailure.Unreachable -> "CAN'T REACH YO"
}
