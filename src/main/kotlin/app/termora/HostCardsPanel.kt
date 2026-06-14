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
import javax.swing.Timer

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

    private val cardWidth = 220
    private val cardHeight = 64
    private val iconSize = 28

    private var filterText: String = StringUtils.EMPTY

    companion object {
        private const val ANIM_DURATION = 120f // мс, длительность hover-анимации
    }

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
        val headerText = if (hosts.isNotEmpty()) "$title  (${hosts.size})" else title
        val header = JLabel(headerText)
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

    private fun getHostIcon(host: Host): Icon {
        // Check if OS was detected
        val osIconName = host.options.extras["osIcon"]
        if (osIconName != null) {
            try {
                val osType = app.termora.plugin.internal.ssh.OSDetector.OSType.valueOf(osIconName)
                val base = osType.getIcon()
                if (base is DynamicIcon) {
                    val themed = if (FlatLaf.isLafDark()) base.dark else base
                    return themed.derive(iconSize, iconSize)
                }
                return base
            } catch (e: Exception) {
                // Invalid OS type, fall through to protocol icon
            }
        }
        return protocolIcon(host.protocol)
    }

    /**
     * Карточка с плавной hover-анимацией и pressed-эффектом.
     */
    private abstract inner class RoundedCard : JPanel() {
        protected var hoverAlpha = 0f // 0..255
        protected var pressed = false
        private var hoverTarget = false
        private var animStart = 0L
        private var animTimer: Timer? = null

        private val normalBg: Color
            get() = UIManager.getColor("TextField.background") ?: background

        private val hoverBg: Color
            get() = UIManager.getColor("List.selectionInactiveBackground")
                ?: UIManager.getColor("Component.background")
                ?: background

        init {
            isOpaque = false
            isFocusable = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
            preferredSize = Dimension(cardWidth, cardHeight)
            maximumSize = preferredSize
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    startHover(true)
                }

                override fun mouseExited(e: MouseEvent) {
                    pressed = false
                    startHover(false)
                }

                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        pressed = true; repaint()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    pressed = false; repaint()
                }
            })
        }

        private fun startHover(target: Boolean) {
            hoverTarget = target
            animStart = System.nanoTime()
            animTimer?.stop()
            animTimer = Timer(16, null).apply {
                addActionListener {
                    val elapsed = (System.nanoTime() - animStart) / 1_000_000f
                    val progress = (elapsed / ANIM_DURATION).coerceIn(0f, 1f)
                    val eased = 1f - (1f - progress) * (1f - progress) // ease-out
                    hoverAlpha = if (hoverTarget) eased * 255f else (1f - eased) * 255f
                    if (progress >= 1f) {
                        hoverAlpha = if (hoverTarget) 255f else 0f
                        stop()
                    }
                    repaint()
                }
                isRepeats = true
                start()
            }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                val arc = 14

                val alpha = (hoverAlpha / 255f).coerceIn(0f, 1f)
                val bg = if (alpha > 0.01f) blend(normalBg, hoverBg, alpha) else normalBg
                val finalBg = if (pressed) bg.darker() else bg

                // Multi-layer shadow for depth (Apple style)
                if (alpha > 0.01f) {
                    // Outer shadow - wide, soft
                    val s1 = (alpha * 18).toInt().coerceIn(0, 18)
                    g2.color = Color(0, 0, 0, s1)
                    g2.fillRoundRect(0, 3, width - 1, height - 1, arc, arc)
                    // Inner shadow - tighter
                    val s2 = (alpha * 12).toInt().coerceIn(0, 12)
                    g2.color = Color(0, 0, 0, s2)
                    g2.fillRoundRect(0, 1, width - 1, height - 1, arc, arc)
                }

                // Card background
                g2.color = finalBg
                g2.fillRoundRect(0, 0, width - 1, height - 2, arc, arc)

                // Subtle top highlight (Apple's signature light reflection)
                val highlightAlpha = (alpha * 40 + 15).toInt().coerceIn(15, 55)
                g2.color = Color(255, 255, 255, highlightAlpha)
                g2.fillRoundRect(0, 0, width - 1, 1, arc, arc)

                // Border - very thin, barely visible
                g2.color = if (alpha > 0.2f)
                    blend(DynamicColor.BorderColor, UIManager.getColor("Component.accentColor") ?: DynamicColor.BorderColor, alpha * 0.2f)
                else
                    DynamicColor.BorderColor
                g2.drawRoundRect(0, 0, width - 1, height - 2, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }

        private fun blend(c1: Color, c2: Color, t: Float): Color {
            val r = (c1.red + (c2.red - c1.red) * t).toInt().coerceIn(0, 255)
            val g = (c1.green + (c2.green - c1.green) * t).toInt().coerceIn(0, 255)
            val b = (c1.blue + (c2.blue - c1.blue) * t).toInt().coerceIn(0, 255)
            return Color(r, g, b)
        }
    }

    private inner class HostCard(private val host: Host) : RoundedCard() {
        var loading = false
            private set
        private var loadingProgress = 0f
        private var loadingTimer: Timer? = null

        init {
            layout = BorderLayout(8, 0)

            // Use OS-specific icon if detected, otherwise protocol icon
            val icon = getHostIcon(host)
            val iconLabel = JLabel(icon)
            iconLabel.verticalAlignment = SwingConstants.CENTER
            add(iconLabel, BorderLayout.WEST)

            val box = Box.createVerticalBox()
            // Dynamic font size based on card height
            val nameFontSize = (cardHeight * 0.22f).coerceIn(12f, 16f)
            val subFontSize = (cardHeight * 0.15f).coerceIn(9f, 12f)

            val nameLabel = JLabel(host.name)
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, nameFontSize)
            nameLabel.alignmentX = LEFT_ALIGNMENT
            box.add(nameLabel)

            val subtitle = subtitleOf(host)
            if (subtitle.isNotBlank()) {
                box.add(Box.createVerticalStrut(2))
                val subLabel = JLabel(subtitle)
                subLabel.foreground = DynamicColor("textInactiveText")
                subLabel.font = subLabel.font.deriveFont(subFontSize)
                subLabel.alignmentX = LEFT_ALIGNMENT
                box.add(subLabel)
            }

            // Add protocol/port info if there's space
            val extraInfo = extraInfoOf(host)
            if (extraInfo.isNotBlank() && cardHeight > 55) {
                box.add(Box.createVerticalStrut(1))
                val extraLabel = JLabel(extraInfo)
                extraLabel.foreground = DynamicColor("textInactiveText")
                extraLabel.font = extraLabel.font.deriveFont(subFontSize - 1f)
                extraLabel.alignmentX = LEFT_ALIGNMENT
                box.add(extraLabel)
            }
            add(box, BorderLayout.CENTER)

            toolTipText = if (host.remark.isNotBlank()) host.remark else subtitle

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        hostTreeProvider()?.showContextmenuForHost(host, this@HostCard, e.x, e.y)
                        return
                    }

                    val isLeft = SwingUtilities.isLeftMouseButton(e)
                    val isMiddle = SwingUtilities.isMiddleMouseButton(e)

                    if (isLeft || isMiddle) {
                        if (loading) return
                        startLoading()
                        val selectTab = isLeft
                        actionManager.getAction(OpenHostAction.OPEN_HOST)
                            ?.actionPerformed(OpenHostActionEvent(this@HostCard, host, e, selected = selectTab))
                        Timer(3000) { stopLoading() }.apply { isRepeats = false; start() }
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        hostTreeProvider()?.showContextmenuForHost(host, this@HostCard, e.x, e.y)
                    }
                }
            })
        }

        fun startLoading() {
            loading = true
            loadingProgress = 0f
            loadingTimer?.stop()
            // 33ms = ~30fps instead of 16ms = 60fps to reduce EDT pressure
            loadingTimer = Timer(33, null).apply {
                addActionListener {
                    loadingProgress += 0.03f
                    if (loadingProgress >= 1f) loadingProgress = 0f
                    repaint()
                }
                isRepeats = true
                start()
            }
        }

        fun stopLoading() {
            loading = false
            loadingTimer?.stop()
            loadingTimer = null
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (!loading) return

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = 14

                // Pulsing accent border
                val accent = UIManager.getColor("Component.accentColor")
                    ?: UIManager.getColor("List.selectionBackground")
                    ?: Color(100, 149, 237)
                val pulse = (kotlin.math.sin(loadingProgress.toDouble() * kotlin.math.PI * 2) * 0.5 + 0.5).coerceIn(0.0, 1.0).toFloat()
                val borderAlpha = (60 + pulse * 120).toInt().coerceIn(60, 180)
                g2.color = Color(accent.red, accent.green, accent.blue, borderAlpha)
                g2.stroke = BasicStroke(2f)
                g2.drawRoundRect(1, 1, width - 3, height - 4, arc, arc)

                // Loading dots at bottom right
                val dotSize = 4
                val dotSpacing = 8
                val dotsX = width - 30
                val dotsY = height - 14
                for (i in 0 until 3) {
                    val phase = (loadingProgress + i * 0.33f) % 1f
                    val dotAlpha = (kotlin.math.sin(phase.toDouble() * kotlin.math.PI) * 200).toInt().coerceIn(30, 200)
                    g2.color = Color(accent.red, accent.green, accent.blue, dotAlpha)
                    g2.fillOval(dotsX + i * dotSpacing, dotsY, dotSize, dotSize)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun subtitleOf(host: Host): String {
            return when {
                StringUtils.equalsIgnoreCase(host.protocol, SSHProtocolProvider.PROTOCOL) ->
                    if (host.username.isNotBlank()) "${host.username}@${host.host}" else host.host

                host.protocol == "Serial" -> host.options.serialComm.port
                else -> host.host
            }
        }

        private fun extraInfoOf(host: Host): String {
            return when {
                StringUtils.equalsIgnoreCase(host.protocol, SSHProtocolProvider.PROTOCOL) -> {
                    val port = if (host.port > 0) host.port else 22
                    "SSH • ${host.host}:$port"
                }
                host.protocol == "Serial" -> "Serial • ${host.options.serialComm.baudRate} baud"
                else -> host.protocol
            }
        }
    }

    /**
     * Карточка «Новый хост» — с пунктирной рамкой и accent-цветом.
     */
    private inner class AddCard : RoundedCard() {
        init {
            layout = BorderLayout(8, 0)
            isFocusable = false

            val icon = (if (FlatLaf.isLafDark()) Icons.add.dark else Icons.add).derive(iconSize, iconSize)
            val iconLabel = JLabel(icon)
            iconLabel.verticalAlignment = SwingConstants.CENTER
            add(iconLabel, BorderLayout.WEST)

            val nameFontSize = (cardHeight * 0.22f).coerceIn(12f, 16f)
            val nameLabel = JLabel(I18n.getString("termora.welcome.new-host"))
            nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, nameFontSize)
            add(nameLabel, BorderLayout.CENTER)

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        actionManager.getAction(NewHostAction.NEW_HOST)
                            ?.actionPerformed(
                                java.awt.event.ActionEvent(this@AddCard, java.awt.event.ActionEvent.ACTION_PERFORMED, StringUtils.EMPTY)
                            )
                    }
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                val arc = 14

                val accent = UIManager.getColor("Component.accentColor")
                    ?: UIManager.getColor("List.selectionInactiveBackground")
                    ?: UIManager.getColor("Component.background")
                    ?: background
                val alpha = (hoverAlpha / 255f).coerceIn(0f, 1f)

                // Shadow on hover
                if (alpha > 0.01f) {
                    val s = (alpha * 15).toInt().coerceIn(0, 15)
                    g2.color = Color(accent.red, accent.green, accent.blue, s)
                    g2.fillRoundRect(0, 2, width - 1, height - 1, arc, arc)
                }

                // Accent background - glass-like, intensifies on hover
                val bgAlpha = (10 + alpha * 22).toInt().coerceIn(10, 32)
                g2.color = Color(accent.red, accent.green, accent.blue, bgAlpha)
                g2.fillRoundRect(0, 0, width - 1, height - 2, arc, arc)

                // Dashed border - smooth, Apple-style
                val strokeWidth = 1.0f + alpha * 0.5f
                val stroke = BasicStroke(strokeWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 0f, floatArrayOf(6f, 4f), 0f)
                g2.stroke = stroke
                g2.color = Color(accent.red, accent.green, accent.blue, (140 + alpha * 115).toInt().coerceIn(140, 255))
                g2.drawRoundRect(1, 1, width - 3, height - 3, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
