package com.aischeck.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFF0E0E0E)
private val Surface = Color(0xFF1B1B1B)
private val OnDark = Color(0xFFEDEDED)
private val Muted = Color(0xFF8A8A8A)
private val Red = Color(0xFFE53935)
private val Orange = Color(0xFFFB8C00)
private val Green = Color(0xFF43A047)

class MainActivity : ComponentActivity() {

    private var listsState = mutableStateOf<Lists?>(null)
    private var loading = mutableStateOf(true)
    private var incomingText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        loadLists()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surface)) {
                Screen(
                    lists = listsState.value,
                    loading = loading.value,
                    prefill = incomingText.value,
                    onPrefillConsumed = { incomingText.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND ->
                if (intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT) else null
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) incomingText.value = text
    }

    private fun loadLists() {
        loading.value = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ListRepository.load(this@MainActivity) }
            listsState.value = result
            loading.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(
    lists: Lists?,
    loading: Boolean,
    prefill: String?,
    onPrefillConsumed: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<MatchStatus?>(null) }

    // When a share/link arrives, prefill and auto-check once lists are ready.
    LaunchedEffect(prefill, lists) {
        if (prefill != null) {
            query = prefill
            if (lists != null) {
                status = ListRepository.check(lists, prefill)
            }
            onPrefillConsumed()
        }
    }

    fun runCheck() {
        val l = lists ?: return
        if (query.isBlank()) { status = null; return }
        status = ListRepository.check(l, query)
    }

    Scaffold(containerColor = Bg) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            Text(
                "AiSCheck",
                color = OnDark,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Is this YouTube channel AI slop?",
                color = Muted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it; status = null },
                placeholder = { Text("Paste a channel name or URL", color = Muted) },
                singleLine = false,
                textStyle = LocalTextStyle.current.copy(fontSize = 18.sp, color = OnDark),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runCheck() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OnDark,
                    unfocusedBorderColor = Color(0xFF3A3A3A),
                    cursorColor = OnDark,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface
                )
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { runCheck() },
                enabled = lists != null && !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = OnDark,
                    contentColor = Bg,
                    disabledContainerColor = Color(0xFF2A2A2A),
                    disabledContentColor = Muted
                )
            ) {
                Text(
                    if (loading) "Updating lists…" else "Check",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(28.dp))

            status?.let { ResultCard(it) }

            Spacer(Modifier.weight(1f))
            Footer(lists, loading)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ResultCard(status: MatchStatus) {
    val (bg, label, sub) = when (status) {
        MatchStatus.BLOCK -> Triple(Red, "AI alert", "On the blocklist")
        MatchStatus.WARN -> Triple(Orange, "Possibly AI", "On the warnlist")
        MatchStatus.CLEAR -> Triple(Green, "Probably human", "Not on either list")
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 44.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                sub,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun Footer(lists: Lists?, loading: Boolean) {
    val text = when {
        loading -> "Refreshing from GitHub…"
        lists == null -> ""
        else -> {
            val counts = "${lists.block.size + lists.warn.size} channels"
            val src = if (lists.fromCache) "cached (offline)" else "up to date"
            val date = lists.lastModified?.let { " · $it" } ?: ""
            "$counts · $src$date"
        }
    }
    if (text.isNotEmpty()) {
        Text(text, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}
