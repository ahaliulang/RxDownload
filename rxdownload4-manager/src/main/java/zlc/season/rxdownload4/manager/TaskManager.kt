package zlc.season.rxdownload4.manager

import android.annotation.SuppressLint
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.subscribers.DisposableSubscriber
import zlc.season.rxdownload4.Progress
import zlc.season.rxdownload4.delete
import zlc.season.rxdownload4.file
import zlc.season.rxdownload4.manager.notification.NotificationCreator
import zlc.season.rxdownload4.manager.notification.notificationManager
import zlc.season.rxdownload4.storage.Storage
import zlc.season.rxdownload4.task.Task
import zlc.season.rxdownload4.utils.log
import zlc.season.rxdownload4.utils.safeDispose
import java.util.concurrent.TimeUnit.MILLISECONDS

class TaskManager(
        private val task: Task,
        private val storage: Storage,

        private val flowable: Flowable<Progress>,
        private val notificationCreator: NotificationCreator
) {

    init {
        notificationCreator.init(task)
    }

    private val connectFlowable = flowable.publish()

    private val downloadHandler = StatusHandler(task, true)
    private val notificationHandler = StatusHandler(task) {
        val notification = notificationCreator.create(task, it)
        notification?.let {
            notificationManager.notify(task.hashCode(), it)
        }
    }

    //Download disposable
    private var disposable: Disposable? = null
    private var downloadDisposable: Disposable? = null
    private var notificationDisposable: Disposable? = null

    fun setCallback(callback: (Status) -> Unit = {}) {
        downloadHandler.callback = callback
    }

    internal fun currentStatus() = downloadHandler.currentStatus

    internal fun getFile() = task.file(storage)

    internal fun innerDelete() = task.delete(storage)

    @SuppressLint("CheckResult")
    @Synchronized
    internal fun innerStart() {
        if (isStarted()) return

        notificationDisposable = connectFlowable.sample(250, MILLISECONDS)
                .subscribeWith(object : DisposableSubscriber<Progress>() {
                    override fun onStart() {
                        super.onStart()
                        notificationHandler.onStart()
                    }

                    override fun onComplete() {
                        notificationHandler.onComplete()
                    }

                    override fun onNext(t: Progress) {
                        notificationHandler.onNext(t)
                    }

                    override fun onError(t: Throwable) {
                        notificationHandler.onError(t)
                    }
                })

        downloadDisposable = connectFlowable
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeWith(object : DisposableSubscriber<Progress>() {
                    override fun onStart() {
                        super.onStart()
                        downloadHandler.onStart()
                    }

                    override fun onComplete() {
                        downloadHandler.onComplete()
                    }

                    override fun onNext(t: Progress) {
                        downloadHandler.onNext(t)
                    }

                    override fun onError(t: Throwable) {
                        downloadHandler.onError(t)
                    }
                })

        disposable = connectFlowable.connect()
    }

    @Synchronized
    internal fun innerStop() {
        if (isStopped()) return

        notificationDisposable.safeDispose()
        downloadDisposable.safeDispose()
        disposable.safeDispose()

        downloadHandler.onPaused()
        notificationHandler.onPaused()
    }

    private fun isStarted(): Boolean {
        return disposable != null && !disposable!!.isDisposed
    }

    private fun isStopped(): Boolean {
        return disposable != null && disposable!!.isDisposed
    }

    class StatusHandler(
            private val task: Task,
            private val enableLog: Boolean = false,
            var callback: (Status) -> Unit = {}
    ) {

        var currentStatus: Status = Normal()
        var currentProgress: Progress = Progress()

        private val normal = Normal()
        private val started = Started()
        private val downloading = Downloading()
        private val paused = Paused()
        private val completed = Completed()
        private val failed = Failed()

        fun onNormal() {
            currentStatus = normal.apply { progress = currentProgress }
            callback(currentStatus)

            if (enableLog) "[${task.tag()}] normal".log()
        }

        fun onStart() {
            currentStatus = started.apply { progress = currentProgress }
            callback(currentStatus)

            if (enableLog) "[${task.tag()}] started".log()
        }

        fun onNext(next: Progress) {
            currentProgress = next
            currentStatus = downloading.apply { progress = currentProgress }
            callback(currentStatus)

            if (enableLog) "[${task.tag()}] downloading ${next.percentStr()}".log()
        }

        fun onComplete() {
            currentStatus = completed.apply { progress = currentProgress }
            callback(currentStatus)

            if (enableLog) "[${task.tag()}] completed".log()
        }

        fun onError(t: Throwable) {
            currentStatus = failed.apply { progress = currentProgress }
            callback(currentStatus)

            if (enableLog) "[${task.tag()}] failed".log()
        }

        fun onPaused() {
            currentStatus = paused.apply { progress = currentProgress }
            callback(currentStatus)

            if (enableLog) "[${task.tag()}] paused".log()
        }
    }
}