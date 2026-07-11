package net.hasumi.coverrotator.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.hasumi.coverrotator.di.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 再起動(およびアプリ更新)後に、擬似自動回転がON設定なら
 * AutoRotationServiceを復帰させる。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // WRITE_SECURE_SETTINGSが付与済みなら、ワイヤレスデバッグを自動でONにする
        WirelessDebugging.tryEnable(context)

        // DataStoreの読み出しはsuspendなので、goAsyncで受信処理を延長する
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val startOnBoot = Graph.settingsRepository.startOnBoot.first()
                val enabled = Graph.settingsRepository.autoRotationEnabled.first()
                if (startOnBoot && enabled) {
                    context.startForegroundService(
                        Intent(context, AutoRotationService::class.java)
                    )
                }
            } catch (_: Exception) {
                // 起動失敗しても再起動直後のクラッシュは避ける
            } finally {
                pendingResult.finish()
            }
        }
    }
}