package com.magichandy.mobile.platform.storage

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One process-owned file. Sync data before replacement and the containing directory afterwards. */
internal class AtomicBytes(
    private val target: File,
    private val syncDirectory: (File) -> Unit = ::syncParent,
) {
    fun read(limit: Int): ByteArray? {
        return try {
            Files.newInputStream(target.toPath()).use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (bytes.size() + n > limit) throw IOException("Invalid record size")
                    bytes.write(buffer, 0, n)
                }
                bytes.toByteArray()
            }
        } catch (_: java.nio.file.NoSuchFileException) {
            null
        }
    }

    fun replace(bytes: ByteArray) {
        val directory = requireNotNull(target.parentFile)
        if (!directory.isDirectory && !directory.mkdirs())
            throw IOException("Storage directory unavailable")
        val temporary = File.createTempFile(".replace-", null, directory)
        try {
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(directory)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    fun remove() {
        if (Files.deleteIfExists(target.toPath())) syncDirectory(requireNotNull(target.parentFile))
    }
}

private fun syncParent(dir: File) {
    try {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    } catch (e: android.system.ErrnoException) {
        throw IOException("Directory sync failed", e)
    }
}
