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

@Composable
fun LoginScreen(repo: Repo) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    var create by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 420.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("DayPulse", style = MaterialTheme.typography.headlineSmall)
                    Text("Plan the day. Check in hourly. Watch yourself improve.", color = p.muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.size(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                error?.let { Text(it, color = p.bad, fontSize = 14.sp) }
                Button(
                    onClick = {
                        busy = true; error = null
                        scope.launch { error = repo.signIn(email, password, create); busy = false }
                    },
                    enabled = !busy && email.contains("@") && password.length >= 6,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "One moment…" else if (create) "Create account" else "Sign in") }
                TextButton(onClick = { create = !create; error = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (create) "Have an account? Sign in" else "New here? Create an account")
                }
                Text(
                    "Use the same account on your phone, laptop and the web — everything stays in sync. Your Mind Dump account works here too.",
                    color = p.faint, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
