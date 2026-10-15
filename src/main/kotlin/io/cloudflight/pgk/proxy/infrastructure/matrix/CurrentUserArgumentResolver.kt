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

package io.cloudflight.pgk.proxy.infrastructure.matrix

import io.cloudflight.pgk.proxy.shared.Mxid
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.server.ResponseStatusException

/**
 * Injects the authenticated mxid into controller params annotated with [CurrentUser], resolving
 * the bearer token once per request via [WhoAmIPort].
 */
@Component
class CurrentUserArgumentResolver(
    private val whoAmI: WhoAmIPort
) : HandlerMethodArgumentResolver {

    companion object {
        private const val AUTH_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val ATTR_CURRENT_USER = "pgk.currentUser"
    }

    /** @return whether [parameter] is an [Mxid] annotated with [CurrentUser] */
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java) && parameter.parameterType == Mxid::class.java

    /**
     * Resolves the current user's mxid, throwing 401 when required but unresolvable.
     *
     * @return the mxid, or null when absent and not required
     */
    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): Any? {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java) ?: return null

        // memoize the token -> mxid resolution once per request
        var mxid = request.getAttribute(ATTR_CURRENT_USER) as? Mxid
        if (mxid == null) {
            mxid = resolve(request)
            request.setAttribute(ATTR_CURRENT_USER, mxid)
        }

        val required = parameter.getParameterAnnotation(CurrentUser::class.java)!!.required
        if (mxid == null && required) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "M_UNKNOWN_TOKEN")
        }
        return mxid
    }

    /** @return the mxid for the bearer token, or null when no token is present or it is rejected */
    private fun resolve(request: HttpServletRequest): Mxid? {
        val token = request.getHeader(AUTH_HEADER)
            ?.substringAfter(BEARER_PREFIX, "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return try {
            whoAmI.whoami(token)
        } catch (e: HttpClientErrorException.Unauthorized) {
            null
        }
    }
}
