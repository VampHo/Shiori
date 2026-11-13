package org.koitharu.kotatsu.novels

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class NovelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val shelf = NovelShelf(applicationContext)
            val book = shelf.cachedDetails(inputData.getString("book").orEmpty()) ?: error("Book unavailable. Open it and try again.")
            val first = book.chapters.indexOfFirst { it.url == inputData.getString("first") }
            val last = book.chapters.indexOfLast { it.url == inputData.getString("last") }
            require(first >= 0 && last >= first) { "Chapter list changed. Select the download range again." }
            withContext(Dispatchers.Main) { NovelHttp.initialize(applicationContext) }
            val repository = NovelCatalogRepository()
            val range = book.chapters.subList(first, last + 1)
            downloadMissing(shelf, book, range, { repository.chapter(it) }, { delay(1000) }) { done ->
                if (done == 1 || done % 5 == 0 || done == range.size)
                    setProgress(workDataOf("done" to done, "total" to range.size))
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: java.io.InterruptedIOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to "Source timed out. Retry the download to continue."))
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to (e.message ?: "Download interrupted").take(200)))
        }
    }

    companion object {
        fun name(url: String) = "novel-download:" + NovelShelf.hash(url)
        internal suspend fun downloadMissing(shelf: NovelShelf, book: NovelDetails, chapters: List<NovelLink>,
            fetch: suspend (String) -> String, pace: suspend () -> Unit, progress: suspend (Int) -> Unit) {
            for ((index, chapter) in chapters.withIndex()) {
                val missing = !shelf.hasChapter(chapter.url)
                if (missing) shelf.cacheChapter(chapter.url, fetch(chapter.url))
                if (index == 0) synchronized(NovelDownloadWorker::class.java) { shelf.downloaded(book) }
                progress(index + 1)
                if (missing && index < chapters.lastIndex) pace()
            }
        }
    }
}
