package com.geeksville.mesh.plannedmessages.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.plannedmessages.data.PlannedMessageDestinationSummary
import com.geeksville.mesh.plannedmessages.data.PlannedMessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlannedMessageListViewModel @Inject constructor(
    private val repository: PlannedMessageRepository,
) : ViewModel() {

    val destinationSummaries: LiveData<List<PlannedMessageDestinationSummary>> =
        repository.observeDestinationSummaries().asLiveData()

    private val _plannerEnabled = MutableLiveData(repository.isPlannerEnabled())
    val plannerEnabled: LiveData<Boolean> = _plannerEnabled

    init {
        bootstrap()
    }

    fun bootstrap() = viewModelScope.launch {
        repository.bootstrap()
        _plannerEnabled.postValue(repository.isPlannerEnabled())
    }

    fun setPlannerEnabled(enabled: Boolean) = viewModelScope.launch {
        repository.setPlannerEnabled(enabled)
        _plannerEnabled.postValue(repository.isPlannerEnabled())
    }
}
