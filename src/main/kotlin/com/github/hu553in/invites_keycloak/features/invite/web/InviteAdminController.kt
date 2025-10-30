package com.github.hu553in.invites_keycloak.features.invite.web

import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/admin/invite")
@Validated
class InviteAdminController
