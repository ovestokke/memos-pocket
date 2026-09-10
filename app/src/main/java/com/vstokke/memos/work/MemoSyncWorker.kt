package com.vstokke.memos.work

import android.content.Context
import androidx.work.CoroutineWorker
import kotlinx.coroutines.CancellationException
import androidx.work.WorkerParameters
import com.vstokke.memos.MemosPocketApp
import com.vstokke.memos.domain.AppError
import com.vstokke.memos.domain.AppException

class MemoSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MemosPocketApp
        if (app.container.repository.account() == null) return Result.success()
        return try {
            app.container.repository.refresh()
            Result.success()
        } catch (error: AppException) {
            when (error.error) {
                AppError.Network -> Result.retry()
                is AppError.Server -> if (error.error.status >= 500) Result.retry() else Result.failure()
                else -> Result.failure()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
