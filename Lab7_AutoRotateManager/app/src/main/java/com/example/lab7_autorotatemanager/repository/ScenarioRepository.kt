package com.example.lab7_autorotatemanager.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Stores smart rules in Room so they survive restarts. ViewModels see only ScenarioRule. */
class ScenarioRepository(
    private val dao: ScenarioDao,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    val rules: Flow<List<ScenarioRule>> = dao.observeAll()
        .map { list -> list.mapNotNull { it.toRule() } }
        .flowOn(io)

    /** First launch: insert example rules into the empty table. */
    suspend fun ensureDefaults() = withContext(io) {
        if (dao.count() == 0) ScenarioRule.DEFAULTS.forEach { dao.upsert(it.toRecord()) }
    }

    suspend fun save(rule: ScenarioRule): Long = withContext(io) { dao.upsert(rule.toRecord()) }

    suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(io) { dao.setEnabled(id, enabled) }

    suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }
}
