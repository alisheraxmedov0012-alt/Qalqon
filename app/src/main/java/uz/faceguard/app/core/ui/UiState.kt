package uz.faceguard.app.core.ui

/** Screen-level UI state shared by the auth/settings screens. */
sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data object Success : UiState
    data class Error(val messageRes: Int) : UiState
}
