package com.github.hu553in.invites_keycloak.features.invite.core.model

import com.github.hu553in.invites_keycloak.features.invite.core.service.InviteInvalid
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.FutureOrPresent
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.*

@Entity
@Table(name = "invite")
class InviteEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    var id: UUID? = null,

    @Column(nullable = false, updatable = false)
    @field:NotBlank
    var realm: String,

    @Column(name = "token_hash", nullable = false, updatable = false)
    @field:NotBlank
    var tokenHash: String,

    @Column(nullable = false, updatable = false)
    @field:NotBlank
    var salt: String,

    @Column(nullable = false, updatable = false, length = 254)
    @field:NotBlank
    @field:Email
    var email: String,

    @Column(name = "created_by", nullable = false, updatable = false)
    @field:NotBlank
    var createdBy: String,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant,

    @Column(name = "expires_at", nullable = false, updatable = false)
    @field:FutureOrPresent
    var expiresAt: Instant,

    @Column(name = "max_uses", nullable = false, updatable = false)
    @field:Positive
    var maxUses: Int = 1,

    @Column(nullable = false)
    @field:PositiveOrZero
    var uses: Int = 0,

    @Column(nullable = false)
    var revoked: Boolean = false,

    @JdbcTypeCode(SqlTypes.ARRAY)
    @field:NotEmpty
    @Column(nullable = false, updatable = false, columnDefinition = "text[]")
    var roles: Set<String>
) {
    fun incrementUses() {
        if (uses + 1 > maxUses) {
            throw InviteInvalid("Invite has already been used >= maxUses")
        }
        uses += 1
    }
}
