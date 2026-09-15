package com.vstokke.memos.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vstokke.memos.MemosPocketApp
import com.vstokke.memos.domain.AppError
import com.vstokke.memos.domain.AppException
import kotlinx.coroutines.CancellationException

class MemoSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MemosPocketApp
        if (app.container.repository.account() == null) return Result.success()
        return try {
            val result = app.container.syncCoordinator.runWorkerTurn()
            if (result.moreWork) Result.retry() else Result.success()
        } catch (error: AppException) {
            when (error.error) {
                AppError.Network,
                AppError.ResourceAuthentication,
                AppError.CredentialPersistence,
                -> Result.retry()
                is AppError.Server -> if (error.error.status == 429 || error.error.status >= 500) {
                    Result.retry()
                } else {
                    Result.failure()
                }
                // Authentication is a durable paused state. Retrying it would create a hot loop
                // while the UI is waiting for the user to sign in again.
                AppError.Authentication,
                AppError.SessionRefreshRejected,
                AppError.Conflict,
                AppError.Permission,
                AppError.InvalidCredentials,
                AppError.SignInFailed,
                AppError.UnsupportedReminder,
                AppError.PendingAccount,
                AppError.InvalidResponse,
                -> Result.success()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.failure()
        }
    }
}
