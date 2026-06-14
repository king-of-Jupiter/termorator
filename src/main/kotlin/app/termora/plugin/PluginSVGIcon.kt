package app.termora.plugin

import app.termora.Icons
import java.io.InputStream
import javax.swing.Icon

/**
 * Simple icon holder for plugin icons.
 * Since we removed the marketplace, we just use a default icon.
 */
object PluginSVGIcon {
    fun create(inputStream: InputStream): Icon {
        return Icons.plugin
    }

    fun create(inputStream: InputStream, darkInputStream: InputStream): Icon {
        return Icons.plugin
    }
}
