package cn.coolgk.rimesyncapp.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * 后台定时同步 Worker：增量上传本地用户词库 + 下载其他设备变更。
 * 以普通后台任务运行（WorkManager 周期调度），不启用前台服务，
 * 避免后台启动前台服务受系统限制导致进程崩溃。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val repo = SyncRepository(applicationContext)
            val store = repo.getRimeStore()
                ?: run {
                    logWarn("后台同步跳过：未选择 Rime 配置目录")
                    return Result.failure()
                }
            val device = repo.resolveDeviceName()
            if (device == "unknown") {
                logWarn("后台同步跳过：无法确定设备名")
                return Result.failure()
            }
            logInfo("后台自动同步开始（设备: $device）...")
            val upload = repo.syncUserdbUpload()
            logInfo("后台同步上传完成: $upload")
            val download = repo.syncUserdbDownload()
            logInfo("后台同步下载完成: $download")
            logInfo("后台自动同步完成")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError("后台同步失败: ${e.message}")
            Result.failure()
        }
    }
}

/** 后台定时同步调度器。 */
object SyncScheduler {

    const val UNIQUE_WORK_NAME = "rime_sync_periodic"

    /** 按配置启用/停用周期同步。 */
    fun apply(context: Context, enabled: Boolean, intervalHours: Int) {
        if (!enabled) {
            cancel(context)
            return
        }
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()
        val request = androidx.work.PeriodicWorkRequestBuilder<SyncWorker>(
            intervalHours.toLong().coerceAtLeast(1L), TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun cancel(context: Context) {
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}