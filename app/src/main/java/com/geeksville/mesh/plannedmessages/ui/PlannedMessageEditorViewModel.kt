package com.geeksville.mesh.plannedmessages.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.plannedmessages.data.PlannedMessageDraft
import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity
import com.geeksville.mesh.plannedmessages.data.PlannedMessageRepository
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlannedMessageEditorViewModel @Inject constructor(
    private val repository: PlannedMessageRepository,
) : ViewModel() {

    private val _plannerEnabled = MutableLiveData(repository.isPlannerEnabled())
    val plannerEnabled: LiveData<Boolean> = _plannerEnabled

    private val _saveSuccess = MutableLiveData<Boolean?>()
    val saveSuccess: LiveData<Boolean?> = _saveSuccess
    private val _saveInProgress = MutableLiveData(false)
    val saveInProgress: LiveData<Boolean> = _saveInProgress

    private val _settings = MutableLiveData(repository.getSettings())
    val settings: LiveData<PlannedMessageSettings> = _settings
    private val _exactAlarmAvailable = MutableLiveData(repository.canScheduleExactAlarms())
    val exactAlarmAvailable: LiveData<Boolean> = _exactAlarmAvailable

    fun observeByDestination(destinationKey: String): LiveData<List<PlannedMessageEntity>> {
        return repository.observeByDestination(destinationKey).asLiveData()
    }

    fun bootstrap() = viewModelScope.launch {
        repository.bootstrap()
        _plannerEnabled.postValue(repository.isPlannerEnabled())
        _settings.postValue(repository.getSettings())
        _exactAlarmAvailable.postValue(repository.canScheduleExactAlarms())
    }

    fun saveRules(destinationKey: String, drafts: List<PlannedMessageDraft>) {
        viewModelScope.launch {
            _saveInProgress.postValue(true)
            runCatching {
                repository.replaceForDestination(destinationKey, drafts)
            }.onSuccess {
                _saveSuccess.postValue(true)
            }.onFailure {
                _saveSuccess.postValue(false)
            }.also {
                _saveInProgress.postValue(false)
            }
        }
    }

    fun clearSaveState() {
        _saveSuccess.value = null
    }

    fun updateLateFireWithinGrace(enabled: Boolean, graceMs: Long) = viewModelScope.launch {
        repository.updateLateFireWithinGrace(enabled, graceMs)
        _settings.postValue(repository.getSettings())
    }

    fun refreshExactAlarmCapability() {
        _exactAlarmAvailable.value = repository.canScheduleExactAlarms()
    }
}
