package com.example.yo.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.yo.domain.model.YoMessage
import com.example.yo.domain.repository.YoRepository
import com.example.yo.domain.usecase.SendYoUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val sendYoUseCase: SendYoUseCase,
    repository: YoRepository,
) : ViewModel() {
    val history: StateFlow<List<YoMessage>> = repository.observeHistory()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun sendYo(recipient: String) {
        viewModelScope.launch {
            sendYoUseCase(sender = "me", recipient = recipient)
        }
    }
}
