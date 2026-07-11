package net.hasumi.coverrotator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import net.hasumi.coverrotator.di.Graph
import net.hasumi.coverrotator.model.RotationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.atan2

/**
 * 加速度センサーで端末の向きを検知し、カバーディスプレイの回転を自動で切り替える
 * フォアグラウンドサービス(擬似自動回転)。
 */
class AutoRotationService : Service(), SensorEventListener {

    private val rotationRepository get() = Graph.rotationRepository
    private val settingsRepository get() = Graph.settingsRepository

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isSensorActive = false
    private var lastOrientationMode: RotationMode? = null
    private var isApplyingRotation = false

    // SettingsRepositoryから更新される設定値
    private var rotationInversionEnabled = false
    private var deadbandDegrees = 30.0
    private var debounceMs = 400L
    private var lastUpdateTime = 0L
    private var stabilityMs = 500L
    private var pendingMode: RotationMode? = null
    private var pendingSince = 0L

    companion object {
        const val CHANNEL_ID = "AutoRotationServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        observeSettings()
        return START_STICKY
    }

    private fun observeSettings() {
        serviceScope.launch {
            launch {
                settingsRepository.autoRotationEnabled.collect { enabled ->
                    if (enabled) startSensorUpdates() else stopSensorUpdates()
                }
            }
            launch {
                settingsRepository.rotationInversionEnabled.collect {
                    rotationInversionEnabled = it
                }
            }
            launch {
                settingsRepository.deadbandDegrees.collect { deadbandDegrees = it }
            }
            launch {
                settingsRepository.debounceMs.collect { debounceMs = it }
            }
            launch {
                settingsRepository.stabilityMs.collect { stabilityMs = it }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopSensorUpdates()
        super.onDestroy()
    }

    private fun startSensorUpdates() {
        if (!isSensorActive) {
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                isSensorActive = true
            }
        }
    }

    private fun stopSensorUpdates() {
        if (isSensorActive) {
            sensorManager.unregisterListener(this)
            isSensorActive = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isSensorActive || isApplyingRotation) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < debounceMs) return
        lastUpdateTime = currentTime

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // 水平置きガード: Z軸に重力の大半が乗っている(≒テーブル置き)場合は
        // X/Y成分がノイズだけになり角度が暴れるので判定しない
        if (kotlin.math.abs(z) > 7.5f) {
            pendingMode = null
            return
        }

        // 端末直立(portrait)で0°、右倒しで90°になる角度を計算
        var angle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toInt()
        if (angle < 0) angle += 360

        // 各向きの判定窓の半幅(deadbandDegreesを窓幅として利用、5〜45°に制限)
        val halfWidth = deadbandDegrees.coerceIn(5.0, 45.0).toInt()

        var detectedMode = when {
            // 0°付近 (360°をまたぐのでwrap考慮)
            angle <= halfWidth || angle >= 360 - halfWidth -> RotationMode.LOCK_0
            angle in (90 - halfWidth)..(90 + halfWidth) -> RotationMode.LOCK_90
            angle in (180 - halfWidth)..(180 + halfWidth) -> RotationMode.LOCK_180
            angle in (270 - halfWidth)..(270 + halfWidth) -> RotationMode.LOCK_270
            else -> RotationMode.UNKNOWN
        }

        // 回転方向の反転設定: 90と270を入れ替える
        if (rotationInversionEnabled) {
            detectedMode = when (detectedMode) {
                RotationMode.LOCK_90 -> RotationMode.LOCK_270
                RotationMode.LOCK_270 -> RotationMode.LOCK_90
                else -> detectedMode
            }
        }

        // 判定不能、または既に確定済みの向きなら保留をリセットして終了
        if (detectedMode == RotationMode.UNKNOWN || detectedMode == lastOrientationMode) {
            pendingMode = null
            return
        }

        // 新しい向きの候補: 保留中の候補と違えばタイマーを仕切り直し
        if (detectedMode != pendingMode) {
            pendingMode = detectedMode
            pendingSince = currentTime
            return
        }

        // 同じ候補が stabilityMs 以上継続したら確定
        if (currentTime - pendingSince < stabilityMs) return

        pendingMode = null
        isApplyingRotation = true
        serviceScope.launch {
            try {
                val result = rotationRepository.setRotationMode(detectedMode)
                if (result.isSuccess) {
                    lastOrientationMode = detectedMode
                }
            } finally {
                isApplyingRotation = false
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("擬似自動回転 動作中")
            .setContentText("端末の向きを監視してカバーディスプレイを回転します")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "擬似自動回転サービス",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
