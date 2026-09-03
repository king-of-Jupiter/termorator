package app.termora.migration

import app.termora.AuthenticationType
import app.termora.Host
import app.termora.ProxyType
import app.termora.TunnelingType
import app.termora.account.AccountOwner
import app.termora.database.OwnerType
import app.termora.snippet.SnippetType
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TermiusMigrationTest {
    private val destination = Host(
        id = "destination",
        name = "Imported",
        protocol = "Folder",
        ownerId = "owner",
        ownerType = OwnerType.User.name,
    )
    private val owner = AccountOwner("owner", "Local", OwnerType.User)

    @Test
    fun `preserves group and snippet paths and links imported key`() {
        val sourceKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val privatePem = buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(sourceKey.private.encoded))
            appendLine("-----END PRIVATE KEY-----")
        }
        val bundle = TermiusMigrationBundle(
            format = TermiusMigration.FORMAT,
            version = TermiusMigration.VERSION,
            keys = listOf(
                TermiusMigrationKey(
                    id = "key-1",
                    label = "Production key",
                    privateKey = privatePem,
                )
            ),
            hosts = listOf(
                TermiusMigrationHost(
                    id = "host-1",
                    label = "Web",
                    address = "192.0.2.10",
                    port = 2222,
                    username = "root",
                    groupPath = listOf("Production", "EU"),
                    keyId = "key-1",
                    proxy = TermiusMigrationProxy("socks5", "127.0.0.1", 1080),
                    agentForward = true,
                    forwards = listOf(
                        TermiusMigrationForward(
                            label = "Postgres",
                            type = "Local",
                            localPort = 15432,
                            targetHost = "db.internal",
                            targetPort = 5432,
                        )
                    ),
                )
            ),
            snippets = listOf(
                TermiusMigrationSnippet(
                    id = "snippet-1",
                    label = "Restart app",
                    command = "systemctl restart app",
                    packagePath = listOf("Operations", "Deploy"),
                )
            ),
        )

        val plan = TermiusMigration.plan(bundle, destination, owner)

        assertTrue(plan.warnings.isEmpty(), plan.warnings.joinToString())
        assertEquals(1, plan.keys.size)
        assertEquals(listOf("Production", "EU"), plan.hosts.filter { it.isFolder }.map(Host::name))
        val host = plan.hosts.single { !it.isFolder }
        assertEquals(AuthenticationType.PublicKey, host.authentication.type)
        assertEquals(plan.keys.single().id, host.authentication.password)
        assertEquals(ProxyType.SOCKS5, host.proxy.type)
        assertEquals("true", host.options.extras["forwardAgent"])
        assertEquals(TunnelingType.Local, host.tunnelings.single().type)

        val snippetFolders = plan.snippets.filter { it.type == SnippetType.Folder }
        assertEquals(listOf("Operations", "Deploy"), snippetFolders.map { it.name })
        val snippet = plan.snippets.single { it.type == SnippetType.Snippet }
        assertEquals(snippetFolders.last().id, snippet.parentId)
    }

    @Test
    fun `invalid key is reported and host falls back to password`() {
        val bundle = TermiusMigrationBundle(
            format = TermiusMigration.FORMAT,
            version = TermiusMigration.VERSION,
            keys = listOf(TermiusMigrationKey(id = "broken", label = "Broken", privateKey = "not a key")),
            hosts = listOf(
                TermiusMigrationHost(
                    label = "Fallback",
                    address = "example.com",
                    password = "password",
                    keyId = "broken",
                )
            ),
        )

        val plan = TermiusMigration.plan(bundle, destination, owner)

        assertEquals(0, plan.keys.size)
        assertEquals(AuthenticationType.Password, plan.hosts.single().authentication.type)
        assertEquals(2, plan.warnings.size)
    }
}
