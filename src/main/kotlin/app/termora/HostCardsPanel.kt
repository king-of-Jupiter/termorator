package app.termora

import app.termora.account.AccountManager
import app.termora.actions.NewHostAction
import app.termora.actions.OpenHostAction
import app.termora.database.DataType
import app.termora.database.DatabaseChangedExtension
import app.termora.plugin.internal.extension.DynamicExtensionHandler
import app.termora.plugin.internal.ssh.SSHProtocolProvider
import app.termora.protocol.ProtocolProvider
import app.termora.tree.NewHostTree
import com.formdev.flatlaf.FlatLaf
import org.apache.commons.lang3.StringUtils
import org.jdesktop.swingx.action.ActionManager
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Termius 风格的主机卡片视图：把所有服务器以较大的卡片形式按文件夹分组展示，
 * 双击卡片即可连接，并提供一个醒目的「新建主机」卡片。
 *
 * 数据来源与刷新逻辑参考 [app.termora.tree.NewHostTreeModel]。
 */
class HostCardsPanel(private val hostTreeProvider: () -> NewHostTree? = { null }) : JPanel(BorderLayout()), Disposable {

    private val hostManager get() = HostManager.getInstance()
    private val accountManager get() = AccountManager.getInstance()
    private val actionManager get() = ActionManager.getInstance()

    private val contentPanel = JPanel(GridBagLayout())
    private val scrollPane = JScrollPane(contentPanel)

    private val cardWidth = 190
    private val cardHeight = 76
    private val iconSize = 30

    private var filterText: String = StringUtils.EMPTY

    init {
        initView()
        initEvents()
        rebuild()
    }

    private fun initView() {
        contentPanel.isOpaque = false
        contentPanel.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBar.unitIncrement = 16
        scrollPane.viewport.isOpaque = false
        scrollPane.isOpaque = false

        add(scrollPane, BorderLayout.CENTER)
    }

    private fun initEvents() {
        // 视口宽度变化时重新布局，使卡片正确换行
        scrollPane.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                contentPanel.revalidate()
            }
        })

        // 底层数据变动时刷新卡片
        DynamicExtensionHandler.getInstance()
            .register(DatabaseChangedExtension::class.java, object : DatabaseChangedExtension {
                override fun onDataChanged(
                    id: String,
                    type: String,
                    action: DatabaseChangedExtension.Action,
                    source: DatabaseChangedExtension.Source
                ) {
                    if (type.isNotBlank() && type != DataType.Host.name) return
                    SwingUtilities.invokeLater { rebuild() }
                }
            }).let { Disposer.register(this, it) }

        // 主题变化时重建（图标按主题选择明/暗变体）
        DynamicExtensionHandler.getInstance()
            .register(ThemeChangeExtension::class.java, object : ThemeChangeExtension {
                override fun onChanged() {
                    SwingUtilities.invokeLater { rebuild() }
                }
            }).let { Disposer.register(this, it) }
    }

    /**
     * 根据搜索文本过滤卡片
     */
    fun filter(text: String) {
        val t = text.trim()
        if (t == filterText) return
        filterText = t
        rebuild()
    }

    private fun matches(host: Host): Boolean {
        if (filterText.isBlank()) return true
        return host.name.contains(filterText, ignoreCase = true)
                || host.host.contains(filterText, ignoreCase = true)
                || host.username.contains(filterText, ignoreCase = true)
                || host.remark.contains(filterText, ignoreCase = true)
    }

    private fun rebuild() {
        contentPanel.removeAll()

        val ownerIds = accountManager.getOwnerIds()
        val all = hostManager.hosts().filter { ownerIds.contains(it.ownerId) && it.isTemporary.not() }
        val folders = all.filter { it.isFolder }.sortedBy { it.sort }
        val folderIds = folders.map { it.id }.toSet()
        val realHosts = all.filter { it.isFolder.not() && matches(it) }

        val rootHosts = realHosts.filter {
            it.parentId.isBlank() || it.parentId == "0" || folderIds.contains(it.parentId).not()
        }

        val row = intArrayOf(0)
        var rendered = false

        // 根分组「我的主机」：始终在未搜索时显示（带新建卡片）
        if (filterText.isBlank() || rootHosts.isNotEmpty()) {
            addGroup(I18n.getString("termora.welcome.my-hosts"), rootHosts, filterText.isBlank(), row)
            rendered = true
        }

        // 文件夹分组
        for (folder in folders) {
            val hosts = realHosts.filter { it.parentId == folder.id }
            if (hosts.isEmpty()) continue
            addGroup(folder.name, hosts, includeAddCard = false, row = row)
            rendered = true
        }

        // 搜索无结果提示
        if (rendered.not()) {
            val label = JLabel(I18n.getString("termora.welcome.no-hosts"))
            label.foreground = DynamicColor("textInactiveText")
            label.horizontalAlignment = SwingConstants.CENTER
            val gbc = GridBagConstraints().apply {
                gridx = 0; gridy = row[0]++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(20, 8, 8, 8)
            }
            contentPanel.add(label, gbc)
        }

        // 底部填充，把内容顶到上方
        val filler = GridBagConstraints().apply {
            gridx = 0; gridy = row[0]++; weightx = 1.0; weighty = 1.0; fill = GridBagConstraints.BOTH
        }
        contentPanel.add(Box.createGlue(), filler)

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun addGroup(title: String, hosts: List<Host>, includeAddCard: Boolean, row: IntArray) {
        val header = JLabel(title)
        header.font = header.font.deriveFont(Font.BOLD)
        header.foreground = DynamicColor("textInactiveText")
        header.border = BorderFactory.createEmptyBorder(if (row[0] == 0) 6 else 18, 6, 6, 6)
        contentPanel.add(header, rowConstraints(row))

        val cards = JPanel(WrapLayout(FlowLayout.LEFT, 10, 10))
        cards.isOpaque = false
        if (includeAddCard) cards.add(AddCard())
        for (host in hosts.sortedBy { it.sort }) {
            cards.add(HostCard(host))
        }
        contentPanel.add(cards, rowConstraints(row))
    }

    private fun rowConstraints(row: IntArray): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = 0; gridy = row[0]++; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
        }
    }

    private fun protocolIcon(protocol: String): Icon {
        val base = ProtocolProvider.valueOf(protocol)?.getIcon() ?: Icons.terminal
        val themed = if (FlatLaf.isLafDark()) base.dark else base
        return themed.derive(iconSize, iconSize)
    }

    /**
     * 圆角卡片基类，封装悬停背景与圆角绘制
     */
    private abstract inner class RoundedCard : JPanel() {
        protected var hover = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
            preferredSize = Dimension(cardWidth, cardHeight)
            maximumSize = preferredSize
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    hover = true; repaint()
                }

                override fun mouseExited(e: MouseEvent) {
                    hover = false; repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = UIManager.getInt("Component.arc").coerceAtLeast(10)
                val bg = if (hover)
                    UIManager.getColor("List.selectionInactiveBackground") ?: UIManager.getColor("Component.background")
                else
                    UIManager.getColor("TextField.background") ?: background
                g2.color = bg
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = DynamicColor.BorderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private inner class HostCard(private val host: Host) : RoundedCard() {
        init {
            layout = BorderLayout(8, 0)

            val iconLabel = JLabel(protocolIcon(host.protocol))
            iconLabel.verticalAlignment = SwingConstants.CENTER
            add(iconLabel, BorderLayout.WEST)

            val box = Box.createVerticalBox()
            val nameLabel = JLabel(host.name)
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)
            nameLabel.alignmentX = LEFT_ALIGNMENT
            box.add(nameLabel)

            val subtitle = subtitleOf(host)
            if (subtitle.isNotBlank()) {
                box.add(Box.createVerticalStrut(4))
                val subLabel = JLabel(subtitle)
                subLabel.foreground = DynamicColor("textInactiveText")
                subLabel.alignmentX = LEFT_ALIGNMENT
                box.add(subLabel)
            }
            add(box, BorderLayout.CENTER)

            toolTipText = if (host.remark.isNotBlank()) host.remark else subtitle

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) {
                        actionManager.getAction(OpenHostAction.OPEN_HOST)
                            ?.actionPerformed(OpenHostActionEvent(this@HostCard, host, e))
                    }
                }

                override fun mousePressed(e: MouseEvent) = maybeShowContextmenu(e)
                override fun mouseReleased(e: MouseEvent) = maybeShowContextmenu(e)

                private fun maybeShowContextmenu(e: MouseEvent) {
                    if (e.isPopupTrigger.not()) return
                    hostTreeProvider()?.showContextmenuForHost(host, this@HostCard, e.x, e.y)
                }
            })
        }

        private fun subtitleOf(host: Host): String {
            return when {
                StringUtils.equalsIgnoreCase(host.protocol, SSHProtocolProvider.PROTOCOL) ->
                    if (host.username.isNotBlank()) "${host.username}@${host.host}" else host.host

                host.protocol == "Serial" -> host.options.serialComm.port
                else -> host.host
            }
        }
    }

    private inner class AddCard : RoundedCard() {
        init {
            layout = BorderLayout(8, 0)

            val icon = (if (FlatLaf.isLafDark()) Icons.add.dark else Icons.add).derive(iconSize, iconSize)
            val iconLabel = JLabel(icon)
            iconLabel.verticalAlignment = SwingConstants.CENTER
            add(iconLabel, BorderLayout.WEST)

            val nameLabel = JLabel(I18n.getString("termora.welcome.new-host"))
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)
            add(nameLabel, BorderLayout.CENTER)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        actionManager.getAction(NewHostAction.NEW_HOST)
                            ?.actionPerformed(
                                java.awt.event.ActionEvent(this@AddCard, java.awt.event.ActionEvent.ACTION_PERFORMED, StringUtils.EMPTY)
                            )
                    }
                }
            })
        }
    }
}
