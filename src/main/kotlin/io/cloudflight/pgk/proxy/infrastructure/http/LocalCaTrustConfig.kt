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

package io.cloudflight.pgk.proxy.infrastructure.http

import io.cloudflight.pgk.proxy.shared.events.AuditAction
import io.cloudflight.pgk.proxy.shared.events.AuditLogger
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.EnvironmentAware
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts the local (mkcert) CA in addition to the JVM default truststore, so the proxy can call
 * local HTTPS endpoints (e.g. the Traefik-terminated https://matrix.traefik.me) without editing
 * the JDK cacerts. Uses only core javax.net.ssl APIs; default trust remains intact.
 *
 * Implemented as a BeanFactoryPostProcessor so the trust is installed before any bean is created
 * (and thus before any outbound HTTPS). A @PostConstruct would not be guaranteed to run before
 * beans that eagerly open HTTPS connections at startup, e.g. the OAuth2 client resolving the
 * issuer during context refresh.
 */
@Component
class LocalCaTrustConfig : BeanFactoryPostProcessor, EnvironmentAware {

    private lateinit var environment: Environment  // ← injected before postProcess runs
    private lateinit var auditLogger: AuditLogger

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        auditLogger = beanFactory.getBean(AuditLogger::class.java)
        val rawPath = environment.getProperty("pgk.security.trust-ca", "")
        val trustCaPath = if (rawPath.isBlank()) "" else beanFactory.resolveEmbeddedValue(rawPath).orEmpty()
        trustLocalCa(trustCaPath)
    }

    private fun trustLocalCa(trustCaPath: String) {
        val caFile = File(trustCaPath)
        if (trustCaPath.isBlank() || !caFile.exists()) {
            auditLogger.debug(
                "system", AuditAction.TruststoreCaMissing,
                "Local CA not found at '$trustCaPath', keeping default truststore only"
            )
            return
        }

        val localTm = loadTrustManager(caFile)
        val defaultTm = defaultTrustManager()

        val combined = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    defaultTm.checkClientTrusted(chain, authType)
                } catch (e: Exception) {
                    localTm.checkClientTrusted(chain, authType)
                }
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    defaultTm.checkServerTrusted(chain, authType)
                } catch (e: Exception) {
                    localTm.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                defaultTm.acceptedIssuers + localTm.acceptedIssuers
        }

        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(combined), null)
        SSLContext.setDefault(context)
        auditLogger.info(
            "system", AuditAction.TruststoreCaTrusted,
            "Trusted local CA in addition to JVM default truststore: ${caFile.absolutePath}"
        )
    }

    private fun loadTrustManager(caFile: File): X509TrustManager {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val cert = caFile.inputStream().use {
            certificateFactory.generateCertificate(it)
        } as X509Certificate
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setCertificateEntry("local-ca", cert)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun defaultTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}