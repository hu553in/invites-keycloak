package com.github.hu553in.invites_keycloak.features.invite.core.repo

import com.github.hu553in.invites_keycloak.features.invite.core.model.InviteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface InviteRepository : JpaRepository<InviteEntity, UUID>
