package app.termora

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * 自动换行的 [FlowLayout]。
 *
 * 原生 [FlowLayout] 在放入 [JScrollPane] 时无法正确计算换行后的首选高度，
 * 这里重写 [preferredLayoutSize] 以根据父容器的目标宽度计算实际所需的尺寸，
 * 使得卡片在视口宽度变化时能够正确换行并撑开滚动区域的高度。
 *
 * 参考 Rob Camick 的 WrapLayout 实现。
 */
class WrapLayout : FlowLayout {

    constructor() : super()
    constructor(align: Int) : super(align)
    constructor(align: Int, hgap: Int, vgap: Int) : super(align, hgap, vgap)

    override fun preferredLayoutSize(target: Container): Dimension {
        return layoutSize(target, true)
    }

    override fun minimumLayoutSize(target: Container): Dimension {
        val minimum = layoutSize(target, false)
        minimum.width -= (hgap + 1)
        return minimum
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            // 目标宽度：优先使用最近的滚动视口宽度，其次是容器自身宽度
            var targetWidth = target.size.width
            if (targetWidth == 0) {
                targetWidth = Int.MAX_VALUE
            }

            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) as? JScrollPane
            if (scrollPane != null) {
                val viewportWidth = scrollPane.viewport.width
                if (viewportWidth > 0) {
                    targetWidth = viewportWidth
                }
            }

            val hgap = hgap
            val vgap = vgap
            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2)
            val maxWidth = targetWidth - horizontalInsetsAndGap

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            val componentCount = target.componentCount
            for (i in 0 until componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue

                val d = if (preferred) m.preferredSize else m.minimumSize

                // 当前行放不下，换行
                if (rowWidth + d.width > maxWidth && rowWidth != 0) {
                    addRow(dim, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }

                if (rowWidth != 0) {
                    rowWidth += hgap
                }

                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }

            addRow(dim, rowWidth, rowHeight)

            dim.width += horizontalInsetsAndGap
            dim.height += insets.top + insets.bottom + vgap * 2

            // 在滚动视口里时，去掉额外的 hgap，避免视口反复出现水平滚动条
            if (scrollPane != null) {
                dim.width -= (hgap + 1)
            }

            return dim
        }
    }

    private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) {
            dim.height += vgap
        }
        dim.height += rowHeight
    }
}
