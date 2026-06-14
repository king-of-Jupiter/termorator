package app.termora.actions

import app.termora.*
import app.termora.protocol.GenericProtocolProvider
import app.termora.protocol.ProtocolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.StringUtils
import javax.swing.JOptionPane

class OpenHostAction : AnAction() {
    companion object {
        /**
         * 打开一个主机
         */
        const val OPEN_HOST = "OpenHostAction"
    }


    override fun actionPerformed(evt: AnActionEvent) {
        if (evt !is OpenHostActionEvent) return
        val terminalTabbedManager = evt.getData(DataProviders.TerminalTabbedManager) ?: return
        val windowScope = evt.getData(DataProviders.WindowScope) ?: return
        val host = evt.host

        var providers = ProtocolProvider.providers

        if (providers.none { StringUtils.equalsIgnoreCase(it.getProtocol(), host.protocol) }) {
            OptionPane.showMessageDialog(
                windowScope.window,
                I18n.getString("termora.protocol.not-supported", host.protocol),
                messageType = JOptionPane.ERROR_MESSAGE,
            )
            return
        }

        // 只处理通用协议
        providers = providers.filterIsInstance<GenericProtocolProvider>()

        var provider: GenericProtocolProvider? = null
        for (p in providers) {
            if (StringUtils.equalsIgnoreCase(p.getProtocol(), host.protocol)) {
                if (p.canCreateTerminalTab(evt, windowScope, host)) {
                    provider = p
                    break
                }
            }
        }

        if (provider == null) return

        // Launch tab creation in background to avoid blocking EDT
        swingCoroutineScope.launch(Dispatchers.IO) {
            val tab = provider.createTerminalTab(evt, windowScope, host)

            withContext(Dispatchers.Swing) {
                if (evt.tabIndex >= 0) {
                    terminalTabbedManager.addTerminalTab(evt.tabIndex, tab, evt.selected)
                } else {
                    terminalTabbedManager.addTerminalTab(tab, evt.selected)
                }

                if (tab is PtyHostTerminalTab) {
                    tab.start()
                }
            }
        }
    }


}
