package com.github.hu553in.invites_keycloak.features.invite.core.repo

import com.github.hu553in.invites_keycloak.features.invite.core.model.InviteEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.*

interface InviteRepository : JpaRepository<InviteEntity, UUID> {

    fun findAllByOrderByCreatedAtDesc(): List<InviteEntity>

    fun existsByRealmAndEmailAndRevokedFalse(realm: String, email: String): Boolean

    @Query(
        """
        select invite
        from InviteEntity invite
        where invite.realm = :realm
          and invite.tokenHash = :tokenHash
          and invite.revoked = false
          and invite.expiresAt > :now
          and invite.uses < invite.maxUses
        """
    )
    fun findValidByRealmAndTokenHash(realm: String, tokenHash: String, now: Instant): Optional<InviteEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select invite
        from InviteEntity invite
        where invite.id = :id
          and invite.revoked = false
          and invite.expiresAt > :now
          and invite.uses < invite.maxUses
        """
    )
    fun findValidByIdForUpdate(id: UUID, now: Instant): Optional<InviteEntity>

    fun deleteByExpiresAtBefore(cutoff: Instant): Long
}
