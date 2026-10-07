package com.example.scan3d

/** Цветное облако точек с прореживанием по вокселям (1 см). */
class PointCloud(private val voxel: Float = 0.01f) {
    companion object { const val MAX = 1_000_000 }

    var count = 0
        private set
    var pos = FloatArray(3 * 50_000)
        private set
    var col = ByteArray(3 * 50_000)
        private set
    private val seen = HashSet<Long>()

    fun add(x: Float, y: Float, z: Float, r: Int, g: Int, b: Int): Boolean {
        if (count >= MAX) return false
        if (!seen.add(key(x, y, z))) return false
        if ((count + 1) * 3 > pos.size) {
            val n = pos.size * 2
            pos = pos.copyOf(n)
            col = col.copyOf(n)
        }
        val i = count * 3
        pos[i] = x; pos[i + 1] = y; pos[i + 2] = z
        col[i] = r.toByte(); col[i + 1] = g.toByte(); col[i + 2] = b.toByte()
        count++
        return true
    }

    fun setData(p: FloatArray, c: ByteArray, n: Int) {
        pos = p; col = c; count = n
    }

    private fun key(x: Float, y: Float, z: Float): Long {
        val ix = Math.floor((x / voxel).toDouble()).toLong() and 0x1FFFFF
        val iy = Math.floor((y / voxel).toDouble()).toLong() and 0x1FFFFF
        val iz = Math.floor((z / voxel).toDouble()).toLong() and 0x1FFFFF
        return (ix shl 42) or (iy shl 21) or iz
    }
}
