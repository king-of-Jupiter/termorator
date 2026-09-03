package app.termora.plugin.internal.ssh

import kotlin.test.Test
import kotlin.test.assertEquals

class OSDetectorTest {

    @Test
    fun `detects a distribution from os-release`() {
        val output = """
            PRETTY_NAME="Ubuntu 24.04.3 LTS"
            NAME="Ubuntu"
            ID=ubuntu
            ID_LIKE=debian
        """.trimIndent()

        assertEquals(OSDetector.OSType.UBUNTU, OSDetector.detect(output))
    }

    @Test
    fun `detects a distribution from ID_LIKE`() {
        val output = """
            NAME="Pop!_OS"
            ID=pop
            ID_LIKE="ubuntu debian"
        """.trimIndent()

        assertEquals(OSDetector.OSType.UBUNTU, OSDetector.detect(output))
    }

    @Test
    fun `detects Windows from cmd output`() {
        assertEquals(
            OSDetector.OSType.WINDOWS,
            OSDetector.detect("Microsoft Windows [Version 10.0.26100.4946]"),
        )
    }

    @Test
    fun `detects macOS from uname output`() {
        assertEquals(OSDetector.OSType.MACOS, OSDetector.detect("Darwin"))
    }

    @Test
    fun `returns unknown for empty output`() {
        assertEquals(OSDetector.OSType.UNKNOWN, OSDetector.detect(""))
    }
}
