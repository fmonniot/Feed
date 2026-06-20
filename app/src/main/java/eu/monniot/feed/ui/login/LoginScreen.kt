package eu.monniot.feed.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.monniot.feed.ui.components.FeedWordmark
import eu.monniot.feed.ui.theme.FeedTheme
import eu.monniot.feed.ui.theme.IbmPlexSans
import eu.monniot.feed.ui.theme.LocalFeedColors
import eu.monniot.feed.ui.theme.SourceSerif4
import eu.monniot.feed.ui.theme.ToneErrBg

@Composable
fun LoginScreen(
    initialUsername: String = "",
    isLoading: Boolean,
    errorMessage: String?,
    onLoginClick: (String, String) -> Unit,
    onErrorDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordFocusRequester = remember { FocusRequester() }
    val colors = LocalFeedColors.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.panel,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
        ) {
            // ── Top bar: 14px vertical padding, wordmark at 18sp ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
            ) {
                FeedWordmark(fontSize = 18.sp)
            }

            // ── Hero section: 24px padding-top, 8px padding-bottom ──
            Column(
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            ) {
                // Eyebrow
                Text(
                    text = "SIGN IN",
                    fontFamily = IbmPlexSans,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    letterSpacing = 0.18.em,
                    color = colors.ink3,
                )
                Spacer(modifier = Modifier.height(10.dp))
                // H1
                Text(
                    text = "Welcome back to your reading room.",
                    fontFamily = SourceSerif4,
                    fontWeight = FontWeight.Medium,
                    fontSize = 30.sp,
                    lineHeight = (30 * 1.1).sp,
                    letterSpacing = (-0.02).em,
                    color = colors.ink,
                )
                Spacer(modifier = Modifier.height(10.dp))
                // Subtitle
                Text(
                    text = "Your feeds, quietly waiting. No algorithm, no infinite scroll.",
                    fontFamily = SourceSerif4,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    lineHeight = (14 * 1.45).sp,
                    color = colors.ink2,
                )
            }

            // ── Form fields: 20px padding-top, 20px gap ──
            Column(
                modifier = Modifier.padding(top = 20.dp),
            ) {
                // Username field
                LoginField(
                    label = "USERNAME",
                    value = username,
                    onValueChange = {
                        username = it
                        if (errorMessage != null) onErrorDismiss()
                    },
                    enabled = !isLoading,
                    tag = "username",
                    autofillContentType = ContentType.Username,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Password field
                LoginField(
                    label = "PASSWORD",
                    value = password,
                    onValueChange = {
                        password = it
                        if (errorMessage != null) onErrorDismiss()
                    },
                    enabled = !isLoading,
                    tag = "password",
                    isPassword = !passwordVisible,
                    autofillContentType = ContentType.Password,
                    fieldFocusRequester = passwordFocusRequester,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (username.isNotBlank() && password.isNotBlank()) onLoginClick(username, password)
                    }),
                    trailingContent = {
                        Text(
                            text = if (passwordVisible) "HIDE" else "SHOW",
                            fontFamily = IbmPlexSans,
                            fontSize = 12.sp,
                            letterSpacing = 0.06.em,
                            color = colors.ink3,
                            modifier = Modifier
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                                .clickable(
                                    enabled = !isLoading,
                                    role = Role.Button,
                                ) {
                                    passwordVisible = !passwordVisible
                                },
                        )
                    },
                )

                // ── Auth error (AUTH-2) ──
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = ToneErrBg,
                                shape = RoundedCornerShape(4.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = colors.danger,
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "!",
                            fontFamily = SourceSerif4,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.danger,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            fontFamily = IbmPlexSans,
                            fontSize = 12.sp,
                            color = colors.danger,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── Primary button ──
                Button(
                    onClick = { onLoginClick(username, password) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && username.isNotBlank() && password.isNotBlank(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.ink,
                        contentColor = colors.onAccent,
                        disabledContainerColor = colors.ink.copy(alpha = 0.4f),
                        disabledContentColor = colors.onAccent.copy(alpha = 0.5f),
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp, horizontal = 22.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.onAccent,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "Sign in",
                        fontFamily = IbmPlexSans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        letterSpacing = 0.02.em,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "→",
                        fontFamily = SourceSerif4,
                        fontSize = 18.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tag: String = "",
    isPassword: Boolean = false,
    autofillContentType: ContentType? = null,
    fieldFocusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val colors = LocalFeedColors.current

    Column(modifier = modifier) {
        // Uppercase label
        Text(
            text = label,
            fontFamily = IbmPlexSans,
            fontSize = 11.sp,
            letterSpacing = 0.14.em,
            color = colors.ink3,
        )
        Spacer(modifier = Modifier.height(6.dp))
        // Input row with bottom border only
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = IbmPlexSans,
                    fontSize = 16.sp,
                    color = if (enabled) colors.ink else colors.muted,
                ),
                cursorBrush = SolidColor(colors.ink),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .weight(1f)
                    .then(if (autofillContentType != null) Modifier.semantics { contentType = autofillContentType } else Modifier)
                    .then(if (fieldFocusRequester != null) Modifier.focusRequester(fieldFocusRequester) else Modifier)
                    .then(if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier),
                decorationBox = { innerTextField ->
                    innerTextField()
                },
            )
            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(12.dp))
                trailingContent()
            }
        }
        // Bottom border
        HorizontalDivider(
            color = if (enabled) colors.borderStrong else colors.border,
            thickness = 1.dp,
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    FeedTheme {
        LoginScreen(
            isLoading = false,
            errorMessage = null,
            onLoginClick = { _, _ -> },
            onErrorDismiss = {},
        )
    }
}
