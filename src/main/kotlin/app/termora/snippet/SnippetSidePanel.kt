package app.termora.snippet

import app.termora.*
import app.termora.actions.DataProviders
import app.termora.terminal.panel.TerminalPanel
import com.formdev.flatlaf.extras.components.FlatTextField
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.*
import javax.swing.border.EmptyBorder


internal class SnippetSidePanel(
    private val tab: PtyHostTerminalTab,
    private val terminalPanel: TerminalPanel
) : JPanel(BorderLayout()) {

    private val snippetManager = SnippetManager.getInstance()
    private val model = DefaultListModel<ListItem>()
    private val searchField = FlatTextField()
    private val expandedFolders = mutableSetOf<String>()
    private var myList: JList<ListItem>? = null

    private sealed class ListItem {
        data class FolderItem(val snippet: Snippet, val childCount: Int) : ListItem()
        data class SnippetItem(val snippet: Snippet) : ListItem()
    }

    init {
        background = DynamicColor("window")
        preferredSize = Dimension(360, 0)
        initViews()
        initEvents()
        refreshSnippets()
    }

    private fun initViews() {
        add(createResizeHandle(), BorderLayout.WEST)
        add(createContentPanel(), BorderLayout.CENTER)
    }

    private fun createResizeHandle(): JComponent {
        val handle = object : JComponent() {
            init {
                preferredSize = Dimension(4, 0)
                cursor = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = DynamicColor.BorderColor
                g2.fillRect(0, 0, 1, height)
            }
        }

        var startX = 0
        var startWidth = 0

        handle.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                startX = e.xOnScreen
                startWidth = this@SnippetSidePanel.width
            }
        })

        handle.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val delta = startX - e.xOnScreen
                val newWidth = (startWidth + delta).coerceIn(200, 600)
                terminalPanel.setSnippetSidePanelWidth(newWidth)
            }
        })

        return handle
    }

    private fun createContentPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = background

        panel.add(createHeaderPanel(), BorderLayout.NORTH)
        panel.add(createListPanel(), BorderLayout.CENTER)

        return panel
    }

    private fun createHeaderPanel(): JComponent {
        val topPanel = JPanel(BorderLayout())
        topPanel.border = EmptyBorder(8, 10, 8, 10)
        topPanel.background = background

        // Title row with close and new snippet buttons
        val titleRow = JPanel(BorderLayout())
        titleRow.isOpaque = false

        val titleLabel = JLabel(I18n.getString("termora.snippet.title"))
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        titleRow.add(titleLabel, BorderLayout.WEST)

        val btnPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0))
        btnPanel.isOpaque = false

        val newSnippetBtn = JButton(Icons.add)
        newSnippetBtn.toolTipText = I18n.getString("termora.snippet.new-snippet")
        newSnippetBtn.isFocusPainted = false
        newSnippetBtn.border = EmptyBorder(2, 2, 2, 2)
        newSnippetBtn.addActionListener {
            val window = SwingUtilities.getWindowAncestor(this) ?: return@addActionListener
            SnippetDialog(window).isVisible = true
            refreshSnippets()
        }
        btnPanel.add(newSnippetBtn)

        val closeBtn = JButton(Icons.close)
        closeBtn.isFocusPainted = false
        closeBtn.border = EmptyBorder(2, 2, 2, 2)
        closeBtn.addActionListener { terminalPanel.toggleSnippetSidePanel() }
        btnPanel.add(closeBtn)

        titleRow.add(btnPanel, BorderLayout.EAST)

        // Search field
        searchField.leadingIcon = Icons.find
        searchField.placeholderText = I18n.getString("termora.snippet.search-placeholder")
        searchField.isShowClearButton = true
        searchField.background = background
        searchField.border = EmptyBorder(4, 8, 4, 8)
        searchField.preferredSize = Dimension(0, 32)

        topPanel.add(titleRow, BorderLayout.NORTH)
        topPanel.add(searchField, BorderLayout.SOUTH)

        return topPanel
    }

    private fun createListPanel(): JComponent {
        val list = object : JList<ListItem>(model) {
            override fun getScrollableTracksViewportWidth(): Boolean = true
        }
        myList = list

        list.fixedCellHeight = UIManager.getInt("Tree.rowHeight") * 2
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.background = DynamicColor("window")
        list.cellRenderer = SnippetListCellRenderer()

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount % 2 == 0) {
                    val selectedIndex = list.selectedIndex
                    if (selectedIndex < 0) return
                    val item = model.getElementAt(selectedIndex)
                    when (item) {
                        is ListItem.FolderItem -> {
                            val id = item.snippet.id
                            if (expandedFolders.contains(id)) expandedFolders.remove(id)
                            else expandedFolders.add(id)
                            refreshSnippets()
                        }
                        is ListItem.SnippetItem -> runSnippet(item.snippet)
                    }
                } else if (e.button == MouseEvent.BUTTON3) {
                    val index = list.locationToIndex(e.point)
                    if (index >= 0) {
                        list.selectedIndex = index
                        val item = model.getElementAt(index)
                        if (item is ListItem.SnippetItem) {
                            showContextMenu(e, item.snippet)
                        }
                    }
                }
            }
        })

        val scrollPane = JScrollPane(list)
        scrollPane.border = EmptyBorder(0, 0, 0, 0)
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        return scrollPane
    }

    private fun showContextMenu(e: MouseEvent, snippet: Snippet) {
        val menu = JPopupMenu()

        val runItem = menu.add(I18n.getString("termora.snippet.run"))
        runItem.addActionListener { runSnippet(snippet) }

        val editItem = menu.add(I18n.getString("termora.snippet.edit"))
        editItem.addActionListener {
            val window = SwingUtilities.getWindowAncestor(this) ?: return@addActionListener
            SnippetDialog(window, snippet.id).isVisible = true
            refreshSnippets()
        }

        menu.addSeparator()

        val deleteItem = menu.add(I18n.getString("termora.snippet.delete"))
        deleteItem.addActionListener {
            val deleted = snippet.copy(deleted = true)
            snippetManager.addSnippet(deleted)
            refreshSnippets()
        }

        menu.show(this, e.x, e.y)
    }

    private fun runSnippet(snippet: Snippet) {
        val writer = tab.getData(DataProviders.TerminalWriter) ?: return
        SnippetAction.getInstance().runSnippet(snippet, writer)
        SwingUtilities.invokeLater { tab.getData(DataProviders.TerminalPanel)?.requestFocusInWindow() }
    }

    private fun initEvents() {
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = refreshSnippets()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = refreshSnippets()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = refreshSnippets()
        })

        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentShown(e: java.awt.event.ComponentEvent) {
                refreshSnippets()
            }
        })
    }

    fun refreshSnippets() {
        model.clear()
        val all = snippetManager.snippets().filter { !it.deleted }
        val searchText = searchField.text.orEmpty().lowercase()

        val filtered = if (searchText.isBlank()) all else all.filter {
            it.name.lowercase().contains(searchText) || it.snippet.lowercase().contains(searchText)
        }

        val folders = filtered.filter { it.type == SnippetType.Folder }
        val rootSnippets = filtered.filter { it.type == SnippetType.Snippet && (it.parentId.isEmpty() || it.parentId == "0") }

        for (folder in folders) {
            val children = filtered.filter { it.parentId == folder.id && it.type == SnippetType.Snippet }
            model.addElement(ListItem.FolderItem(folder, children.size))
            if (expandedFolders.contains(folder.id)) {
                children.forEach { model.addElement(ListItem.SnippetItem(it)) }
            }
        }

        rootSnippets.forEach { model.addElement(ListItem.SnippetItem(it)) }

        myList?.revalidate()
        myList?.repaint()
    }

    private class SnippetListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val list = list!!
            val panel = JPanel(BorderLayout())
            panel.border = EmptyBorder(4, 8, 4, 8)

            if (isSelected) {
                panel.background = UIManager.getColor("List.selectionBackground")
            } else {
                panel.background = UIManager.getColor("window")
            }

            when (value) {
                is ListItem.FolderItem -> {
                    val iconLabel = JLabel(Icons.folder)
                    iconLabel.border = EmptyBorder(0, 0, 0, 6)
                    panel.add(iconLabel, BorderLayout.WEST)

                    val textPanel = JPanel(BorderLayout())
                    textPanel.isOpaque = false

                    val nameLabel = JLabel(value.snippet.name)
                    nameLabel.font = list.font.deriveFont(Font.BOLD)
                    if (isSelected) nameLabel.foreground = UIManager.getColor("List.selectionForeground")
                    textPanel.add(nameLabel, BorderLayout.CENTER)

                    val countLabel = JLabel("${value.childCount}")
                    countLabel.foreground = UIManager.getColor("TextField.placeholderForeground")
                    countLabel.font = list.font.deriveFont(Font.PLAIN, (list.font.size.toFloat()) - 2f)
                    textPanel.add(countLabel, BorderLayout.EAST)

                    panel.add(textPanel, BorderLayout.CENTER)
                }

                is ListItem.SnippetItem -> {
                    val iconLabel = JLabel(Icons.codeSpan)
                    iconLabel.border = EmptyBorder(0, 0, 0, 6)
                    panel.add(iconLabel, BorderLayout.WEST)

                    val textPanel = JPanel(BorderLayout())
                    textPanel.isOpaque = false

                    val nameLabel = JLabel(value.snippet.name)
                    if (isSelected) nameLabel.foreground = UIManager.getColor("List.selectionForeground")
                    textPanel.add(nameLabel, BorderLayout.NORTH)

                    val previewText = value.snippet.snippet.replace("\n", " ").replace("\r", "")
                        .take(40) + if (value.snippet.snippet.length > 40) "..." else ""
                    val previewLabel = JLabel(previewText)
                    previewLabel.foreground = UIManager.getColor("TextField.placeholderForeground")
                    previewLabel.font = list.font.deriveFont(Font.PLAIN, (list.font.size.toFloat()) - 2f)
                    textPanel.add(previewLabel, BorderLayout.CENTER)

                    panel.add(textPanel, BorderLayout.CENTER)
                }
            }

            return panel
        }
    }
}
