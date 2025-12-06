package com.github.hu553in.invites_keycloak.repo

import com.github.hu553in.invites_keycloak.entity.InviteEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.*

interface InviteRepository : JpaRepository<InviteEntity, UUID> {

    fun findAllByOrderByCreatedAtDesc(): List<InviteEntity>

    @Query(
        """
        select case when count(invite) > 0 then true else false end
        from InviteEntity invite
        where invite.realm = :realm
          and invite.email = :email
          and invite.revoked = false
          and invite.expiresAt > :now
          and invite.uses < invite.maxUses
        """
    )
    fun existsActiveByRealmAndEmail(realm: String, email: String, now: Instant): Boolean

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update InviteEntity invite
        set invite.revoked = true
        where invite.realm = :realm
          and invite.email = :email
          and invite.revoked = false
          and invite.expiresAt <= :now
        """
    )
    fun revokeExpired(realm: String, email: String, now: Instant): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update InviteEntity invite
        set invite.revoked = true
        where invite.realm = :realm
          and invite.email = :email
          and invite.revoked = false
          and invite.uses >= invite.maxUses
        """
    )
    fun revokeOverused(realm: String, email: String): Int

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
