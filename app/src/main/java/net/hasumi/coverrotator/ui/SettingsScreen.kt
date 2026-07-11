package net.hasumi.coverrotator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.hasumi.coverrotator.di.Graph
import net.hasumi.coverrotator.executor.ConnectionStatus
import net.hasumi.coverrotator.repository.SettingsRepository
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Context
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val startOnBoot by settingsRepository.startOnBoot.collectAsState(initial = true)

    var batteryUnrestricted by remember { mutableStateOf(false) }

    // 設定アプリから戻ってきたときに電池最適化の状態を再チェック
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Collect states from repository
    val autoRotationEnabled by settingsRepository.autoRotationEnabled.collectAsState(initial = false)
    val rotationInversionEnabled by settingsRepository.rotationInversionEnabled.collectAsState(initial = false)
    val deadbandDegrees by settingsRepository.deadbandDegrees.collectAsState(initial = 30.0)
    val debounceMs by settingsRepository.debounceMs.collectAsState(initial = 400L)
    val stabilityMs by settingsRepository.stabilityMs.collectAsState(initial = 0L)
    val savedHost by settingsRepository.adbHost.collectAsState(initial = null)
    val savedPort by settingsRepository.adbConnectionPort.collectAsState(initial = null)

    // ADB connection state
    val adbStatus by Graph.adbExecutor.getConnectionStatus()
        .collectAsState(initial = ConnectionStatus.DISCONNECTED)

    // Local states for text fields
    var hostText by remember { mutableStateOf("127.0.0.1") }
    var portText by remember { mutableStateOf("") }
    var pairPortText by remember { mutableStateOf("") }
    var pairCodeText by remember { mutableStateOf("") }
    var deadbandText by remember { mutableStateOf(deadbandDegrees.toString()) }
    var debounceText by remember { mutableStateOf(debounceMs.toString()) }
    var stabilityText by remember { mutableStateOf(stabilityMs.toString()) }
    var adbMessage by remember { mutableStateOf<String?>(null) }

    // DataStoreの保存値が読めたらフィールドに反映
    LaunchedEffect(savedHost) { savedHost?.let { hostText = it } }
    LaunchedEffect(savedPort) { savedPort?.let { portText = it.toString() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("回転設定", style = MaterialTheme.typography.titleLarge)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("擬似自動回転", modifier = Modifier.weight(1f))
                Switch(
                    checked = autoRotationEnabled,
                    onCheckedChange = {
                        scope.launch { settingsRepository.setAutoRotationEnabled(it) }
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("回転方向を反転", modifier = Modifier.weight(1f))
                Switch(
                    checked = rotationInversionEnabled,
                    onCheckedChange = {
                        scope.launch { settingsRepository.setRotationInversionEnabled(it) }
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("再起動後に自動起動", modifier = Modifier.weight(1f))
                Switch(
                    checked = startOnBoot,
                    onCheckedChange = {
                        scope.launch { settingsRepository.setStartOnBoot(it) }
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("バッテリー最適化")
                    Text(
                        text = if (batteryUnrestricted) "制限なし (推奨状態)" else "最適化中 (サービスが停止する場合があります)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (batteryUnrestricted)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
                Button(
                    onClick = {
                        val intent = if (batteryUnrestricted) {
                            // 既に除外済みなら、アプリ情報画面を開く(手動で戻す用)
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        } else {
                            // 除外リクエストのシステムダイアログ
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                        }
                        context.startActivity(intent)
                    }
                ) {
                    Text(if (batteryUnrestricted) "確認" else "変更")
                }
            }

            HorizontalDivider()

            Text("検知感度", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = deadbandText,
                onValueChange = {
                    deadbandText = it
                    it.toDoubleOrNull()?.let { d ->
                        scope.launch { settingsRepository.setDeadbandDegrees(d) }
                    }
                },
                label = { Text("判定窓の半幅 (度)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = debounceText,
                onValueChange = {
                    debounceText = it
                    it.toLongOrNull()?.let { l ->
                        scope.launch { settingsRepository.setDebounceMs(l) }
                    }
                },
                label = { Text("デバウンス (ms)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = stabilityText,
                onValueChange = {
                    stabilityText = it
                    it.toLongOrNull()?.let { l ->
                        scope.launch { settingsRepository.setStabilityMs(l) }
                    }
                },
                label = { Text("回転確定までの安定時間 (ms)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Text("内蔵adb接続", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Shizuku未使用時のバックエンド。開発者向けオプションの" +
                        "ワイヤレスデバッグ画面に表示される値を入力してください。",
                style = MaterialTheme.typography.bodySmall
            )

            val statusLabel = when (adbStatus) {
                ConnectionStatus.CONNECTED -> "接続済み"
                ConnectionStatus.DISCONNECTED -> "未接続"
                ConnectionStatus.PERMISSION_DENIED -> "権限なし"
                ConnectionStatus.ERROR -> "エラー"
            }
            Text("状態: $statusLabel")
            adbMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            OutlinedTextField(
                value = hostText,
                onValueChange = { hostText = it },
                label = { Text("ホスト (自機は 127.0.0.1)") },
                modifier = Modifier.fillMaxWidth()
            )

            // ペアリング(初回のみ必要)
            OutlinedTextField(
                value = pairPortText,
                onValueChange = { pairPortText = it },
                label = { Text("ペアリングポート") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pairCodeText,
                onValueChange = { pairCodeText = it },
                label = { Text("ペアリングコード (6桁)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val port = pairPortText.toIntOrNull()
                    if (port == null || pairCodeText.isBlank()) {
                        adbMessage = "ペアリングポートとコードを入力してください"
                        return@Button
                    }
                    scope.launch {
                        adbMessage = "ペアリング中..."
                        Graph.adbExecutor.pair(hostText, port, pairCodeText)
                            .onSuccess { adbMessage = "ペアリング成功" }
                            .onFailure { adbMessage = "ペアリング失敗: ${it.message}" }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ペアリング")
            }

            // 接続
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("接続ポート") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val port = portText.toIntOrNull()
                    if (port == null) {
                        adbMessage = "接続ポートを入力してください"
                        return@Button
                    }
                    scope.launch {
                        adbMessage = "接続中..."
                        settingsRepository.setAdbHost(hostText)
                        settingsRepository.setAdbConnectionPort(port)
                        Graph.adbExecutor.connect(hostText, port)
                            .onSuccess { adbMessage = "接続成功" }
                            .onFailure { adbMessage = "接続失敗: ${it.message}" }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("接続")
            }
        }
    }
}
