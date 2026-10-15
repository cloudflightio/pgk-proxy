# Custom Keycloak image that ships the pgk-idp-provisioning event listener.
#
# Build the provider jar first:
#   ./gradlew :keycloak-provider:keycloakProviderJar
# (produces keycloak-provider/build/keycloak/pgk-idp-event-listener.jar)
#
# Base tag defaults to the Keycloak version the provider is compiled against (see
# keycloak-provider/build.gradle.kts) and matches docker-compose.yml.
ARG KEYCLOAK_TAG=26.7
FROM quay.io/keycloak/keycloak:${KEYCLOAK_TAG}

COPY keycloak-provider/build/keycloak/pgk-idp-event-listener.jar /opt/keycloak/providers/