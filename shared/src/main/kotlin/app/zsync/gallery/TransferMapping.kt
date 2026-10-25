package app.zsync.gallery

import java.time.format.DateTimeFormatter

private val captureLabel = DateTimeFormatter.ISO_LOCAL_DATE_TIME

fun CameraFile.syncTransfer(): TransferRequest {
    val media = when (format) {
        PhotoFormat.JPEG -> TransferFormat.JPEG
        PhotoFormat.NEF -> TransferFormat.NEF
        PhotoFormat.MOV -> TransferFormat.MOV
        PhotoFormat.MP4 -> TransferFormat.MP4
    }
    return TransferRequest(
        identity = TransferIdentity(
            name = name,
            bytes = bytes,
            capturedAt = capturedLocalAt?.format(captureLabel),
        ),
        format = media,
        kind = TransferKind.Sync,
        handle = handle,
    )
}

fun syncTransfers(files: List<CameraFile>): List<TransferRequest> = files.map { it.syncTransfer() }

fun beginSync(files: List<CameraFile>, selecting: Boolean = true): QueueTransition {
    return reduce(
        TransferQueue(selecting = selecting),
        QueueEvent.BeginSync(syncTransfers(files)),
    )
}

fun TransferIdentity.sameFileAs(file: CameraFile): Boolean {
    return identityKey(this) == identityKey(file.syncTransfer().identity)
}

fun handleFor(queue: TransferQueue, identity: TransferIdentity): Long? {
    return queue.items.firstOrNull { identityKey(it.identity) == identityKey(identity) }?.handle
}
