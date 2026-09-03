package cn.coolgk.rimesyncapp.core

/**
 * core 层日志钩子：默认空实现（JVM 测试无副作用），
 * Android 端在 [cn.coolgk.rimesyncapp.MainActivity] 中挂接到 LogBuffer。
 */
object CoreLog {
    @Volatile
    var sink: ((level: String, message: String) -> Unit)? = null

    fun info(message: String) = sink?.invoke("INFO", message)
    fun warn(message: String) = sink?.invoke("WARN", message)
    fun error(message: String) = sink?.invoke("ERROR", message)
}