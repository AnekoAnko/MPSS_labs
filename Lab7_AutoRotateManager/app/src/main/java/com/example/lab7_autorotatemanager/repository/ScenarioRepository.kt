package com.example.lab7_autorotatemanager.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * ЛР №7: репозиторій Smart-сценаріїв. Правила зберігаються в Room, тому переживають
 * перезапуск додатка. ViewModel працює лише з ScenarioRule, про ScenarioRecord не знає.
 */
class ScenarioRepository(
    private val dao: ScenarioDao,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    val rules: Flow<List<ScenarioRule>> = dao.observeAll()
        .map { list -> list.mapNotNull { it.toRule() } }
        .flowOn(io)

    /** Перший запуск: таблиця порожня → додаємо приклади правил. */
    suspend fun ensureDefaults() = withContext(io) {
        if (dao.count() == 0) ScenarioRule.DEFAULTS.forEach { dao.upsert(it.toRecord()) }
    }

    suspend fun save(rule: ScenarioRule): Long = withContext(io) { dao.upsert(rule.toRecord()) }

    suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(io) { dao.setEnabled(id, enabled) }

    suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }
}
