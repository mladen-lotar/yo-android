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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
import com.example.yo.ui.theme.YoRowTextSmall
import com.example.yo.ui.theme.YoTagline
import com.example.yo.ui.theme.YoWordmark

private val ROW_HEIGHT = 89.dp

/**
 * The sign-in gate. 89dp bands over the Amethyst field, in the same chromeless idiom as the main
 * screen: no app bar, no card, no outlined text fields.
 *
 * Yo asked for a username and a password and nothing else - no email, no phone verification - and
 * that is exactly what this asks for. Google sign-in is offered alongside, never instead, and only
 * when the build carries a client id.
 *
 * The screen has two shapes. Normally it takes a username and a password. After Google identifies
 * an account the backend has never seen, it asks for a username alone - the password field would
 * be meaningless, because a Google account never gets one.
 */
@Composable
fun AuthScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    val submitSignUp = { viewModel.signUp(username, password) }
    val submitLogIn = { viewModel.logIn(username, password) }
    val submitGoogleUsername = { viewModel.confirmGoogleUsername(username) }

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
                imeAction = if (state.awaitingUsername) ImeAction.Done else ImeAction.Next,
                onSubmit = if (state.awaitingUsername) submitGoogleUsername else submitSignUp,
                modifier = Modifier.padding(top = 40.dp),
            )
            if (!state.awaitingUsername) {
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
            }

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

        if (state.awaitingUsername) {
            ActionBand(
                label = if (state.busy) "..." else "CLAIM IT",
                color = YoPalette.Rows[0],
                enabled = !state.busy,
                onClick = submitGoogleUsername,
            )
            ActionBand(
                label = "CANCEL",
                color = YoPalette.Rows[3],
                enabled = !state.busy,
                onClick = viewModel::cancelGoogle,
            )
        } else {
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
            // Absent entirely rather than disabled when the build has no client id: a dead band
            // in Yo's own idiom is indistinguishable from a live one.
            if (viewModel.googleAvailable) {
                ActionBand(
                    // "CONTINUE WITH GOOGLE" does not fit: Montserrat Bold caps run about 0.75em,
                    // so twenty of them need ~467dp at the row size against this screen's 411dp.
                    // It wrapped and the 89dp band clipped the second line. Measured on an S25 -
                    // dropping to the long-label size was still not enough, hence a shorter label.
                    label = "GOOGLE SIGN-IN",
                    color = YoPalette.Rows[6],
                    enabled = !state.busy,
                    onClick = { viewModel.continueWithGoogle(context) },
                    style = YoRowTextSmall,
                )
            }
        }
    }
}

@Composable
private fun ActionBand(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    style: TextStyle = YoRowText,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(color)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // A band is a fixed 89dp, so a label that wraps loses its second line rather than growing
        // the band. One line, always.
        Text(text = label, style = style, maxLines = 1)
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
    AuthFailure.GoogleRejected -> "GOOGLE SIGN-IN FAILED"
    AuthFailure.GoogleUnavailable -> "GOOGLE SIGN-IN UNAVAILABLE"
    AuthFailure.Unreachable -> "CAN'T REACH YO"
}
