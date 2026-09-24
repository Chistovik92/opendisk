package com.opendisk.android.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opendisk.android.LibrcloneTransport
import com.opendisk.bridge.RcloneClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Досылает в облако то, что сохранили без сети.
 *
 * Сохранённый в облако файл сначала ложится в очередь на телефоне и уходит,
 * когда приложение его закрывает. Не ушёл — до 0.5.11 он ждал, пока облако
 * снова откроют в «Файлах»: не откроют — файл так и лежал на телефоне,
 * а человек считал его сохранённым.
 *
 * Теперь отправку доделывает WorkManager: система запускает задачу, когда
 * появляется сеть, и сама повторяет с нарастающей паузой, если облако
 * опять не ответило. Значок в шторке для этого не нужен — работу в фоне
 * выдаёт система, в своё окно.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val files = CloudFiles(applicationContext)
        if (files.pendingUploads().isEmpty()) return@withContext Result.success()

        LibrcloneTransport.useConfig(File(applicationContext.filesDir, "rclone.conf"))
        val client = RcloneClient(LibrcloneTransport.get())
        if (files.uploadAll(client)) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "opendisk-uploads"

        /**
         * Ставит отправку очереди в план системы. Повторный вызов ничего не
         * дублирует: задача одна, и она отправляет очередь целиком.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
