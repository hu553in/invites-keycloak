package com.github.hu553in.invites_keycloak.features.invite.web

import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/invite")
@Validated
class InviteAdminController
