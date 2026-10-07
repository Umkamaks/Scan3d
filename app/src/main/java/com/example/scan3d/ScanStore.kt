package com.example.scan3d

import android.content.Context
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class ScanInfo(val id: String, val name: String, val points: Int, val time: Long)

object ScanStore {
    private fun root(ctx: Context) = File(ctx.filesDir, "scans").apply { mkdirs() }

    fun list(ctx: Context): List<ScanInfo> =
        root(ctx).listFiles()?.mapNotNull { info(ctx, it.name) }
            ?.sortedByDescending { it.time } ?: emptyList()

    fun info(ctx: Context, id: String): ScanInfo? {
        val f = File(File(root(ctx), id), "meta.txt")
        if (!f.exists()) return null
        val l = f.readLines()
        if (l.size < 3) return null
        return ScanInfo(id, l[0], l[1].toIntOrNull() ?: 0, l[2].toLongOrNull() ?: 0L)
    }

    fun save(ctx: Context, cloud: PointCloud, name: String): ScanInfo {
        val id = System.currentTimeMillis().toString()
        val d = File(root(ctx), id).apply { mkdirs() }
        val n = cloud.count
        DataOutputStream(BufferedOutputStream(FileOutputStream(File(d, "cloud.bin")))).use { out ->
            out.writeInt(n)
            val bb = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(cloud.pos, 0, n * 3)
            out.write(bb.array())
            out.write(cloud.col, 0, n * 3)
        }
        val t = System.currentTimeMillis()
        File(d, "meta.txt").writeText("$name\n$n\n$t")
        return ScanInfo(id, name, n, t)
    }

    fun load(ctx: Context, id: String): PointCloud {
        DataInputStream(BufferedInputStream(FileInputStream(File(File(root(ctx), id), "cloud.bin")))).use { inp ->
            val n = inp.readInt()
            val raw = ByteArray(n * 12)
            inp.readFully(raw)
            val pos = FloatArray(n * 3)
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pos)
            val col = ByteArray(n * 3)
            inp.readFully(col)
            return PointCloud().apply { setData(pos, col, n) }
        }
    }

    fun delete(ctx: Context, id: String) {
        File(root(ctx), id).deleteRecursively()
    }

    /** format: "ply" или "obj". Возвращает файл в cache/export для шаринга. */
    fun export(ctx: Context, id: String, format: String): File {
        val c = load(ctx, id)
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "scan_$id.$format")
        if (format == "ply") writePly(f, c) else writeObj(f, c)
        return f
    }

    private fun writePly(f: File, c: PointCloud) {
        val n = c.count
        BufferedOutputStream(FileOutputStream(f)).use { out ->
            val header = "ply\nformat binary_little_endian 1.0\nelement vertex $n\n" +
                "property float x\nproperty float y\nproperty float z\n" +
                "property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            val chunk = 2000
            val bb = ByteBuffer.allocate(chunk * 15).order(ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < n) {
                bb.clear()
                val end = minOf(n, i + chunk)
                for (k in i until end) {
                    bb.putFloat(c.pos[k * 3]).putFloat(c.pos[k * 3 + 1]).putFloat(c.pos[k * 3 + 2])
                    bb.put(c.col[k * 3]).put(c.col[k * 3 + 1]).put(c.col[k * 3 + 2])
                }
                out.write(bb.array(), 0, (end - i) * 15)
                i = end
            }
        }
    }

    /** OBJ с цветом вершин (v x y z r g b) — открывается в MeshLab, Blender, CloudCompare. */
    private fun writeObj(f: File, c: PointCloud) {
        BufferedWriter(FileWriter(f)).use { w ->
            w.write("# Scan3D point cloud\n")
            for (k in 0 until c.count) {
                w.write(
                    String.format(
                        Locale.US, "v %.4f %.4f %.4f %.3f %.3f %.3f\n",
                        c.pos[k * 3], c.pos[k * 3 + 1], c.pos[k * 3 + 2],
                        (c.col[k * 3].toInt() and 0xFF) / 255f,
                        (c.col[k * 3 + 1].toInt() and 0xFF) / 255f,
                        (c.col[k * 3 + 2].toInt() and 0xFF) / 255f
                    )
                )
            }
        }
    }
}
