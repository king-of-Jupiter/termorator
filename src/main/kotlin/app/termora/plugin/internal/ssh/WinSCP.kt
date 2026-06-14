package app.termora.plugin.internal.ssh

import app.termora.Application
import app.termora.AuthenticationType
import app.termora.Host
import app.termora.I18n
import app.termora.keymgr.KeyManager
import app.termora.keymgr.OhKeyPairKeyPairProvider
import app.termora.randomUUID
import com.formdev.flatlaf.util.SystemInfo
import org.apache.commons.codec.binary.Base64
import org.apache.commons.codec.binary.Hex
import org.apache.commons.lang3.StringUtils
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateCrtKey
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 与 WinSCP 集成：将主机以 SFTP 会话在 WinSCP 中打开，并支持 SSH 私钥。
 *
 * WinSCP 仅接受 PuTTY 格式（.ppk）的私钥，因此当主机使用公钥认证时，
 * 会把 Termora 保存的密钥导出为临时 .ppk 文件，并通过 `/privatekey=` 传给 WinSCP。
 */
object WinSCP {

    private val log = LoggerFactory.getLogger(WinSCP::class.java)

    val isSupported: Boolean get() = SystemInfo.isWindows

    /**
     * 在 WinSCP 中打开指定主机。失败时抛出异常（由调用方提示）。
     */
    fun open(host: Host) {
        val executable = findExecutable()
            ?: throw IllegalStateException(I18n.getString("termora.welcome.contextmenu.winscp.not-found"))

        val port = if (host.port <= 0) 22 else host.port
        val authentication = host.authentication

        // sftp://user[:password]@host:port/
        val url = StringBuilder("sftp://")
        if (host.username.isNotBlank()) {
            url.append(encodeUserInfo(host.username))
            if (authentication.type == AuthenticationType.Password && authentication.password.isNotBlank()) {
                url.append(':').append(encodeUserInfo(authentication.password))
            }
            url.append('@')
        }
        url.append(host.host).append(':').append(port).append('/')

        val args = mutableListOf(executable.absolutePath, url.toString())

        // 公钥认证：导出为 .ppk 并通过 /privatekey 传入
        if (authentication.type == AuthenticationType.PublicKey) {
            val ppk = exportPpk(host)
            args.add("/privatekey=${ppk.absolutePath}")
        }

        if (log.isInfoEnabled) {
            log.info("Opening host {} in WinSCP", host.host)
        }

        ProcessBuilder(args).start()
    }

    private fun exportPpk(host: Host): File {
        val ohKeyPair = KeyManager.getInstance().getOhKeyPair(host.authentication.password)
            ?: throw IllegalStateException(I18n.getString("termora.welcome.contextmenu.winscp.key-not-found"))
        val keyPair = OhKeyPairKeyPairProvider.generateKeyPair(ohKeyPair)
        val comment = StringUtils.defaultIfBlank(host.name, ohKeyPair.name).toString()
        val content = toPuttyPrivateKey(keyPair, comment)

        val file = File(Application.getTemporaryDir(), "termora-${randomUUID()}.ppk")
        // 以 UTF-8 写入：注释可能包含非 ASCII 字符，需与 MAC 中使用的 UTF-8 字节保持一致
        file.writeText(content, Charsets.UTF_8)
        // 包含明文私钥，进程退出时清理
        file.deleteOnExit()
        return file
    }

    // ------------------------------------------------------------------
    // WinSCP 可执行文件定位
    // ------------------------------------------------------------------

    private fun findExecutable(): File? {
        val candidates = buildList {
            System.getenv("ProgramFiles")?.let { add(File(it, "WinSCP/WinSCP.exe")) }
            System.getenv("ProgramFiles(x86)")?.let { add(File(it, "WinSCP/WinSCP.exe")) }
            System.getenv("LOCALAPPDATA")?.let { add(File(it, "Programs/WinSCP/WinSCP.exe")) }
        }
        for (candidate in candidates) {
            if (candidate.exists()) return candidate
        }

        // 注册表 App Paths
        queryAppPaths()?.let { return it }

        // PATH
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue
            val file = File(dir, "WinSCP.exe")
            if (file.exists()) return file
        }
        return null
    }

    private fun queryAppPaths(): File? {
        val keys = listOf(
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\WinSCP.exe",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\WinSCP.exe",
        )
        for (key in keys) {
            try {
                val process = ProcessBuilder("reg", "query", key, "/ve")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                val index = output.indexOf("REG_SZ")
                if (index >= 0) {
                    val value = output.substring(index + "REG_SZ".length).trim().substringBefore("\r").substringBefore("\n").trim()
                    val file = File(value)
                    if (file.exists()) return file
                }
            } catch (e: Exception) {
                if (log.isDebugEnabled) {
                    log.debug("reg query failed for {}", key, e)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // OpenSSH 私钥 -> PuTTY .ppk（v2，未加密）
    // ------------------------------------------------------------------

    internal fun toPuttyPrivateKey(keyPair: KeyPair, comment: String): String {
        val publicBlob = ByteArrayBuffer().apply { putRawPublicKey(keyPair.public) }.compactData
        val algorithm = readSshString(publicBlob, 0)
        val privateBlob = buildPrivateBlob(keyPair.private)
        val safeComment = comment.replace('\n', ' ').replace('\r', ' ')
        val mac = computeMac(algorithm, "none", safeComment, publicBlob, privateBlob)

        val sb = StringBuilder()
        sb.append("PuTTY-User-Key-File-2: ").append(algorithm).append('\n')
        sb.append("Encryption: none\n")
        sb.append("Comment: ").append(safeComment).append('\n')
        appendBase64Lines(sb, "Public-Lines", publicBlob)
        appendBase64Lines(sb, "Private-Lines", privateBlob)
        sb.append("Private-MAC: ").append(mac).append('\n')
        return sb.toString()
    }

    private fun buildPrivateBlob(privateKey: PrivateKey): ByteArray {
        val buffer = ByteArrayBuffer()
        when (privateKey) {
            is RSAPrivateCrtKey -> {
                buffer.putMPInt(privateKey.privateExponent)
                buffer.putMPInt(privateKey.primeP)
                buffer.putMPInt(privateKey.primeQ)
                buffer.putMPInt(privateKey.crtCoefficient) // iqmp = q^-1 mod p
            }

            is ECPrivateKey -> {
                buffer.putMPInt(privateKey.s)
            }

            else -> {
                // ED25519：私钥为 32 字节种子（PuTTY 以 SSH string 存储）
                val seed = extractEd25519Seed(privateKey)
                    ?: throw UnsupportedOperationException("Unsupported private key: ${privateKey.algorithm}")
                buffer.putBytes(seed)
            }
        }
        return buffer.compactData
    }

    private fun extractEd25519Seed(privateKey: PrivateKey): ByteArray? {
        // net.i2p.crypto.eddsa.EdDSAPrivateKey#getSeed() -> ByteArray(32)
        runCatching {
            val seed = privateKey.javaClass.getMethod("getSeed").invoke(privateKey) as? ByteArray
            if (seed != null && seed.size == 32) return seed
        }

        // java.security.interfaces.EdECPrivateKey#getBytes() -> Optional<ByteArray>(32)
        runCatching {
            val value = privateKey.javaClass.getMethod("getBytes").invoke(privateKey)
            val bytes = when (value) {
                is java.util.Optional<*> -> value.orElse(null) as? ByteArray
                is ByteArray -> value
                else -> null
            }
            if (bytes != null && bytes.size == 32) return bytes
        }

        if (log.isWarnEnabled) {
            log.warn("Unable to extract ED25519 seed from {}", privateKey.javaClass.name)
        }
        return null
    }

    private fun computeMac(
        algorithm: String,
        encryption: String,
        comment: String,
        publicBlob: ByteArray,
        privateBlob: ByteArray
    ): String {
        val macKey = MessageDigest.getInstance("SHA-1")
            .digest("putty-private-key-file-mac-key".toByteArray(Charsets.US_ASCII))

        val data = ByteArrayOutputStream()
        writeSshString(data, algorithm.toByteArray(Charsets.US_ASCII))
        writeSshString(data, encryption.toByteArray(Charsets.US_ASCII))
        writeSshString(data, comment.toByteArray(Charsets.UTF_8))
        writeSshString(data, publicBlob)
        writeSshString(data, privateBlob)

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(macKey, "HmacSHA1"))
        return Hex.encodeHexString(mac.doFinal(data.toByteArray()))
    }

    private fun appendBase64Lines(sb: StringBuilder, label: String, blob: ByteArray) {
        val base64 = Base64.encodeBase64String(blob)
        val lines = base64.chunked(64)
        sb.append(label).append(": ").append(lines.size).append('\n')
        for (line in lines) {
            sb.append(line).append('\n')
        }
    }

    private fun writeSshString(out: ByteArrayOutputStream, bytes: ByteArray) {
        val length = bytes.size
        out.write((length ushr 24) and 0xff)
        out.write((length ushr 16) and 0xff)
        out.write((length ushr 8) and 0xff)
        out.write(length and 0xff)
        out.write(bytes)
    }

    private fun readSshString(blob: ByteArray, offset: Int): String {
        val length = ((blob[offset].toInt() and 0xff) shl 24) or
                ((blob[offset + 1].toInt() and 0xff) shl 16) or
                ((blob[offset + 2].toInt() and 0xff) shl 8) or
                (blob[offset + 3].toInt() and 0xff)
        return String(blob, offset + 4, length, Charsets.US_ASCII)
    }

    private fun encodeUserInfo(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
                sb.append(c)
            } else {
                sb.append('%').append(String.format("%02X", b.toInt() and 0xff))
            }
        }
        return sb.toString()
    }
}
