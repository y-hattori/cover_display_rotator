package net.hasumi.coverrotator.executor

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * libadb-android 3.1.1 用の接続マネージャ。
 * クライアント鍵/証明書は初回生成して filesDir に永続化する
 * (毎回変わるとワイヤレスデバッグの承認をやり直しになるため)。
 */
class AdbConnectionManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)

        val keyFile = File(context.filesDir, "adb_key.pk8")
        val certFile = File(context.filesDir, "adb_cert.der")

        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certFile.inputStream())
        } else {
            val keyPair = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
            val subject = X500Name("CN=CoverDisplayRotator")
            val notBefore = Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
            val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(System.currentTimeMillis()),
                notBefore,
                notAfter,
                subject,
                keyPair.public
            )
            val signer = JcaContentSignerBuilder("SHA512withRSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

            keyFile.writeBytes(keyPair.private.encoded)
            certFile.writeBytes(cert.encoded)

            privateKey = keyPair.private
            certificate = cert
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "CoverDisplayRotator"

    companion object {
        @Volatile
        private var instance: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}

class AdbExecutor(
    private val context: Context
) : net.hasumi.coverrotator.executor.ShellExecutor {

    private val _connectionStatus = MutableStateFlow(net.hasumi.coverrotator.executor.ConnectionStatus.DISCONNECTED)
    override fun getConnectionStatus(): StateFlow<net.hasumi.coverrotator.executor.ConnectionStatus> = _connectionStatus.asStateFlow()

    private val manager: net.hasumi.coverrotator.executor.AdbConnectionManager
        get() = net.hasumi.coverrotator.executor.AdbConnectionManager.getInstance(context)

    override suspend fun checkPermission(): Boolean {
        return _connectionStatus.value == net.hasumi.coverrotator.executor.ConnectionStatus.CONNECTED
    }

    /**
     * ワイヤレスデバッグのペアリング。
     * 開発者向けオプションの「ペアリングコードによるペア設定」に表示される
     * ポートとコードを渡す。自機に対しては host は 127.0.0.1 でよい。
     */
    suspend fun pair(host: String, port: Int, pairingCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (manager.pair(host, port, pairingCode)) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("ペアリングに失敗しました"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** ワイヤレスデバッグ画面に表示される接続ポートに対して接続する */
    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (manager.connect(host, port)) {
                _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.CONNECTED
                Result.success(Unit)
            } else {
                _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.ERROR
                Result.failure(Exception("ADB接続に失敗しました"))
            }
        } catch (e: Exception) {
            android.util.Log.e("AdbExecutor", "connect failed: host=$host port=$port", e)
            _connectionStatus.value = ConnectionStatus.ERROR
            Result.failure(e)
        }
    }

    fun disconnect() {
        try {
            manager.disconnect()
        } catch (_: Exception) {
            // ignore
        }
        _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.DISCONNECTED
    }

    override suspend fun executeCommand(command: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (_connectionStatus.value != net.hasumi.coverrotator.executor.ConnectionStatus.CONNECTED) {
                    return@withContext Result.failure(Exception("ADB not connected"))
                }
                // shell: サービスはコマンド終了時にストリームが閉じる
                val output = manager.openStream("shell:$command").use { stream ->
                    stream.openInputStream().readBytes().toString(Charsets.UTF_8)
                }
                Result.success(output.trim())
            } catch (e: Exception) {
                _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.ERROR
                Result.failure(e)
            }
        }
}
