package it.federicoraponi.frigatebridge.server

import it.federicoraponi.frigatebridge.frigate.FrigateError

/** The download of one file, ready to run on any thread. Cancelling it stops it. */
fun interface ExportDownload {
    suspend fun into(receive: suspend (bytes: ByteArray, count: Int) -> Unit)
}

/** Downloads exports, for whatever the platform does with them. */
class ExportDownloads(
    private val registry: ServerRegistry,
) {
    /** Call it on the main thread, as the registry asks; the result may then run elsewhere. */
    fun prepare(file: ExportedFile): ExportDownload {
        val session = registry.session(file.serverId)
        return ExportDownload { receive ->
            if (session == null) throw FrigateError.Unreachable()
            session.download(file, receive)
        }
    }
}
