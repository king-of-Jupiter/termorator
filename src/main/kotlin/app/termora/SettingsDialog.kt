package app.termora

import app.termora.database.DatabaseManager
import com.formdev.flatlaf.util.UIScale
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.UIManager

internal class SettingsDialog(owner: Window) : DialogWrapper(owner) {
    private val optionsPane = SettingsOptionsPane()
    private val properties get() = DatabaseManager.getInstance().properties

    init {
        val w = UIScale.scale(UIManager.getInt("Dialog.width")).coerceAtLeast(860)
        val h = UIScale.scale(UIManager.getInt("Dialog.height")).coerceAtLeast(580)
        size = Dimension(w, h)
        minimumSize = Dimension(780, 520)
        isModal = true
        title = I18n.getString("termora.setting")
        setLocationRelativeTo(null)

        val index = properties.getString("Settings-SelectedOption")?.toIntOrNull() ?: 0
        optionsPane.setSelectedIndex(index)

        Disposer.register(disposable, optionsPane)

        init()
        initEvents()
    }

    private fun initEvents() {
        Disposer.register(disposable, object : Disposable {
            override fun dispose() {
                properties.putString("Settings-SelectedOption", optionsPane.getSelectedIndex().toString())
            }
        })

        Disposer.register(disposable, optionsPane)

    }

    override fun createCenterPanel(): JComponent {
        optionsPane.background = UIManager.getColor("window")

        val panel = JPanel(BorderLayout())
        panel.add(optionsPane, BorderLayout.CENTER)
        panel.background = UIManager.getColor("window")
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, DynamicColor.BorderColor),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        )

        return panel
    }

    override fun createSouthPanel(): JComponent? {
        return null
    }


}