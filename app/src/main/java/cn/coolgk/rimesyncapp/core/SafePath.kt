package cn.coolgk.rimesyncapp.core

/**
 * 相对路径安全校验。用于所有来自服务端或配置的相对路径，
 * 拒绝绝对路径、`..` 穿越与空段，防止路径遍历。
 */
object SafePath {

    /** 规范化并校验相对路径，返回以 `/` 分隔的干净相对路径。 */
    fun normalize(relPath: String): String {
        if (relPath.isBlank()) return ""
        if (relPath.startsWith("/")) {
            throw PathTraversalException("拒绝绝对路径: $relPath")
        }
        if (Regex("^[A-Za-z]:").containsMatchIn(relPath)) {
            throw PathTraversalException("拒绝盘符路径: $relPath")
        }
        val segments = relPath.replace('\\', '/').split('/')
        val result = ArrayList<String>(segments.size)
        for (seg in segments) {
            when {
                seg.isEmpty() || seg == "." -> continue
                seg == ".." -> throw PathTraversalException("拒绝路径: 路径遍历攻击 $relPath")
                else -> result.add(seg)
            }
        }
        return result.joinToString("/")
    }

    /** 校验文件名（不含路径分隔符）。 */
    fun validateFileName(name: String) {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
            throw PathTraversalException("拒绝非法文件名: $name")
        }
    }
}