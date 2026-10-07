package com.example.scan3d

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object Gl {
    fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a); position(0)
        }

    const val POINT_VS = """
        uniform mat4 u_MVP;
        uniform float u_Size;
        attribute vec3 a_Pos;
        attribute vec3 a_Color;
        varying vec3 v_Color;
        void main() {
            gl_Position = u_MVP * vec4(a_Pos, 1.0);
            gl_PointSize = u_Size;
            v_Color = a_Color;
        }"""
    const val POINT_FS = """
        precision mediump float;
        varying vec3 v_Color;
        void main() { gl_FragColor = vec4(v_Color, 1.0); }"""
}

/** VBO с интерливом x,y,z,r,g,b (float). */
class PointVbo {
    companion object { const val STRIDE = 24 }

    var id = 0
    var count = 0
    var capacity = 0

    fun create() {
        val a = IntArray(1)
        GLES20.glGenBuffers(1, a, 0)
        id = a[0]; count = 0; capacity = 0
    }

    fun allocate(maxPoints: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, maxPoints * STRIDE, null, GLES20.GL_DYNAMIC_DRAW)
        capacity = maxPoints; count = 0
    }

    fun append(data: FloatArray, n: Int) {
        if (n <= 0 || count + n > capacity) return
        val fb = ByteBuffer.allocateDirect(n * STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data, 0, n * 6); fb.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, count * STRIDE, n * STRIDE, fb)
        count += n
    }

    fun draw(posLoc: Int, colLoc: Int) {
        if (count == 0) return
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(colLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, STRIDE, 0)
        GLES20.glVertexAttribPointer(colLoc, 3, GLES20.GL_FLOAT, false, STRIDE, 12)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(colLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
