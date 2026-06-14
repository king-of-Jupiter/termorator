package app.termora

import com.formdev.flatlaf.FlatLaf
import org.apache.commons.lang3.RandomUtils
import java.awt.*
import javax.swing.JComponent
import javax.swing.UIManager

class BannerPanel(fontSize: Int = 11, val beautiful: Boolean = false) : JComponent() {
    private val banner = """
  ______                                    
 /_  __/__  _________ ___  ____  _________ _
  / / / _ \/ ___/ __ `__ \/ __ \/ ___/ __ `/
 / / /  __/ /  / / / / / / /_/ / /  / /_/ / 
/_/  \___/_/  /_/ /_/ /_/\____/_/   \__,_/  
""".trimIndent().lines()

    init {
        font = Font("JetBrains Mono", Font.PLAIN, fontSize)
        preferredSize = Dimension(width, getFontMetrics(font).height * banner.size)
        size = preferredSize
    }

    public override fun paintComponent(g: Graphics) {
        if (g !is Graphics2D) return
        g.setRenderingHints(
            RenderingHints(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
        )

        g.font = font

        val fm = g.fontMetrics
        val height = fm.height
        val descent = fm.descent
        val offset = width / 2 - fm.stringWidth(banner.maxBy { it.length }) / 2

        // Apple-style gradient: accent to muted
        val accent = UIManager.getColor("Component.accentColor")
            ?: UIManager.getColor("List.selectionBackground")
            ?: Color(100, 149, 237)
        val isDark = FlatLaf.isLafDark()

        val totalChars = banner.sumOf { it.length }
        var charIndex = 0

        for (i in banner.indices) {
            var x = offset
            val y = height * (i + 1) - descent
            val chars = banner[i].toCharArray()
            for (j in chars.indices) {
                if (beautiful) {
                    val t = charIndex.toFloat() / totalChars.coerceAtLeast(1)
                    val r = (accent.red + (180 - accent.red) * t).toInt().coerceIn(0, 255)
                    val gr = (accent.green + (180 - accent.green) * t).toInt().coerceIn(0, 255)
                    val b = (accent.blue + (180 - accent.blue) * t).toInt().coerceIn(0, 255)
                    g.color = if (isDark) Color(r, gr, b).brighter() else Color(r, gr, b).darker()
                    charIndex++
                } else {
                    g.color = foreground ?: UIManager.getColor("TextField.placeholderForeground")
                }
                g.drawChars(chars, j, 1, x, y)
                x += fm.charWidth(chars[j])
            }
        }
    }
}