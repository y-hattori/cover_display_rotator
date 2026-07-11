package net.hasumi.coverrotator.executor

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.TimeUnit

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
        // デフォルトはタイムアウト無し(Long.MAX_VALUE)のため、
        // 相手が応答しない場合にconnect()が無限に返ってこなくなる。妥当な上限を設定する
        setTimeout(ADB_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

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

        /**
         * AbsAdbConnectionManagerは内部の単一ロック(mLock)をconnect/disconnect/openStream間で共有しており、
         * 相手が応答しないとそのロックを握ったまま返ってこないことがある(ライブラリ側の制約で回避不可)。
         * その状態になると以後すべての操作が永久に詰まるため、タイムアウト検知時はインスタンスごと
         * 差し替えて、詰まった古いオブジェクトは見捨てる。
         */
        fun resetInstance(context: Context): AdbConnectionManager =
            synchronized(this) {
                AdbConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}

private const val ADB_CONNECT_TIMEOUT_MS = 1_000L
private const val ADB_LOCK_TIMEOUT_MS = 1_200L
private const val ADB_MDNS_DISCOVERY_TIMEOUT_MS = 8_000L
private const val ADB_MDNS_LOCK_TIMEOUT_MS = 12_000L

class AdbExecutor(
    private val context: Context
) : net.hasumi.coverrotator.executor.ShellExecutor {

    private val _connectionStatus = MutableStateFlow(net.hasumi.coverrotator.executor.ConnectionStatus.DISCONNECTED)
    override fun getConnectionStatus(): StateFlow<net.hasumi.coverrotator.executor.ConnectionStatus> = _connectionStatus.asStateFlow()

    private val manager: net.hasumi.coverrotator.executor.AdbConnectionManager
        get() = net.hasumi.coverrotator.executor.AdbConnectionManager.getInstance(context)

    // manager(単一のTCP接続を多重化するAdbConnectionManager)への操作は
    // pair/connect/disconnect/executeCommandのどこから同時に呼ばれても安全なように直列化する。
    // タイムアウト発生時はresetConnection()で新しいMutexに差し替える(volatileで可視性を保証)ため var にしている
    @Volatile
    private var mutex = Mutex()

    /**
     * ライブラリ内部のロック(mLock)がハングして返ってこないコルーチンが残った場合、
     * このMutex自体も永久にロックされたままになる。タイムアウト検知時はAdbConnectionManagerの
     * インスタンスとこのMutexを両方差し替え、詰まった呼び出しを完全に見捨てて以降の操作を復旧させる。
     */
    private fun resetConnection() {
        net.hasumi.coverrotator.executor.AdbConnectionManager.resetInstance(context)
        mutex = Mutex()
    }

    /**
     * 現在接続中のホスト/ポートを取得する(connectAuto()のmDNS自動検出結果を
     * 設定画面に反映する用途)。AdbConnectionはポートのpublic getterを持たないため、
     * ShizukuExecutor.newProcess()と同様にリフレクションで読む。
     */
    fun currentEndpoint(): Pair<String, Int>? {
        val connection = manager.adbConnection ?: return null
        val host = manager.hostAddress
        val port = try {
            val field = connection.javaClass.getDeclaredField("mPort")
            field.isAccessible = true
            field.getInt(connection)
        } catch (_: Exception) {
            return null
        }
        return host to port
    }

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
            withTimeoutOrNull(ADB_LOCK_TIMEOUT_MS) {
                mutex.withLock {
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
            } ?: run {
                android.util.Log.w("AdbExecutor", "pair timed out, resetting connection manager")
                resetConnection()
                Result.failure(Exception("ペアリング処理がタイムアウトしました"))
            }
        }

    /** ワイヤレスデバッグ画面に表示される接続ポートに対して接続する */
    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(ADB_LOCK_TIMEOUT_MS) {
            mutex.withLock {
                try {
                    // manager.isConnected()がtrueのままだとconnect()が何もせずfalseを返すため、
                    // ソケットが実際には切れていても再接続できない。再接続前に内部状態を必ずクリアする
                    try {
                        manager.disconnect()
                    } catch (_: Exception) {
                        // ignore
                    }
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
        } ?: run {
            android.util.Log.w("AdbExecutor", "connect timed out, resetting connection manager")
            resetConnection()
            _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.ERROR
            Result.failure(Exception("ADB接続処理がタイムアウトしました"))
        }
    }

    /**
     * ワイヤレスデバッグが広告するmDNS(_adb-tls-connect._tcp)を使って現在の接続ポートを自動検出して接続する。
     * ワイヤレスデバッグの再起動やトグルでポート番号が変わっても、保存済みポートの手動更新が不要になる。
     * (Shizuku等が同じ仕組みでポート変更に自動追従しているのと同様)
     */
    suspend fun connectAuto(): Result<Unit> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(ADB_MDNS_LOCK_TIMEOUT_MS) {
            mutex.withLock {
                try {
                    try {
                        manager.disconnect()
                    } catch (_: Exception) {
                        // ignore
                    }
                    if (manager.connectTls(context, ADB_MDNS_DISCOVERY_TIMEOUT_MS)) {
                        _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.CONNECTED
                        Result.success(Unit)
                    } else {
                        _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.ERROR
                        Result.failure(Exception("mDNS自動検出でのADB接続に失敗しました"))
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AdbExecutor", "connectAuto (mDNS) failed: ${e.message}")
                    _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.ERROR
                    Result.failure(e)
                }
            }
        } ?: run {
            android.util.Log.w("AdbExecutor", "connectAuto timed out, resetting connection manager")
            resetConnection()
            _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.ERROR
            Result.failure(Exception("mDNS自動検出処理がタイムアウトしました"))
        }
    }

    suspend fun disconnect() {
        val completed = withTimeoutOrNull(ADB_LOCK_TIMEOUT_MS) {
            mutex.withLock {
                try {
                    manager.disconnect()
                } catch (_: Exception) {
                    // ignore
                }
                _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.DISCONNECTED
            }
        }
        if (completed == null) {
            android.util.Log.w("AdbExecutor", "disconnect timed out, resetting connection manager")
            resetConnection()
            _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.DISCONNECTED
        }
    }

    override suspend fun executeCommand(command: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (_connectionStatus.value != net.hasumi.coverrotator.executor.ConnectionStatus.CONNECTED) {
                return@withContext Result.failure(Exception("ADB not connected"))
            }
            withTimeoutOrNull(ADB_LOCK_TIMEOUT_MS) {
                mutex.withLock {
                    try {
                        // shell: サービスはコマンド終了時にストリームが閉じる。
                        // ただしlibadb-android側に既知の競合があり、フォアグラウンドがキューを
                        // 読み切った直後にCLSEフレームが処理されると、正常終了用の1回だけのEOF(-1)を
                        // 返す猶予をスキップしてIOException("Stream closed")を投げてしまう。
                        // readBytes()任せだと区別できないため、自前ループでこの特定の例外だけは
                        // 正常終了(EOF)として扱う。
                        val output = manager.openStream("shell:$command").use { stream ->
                            val input = stream.openInputStream()
                            val buffer = ByteArray(8192)
                            val bytes = java.io.ByteArrayOutputStream()
                            while (true) {
                                val n = try {
                                    input.read(buffer)
                                } catch (e: java.io.IOException) {
                                    if (e.message?.startsWith("Stream closed") == true) break else throw e
                                }
                                if (n < 0) break
                                bytes.write(buffer, 0, n)
                            }
                            bytes.toString(Charsets.UTF_8.name())
                        }
                        Result.success(output.trim())
                    } catch (e: Exception) {
                        android.util.Log.e("AdbExecutor", "executeCommand failed: $command", e)
                        // このコマンド用のストリームが一時的にこけただけの可能性があるため、
                        // 接続自体が本当に切れている場合のみ全体をERRORにする。
                        // ここでERRORにしてしまうと、同時に動く別のexecuteCommand呼び出し
                        // (擬似自動回転のセンサー処理など)が接続は生きているのに
                        // 「ADB not connected」で即失敗するようになってしまう。
                        if (!manager.isConnected()) {
                            _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.ERROR
                        }
                        Result.failure(e)
                    }
                }
            } ?: run {
                android.util.Log.w("AdbExecutor", "executeCommand timed out, resetting connection manager: $command")
                resetConnection()
                _connectionStatus.value = net.hasumi.coverrotator.executor.ConnectionStatus.ERROR
                Result.failure(Exception("ADBコマンドがタイムアウトしました: $command"))
            }
        }
}
