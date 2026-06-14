package app.termora

import app.termora.keymgr.OhKeyPair
import app.termora.keymgr.OhKeyPairKeyPairProvider
import app.termora.plugin.internal.ssh.WinSCP
import org.apache.commons.codec.binary.Base64
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WinSCPPpkTest {

    private fun field(ppk: String, name: String): String =
        ppk.lines().first { it.startsWith("$name:") }.substringAfter(":").trim()

    private fun base64Lines(ppk: String, name: String): ByteArray {
        val lines = ppk.lines()
        val index = lines.indexOfFirst { it.startsWith("$name:") }
        val count = lines[index].substringAfter(":").trim().toInt()
        val base64 = lines.subList(index + 1, index + 1 + count).joinToString("")
        return Base64.decodeBase64(base64)
    }

    /**
     * 校验生成的 .ppk：头部算法名正确、Public 段能还原出原始公钥、MAC 为 40 位十六进制。
     */
    private fun verify(keyPair: KeyPair, expectedAlgorithm: String) {
        val ppk = WinSCP.toPuttyPrivateKey(keyPair, "termora-test")

        assertEquals(expectedAlgorithm, field(ppk, "PuTTY-User-Key-File-2"))
        assertEquals("none", field(ppk, "Encryption"))

        // Public 段还原后应与原始公钥一致（验证算法名与公钥参数编码）
        val publicBlob = base64Lines(ppk, "Public-Lines")
        val parsed = ByteArrayBuffer(publicBlob).rawPublicKey
        assertEquals(keyPair.public, parsed)

        // 私钥段非空
        assertTrue(base64Lines(ppk, "Private-Lines").isNotEmpty())

        // MAC 为 HMAC-SHA1 的 40 位十六进制
        val mac = field(ppk, "Private-MAC")
        assertEquals(40, mac.length)
        assertTrue(mac.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun rsa() {
        verify(RSA.generateKeyPair(2048), "ssh-rsa")
    }

    @Test
    fun ecdsa() {
        verify(KeyUtils.generateKeyPair(KeyPairProvider.ECDSA_SHA2_NISTP256, 256), "ecdsa-sha2-nistp256")
    }

    @Test
    fun ed25519() {
        // 模拟应用真实路径：以 net.i2p 解码器还原私钥（提供 getSeed()）
        val generated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val ohKeyPair = OhKeyPair.empty.copy(
            type = "ED25519",
            publicKey = Base64.encodeBase64String(generated.public.encoded),
            privateKey = Base64.encodeBase64String(generated.private.encoded),
        )
        val keyPair = OhKeyPairKeyPairProvider.generateKeyPair(ohKeyPair)
        verify(keyPair, "ssh-ed25519")
    }
}
