package app.termora.plugin.internal.ssh

import app.termora.Icons
import javax.swing.Icon

/**
 * Detects the OS/distribution of a remote server and returns the appropriate icon.
 */
object OSDetector {

    private val detectCommands = listOf(
        "cat /etc/os-release 2>/dev/null || uname -s 2>/dev/null",
        "cmd.exe /d /c ver",
    )

    enum class OSType {
        UBUNTU,
        DEBIAN,
        CENTOS,
        FEDORA,
        RHEL,
        ARCH,
        ALPINE,
        AMAZON,
        SUSE,
        GENTOO,
        KALI,
        MINT,
        ROCKY,
        ALMA,
        ORACLE,
        WINDOWS,
        MACOS,
        LINUX,
        UNKNOWN;

        fun getIcon(): Icon {
            return when (this) {
                UBUNTU -> Icons.ubuntu
                DEBIAN -> Icons.debian
                FEDORA -> Icons.fedora
                ALMA -> Icons.almalinux
                WINDOWS -> Icons.microsoftWindows
                LINUX, CENTOS, RHEL, ARCH, ALPINE, AMAZON, SUSE, GENTOO, KALI, MINT, ROCKY, ORACLE, UNKNOWN -> Icons.linux
                MACOS -> Icons.terminal // no macos icon, use terminal
            }
        }
    }

    /**
     * Parses /etc/os-release content to determine the OS type.
     */
    fun detectFromOsRelease(content: String): OSType {
        val lines = content.lines()
        val idMap = mutableMapOf<String, String>()

        for (line in lines) {
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                idMap[key] = value
            }
        }

        val id = idMap["ID"]?.lowercase() ?: ""
        val idLike = idMap["ID_LIKE"]?.lowercase() ?: ""
        val name = idMap["NAME"]?.lowercase() ?: ""

        return when {
            id.contains("ubuntu") -> OSType.UBUNTU
            id.contains("debian") -> OSType.DEBIAN
            id.contains("centos") -> OSType.CENTOS
            id.contains("fedora") -> OSType.FEDORA
            id.contains("rhel") || id.contains("redhat") -> OSType.RHEL
            id.contains("arch") || id.contains("manjaro") -> OSType.ARCH
            id.contains("alpine") -> OSType.ALPINE
            id.contains("amzn") || name.contains("amazon") -> OSType.AMAZON
            id.contains("suse") || id.contains("opensuse") -> OSType.SUSE
            id.contains("gentoo") -> OSType.GENTOO
            id.contains("kali") -> OSType.KALI
            id.contains("linuxmint") || id.contains("mint") -> OSType.MINT
            id.contains("rocky") -> OSType.ROCKY
            id.contains("almalinux") || id.contains("alma") -> OSType.ALMA
            id.contains("ol") || name.contains("oracle") -> OSType.ORACLE
            idLike.contains("ubuntu") -> OSType.UBUNTU
            idLike.contains("debian") -> OSType.DEBIAN
            idLike.contains("rhel") || idLike.contains("centos") -> OSType.CENTOS
            idLike.contains("fedora") -> OSType.FEDORA
            idLike.contains("arch") -> OSType.ARCH
            idLike.contains("suse") -> OSType.SUSE
            else -> OSType.LINUX
        }
    }

    fun getDetectCommands(): List<String> = detectCommands

    /**
     * Parses the command output to determine OS type.
     */
    fun detect(output: String): OSType {
        val trimmed = output.trim()

        // Check if it's os-release format
        if (trimmed.contains("ID=") || trimmed.contains("NAME=")) {
            return detectFromOsRelease(trimmed)
        }

        // Check uname output
        val uname = trimmed.lowercase()
        return when {
            uname.contains("linux") -> OSType.LINUX
            uname.contains("darwin") -> OSType.MACOS
            uname.contains("windows") || uname.contains("mingw") || uname.contains("msys") ||
                    uname.contains("cygwin") -> OSType.WINDOWS
            else -> OSType.UNKNOWN
        }
    }
}
