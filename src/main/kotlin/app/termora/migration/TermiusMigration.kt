package app.termora.migration

import app.termora.*
import app.termora.Application.ohMyJson
import app.termora.account.AccountOwner
import app.termora.database.DatabaseChangedExtension
import app.termora.keymgr.KeyManager
import app.termora.keymgr.OhKeyPair
import app.termora.plugin.internal.ssh.SSHProtocolProvider
import app.termora.snippet.Snippet
import app.termora.snippet.SnippetManager
import app.termora.snippet.SnippetType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.commons.codec.binary.Base64
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.util.security.SecurityUtils
import java.io.File
import java.security.KeyPair
import java.util.Locale

/**
 * Imports the bundle produced by tools/termius-to-termorator.
 *
 * The companion exporter is based on y01and3/termius-export. Termorator deliberately consumes
 * a normalized bundle instead of reading Chromium IndexedDB itself: V8 structured-clone parsing
 * is the fragile part of the migration and is already implemented and tested by that project.
 */
object TermiusMigration {
    const val FORMAT = "termorator-termius-migration"
    const val VERSION = 1
    private const val MAX_BUNDLE_SIZE = 64L * 1024L * 1024L

    fun read(file: File): TermiusMigrationBundle {
        require(file.isFile) { "Termius migration bundle not found: ${file.absolutePath}" }
        require(file.length() in 1..MAX_BUNDLE_SIZE) {
            "Termius migration bundle must be between 1 byte and 64 MiB"
        }

        val bundle = ohMyJson.decodeFromString<TermiusMigrationBundle>(file.readText(Charsets.UTF_8))
        require(bundle.format == FORMAT) { "Unsupported migration format: ${bundle.format}" }
        require(bundle.version == VERSION) { "Unsupported migration version: ${bundle.version}" }
        return bundle
    }

    fun plan(
        bundle: TermiusMigrationBundle,
        destination: Host,
        accountOwner: AccountOwner,
    ): TermiusImportPlan {
        require(bundle.format == FORMAT) { "Unsupported migration format: ${bundle.format}" }
        require(bundle.version == VERSION) { "Unsupported migration version: ${bundle.version}" }
        require(destination.isFolder) { "Termius data can only be imported into a host folder" }

        val warnings = mutableListOf<String>()
        val importedKeys = mutableListOf<OhKeyPair>()
        val keyIds = mutableMapOf<String, String>()

        bundle.keys.forEachIndexed { index, source ->
            if (source.privateKey.isBlank()) {
                warnings += "Key '${source.displayName()}': private key is unavailable"
                return@forEachIndexed
            }

            runCatching { source.toOhKeyPair(index) }
                .onSuccess { keyPair ->
                    importedKeys += keyPair
                    keyIds[source.id] = keyPair.id
                }
                .onFailure { error ->
                    warnings += "Key '${source.displayName()}': ${error.message ?: error.javaClass.simpleName}"
                }
        }

        val importedHosts = mutableListOf<Host>()
        val hostFolders = mutableMapOf<List<String>, Host>()
        val hostSorts = mutableMapOf<String, Long>()

        bundle.hosts.forEach { source ->
            if (source.address.isBlank()) {
                warnings += "Host '${source.displayName()}': address is empty"
                return@forEach
            }

            val parent = ensureHostPath(
                source.groupPath,
                destination,
                accountOwner,
                hostFolders,
                hostSorts,
                importedHosts,
            )
            val importedKeyId = source.keyId?.let(keyIds::get)
            if (source.keyId != null && importedKeyId == null) {
                warnings += "Host '${source.displayName()}': referenced key was not imported"
            }

            val authentication = when {
                importedKeyId != null -> Authentication(AuthenticationType.PublicKey, importedKeyId)
                source.password.isNotBlank() -> Authentication(AuthenticationType.Password, source.password)
                else -> Authentication.No
            }
            val parentId = parent?.id ?: destination.id
            importedHosts += Host(
                name = source.displayName(),
                protocol = SSHProtocolProvider.PROTOCOL,
                host = source.address,
                port = source.port.takeIf { it in 1..65535 } ?: 22,
                username = source.username,
                authentication = authentication,
                proxy = source.proxy.toTermoraProxy(),
                options = Options.Default.copy(
                    extras = if (source.agentForward) mapOf("forwardAgent" to "true") else emptyMap()
                ),
                tunnelings = source.forwards.mapNotNull { it.toTermoraTunneling() },
                sort = hostSorts.next(parentId),
                parentId = parentId,
                ownerId = accountOwner.id,
                ownerType = accountOwner.type.name,
            )
        }

        val importedSnippets = mutableListOf<Snippet>()
        val snippetFolders = mutableMapOf<List<String>, Snippet>()
        val snippetSorts = mutableMapOf<String, Long>()
        bundle.snippets.forEach { source ->
            if (source.command.isBlank()) {
                warnings += "Snippet '${source.displayName()}': command is empty"
                return@forEach
            }
            val parent = ensureSnippetPath(
                source.packagePath,
                snippetFolders,
                snippetSorts,
                importedSnippets,
            )
            importedSnippets += Snippet(
                name = source.displayName(),
                snippet = source.command,
                parentId = parent?.id ?: "0",
                type = SnippetType.Snippet,
                sort = snippetSorts.next(parent?.id ?: "0"),
            )
        }

        return TermiusImportPlan(
            hosts = importedHosts,
            keys = importedKeys,
            snippets = importedSnippets,
            warnings = warnings,
        )
    }

    fun import(
        file: File,
        destination: Host,
        accountOwner: AccountOwner,
    ): TermiusImportResult {
        assertEventDispatchThread()
        val plan = plan(read(file), destination, accountOwner)

        val keyManager = KeyManager.getInstance()
        plan.keys.forEach { keyManager.addOhKeyPair(it, accountOwner) }

        val hostManager = HostManager.getInstance()
        plan.hosts.forEach { hostManager.addHost(it, DatabaseChangedExtension.Source.User) }

        val snippetManager = SnippetManager.getInstance()
        plan.snippets.forEach(snippetManager::addSnippet)

        return TermiusImportResult(
            hosts = plan.hosts.count { !it.isFolder },
            groups = plan.hosts.count { it.isFolder },
            keys = plan.keys.size,
            snippets = plan.snippets.count { it.type == SnippetType.Snippet },
            snippetFolders = plan.snippets.count { it.type == SnippetType.Folder },
            warnings = plan.warnings,
        )
    }

    private fun ensureHostPath(
        rawPath: List<String>,
        destination: Host,
        accountOwner: AccountOwner,
        folders: MutableMap<List<String>, Host>,
        sorts: MutableMap<String, Long>,
        hosts: MutableList<Host>,
    ): Host? {
        val path = normalizedPath(rawPath)
        var parent: Host? = null
        path.indices.forEach { index ->
            val segmentPath = path.take(index + 1)
            parent = folders.getOrPut(segmentPath) {
                val parentId = parent?.id ?: destination.id
                Host(
                    name = segmentPath.last(),
                    protocol = "Folder",
                    sort = sorts.next(parentId),
                    parentId = parentId,
                    ownerId = accountOwner.id,
                    ownerType = accountOwner.type.name,
                ).also(hosts::add)
            }
        }
        return parent
    }

    private fun ensureSnippetPath(
        rawPath: List<String>,
        folders: MutableMap<List<String>, Snippet>,
        sorts: MutableMap<String, Long>,
        snippets: MutableList<Snippet>,
    ): Snippet? {
        val path = normalizedPath(rawPath)
        var parent: Snippet? = null
        path.indices.forEach { index ->
            val segmentPath = path.take(index + 1)
            parent = folders.getOrPut(segmentPath) {
                val parentId = parent?.id ?: "0"
                Snippet(
                    name = segmentPath.last(),
                    parentId = parentId,
                    type = SnippetType.Folder,
                    sort = sorts.next(parentId),
                ).also(snippets::add)
            }
        }
        return parent
    }

    private fun normalizedPath(path: List<String>): List<String> =
        path.map(String::trim).filter(String::isNotBlank)

    private fun MutableMap<String, Long>.next(parentId: String): Long {
        val next = getOrDefault(parentId, 0L)
        this[parentId] = next + 1
        return next
    }

    private fun TermiusMigrationKey.toOhKeyPair(index: Int): OhKeyPair {
        val passwordProvider = FilePasswordProvider { _, _, _ -> passphrase }
        val keyPair = privateKey.byteInputStream(Charsets.UTF_8).use { input ->
            SecurityUtils.loadKeyPairIdentities(
                null,
                NamedResource.ofName(displayName()),
                input,
                passwordProvider,
            ).firstOrNull()
        } ?: error("SSH private key could not be decoded")
        val keyType = KeyUtils.getKeyType(keyPair)
        val type = when (keyType) {
            "ssh-rsa" -> "RSA"
            "ssh-ed25519" -> "ED25519"
            "ecdsa-sha2-nistp256" -> "ECDSA-SHA2-NISTP256"
            "ecdsa-sha2-nistp384" -> "ECDSA-SHA2-NISTP384"
            "ecdsa-sha2-nistp521" -> "ECDSA-SHA2-NISTP521"
            else -> error("Unsupported key type: $keyType")
        }
        return keyPair.toOhKeyPair(this, type, index)
    }

    private fun KeyPair.toOhKeyPair(source: TermiusMigrationKey, type: String, index: Int): OhKeyPair =
        OhKeyPair(
            id = randomUUID(),
            publicKey = Base64.encodeBase64String(public.encoded),
            privateKey = Base64.encodeBase64String(private.encoded),
            type = type,
            name = source.displayName(),
            remark = "Imported from Termius",
            length = KeyUtils.getKeySize(private),
            sort = System.currentTimeMillis() + index,
        )

    private fun TermiusMigrationProxy?.toTermoraProxy(): Proxy {
        this ?: return Proxy.No
        val type = when {
            kind.contains("socks", ignoreCase = true) -> ProxyType.SOCKS5
            kind.contains("http", ignoreCase = true) -> ProxyType.HTTP
            else -> ProxyType.No
        }
        if (type == ProxyType.No || host.isBlank()) return Proxy.No
        return Proxy(
            type = type,
            host = host,
            port = port.takeIf { it in 1..65535 } ?: if (type == ProxyType.HTTP) 8080 else 1080,
            authenticationType = if (username.isNotBlank() || password.isNotBlank()) {
                AuthenticationType.Password
            } else {
                AuthenticationType.No
            },
            username = username,
            password = password,
        )
    }

    private fun TermiusMigrationForward.toTermoraTunneling(): Tunneling? {
        if (localPort !in 1..65535) return null
        val tunnelingType = when (type.lowercase(Locale.ROOT)) {
            "remote" -> TunnelingType.Remote
            "dynamic" -> TunnelingType.Dynamic
            else -> TunnelingType.Local
        }
        if (tunnelingType != TunnelingType.Dynamic && (targetHost.isBlank() || targetPort !in 1..65535)) {
            return null
        }
        return Tunneling(
            name = label,
            type = tunnelingType,
            sourceHost = boundAddress.ifBlank { "127.0.0.1" },
            sourcePort = localPort,
            destinationHost = targetHost,
            destinationPort = targetPort,
        )
    }
}

data class TermiusImportPlan(
    val hosts: List<Host>,
    val keys: List<OhKeyPair>,
    val snippets: List<Snippet>,
    val warnings: List<String>,
)

data class TermiusImportResult(
    val hosts: Int,
    val groups: Int,
    val keys: Int,
    val snippets: Int,
    val snippetFolders: Int,
    val warnings: List<String>,
)

@Serializable
data class TermiusMigrationBundle(
    val format: String = String(),
    val version: Int = 0,
    val source: Map<String, String> = emptyMap(),
    val hosts: List<TermiusMigrationHost> = emptyList(),
    val keys: List<TermiusMigrationKey> = emptyList(),
    val snippets: List<TermiusMigrationSnippet> = emptyList(),
)

@Serializable
data class TermiusMigrationHost(
    val id: String = String(),
    val label: String = String(),
    val address: String = String(),
    val port: Int = 22,
    val username: String = String(),
    val password: String = String(),
    @SerialName("group_path")
    val groupPath: List<String> = emptyList(),
    @SerialName("key_id")
    val keyId: String? = null,
    val proxy: TermiusMigrationProxy? = null,
    @SerialName("agent_forward")
    val agentForward: Boolean = false,
    val forwards: List<TermiusMigrationForward> = emptyList(),
) {
    fun displayName(): String = label.ifBlank { address.ifBlank { id.ifBlank { "Unnamed host" } } }
}

@Serializable
data class TermiusMigrationKey(
    val id: String = String(),
    val label: String = String(),
    @SerialName("private_key")
    val privateKey: String = String(),
    @SerialName("public_key")
    val publicKey: String = String(),
    val passphrase: String = String(),
) {
    fun displayName(): String = label.ifBlank { id.ifBlank { "Unnamed key" } }
}

@Serializable
data class TermiusMigrationSnippet(
    val id: String = String(),
    val label: String = String(),
    val command: String = String(),
    @SerialName("package_path")
    val packagePath: List<String> = emptyList(),
) {
    fun displayName(): String = label.ifBlank { id.ifBlank { "Unnamed snippet" } }
}

@Serializable
data class TermiusMigrationProxy(
    val kind: String = String(),
    val host: String = String(),
    val port: Int = 0,
    val username: String = String(),
    val password: String = String(),
)

@Serializable
data class TermiusMigrationForward(
    val label: String = String(),
    val type: String = "Local",
    @SerialName("bound_address")
    val boundAddress: String = "127.0.0.1",
    @SerialName("local_port")
    val localPort: Int = 0,
    @SerialName("target_host")
    val targetHost: String = String(),
    @SerialName("target_port")
    val targetPort: Int = 0,
)
