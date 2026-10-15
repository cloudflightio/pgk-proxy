/*
 * Copyright 2026 MA 01 - Wien Digital
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.cloudflight.pgk.proxy.ui.adapter.inbound

import io.cloudflight.pgk.proxy.provisioning.domain.model.OrganizationType
import io.cloudflight.pgk.proxy.shared.events.OrganizationCreateFailedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationCreatedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationDeleteFailedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationDeletedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationSearchFailedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationTreeLoadFailedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationUpdateFailedEvent
import io.cloudflight.pgk.proxy.shared.events.OrganizationUpdatedEvent
import io.cloudflight.pgk.proxy.ui.application.CurrentUser
import io.cloudflight.pgk.proxy.ui.application.SubOrganizationService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException

/**
 * Admin page "Untergeordnete Organisationen": the whole organization tree of the provider
 * directory is shown and every organization can be edited or deleted.
 */
@Controller
@RequestMapping("/admin/organizations")
class SubOrganizationController(
    private val service: SubOrganizationService,
    private val eventPublisher: ApplicationEventPublisher
) {

    @GetMapping
    fun page(
        model: Model,
        @AuthenticationPrincipal principal: OidcUser?
    ): String {
        model.addAttribute("currentUser", CurrentUser.from(principal))
        addTreeModel(model)
        addFormModel(model, FormValues())
        return "organizations"
    }

    @GetMapping("/form")
    fun form(model: Model): String {
        addFormModel(model, FormValues())
        return "fragments/organizations :: form"
    }

    @GetMapping("/{id}/edit")
    fun edit(
        @PathVariable id: String,
        model: Model
    ): String {
        val organization = service.findOrganization(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unbekannte Organisation: $id")
        val parentId = organization.parentId.orEmpty()
        addFormModel(
            model,
            FormValues(
                organizationId = organization.id ?: id,
                organizationName = organization.name,
                parentId = parentId,
                parentName = parentName(parentId),
                typeCode = organization.type?.code.orEmpty(),
                edit = true
            )
        )
        return "fragments/organizations :: form"
    }

    @GetMapping("/{id}/delete-dialog")
    fun deleteDialog(
        @PathVariable id: String,
        model: Model
    ): String {
        val organization = service.findOrganization(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unbekannte Organisation: $id")
        val deleteId = organization.id?.takeIf { it.isNotBlank() } ?: id.removePrefix("Organization/")
        require(deleteId.isNotBlank()) { "Ungültige Organisation: $id" }
        model.addAttribute("deleteOrganizationId", deleteId)
        model.addAttribute(
            "deleteOrganizationName",
            organization.name.takeIf { it.isNotBlank() } ?: deleteId
        )
        return "fragments/organizations :: delete-dialog"
    }

    @GetMapping("/parents/search")
    fun searchParents(
        @RequestParam(required = false, defaultValue = "") q: String,
        model: Model
    ): String {
        val query = q.trim()
        model.addAttribute("q", query)
        model.addAttribute(
            "items",
            if (query.isBlank()) emptyList()
            else runCatching { service.searchParentCandidates(query) }
                .onFailure { eventPublisher.publishEvent(OrganizationSearchFailedEvent(query, it)) }
                .getOrDefault(emptyList())
        )
        return "fragments/organizations :: parent-suggestions"
    }

    @GetMapping("/parents/select")
    fun selectParent(
        @RequestParam id: String,
        @RequestParam name: String,
        model: Model
    ): String {
        model.addAttribute("parentId", id)
        model.addAttribute("parentName", name)
        return "fragments/organizations :: parentBox"
    }

    @GetMapping("/types/search")
    fun searchTypes(
        @RequestParam(required = false, defaultValue = "") q: String,
        model: Model
    ): String {
        val query = q.trim()
        model.addAttribute("q", query)
        model.addAttribute(
            "items",
            if (query.isBlank()) emptyList()
            else service.typeOptions().filter {
                it.display.contains(query, ignoreCase = true) || it.code.contains(query, ignoreCase = true)
            }
        )
        return "fragments/organizations :: type-suggestions"
    }

    @GetMapping("/types/select")
    fun selectType(
        @RequestParam code: String,
        model: Model
    ): String {
        val type = service.typeOptions().find { it.code == code }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unbekannte Einrichtungsart: $code")
        model.addAttribute("typeCode", type.code)
        model.addAttribute("typeDisplay", type.display)
        return "fragments/organizations :: typeBox"
    }

    @PostMapping
    fun create(
        @RequestParam name: String,
        @RequestParam(required = false, defaultValue = "") parentId: String,
        @RequestParam(required = false, defaultValue = "") typeCode: String,
        model: Model
    ): String {
        val type = parseType(typeCode)
        return try {
            val createdId = service.create(name, parentId, type)
            eventPublisher.publishEvent(OrganizationCreatedEvent(createdId, parentId))
            model.addAttribute("message", "Organisation '${name.trim()}' hinzugefügt.")
            addFormModel(model, FormValues())
            addTreeModel(model)
            "fragments/organizations :: save-ok"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message.orEmpty())
            addFormModel(
                model, FormValues(organizationName = name, parentId = parentId,
                    parentName = parentName(parentId), typeCode = typeCode)
            )
            "fragments/organizations :: save-error"
        } catch (e: Exception) {
            eventPublisher.publishEvent(OrganizationCreateFailedEvent(e))
            model.addAttribute("error", "Hinzufügen fehlgeschlagen.")
            addFormModel(
                model, FormValues(organizationName = name, parentId = parentId,
                    parentName = parentName(parentId), typeCode = typeCode)
            )
            "fragments/organizations :: save-error"
        }
    }

    @PostMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestParam name: String,
        @RequestParam(required = false, defaultValue = "") parentId: String,
        @RequestParam(required = false, defaultValue = "") typeCode: String,
        model: Model
    ): String {
        val type = parseType(typeCode)
        return try {
            service.update(id, name, parentId, type)
            eventPublisher.publishEvent(OrganizationUpdatedEvent(id))
            model.addAttribute("message", "Organisation '${name.trim()}' aktualisiert.")
            addFormModel(model, FormValues())
            addTreeModel(model)
            "fragments/organizations :: save-ok"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message.orEmpty())
            addFormModel(
                model, FormValues(organizationId = id, organizationName = name, parentId = parentId,
                    parentName = parentName(parentId), typeCode = typeCode, edit = true)
            )
            "fragments/organizations :: save-error"
        } catch (e: Exception) {
            eventPublisher.publishEvent(OrganizationUpdateFailedEvent(id, e))
            model.addAttribute("error", "Speichern fehlgeschlagen.")
            addFormModel(
                model, FormValues(organizationId = id, organizationName = name, parentId = parentId,
                    parentName = parentName(parentId), typeCode = typeCode, edit = true)
            )
            "fragments/organizations :: save-error"
        }
    }

    @PostMapping("/{id}/delete")
    fun delete(
        @PathVariable id: String,
        model: Model
    ): String {
        return try {
            service.delete(id)
            eventPublisher.publishEvent(OrganizationDeletedEvent(id))
            model.addAttribute("message", "Organisation gelöscht.")
            addTreeModel(model)
            "fragments/organizations :: delete-ok"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message.orEmpty())
            "fragments/organizations :: delete-error"
        } catch (e: Exception) {
            eventPublisher.publishEvent(OrganizationDeleteFailedEvent(id, e))
            model.addAttribute("error", "Löschen fehlgeschlagen.")
            "fragments/organizations :: delete-error"
        }
    }

    private fun parentName(parentId: String): String =
        service.parentName(parentId.takeIf { it.isNotBlank() }).orEmpty()

    private fun addTreeModel(model: Model) {
        model.addAttribute("tree", runCatching { service.directoryTree() }
            .onFailure { eventPublisher.publishEvent(OrganizationTreeLoadFailedEvent(it)) }
            .getOrDefault(emptyList()))
    }

    private fun addFormModel(model: Model, values: FormValues) {
        model.addAttribute("formMode", if (values.edit) "edit" else "add")
        model.addAttribute("organizationId", values.organizationId)
        model.addAttribute("organizationName", values.organizationName)
        model.addAttribute("parentId", values.parentId)
        model.addAttribute("parentName", values.parentName)
        model.addAttribute("typeCode", values.typeCode)
        model.addAttribute("typeDisplay", typeDisplay(values.typeCode))
    }

    private fun typeDisplay(code: String): String =
        code.takeIf { it.isNotBlank() }
            ?.let { service.typeOptions().firstOrNull { type -> type.code == it }?.display }
            .orEmpty()

    private fun parseType(code: String): OrganizationType? =
        code.takeIf { it.isNotBlank() }?.let {
            service.typeOptions().find { type -> type.code == it }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unbekannte Einrichtungsart: $it")
        }

    private data class FormValues(
        val organizationId: String = "",
        val organizationName: String = "",
        val parentId: String = "",
        val parentName: String = "",
        val typeCode: String = "",
        val edit: Boolean = false
    )
}
