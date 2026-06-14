package app.termora.plugin.internal.wsl

import com.formdev.flatlaf.util.SystemInfo
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory


object WSLSupport {
    private val log = LoggerFactory.getLogger(javaClass)
    val isSupported by lazy { checkSupported() }

    private fun checkSupported(): Boolean {
        if (SystemInfo.isWindows.not()) return false
        val drive = System.getenv("SystemRoot") ?: return false
        val wsl = FileUtils.getFile(drive, "System32", "wsl.exe")
        return wsl.exists()
    }

    fun getDistributions(): List<WSLDistribution> {
        if (isSupported.not()) return emptyList()
        val distributions = mutableListOf<WSLDistribution>()

        try {
            val baseKeyPath = "Software\\Microsoft\\Windows\\CurrentVersion\\Lxss"
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, baseKeyPath)) {
                return emptyList()
            }
            val guids = Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, baseKeyPath)

            for (guid in guids) {
                val key = baseKeyPath + "\\" + guid
                try {
                    if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, key)) continue

                    val distroName = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "DistributionName")
                        ?: continue
                    val basePath = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "BasePath")
                        ?: continue
                    val flavor = Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "Flavor")
                        ?: continue

                    if (StringUtils.isAnyBlank(distroName, guid, basePath, flavor)) continue
                    distributions.add(
                        WSLDistribution(
                            guid = guid,
                            flavor = flavor,
                            basePath = basePath,
                            distributionName = distroName
                        )
                    )
                } catch (_: Exception) {
                    // Skip entries with missing or invalid registry values
                }
            }
        } catch (_: Exception) {
            // Lxss registry key not found — WSL not configured
        }

        return distributions
    }
}
