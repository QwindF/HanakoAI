package `fun`.kirari.hanako.runtime

import `fun`.kirari.hanako.data.AppSettings
import `fun`.kirari.hanako.data.ProcessingResult
import `fun`.kirari.hanako.data.ProcessingRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowResultStoreTest {

    @Test
    fun update_accumulatesLiveResultBeforeThrottledPersistence() = runTest {
        val repository = InMemoryWorkflowHistoryRepository()
        val store = WorkflowResultStore(
            repository = repository,
            scope = TestScope(testScheduler),
            persistDelayMillis = 250L
        )
        val base = result(id = "history-1")

        store.upsert(base)
        assertEquals("", repository.settings.history.single().answer)

        store.update(base.id) { it.copy(answer = it.answer + "A") }
        store.update(base.id) { it.copy(answer = it.answer + "B") }

        assertEquals("AB", store.liveResults.value.getValue(base.id).answer)
        assertEquals("", repository.settings.history.single().answer)

        advanceTimeBy(251)

        assertEquals("AB", repository.settings.history.single().answer)
    }

    @Test
    fun latest_prefersLiveResultOverPersistedHistory() = runTest {
        val repository = InMemoryWorkflowHistoryRepository(
            AppSettings(history = listOf(result(id = "history-1", answer = "persisted")))
        )
        val store = WorkflowResultStore(
            repository = repository,
            scope = TestScope(testScheduler),
            persistDelayMillis = 250L
        )

        store.upsert(result(id = "history-1", answer = "live"))

        assertEquals("live", store.latest("history-1")?.answer)
    }

    @Test
    fun remove_cancelsPendingPersistAndPreventsDeletedResultResurrection() = runTest {
        val repository = InMemoryWorkflowHistoryRepository()
        val store = WorkflowResultStore(
            repository = repository,
            scope = TestScope(testScheduler),
            persistDelayMillis = 250L
        )
        val base = result(id = "history-1")

        store.upsert(base)
        store.update(base.id) { it.copy(answer = "streamed") }
        store.remove(base.id)
        repository.update { it.copy(history = emptyList(), lastResult = null) }
        store.upsert(base.copy(answer = "late final"))
        advanceTimeBy(251)

        assertNull(store.liveResults.value[base.id])
        assertEquals(emptyList<ProcessingResult>(), repository.settings.history)
        assertNull(repository.settings.lastResult)
    }

    @Test
    fun clear_cancelsAllPendingPersistsAndClearsLiveResults() = runTest {
        val repository = InMemoryWorkflowHistoryRepository()
        val store = WorkflowResultStore(
            repository = repository,
            scope = TestScope(testScheduler),
            persistDelayMillis = 250L
        )
        val first = result(id = "history-1")
        val second = result(id = "history-2")

        store.upsert(first)
        store.upsert(second)
        store.update(first.id) { it.copy(answer = "first") }
        store.update(second.id) { it.copy(answer = "second") }
        store.clear()
        repository.update { it.copy(history = emptyList(), lastResult = null) }
        store.upsert(first.copy(answer = "late first"))
        store.upsert(second.copy(answer = "late second"))
        advanceTimeBy(251)

        assertEquals(emptyMap<String, ProcessingResult>(), store.liveResults.value)
        assertEquals(emptyList<ProcessingResult>(), repository.settings.history)
        assertNull(repository.settings.lastResult)
    }

    @Test
    fun mergedWith_includesLiveOnlyResultsAndLetsLiveOverridePersisted() = runTest {
        val repository = InMemoryWorkflowHistoryRepository()
        val store = WorkflowResultStore(
            repository = repository,
            scope = TestScope(testScheduler),
            persistDelayMillis = 250L
        )
        val persisted = listOf(
            result(id = "persisted", answer = "old"),
            result(id = "other", answer = "other")
        )

        store.upsert(result(id = "persisted", answer = "live"))
        store.upsert(result(id = "live-only", answer = "new"))

        val merged = store.mergedWith(persisted)

        assertEquals(listOf("live-only", "persisted", "other"), merged.map { it.id })
        assertEquals("live", merged.first { it.id == "persisted" }.answer)
    }

    private fun result(
        id: String,
        answer: String = ""
    ): ProcessingResult {
        return ProcessingResult(
            id = id,
            assistantName = "assistant",
            route = ProcessingRoute.OCR_THEN_LLM,
            answer = answer
        )
    }
}

private class InMemoryWorkflowHistoryRepository(
    initialSettings: AppSettings = AppSettings()
) : WorkflowHistoryRepository {
    var settings: AppSettings = initialSettings
        private set

    override suspend fun read(): AppSettings = settings

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
    }
}
