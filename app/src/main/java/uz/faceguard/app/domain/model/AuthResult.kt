package uz.faceguard.app.domain.model

/** Local auth outcomes. Screen maps these to string resources. */
sealed interface AuthResult {
    data class Success(val account: UserAccount) : AuthResult
    data class Failure(val reason: Reason) : AuthResult

    enum class Reason {
        /** phone already belongs to an existing account */
        DUPLICATE_PHONE,
        /** wrong phone/PIN pair — identical message on purpose (no account enumeration) */
        INVALID_CREDENTIALS,
    }
}
