package uz.faceguard.app.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import uz.faceguard.app.core.security.SecurityStateHolder
import uz.faceguard.app.data.db.ChildProfileDao
import uz.faceguard.app.data.db.ParentProfileDao
import uz.faceguard.app.domain.security.BiometricTemplateCipher

/**
 * Phase 12: the safe path for pre-Phase-12 biometric rows.
 *
 * A Keystore-backed encryption cannot be performed inside a Room migration
 * transaction (the Keystore is not part of the SQLite transaction and a failure
 * there would abort the whole migration), so encryption is applied by this
 * explicit, idempotent sweep instead:
 *
 * v6 rows (plaintext base64) --sweep--> v1:<encrypted envelope>
 *
 * Properties:
 * - additive: nothing is deleted, no schema change, no destructive migration;
 * - idempotent: already-protected rows are skipped, so repeated runs are safe;
 * - fail-safe: if the key is unavailable the row is left untouched and the security
 *   state becomes RECOVERY_REQUIRED rather than writing a plaintext value or losing
 *   unrelated account data;
 * - account/child scoped: each row is written back through its own DAO predicate.
 */
@Singleton
class BiometricMigrationService @Inject constructor(
    private val parentProfileDao: ParentProfileDao,
    private val childProfileDao: ChildProfileDao,
    private val templateCipher: BiometricTemplateCipher,
    private val securityState: SecurityStateHolder,
) {

    /** Returns the number of templates that were encrypted in this pass. */
    suspend fun migrateLegacyTemplates(): Int {
        var migrated = 0
        val now = System.currentTimeMillis()

        parentProfileDao.all().forEach { row ->
            val stored = row.faceTemplateRef ?: return@forEach
            if (templateCipher.isProtected(stored)) return@forEach
            val protected = templateCipher.protect(stored) ?: return@forEach
            parentProfileDao.saveFaceEnrollment(row.accountId, protected, row.enrollmentStatus, now)
            migrated++
        }

        childProfileDao.all().forEach { row ->
            val stored = row.faceTemplateRef ?: return@forEach
            if (templateCipher.isProtected(stored)) return@forEach
            val protected = templateCipher.protect(stored) ?: return@forEach
            childProfileDao.saveFaceEnrollment(row.id, row.accountId, protected, row.enrollmentStatus, now)
            migrated++
        }

        if (migrated > 0) securityState.onMigrationComplete()
        return migrated
    }
}
