package com.smartcloset

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartcloset.data.OutfitReminderSettings
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class DailyOutfitReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "데일리 코디 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle("오늘의 코디를 확인해 보세요")
            .setContentText("등록된 옷으로 오늘 입을 조합을 살펴보세요.")
            .setAutoCancel(true).build()
        runCatching { manager.notify(REMINDER_ID, notification) }
        return Result.success()
    }

    companion object { const val CHANNEL_ID = "daily_outfit_reminder"; const val REMINDER_ID = 4103 }
}

object DailyOutfitReminderScheduler {
    private const val UNIQUE_NAME = "daily-outfit-reminder"
    fun apply(context: Context, settings: OutfitReminderSettings) {
        val manager = WorkManager.getInstance(context)
        if (!settings.enabled) { manager.cancelUniqueWork(UNIQUE_NAME); return }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(settings.hour, settings.minute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val request = PeriodicWorkRequestBuilder<DailyOutfitReminderWorker>(24, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        manager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
