package app.termora.plugin.internal.ssh

import app.termora.*
import app.termora.actions.DataProviders
import app.termora.actions.TabReconnectAction
import app.termora.addons.zmodem.ZModemPtyConnectorAdaptor
import app.termora.database.DatabaseManager
import app.termora.keymap.KeyShortcut
import app.termora.keymap.KeymapManager
import app.termora.plugin.internal.telnet.TelnetHostOptionsPane
import app.termora.terminal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import org.apache.commons.io.Charsets
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.future.CloseFuture
import org.apache.sshd.common.future.SshFutureListener
import org.slf4j.LoggerFactory
import java.awt.event.KeyEvent
import java.nio.charset.StandardCharsets
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

class SSHTerminalTab(
    windowScope: WindowScope, host: Host,
    private val handler: SshHandler = SshHandler()
) : PtyHostTerminalTab(windowScope, host) {

    companion object {
        val SSHSession = DataKey(ClientSession::class)
        internal val MySshHandler = DataKey(SshHandler::class)
        private val log = LoggerFactory.getLogger(SSHTerminalTab::class.java)
        private val detectingHosts = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }

    private val mutex = Mutex()
    private val owner get() = SwingUtilities.getWindowAncestor(terminalPanel)
    private val tab get() = this

    init {
        terminalPanel.dropFiles = false
        terminalPanel.dataProviderSupport.addData(DataProviders.TerminalTab, this)
    }

    override fun getJComponent(): JComponent {
        return terminalPanel
    }

    override fun canReconnect(): Boolean {
        return mutex.isLocked.not()
    }

    override fun createReconnectTerminalTab(): TerminalTab {
        return SSHTerminalTab(windowScope, host)
    }

    override suspend fun openPtyConnector(): PtyConnector {
        // Wait for mutex with timeout to handle concurrent connections
        val acquired = withTimeoutOrNull(30_000) {
            mutex.lock()
            true
        } ?: false

        if (acquired) {
            try {
                return doOpenPtyConnector()
            } finally {
                mutex.unlock()
            }
        }
        throw IllegalStateException("Connection timeout - another connection is in progress")
    }


    private suspend fun doOpenPtyConnector(): PtyConnector {

        // 连接提示
        withContext(Dispatchers.Swing) {
            // clear screen
            terminal.clearScreen()
            // hide cursor
            terminalModel.setData(DataKey.Companion.ShowCursor, false)
            // print
            terminal.write("Connecting to remote server ")
        }

        val loading = coroutineScope.launch(Dispatchers.Swing) {
            val braille = "⡿⣟⣯⣷⣾⣽⣻⢿".reversed().toCharArray()
//            val braille = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏".toCharArray()
            var c = 0
            while (isActive) {
                if (++c >= braille.size) c = 0
                terminal.write("${braille[c]}")
                delay(100.milliseconds)
                terminal.write("${ControlCharacters.BS}")
            }
        }

        val channel: ChannelShell
        var session: ClientSession? = null
        try {
            val client = openClient()
            session = openSession(client)
            channel = openChannel(session)
            // 打开隧道
            openTunnelings(session, host)
        } finally {
            loading.cancel()
        }

        // 隐藏提示
        withContext(Dispatchers.Swing) {
            // clear screen
            terminal.clearScreen()
            // show cursor
            terminalModel.setData(DataKey.ShowCursor, true)

            val encoder = terminal.getKeyEncoder()
            if (encoder is KeyEncoderImpl) {
                val backspace = host.options.extras["backspace"]
                if (backspace == TelnetHostOptionsPane.Backspace.Backspace.name) {
                    encoder.putCode(TerminalKeyEvent(keyCode = KeyEvent.VK_BACK_SPACE), String(byteArrayOf(0x08)))
                } else if (backspace == TelnetHostOptionsPane.Backspace.VT220.name) {
                    encoder.putCode(TerminalKeyEvent(keyCode = KeyEvent.VK_BACK_SPACE), "${ControlCharacters.ESC}[3~")
                }
            }

        }

        // TODO: OS detection temporarily disabled - causes SSH lag
        // detectOS()

        return ptyConnectorFactory.decorate(
            ZModemPtyConnectorAdaptor(
                terminal,
                terminalPanel,
                ChannelShellPtyConnector(
                    channel,
                    charset = Charsets.toCharset(host.options.encoding, StandardCharsets.UTF_8)
                )
            )
        )
    }

    private fun detectOS() {
        // Skip if already detected
        if (host.options.extras.containsKey("osIcon")) return
        // Skip if another tab is already detecting this host
        if (!detectingHosts.add(host.id)) return

        // Run detection in background with delay to avoid connection burst
        swingCoroutineScope.launch(Dispatchers.IO) {
            try {
                // Wait before connecting to avoid simultaneous connections
                delay(2000.milliseconds)

                log.info("Detecting OS for host: {}", host.name)
                val client = SshClients.openClient(host, owner)
                try {
                    val session = SshClients.openSession(host, client)
                    try {
                        val channel = session.createExecChannel(OSDetector.getDetectCommand())
                        val baos = java.io.ByteArrayOutputStream()
                        channel.out = baos
                        val timeout = 5000L
                        if (channel.open().verify(timeout).await(timeout)) {
                            channel.waitFor(java.util.EnumSet.of(ClientChannelEvent.CLOSED), timeout)
                        }
                        val output = baos.toString()
                        log.info("OS detection output: {}", output.take(200))
                        channel.close(false)
                        val osType = OSDetector.detect(output)
                        log.info("Detected OS type: {}", osType)
                        // Store detected OS in host extras
                        withContext(Dispatchers.Swing) {
                            val newExtras = host.options.extras.toMutableMap()
                            newExtras["osIcon"] = osType.name
                            val newHost = host.copy(
                                options = host.options.copy(extras = newExtras),
                                ownerType = if (host.ownerType.isBlank()) "User" else host.ownerType
                            )
                            HostManager.getInstance().addHost(newHost)
                            log.info("Saved OS icon for host: {}", host.name)
                        }
                    } finally {
                        session.close(false)
                    }
                } finally {
                    client.close()
                }
            } catch (e: Exception) {
                log.warn("OS detection failed for {}: {}", host.name, e.message)
            } finally {
                detectingHosts.remove(host.id)
            }
        }
    }

    private suspend fun openTunnelings(session: ClientSession, host: Host) {
        if (host.tunnelings.isEmpty()) {
            return
        }

        for (tunneling in host.tunnelings) {
            try {
                SshClients.openTunneling(session, host, tunneling)
                withContext(Dispatchers.Swing) {
                    terminal.write("Start [${tunneling.name}] port forwarding successfully.\r\n")
                }
            } catch (e: Exception) {
                if (log.isErrorEnabled) {
                    log.error("Start [${tunneling.name}] port forwarding failed: {}", e.message, e)
                }
                withContext(Dispatchers.Swing) {
                    terminal.write("Start [${tunneling.name}] port forwarding failed: ${e.message}\r\n")
                }
            }

        }
    }

    private fun openClient(): SshClient {
        val client = handler.client
        if (client != null) return client
        return SshClients.openClient(host, owner).also { handler.client = it }
    }

    private fun openSession(client: SshClient): ClientSession {
        val session = handler.session
        if (session != null) return SshSessionPool.register(session, client)
        return SshClients.openSession(host, client).also { handler.session = SshSessionPool.register(it, client) }
    }

    private fun openChannel(session: ClientSession): ChannelShell {
        val channel = SshClients.openShell(host, terminalPanel.winSize(), session)
        handler.channel = channel

        channel.addCloseFutureListener(object : SshFutureListener<CloseFuture> {
            private val reconnectShortcut
                get() = KeymapManager.Companion.getInstance().getActiveKeymap()
                    .getShortcut(TabReconnectAction.Companion.RECONNECT_TAB).firstOrNull()
            private val autoCloseTabWhenDisconnected get() = DatabaseManager.getInstance().terminal.autoCloseTabWhenDisconnected

            override fun operationComplete(future: CloseFuture) {
                coroutineScope.launch(Dispatchers.Swing) {
                    terminal.write("\r\n\r\n${ControlCharacters.Companion.ESC}[31m")
                    terminal.write(I18n.getString("termora.terminal.channel-disconnected"))
                    if (reconnectShortcut is KeyShortcut) {
                        terminal.write(
                            I18n.getString(
                                "termora.terminal.channel-reconnect",
                                reconnectShortcut.toString()
                            )
                        )
                    }
                    terminal.write("\r\n")
                    terminal.write("${ControlCharacters.Companion.ESC}[0m")
                    terminalModel.setData(DataKey.Companion.ShowCursor, false)

                    if (autoCloseTabWhenDisconnected) {
                        terminalTabbedManager?.let { manager ->
                            SwingUtilities.invokeLater {
                                manager.closeTerminalTab(tab, true)
                            }
                        }
                    }

                    // stop
                    stop()
                }
            }
        })

        return channel
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getData(dataKey: DataKey<T>): T? {
        if (dataKey == SSHSession) {
            return handler.session as T?
        }
        if (dataKey == MySshHandler) {
            return handler as T?
        }
        return super.getData(dataKey)
    }

    override fun stop() {
        if (mutex.tryLock()) {
            try {
                super.stop()
                handler.close()
            } finally {
                mutex.unlock()
            }
        }
    }

    override fun getIcon(): Icon {
        return Icons.terminal
    }

    override fun beforeClose() {
        // 保存窗口状态
        terminalPanel.storeVisualWindows(host.id)
    }

}