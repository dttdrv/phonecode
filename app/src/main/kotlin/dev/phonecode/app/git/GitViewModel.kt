package dev.phonecode.app.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class GitUiState(
    val isRepo: Boolean = true,
    val status: GitStatus? = null,
    val branches: List<String> = emptyList(),
    val log: List<GitCommit> = emptyList(),
    val busy: Boolean = false,
)

/**
 * Drives the git UI over the ACTIVE chat's project workspace (workspaces are per-project).
 * The root composable calls [setWorkspace] whenever the active project changes.
 */
class GitViewModel(app: Application) : AndroidViewModel(app) {
    @Volatile private var service = GitService(File(app.filesDir, "workspaces/default"))

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    init { refresh() }

    /** Point the git UI at a different project workspace (no-op when unchanged). */
    fun setWorkspace(dir: File) {
        if (service.workspace.absolutePath == dir.absolutePath) return
        service = GitService(dir)
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val repo = service.isRepo()
        _state.update {
            it.copy(
                isRepo = repo,
                status = if (repo) service.status() else null,
                branches = if (repo) service.branches() else emptyList(),
                log = if (repo) service.log() else emptyList(),
                busy = false,
            )
        }
    }

    fun init() = viewModelScope.launch { service.init(); refresh() }
    fun stageAll() = viewModelScope.launch { service.stageAll(); refresh() }
    fun commit(message: String) = viewModelScope.launch { if (service.commit(message)) refresh() }
    fun checkout(branch: String) = viewModelScope.launch { service.checkout(branch); refresh() }
    fun createBranch(name: String) = viewModelScope.launch { service.createBranch(name); refresh() }
}
