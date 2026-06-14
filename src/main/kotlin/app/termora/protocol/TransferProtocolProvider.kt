package app.termora.protocol

import app.termora.protocol.ProtocolProvider.Companion.providers
import org.apache.commons.lang3.StringUtils

interface TransferProtocolProvider : ProtocolProvider {

    companion object {
        fun valueOf(protocol: String): TransferProtocolProvider? {
            return providers.filterIsInstance<TransferProtocolProvider>()
                .firstOrNull { StringUtils.equalsIgnoreCase(it.getProtocol(), protocol) }
        }
    }

    /**
     * 创建一个文件
     */
    fun createPathHandler(requester: PathHandlerRequest): PathHandler
}
