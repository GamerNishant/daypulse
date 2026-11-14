package com.nishant.daypulse.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nishant.daypulse.R
import com.nishant.daypulse.data.Repo
import kotlinx.coroutines.launch

private enum class Mode { SignIn, Create, Forgot, Finish }

@Composable
fun LoginScreen(repo: Repo) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Mode.SignIn) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun go(next: Mode) {
        mode = next; error = null; info = null; password = ""; confirm = ""
    }

    val title = when (mode) {
        Mode.Forgot -> "Reset password"
        Mode.Finish -> "Choose a new password"
        else -> "DayPulse"
    }
    val subtitle = when (mode) {
        Mode.Forgot -> "We'll email you a reset link."
        Mode.Finish -> "Finish the reset with the link from the email."
        else -> "Plan the day. Check in hourly. Watch yourself improve."
    }

    Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 420.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(subtitle, color = p.muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mode != Mode.Finish) {
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                }
                if (mode == Mode.SignIn || mode == Mode.Create) {
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                    if (mode == Mode.SignIn) {
                        TextButton(onClick = { go(Mode.Forgot) }, modifier = Modifier.align(Alignment.End)) { Text("Forgot password?") }
                    }
                }
                if (mode == Mode.Finish) {
                    info?.let { Text(it, fontSize = 14.sp) }
                    Text(
                        "Tap the link in the email: it opens Mind Dump's reset page, and the new password works here too. " +
                            "Or long-press the email's button, copy the link and paste it below. " +
                            "If the link opens a page that doesn't load, paste that page's address instead.",
                        color = p.muted, fontSize = 13.sp,
                    )
                    OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Link from the email") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("New password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
                    OutlinedTextField(confirm, { confirm = it }, Modifier.fillMaxWidth(), label = { Text("Confirm new password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                }
                error?.let { Text(it, color = p.bad, fontSize = 14.sp) }
                Button(
                    onClick = {
                        error = null
                        if (mode == Mode.Finish && password != confirm) {
                            error = "Passwords don't match."
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            when (mode) {
                                Mode.SignIn, Mode.Create -> error = repo.signIn(email, password, mode == Mode.Create)
                                Mode.Forgot -> {
                                    val err = repo.requestPasswordReset(email)
                                    if (err == null) {
                                        val sentTo = email.trim()
                                        go(Mode.Finish)
                                        info = "If an account exists for $sentTo, a reset email is on its way."
                                    } else {
                                        error = err
                                    }
                                }
                                Mode.Finish -> error = repo.resetWithLink(link, email, password)
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && when (mode) {
                        Mode.SignIn, Mode.Create -> email.contains("@") && password.length >= 6
                        Mode.Forgot -> email.contains("@")
                        Mode.Finish -> link.isNotBlank() && password.length >= 6 && confirm.isNotEmpty()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (busy) "One moment…" else when (mode) {
                            Mode.SignIn -> "Sign in"
                            Mode.Create -> "Create account"
                            Mode.Forgot -> "Send reset email"
                            Mode.Finish -> "Set new password"
                        },
                    )
                }
                if (mode == Mode.SignIn || mode == Mode.Create) {
                    TextButton(onClick = { go(if (mode == Mode.Create) Mode.SignIn else Mode.Create) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (mode == Mode.Create) "Have an account? Sign in" else "New here? Create an account")
                    }
                    Text(
                        "Use the same account on your phone, laptop and the web — everything stays in sync. Your Mind Dump account works here too.",
                        color = p.faint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { go(Mode.SignIn) }) { Text("Back to sign in") }
                        if (mode == Mode.Forgot) {
                            TextButton(onClick = { go(Mode.Finish) }) { Text("I have an email") }
                        } else {
                            TextButton(onClick = { go(Mode.Forgot) }) { Text("Send again") }
                        }
                    }
                }
            }
        }
    }
}
