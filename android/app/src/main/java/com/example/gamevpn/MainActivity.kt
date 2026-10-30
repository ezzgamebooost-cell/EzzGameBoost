package com.example.gamevpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppItem(val label: String, val pkg: String, val icon: Bitmap?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { Surface(Modifier.fillMaxSize()) { Root() } }
            }
        }
    }
}

private fun prefs(c: Context) = c.getSharedPreferences("gamevpn", Context.MODE_PRIVATE)

private fun loadApps(c: Context): List<AppItem> {
    val pm = c.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .map { it.activityInfo.packageName to it }
        .distinctBy { it.first }
        .filter { it.first != c.packageName }
        .map { (pkg, ri) ->
            val d = ri.loadIcon(pm)
            val bmp = try {
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                    val cv = Canvas(it); d.setBounds(0, 0, 96, 96); d.draw(cv)
                }
            } catch (e: Exception) { null }
            AppItem(ri.loadLabel(pm).toString(), pkg, bmp)
        }.sortedBy { it.label.lowercase() }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    var token by remember { mutableStateOf(prefs(ctx).getString("token", null)) }
    if (token == null) {
        LoginScreen { t -> prefs(ctx).edit().putString("token", t).apply(); token = t }
    } else {
        MainScreen(token!!) { prefs(ctx).edit().remove("token").apply(); token = null }
    }
}

@Composable
fun LoginScreen(onToken: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("ورود به حساب", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(u, { u = it.trim() }, label = { Text("نام کاربری") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(p, { p = it }, label = { Text("رمز عبور") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !busy && u.isNotEmpty() && p.isNotEmpty(),
            onClick = {
                busy = true; err = null
                scope.launch {
                    try { onToken(Api.login(u, p)) }
                    catch (e: Exception) { err = if (e is ApiError) e.message else "اتصال به سرور ناموفق بود" }
                    busy = false
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "..." else "ورود") }
    }
}

@Composable
fun MainScreen(token: String, onLogout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Text("🎮") }, label = { Text("اتصال") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Text("💳") }, label = { Text("تعرفه و خرید") })
        }
    }) { pad ->
        Box(Modifier.padding(pad)) {
            if (tab == 0) HomeTab(token, onLogout) else PlansTab()
        }
    }
}

@Composable
fun HomeTab(token: String, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<Me?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var selected by remember { mutableStateOf(prefs(ctx).getStringSet("games", emptySet())!!.toSet()) }
    var cfgId by remember { mutableIntStateOf(prefs(ctx).getInt("cfg", -1)) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try { me = Api.me(token); msg = null }
        catch (e: ApiError) { if (e.code == 401 || e.code == 403) { onLogout() } else msg = e.message }
        catch (e: Exception) { msg = "اتصال به سرور ناموفق بود" }
    }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { loadApps(ctx) }
        refresh()
    }

    fun connect() {
        val m = me ?: return
        val cfg = m.configs.firstOrNull { it.id == cfgId } ?: m.configs.firstOrNull() ?: run { msg = "کانفیگی برای شما ثبت نشده"; return }
        if (selected.isEmpty()) { msg = "حداقل یک بازی انتخاب کنید"; return }
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (cfg.type == "wireguard") WgTunnel.up(ctx, cfg.content, selected)
                    else ctx.startService(Intent(ctx, V2RayVpnService::class.java)
                        .putExtra(V2RayVpnService.EXTRA_CONFIG, cfg.content)
                        .putStringArrayListExtra(V2RayVpnService.EXTRA_PACKAGES, ArrayList(selected)))
                }
                connected = true; msg = null
            } catch (e: Exception) { msg = e.message ?: "اتصال ناموفق بود" }
            busy = false
        }
    }
    fun disconnect() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try { WgTunnel.down(ctx) } catch (_: Exception) {}
                ctx.startService(Intent(ctx, V2RayVpnService::class.java).setAction(V2RayVpnService.ACTION_STOP))
            }
            connected = false
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) connect() else msg = "مجوز VPN داده نشد"
    }
    fun requestAndConnect() {
        val i = VpnService.prepare(ctx)
        if (i != null) permLauncher.launch(i) else connect()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        val m = me
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(m?.username ?: "...", style = MaterialTheme.typography.titleMedium)
                Text(
                    when { m == null -> ""; m.active -> "${m.daysLeft} روز اعتبار باقی مانده"; else -> "اشتراک تمام شده — از تب تعرفه تمدید کنید" },
                    color = if (m?.active == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { disconnect(); onLogout() }) { Text("خروج") }
        }
        msg?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error) }

        if (m != null && m.configs.size > 1) {
            Spacer(Modifier.height(8.dp))
            Text("سرور", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.configs.forEach { c ->
                    FilterChip(selected = (cfgId == c.id) || (cfgId == -1 && c == m.configs.first()),
                        onClick = { cfgId = c.id; prefs(ctx).edit().putInt("cfg", c.id).apply() },
                        label = { Text(c.name) })
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("بازی‌هایی که از تانل رد شوند (${selected.size})", style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.pkg }) { a ->
                val on = a.pkg in selected
                Row(Modifier.fillMaxWidth().clickable(enabled = !connected) {
                    selected = if (on) selected - a.pkg else selected + a.pkg
                    prefs(ctx).edit().putStringSet("games", selected).apply()
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, null, enabled = !connected)
                    a.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(36.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Text(a.label)
                }
            }
        }
        Button(
            onClick = { if (connected) disconnect() else requestAndConnect() },
            enabled = !busy && (connected || (m?.active == true && m.configs.isNotEmpty())),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (busy) "..." else if (connected) "قطع اتصال" else "اتصال") }
    }
}

@Composable
fun PlansTab() {
    val ctx = LocalContext.current
    var plans by remember { mutableStateOf<Plans?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { plans = Api.plans() } catch (e: Exception) { err = "دریافت تعرفه ناموفق بود" } }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("تعرفه‌ها", style = MaterialTheme.typography.headlineSmall)
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        plans?.plans?.forEach { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium)
                    Text(p.price, style = MaterialTheme.typography.titleLarge)
                    if (p.desc.isNotBlank()) Text(p.desc, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        plans?.let { pl ->
            Button(
                onClick = {
                    // خارج از اپ (مرورگر/تلگرام) باز می‌شود
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pl.telegramUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("خرید از تلگرام") }
        }
    }
}
