package net.hasumi.coverrotator.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.hasumi.coverrotator.di.Graph
import net.hasumi.coverrotator.model.CoverDisplayRotationState
import net.hasumi.coverrotator.model.RotationMode
import net.hasumi.coverrotator.repository.RotationRepository
import net.hasumi.coverrotator.repository.SettingsRepository
import net.hasumi.coverrotator.service.AutoRotationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextOverflow

class MainViewModel(
    private val rotationRepository: RotationRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val autoRotationActive: StateFlow<Boolean> = settingsRepository.autoRotationEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshState()
    }

    fun toggleAutoRotation() {
        viewModelScope.launch {
            settingsRepository.setAutoRotationEnabled(!autoRotationActive.value)
        }
    }

    fun refreshState() {
        viewModelScope.launch {
            _uiState.value = MainUiState.Loading
            val result = rotationRepository.getCurrentRotationState()
            result.onSuccess {
                _uiState.value = MainUiState.Success(it)
            }.onFailure {
                _uiState.value = MainUiState.Error(it.message ?: "Unknown error")
            }
        }
    }

    fun setRotation(mode: RotationMode) {
        viewModelScope.launch {
            val result = rotationRepository.setRotationMode(mode)
            if (result.isSuccess) {
                refreshState()
            } else {
                _uiState.value = MainUiState.Error(
                    result.exceptionOrNull()?.message ?: "Failed to set rotation"
                )
            }
        }
    }

    fun resetRotation() {
        viewModelScope.launch {
            rotationRepository.setRotationMode(RotationMode.FREE)
                .onSuccess { refreshState() }
                .onFailure { _uiState.value = MainUiState.Error("Failed to reset rotation") }
        }
    }
}

sealed class MainUiState {
    data object Loading : MainUiState()
    data class Success(val state: CoverDisplayRotationState) : MainUiState()
    data class Error(val message: String) : MainUiState()
}

@Composable
fun MainScreen(
    settingsRepository: SettingsRepository,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current

    val viewModel = remember {
        MainViewModel(Graph.rotationRepository, settingsRepository)
    }

    val uiState by viewModel.uiState.collectAsState()
    val autoRotationActive by viewModel.autoRotationActive.collectAsState()

    LaunchedEffect(autoRotationActive) {
        val serviceIntent = Intent(context, AutoRotationService::class.java)
        if (autoRotationActive) {
            context.startForegroundService(serviceIntent)
        } else {
            context.stopService(serviceIntent)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 最上段: 状態サマリ(タップで再取得) + 設定アイコン
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { viewModel.refreshState() }
            ) {
                when (val state = uiState) {
                    is MainUiState.Loading -> {
                        Text("状態取得中...", style = MaterialTheme.typography.titleMedium)
                    }
                    is MainUiState.Error -> {
                        Text(
                            text = "未接続",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is MainUiState.Success -> {
                        val modeLabel = when (state.state.mode) {
                            RotationMode.FREE -> "ロック解除"
                            RotationMode.LOCK_0 -> "0° 固定"
                            RotationMode.LOCK_90 -> "90° 固定"
                            RotationMode.LOCK_180 -> "180° 固定"
                            RotationMode.LOCK_270 -> "270° 固定"
                            RotationMode.UNKNOWN -> "不明"
                        }
                        Text(modeLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "FTU:${if (state.state.isFixedToUserRotationEnabled) "ON" else "OFF"}  " +
                                    "IOR:${if (state.state.isIgnoreOrientationRequestEnabled) "ON" else "OFF"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(Icons.Default.Settings, contentDescription = "設定")
            }
        }

        HorizontalDivider()

        // 擬似自動回転トグル
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "擬似自動回転",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = autoRotationActive,
                onCheckedChange = { viewModel.toggleAutoRotation() }
            )
        }

        // 回転ボタン: 0° / 90° / 180° / 270° を1行に
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.setRotation(RotationMode.LOCK_0) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
            ) { Text("0°") }
            OutlinedButton(
                onClick = { viewModel.setRotation(RotationMode.LOCK_90) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
            ) { Text("90°") }
            OutlinedButton(
                onClick = { viewModel.setRotation(RotationMode.LOCK_180) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
            ) { Text("180°") }
            OutlinedButton(
                onClick = { viewModel.setRotation(RotationMode.LOCK_270) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
            ) { Text("270°") }
        }

        Button(
            onClick = { viewModel.resetRotation() },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) { Text("ロック解除 (free)") }
    }
}
