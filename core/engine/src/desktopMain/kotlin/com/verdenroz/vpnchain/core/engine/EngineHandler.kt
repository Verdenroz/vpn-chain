package com.verdenroz.vpnchain.core.engine

import com.verdenroz.vpnchain.core.data.ChainRepository
import com.verdenroz.vpnchain.core.data.LogRepository
import com.verdenroz.vpnchain.core.data.ProfileRepository
import com.verdenroz.vpnchain.core.domain.ConnectChainUseCase
import com.verdenroz.vpnchain.core.domain.DisconnectChainUseCase
import com.verdenroz.vpnchain.core.domain.SwitchProfileUseCase
import com.verdenroz.vpnchain.core.model.LogEntry
import com.verdenroz.vpnchain.core.model.SavedProfile
import com.verdenroz.vpnchain.core.model.TunnelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Answers control-socket requests from the same repositories and use cases the GUI drives. */
class EngineHandler(
    private val chain: ChainRepository,
    private val profiles: ProfileRepository,
    private val logs: LogRepository,
    private val connect: ConnectChainUseCase,
    private val disconnect: DisconnectChainUseCase,
    private val switchProfile: SwitchProfileUseCase,
) {

    /** One reply for a plain request; a stream for [Request.Subscribe]. */
    fun handle(request: Request): Flow<Reply> = when (request) {
        is Request.Subscribe -> subscribe(request.topic)
        else -> flow { emit(reply(request)) }
    }

    private suspend fun reply(request: Request): Reply = when (request) {
        is Request.Hello, is Request.Subscribe -> Reply.Failure(FailureCode.BadRequest, "unexpected ${request::class.simpleName}")
        Request.Status -> Reply.Status(status().first())
        is Request.Up -> up(request.profile)
        Request.Down -> {
            disconnect()
            Reply.Ok
        }
        Request.Profiles -> Reply.Profiles(summaries().first())
        is Request.Use -> withProfile(request.profile) { switchProfile(it.id).toReply() }
        is Request.Logs -> {
            val entries = logs.entries.value
            Reply.Logs((request.limit?.let { entries.takeLast(it.coerceAtLeast(0)) } ?: entries).map(LogEntry::toWire))
        }
    }

    private fun subscribe(topic: Topic): Flow<Reply> = when (topic) {
        Topic.Status -> status().distinctUntilChanged().map(Reply::Status)
        Topic.Stats -> chain.stats.map { it.toWire() }.distinctUntilChanged().map(Reply::Stats)
        Topic.Logs -> newLogLines().map(Reply::Logs)
    }

    private suspend fun up(profile: String?): Reply {
        if (profile != null) {
            val switched = withProfile(profile) { switchProfile(it.id).toReply() }
            if (switched != Reply.Ok) return switched
        }
        val state = chain.status.first().state
        if (state == TunnelState.Connected || state == TunnelState.Connecting) return Reply.Ok
        return connect().toReply()
    }

    private suspend fun withProfile(query: String, action: suspend (SavedProfile) -> Reply): Reply {
        val saved = profiles.profiles.first()
        val byId = saved.firstOrNull { it.id == query }
        if (byId != null) return action(byId)
        val byName = saved.filter { it.name.equals(query, ignoreCase = true) }
        return when (byName.size) {
            0 -> Reply.Failure(FailureCode.NotFound, "no saved connection is called \"$query\"")
            1 -> action(byName.single())
            else -> Reply.Failure(FailureCode.Ambiguous, "${byName.size} saved connections are called \"$query\"; use an id")
        }
    }

    private fun summaries(): Flow<List<ProfileSummary>> =
        combine(profiles.profiles, profiles.activeProfileId) { saved, activeId -> saved.map { it.toSummary(activeId) } }

    private fun status(): Flow<EngineStatus> =
        combine(chain.status, summaries()) { status, summaries -> status.toWire(summaries.firstOrNull { it.active }) }

    // Entries are kept by reference across ring updates, so identity finds where the last batch ended.
    private fun newLogLines(): Flow<List<LogLine>> = flow {
        var last: LogEntry? = null
        var first = true
        logs.entries.collect { entries ->
            val start = last?.let { previous -> entries.indexOfLast { it === previous } + 1 } ?: 0
            val batch = entries.drop(start)
            last = entries.lastOrNull()
            if (first || batch.isNotEmpty()) emit(batch.map(LogEntry::toWire))
            first = false
        }
    }

    private fun Result<Unit>.toReply(): Reply = fold(
        onSuccess = { Reply.Ok },
        onFailure = { Reply.Failure(FailureCode.Failed, it.message ?: it::class.simpleName.orEmpty()) },
    )
}
