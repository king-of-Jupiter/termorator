package app.termora


import app.termora.actions.*
import app.termora.database.DatabaseManager
import app.termora.findeverywhere.FindEverywhereProvider
import app.termora.terminal.DataKey
import app.termora.tree.*
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.extras.FlatSVGIcon
import com.formdev.flatlaf.extras.components.FlatButton
import org.apache.commons.lang3.StringUtils
import org.jdesktop.swingx.action.ActionManager
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import kotlin.math.max

class WelcomePanel(
    private val embedTree: Boolean = true,
    private val externalHostTreeProvider: (() -> NewHostTree?)? = null,
) : JPanel(BorderLayout()), Disposable, TerminalTab, DataProvider {

    private val properties get() = DatabaseManager.getInstance().properties
    private val rootPanel = JPanel(BorderLayout())
    private val hostTree = NewHostTree()
    private val hostCardsPanel = HostCardsPanel(
        hostTreeProvider = { if (embedTree) hostTree else externalHostTreeProvider?.invoke() }
    )
    private val centerCardLayout = CardLayout()
    private val centerPanel = JPanel(centerCardLayout)
    private val bannerPanel = BannerPanel()
    private val toggle = FlatButton()
    private var fullContent = properties.getString("WelcomeFullContent", "false").toBoolean()
    private var viewMode = if (embedTree) properties.getString("WelcomeViewMode", "cards") else "cards"
    private val dataProviderSupport = DataProviderSupport()
    private val hostTreeModel = hostTree.model as NewHostTreeModel
    private val filterableTreeModel = FilterableTreeModel(hostTree).apply { expand = true }
    private var lastFocused: Component? = null
    private val searchTextField = filterableTreeModel.filterableTextField

    init {
        initView()
        initEvents()
    }


    private fun initView() {
        putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, false)
        putClientProperty(FindEverywhereProvider.SKIP_FIND_EVERYWHERE, true)

        val panel = JPanel(BorderLayout())
        panel.add(createSearchPanel(), BorderLayout.NORTH)
        panel.add(createHostPanel(), BorderLayout.CENTER)
        bannerPanel.foreground = UIManager.getColor("TextField.placeholderForeground")

        if (!fullContent) {
            rootPanel.add(bannerPanel, BorderLayout.NORTH)
        }

        rootPanel.add(panel, BorderLayout.CENTER)
        add(rootPanel, BorderLayout.CENTER)

        // 在 Fence 布局中，主机树由侧边栏提供，这里不再注册，避免覆盖
        if (embedTree) {
            dataProviderSupport.addData(DataProviders.Welcome.HostTree, hostTree)
        }

    }

    private fun createSearchPanel(): JComponent {
        searchTextField.focusTraversalKeysEnabled = false
        searchTextField.preferredSize = Dimension(
            searchTextField.preferredSize.width,
            (UIManager.getInt("TitleBar.height") * 0.85).toInt()
        )

        // Отключить звук Windows при лишнем backspace (пустое поле)
        searchTextField.actionMap.put("beep", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {}
        })

        // Focus glow: accent bottom border with subtle glow
        val unfocusedBorder = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, DynamicColor.BorderColor),
            BorderFactory.createEmptyBorder(0, 0, 2, 0)
        )
        searchTextField.border = unfocusedBorder
        searchTextField.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                val accent = UIManager.getColor("Component.accentColor")
                    ?: UIManager.getColor("List.selectionBackground")
                    ?: DynamicColor.BorderColor
                searchTextField.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, accent),
                    BorderFactory.createEmptyBorder(0, 0, 1, 0)
                )
            }

            override fun focusLost(e: FocusEvent) {
                searchTextField.border = unfocusedBorder
            }
        })

        val iconSize = (searchTextField.preferredSize.height * 0.65).toInt()

        val newHost = FlatButton()
        newHost.icon = FlatSVGIcon(
            Icons.openNewTab.name,
            iconSize,
            iconSize
        )
        newHost.isFocusable = false
        newHost.buttonType = FlatButton.ButtonType.toolBarButton
        newHost.addActionListener { e ->
            ActionManager.getInstance().getAction(NewHostAction.NEW_HOST)?.actionPerformed(e)
        }

        // 卡片 / 列表 视图切换（仅 Screen 布局内嵌树时显示）
        val viewToggle = FlatButton()
        viewToggle.isFocusable = false
        viewToggle.buttonType = FlatButton.ButtonType.toolBarButton
        fun refreshViewToggleIcon() {
            viewToggle.icon = FlatSVGIcon(
                (if (viewMode == "cards") Icons.listFiles else Icons.homeFolder).name,
                iconSize, iconSize
            )
            viewToggle.toolTipText = I18n.getString(
                if (viewMode == "cards") "termora.welcome.view.list" else "termora.welcome.view.cards"
            )
        }
        refreshViewToggleIcon()
        viewToggle.addActionListener {
            viewMode = if (viewMode == "cards") "tree" else "cards"
            centerCardLayout.show(centerPanel, viewMode)
            refreshViewToggleIcon()
            perform()
        }


        toggle.icon = FlatSVGIcon(
            if (fullContent) Icons.collapseAll.name else Icons.expandAll.name,
            iconSize,
            iconSize
        )
        toggle.isFocusable = false
        toggle.buttonType = FlatButton.ButtonType.toolBarButton
        toggle.toolTipText = I18n.getString(
            if (fullContent) "termora.welcome.collapse" else "termora.welcome.expand"
        )

        val box = Box.createHorizontalBox()
        box.add(searchTextField)
        box.add(Box.createHorizontalStrut(6))
        box.add(newHost)
        if (embedTree) {
            box.add(Box.createHorizontalStrut(6))
            box.add(viewToggle)
        }
        box.add(Box.createHorizontalStrut(6))
        box.add(toggle)

        if (!fullContent) {
            box.border = BorderFactory.createEmptyBorder(24, 0, 0, 0)
        }

        toggle.addActionListener {
            fullContent = !fullContent
            toggle.icon = FlatSVGIcon(
                if (fullContent) Icons.collapseAll.name else Icons.expandAll.name,
                iconSize,
                iconSize
            )
            toggle.toolTipText = I18n.getString(
                if (fullContent) "termora.welcome.collapse" else "termora.welcome.expand"
            )
            if (fullContent) {
                box.border = BorderFactory.createEmptyBorder()
            } else {
                box.border = BorderFactory.createEmptyBorder(20, 0, 0, 0)
            }
            perform()
        }

        return box
    }

    private fun createHostPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        // Отключить звук при backspace в дереве без выделенного узла
        hostTree.actionMap.put("beep", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {}
        })
        hostTree.actionMap.put("find", object : AnAction() {
            override fun actionPerformed(evt: AnActionEvent) {
                searchTextField.requestFocusInWindow()
            }
        })
        hostTree.showsRootHandles = true

        val scrollPane = JScrollPane(hostTree)
        scrollPane.verticalScrollBar.maximumSize = Dimension(0, 0)
        scrollPane.verticalScrollBar.preferredSize = Dimension(0, 0)
        scrollPane.verticalScrollBar.minimumSize = Dimension(0, 0)
        scrollPane.border = BorderFactory.createEmptyBorder()


        panel.add(scrollPane, BorderLayout.CENTER)
        panel.border = BorderFactory.createEmptyBorder(10, 0, 0, 0)

        hostTree.model = filterableTreeModel
        hostTree.name = "WelcomeHostTree"
        hostTree.restoreExpansions()

        // 卡片视图为默认；树视图仅在内嵌树时可切换
        centerPanel.add(hostCardsPanel, "cards")
        if (embedTree) {
            centerPanel.add(panel, "tree")
        }
        centerCardLayout.show(centerPanel, if (embedTree) viewMode else "cards")

        return centerPanel
    }


    private fun initEvents() {

        Disposer.register(this, hostTree)
        Disposer.register(this, hostCardsPanel)
        Disposer.register(hostTree, filterableTreeModel)

        // 搜索框同时过滤卡片视图
        searchTextField.document.addDocumentListener(object : DocumentAdaptor() {
            override fun changedUpdate(e: DocumentEvent) {
                hostCardsPanel.filter(searchTextField.text)
            }
        })

        addComponentListener(object : ComponentAdapter() {
            override fun componentShown(e: ComponentEvent) {
                if (!searchTextField.hasFocus()) {
                    searchTextField.requestFocusInWindow()
                }
                perform()
                removeComponentListener(this)
            }
        })


        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                perform()
            }
        })

        filterableTreeModel.addFilter(object : Filter {
            override fun filter(node: Any): Boolean {
                val text = searchTextField.text.trim()
                if (text.isBlank()) return true
                if (node !is HostTreeNode) return false
                if (node is TeamTreeNode || node.id == "0") return true
                return node.host.name.contains(text, ignoreCase = true)
                        || node.host.host.contains(text, ignoreCase = true)
                        || node.host.username.contains(text, ignoreCase = true)
                        || node.host.remark.contains(text, ignoreCase = true)
            }

            override fun canFilter(): Boolean {
                return searchTextField.text.trim().isNotBlank()
            }

        })

        searchTextField.addKeyListener(object : KeyAdapter() {
            private val event = ActionEvent(hostTree, ActionEvent.ACTION_PERFORMED, StringUtils.EMPTY)
            private val openHostAction get() = ActionManager.getInstance().getAction(OpenHostAction.OPEN_HOST)

            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DOWN || e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_UP) {
                    when (e.keyCode) {
                        KeyEvent.VK_UP -> hostTree.actionMap.get("selectPrevious")?.actionPerformed(event)
                        KeyEvent.VK_DOWN -> hostTree.actionMap.get("selectNext")?.actionPerformed(event)
                        else -> {
                            for (node in hostTree.getSelectionSimpleTreeNodes(true)) {
                                openHostAction?.actionPerformed(OpenHostActionEvent(hostTree, node.host, e))
                            }
                        }
                    }
                    e.consume()
                }
            }
        })

    }

    private fun perform() {
        rootPanel.remove(bannerPanel)
        if (fullContent) {
            rootPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        } else {
            val top = max((height * 0.08).toInt(), 30)
            // 卡片视图占用更宽的横向空间，让每行能容纳更多卡片
            val left = if (viewMode == "cards") max((width * 0.04).toInt(), 24)
            else max((width * 0.25).toInt(), 30)
            rootPanel.add(bannerPanel, BorderLayout.NORTH)
            rootPanel.border = BorderFactory.createEmptyBorder(top, left, top / 2, left)
            SwingUtilities.invokeLater {
                rootPanel.revalidate()
                rootPanel.repaint()
            }
        }
    }


    override fun getTitle(): String {
        return StringUtils.EMPTY
    }

    override fun getIcon(): Icon {
        return Icons.homeFolder
    }

    override fun getJComponent(): JComponent {
        return this
    }

    override fun canReconnect(): Boolean {
        return false
    }

    override fun canClose(): Boolean {
        return false
    }

    override fun canClone(): Boolean {
        return false
    }

    override fun onLostFocus() {
        lastFocused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    }

    override fun onGrabFocus() {
        SwingUtilities.invokeLater { lastFocused?.requestFocusInWindow() }
    }

    override fun dispose() {
        properties.putString("WelcomeFullContent", fullContent.toString())
        if (embedTree) {
            properties.putString("WelcomeViewMode", viewMode)
        }
    }

    override fun <T : Any> getData(dataKey: DataKey<T>): T? {
        return dataProviderSupport.getData(dataKey)
    }


}
