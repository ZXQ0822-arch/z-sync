package app.zsync.gallery

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

class TransferFiles(private val root: File) {
    fun append(identity: TransferIdentity, bytes: ByteArray): Long {
        if (bytes.isEmpty()) return length(identity)
        root.mkdirs()
        val existing = readMeta(identity)
        if (existing != null && existing.expected != identity.bytes) discard(identity)
        val data = dataFile(identity)
        val safeStart = rememberedLength(identity)
        RandomAccessFile(data, "rw").use { file ->
            if (file.length() != safeStart) file.setLength(safeStart)
            file.seek(safeStart)
            file.write(bytes)
            file.fd.sync()
        }
        val safe = data.length()
        writeMeta(identity, safe)
        return safe
    }

    fun length(identity: TransferIdentity): Long = rememberedLength(identity)

    fun remembered(identity: TransferIdentity): ByteArray? {
        val meta = readMeta(identity) ?: return null
        val data = dataFile(identity)
        if (!data.exists()) return ByteArray(0)
        RandomAccessFile(data, "rw").use { file ->
            if (file.length() != meta.safe) file.setLength(meta.safe)
        }
        if (meta.safe == 0L) return ByteArray(0)
        return data.readBytes()
    }

    fun publishable(identity: TransferIdentity): Boolean {
        val meta = readMeta(identity) ?: return false
        return identity.bytes > 0 && meta.safe == identity.bytes && dataFile(identity).length() == identity.bytes
    }

    fun source(identity: TransferIdentity): File? {
        if (!publishable(identity)) return null
        return dataFile(identity)
    }

    fun discard(identity: TransferIdentity) {
        dataFile(identity).delete()
        metaFile(identity).delete()
    }

    fun discardIncomplete() {
        val metas = root.listFiles { file -> file.isFile && file.name.endsWith(".meta") }.orEmpty()
        metas.forEach { meta ->
            val stored = parse(meta.readText(Charsets.UTF_8)) ?: return@forEach
            if (stored.safe != stored.expected) {
                File(root, meta.name.removeSuffix(".meta") + ".part").delete()
                meta.delete()
            }
        }
    }

    private fun rememberedLength(identity: TransferIdentity): Long {
        val meta = readMeta(identity) ?: return 0
        val data = dataFile(identity)
        if (!data.isFile) return 0
        if (data.length() != meta.safe) {
            RandomAccessFile(data, "rw").use { it.setLength(meta.safe.coerceAtMost(data.length())) }
        }
        return meta.safe.coerceAtMost(data.length())
    }

    private fun writeMeta(identity: TransferIdentity, safe: Long) {
        val target = metaFile(identity)
        val tmp = File(root, target.name + ".tmp")
        val text = listOf(
            identity.bytes.toString(),
            safe.toString(),
            identity.capturedAt.orEmpty(),
            identity.name,
        ).joinToString("\n")
        FileOutputStream(tmp).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not store transfer offset" }
        }
    }

    private fun readMeta(identity: TransferIdentity): StoredMeta? {
        val meta = metaFile(identity)
        if (!meta.isFile) return null
        return parse(meta.readText(Charsets.UTF_8))
    }

    private fun dataFile(identity: TransferIdentity) = File(root, slug(identity) + ".part")

    private fun metaFile(identity: TransferIdentity) = File(root, slug(identity) + ".meta")

    private fun slug(identity: TransferIdentity): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(identityKey(identity).toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { byte -> "%02x".format(byte) }.take(32)
    }
}

private data class StoredMeta(val expected: Long, val safe: Long)

private fun parse(text: String): StoredMeta? {
    val lines = text.split('\n')
    if (lines.size < 2) return null
    val expected = lines[0].toLongOrNull() ?: return null
    val safe = lines[1].toLongOrNull() ?: return null
    if (expected < 0 || safe < 0 || safe > expected) return null
    return StoredMeta(expected, safe)
}
