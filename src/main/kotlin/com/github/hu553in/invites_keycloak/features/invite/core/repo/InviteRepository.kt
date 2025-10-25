package com.github.hu553in.invites_keycloak.features.invite.core.repo

import com.github.hu553in.invites_keycloak.features.invite.core.model.InviteEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.*

interface InviteRepository : JpaRepository<InviteEntity, UUID> {

    fun findByRealmAndEmail(realm: String, email: String): Optional<InviteEntity>

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
    fun findValidByRealmAndTokenHash(
        @Param("realm") realm: String,
        @Param("tokenHash") tokenHash: String,
        @Param("now") now: Instant
    ): Optional<InviteEntity>
}
