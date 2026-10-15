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

package io.cloudflight.pgk.proxy.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val properties: ProxyProperties
) {

    @Bean
    @Order(1)
    fun internalfilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/internal/idp/**")
            .authorizeHttpRequests { authz ->
                authz
                    .requestMatchers("/internal/idp/**").hasRole(IDP_ROLE)
            }
            .httpBasic(Customizer.withDefaults())
            .csrf { csrf -> csrf.disable() }

        return http.build()
    }

    @Bean
    @Order(2)
    fun webFilterChain(
        http: HttpSecurity,
        cloentRegistrationRepository: ClientRegistrationRepository
    ): SecurityFilterChain {
        val requestHandler = XorCsrfTokenRequestAttributeHandler()

        http
            .authorizeHttpRequests { authz ->
                authz
                    .requestMatchers("/actuator/health", "/error", "/_matrix/**")
                    .permitAll()
                    .requestMatchers("/admin/**")
                    .hasRole(ADMIN_ROLE)
                    .anyRequest()
                    .authenticated()
            }
            .cors(Customizer.withDefaults())
            .oauth2Login { oauth2 ->
                oauth2
                    .userInfoEndpoint { userInfo ->
                        userInfo.oidcUserService(oidcUserService())
                    }
                    .defaultSuccessUrl("/",true)
            }
            .logout{ logout ->
                logout
                    .logoutUrl("/logout")
                    .logoutSuccessHandler(
                        oidcLogoutSuccessHandler(cloentRegistrationRepository)
                    )
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID")
            }
            .csrf { csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(requestHandler)
                    .ignoringRequestMatchers("/_matrix/**")
            }
        return http.build()
    }

    private fun oidcLogoutSuccessHandler(
        clientRegistrationRepository: ClientRegistrationRepository
    ) = org.springframework.security.oauth2.client.oidc.web.logout
        .OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository)

    @Bean
    fun userDetailsService(): UserDetailsService =
        InMemoryUserDetailsManager(
            User.withUsername(IDP_USERNAME)
                .password("{noop}${properties.idp.webhookSecret}")
                .roles("IDP")
                .build()
        )

    /**
     * OIDC user service that additionally maps the Keycloak realm roles
     * (`realm_access.roles`) to `ROLE_*` authorities, so `hasRole("ADMIN")` works for
     * users holding the `admin` realm role.
     */
    @Bean
    fun oidcUserService(): OidcUserService {
        val delegate = OidcUserService()
        return object : OidcUserService() {
            override fun loadUser(userRequest: OidcUserRequest): OidcUser {
                val user = delegate.loadUser(userRequest)
                val realmRoles = (user.claims[REALM_ACCESS_CLAIM] as? Map<*, *>)
                    ?.get(ROLES_CLAIM) as? List<*> ?: emptyList<Any>()
                val authorities: MutableList<GrantedAuthority> = user.authorities.toMutableList()
                authorities.addAll(
                    realmRoles.filterIsInstance<String>()
                        .map { SimpleGrantedAuthority("ROLE_${it.uppercase()}") }
                )
                return DefaultOidcUser(authorities, user.idToken, user.userInfo)
            }
        }
    }

    companion object {
        const val IDP_USERNAME = "pgk-idp"
        const val ADMIN_ROLE = "ADMIN"
        const val REALM_ACCESS_CLAIM = "realm_access"
        const val ROLES_CLAIM = "roles"
        const val IDP_ROLE = "IDP"
    }
}
