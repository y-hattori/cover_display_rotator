package net.hasumi.coverrotator.ui

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import net.hasumi.coverrotator.di.Graph
import net.hasumi.coverrotator.model.RotationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * カバーディスプレイ回転のクイック設定タイル。
 * タップごとに OFF → 90° → 270° → OFF とサイクルする。
 * 状態はタイルではなくリポジトリ(実際のwm状態)を正として判定する。
 * ※ Tileの状態は STATE_ACTIVE / STATE_INACTIVE / STATE_UNAVAILABLE の3つのみ。
 */
class QuickSettingsTileService : TileService() {

    private var serviceScope: CoroutineScope? = null

    private companion object {
        const val TAG = "QuickSettingsTile"
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        serviceScope?.launch {
            try {
                val currentMode = Graph.rotationRepository.getCurrentRotationState()
                    .getOrNull()?.mode

                val nextMode = when (currentMode) {
                    RotationMode.LOCK_90 -> RotationMode.LOCK_270
                    RotationMode.LOCK_270 -> RotationMode.FREE
                    else -> RotationMode.LOCK_90 // FREE / UNKNOWN / 取得失敗
                }

                Graph.rotationRepository.setRotationMode(nextMode)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cycle rotation", e)
            }
            updateTileState()
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        serviceScope?.launch {
            try {
                val mode = Graph.rotationRepository.getCurrentRotationState()
                    .getOrNull()?.mode

                when (mode) {
                    RotationMode.LOCK_90 -> {
                        tile.state = Tile.STATE_ACTIVE
                        tile.label = "カバー回転 90°"
                    }
                    RotationMode.LOCK_270 -> {
                        tile.state = Tile.STATE_ACTIVE
                        tile.label = "カバー回転 270°"
                    }
                    RotationMode.FREE -> {
                        tile.state = Tile.STATE_INACTIVE
                        tile.label = "カバー回転 OFF"
                    }
                    else -> {
                        // 取得失敗(Shizuku/adb未接続など)
                        tile.state = Tile.STATE_UNAVAILABLE
                        tile.label = "カバー回転 (未接続)"
                    }
                }
                tile.icon = Icon.createWithResource(
                    applicationContext,
                    android.R.drawable.ic_menu_rotate
                )
                tile.updateTile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update tile state", e)
            }
        }
    }
}
