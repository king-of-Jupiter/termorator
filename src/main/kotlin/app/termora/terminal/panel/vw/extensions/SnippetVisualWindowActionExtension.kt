package app.termora.terminal.panel.vw.extensions

import app.termora.I18n
import app.termora.Icons
import app.termora.PtyHostTerminalTab
import app.termora.TerminalTab
import app.termora.actions.AnAction
import app.termora.actions.AnActionEvent
import app.termora.terminal.panel.FloatingToolbarActionExtension
import app.termora.terminal.panel.TerminalPanel
import app.termora.terminal.panel.vw.VisualWindow
import app.termora.terminal.panel.vw.VisualWindowManager

class SnippetVisualWindowActionExtension private constructor() : FloatingToolbarActionExtension {

    companion object {
        val instance = SnippetVisualWindowActionExtension()
    }

    override fun createActionButton(visualWindowManager: VisualWindowManager, tab: TerminalTab): AnAction {
        if (tab !is PtyHostTerminalTab) throw UnsupportedOperationException()
        val panel = visualWindowManager as? TerminalPanel ?: throw UnsupportedOperationException()
        return object : AnAction(Icons.codeSpan) {
            init {
                putValue(SHORT_DESCRIPTION, I18n.getString("termora.snippet.title"))
            }

            override fun actionPerformed(evt: AnActionEvent) {
                panel.toggleSnippetSidePanel()
            }
        }
    }

    override fun getVisualWindowClass(tab: TerminalTab): Class<out VisualWindow> {
        throw UnsupportedOperationException()
    }

    override fun ordered(): Long {
        return 2;
    }
}
