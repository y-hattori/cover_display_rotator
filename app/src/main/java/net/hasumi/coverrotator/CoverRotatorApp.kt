package net.hasumi.coverrotator

import android.app.Application
import net.hasumi.coverrotator.di.Graph
import org.lsposed.hiddenapibypass.HiddenApiBypass

class CoverRotatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        net.hasumi.coverrotator.di.Graph.init(this)
        // libadb-android がAndroid標準のConscrypt(隠しAPI)を使うために必要
        HiddenApiBypass.addHiddenApiExemptions("")
        Graph.tryAutoConnectAdb()
    }
}
