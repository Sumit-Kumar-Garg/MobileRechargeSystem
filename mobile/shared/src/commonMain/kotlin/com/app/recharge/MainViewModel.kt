package com.app.recharge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.recharge.core.network.NetworkMonitor
import com.app.recharge.core.network.NetworkState
import com.app.recharge.core.telemetry.ConsentStore
import com.app.recharge.feature.auth.AuthRepository
import com.app.recharge.feature.recharge.data.RechargeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SessionState { SIGNING_IN, READY, FAILED }

data class MainUiState(
    val session: SessionState = SessionState.SIGNING_IN,
    val showConsent: Boolean = false,
    val network: NetworkState = NetworkState(),
)

class MainViewModel(
    private val auth: AuthRepository,
    private val consent: ConsentStore,
    private val repository: RechargeRepository,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val session = MutableStateFlow(SessionState.SIGNING_IN)

    val state: StateFlow<MainUiState> =
        combine(session, consent.snapshot, networkMonitor.state) { s, c, n ->
            MainUiState(session = s, showConsent = s == SessionState.READY && !c.decided, network = n)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        networkMonitor.start()
        signIn()
    }

    fun signIn() {
        session.value = SessionState.SIGNING_IN
        viewModelScope.launch {
            session.value = if (auth.ensureLoggedIn()) SessionState.READY else SessionState.FAILED
        }
    }

    /** Stores the choice locally (the recorder reads it) and tells the backend (it gates analytics and recommendations). */
    fun saveConsent(personalization: Boolean, analytics: Boolean) {
        consent.save(personalization, analytics)
        viewModelScope.launch {
            repository.setConsent("personalization", personalization)
            repository.setConsent("analytics", analytics)
        }
    }
}
