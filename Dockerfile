FROM docker.io/library/eclipse-temurin:21-alpine

EXPOSE 8080

COPY build/libs/pgk-proxy-0.0.1-SNAPSHOT.jar /home/default/pgk-proxy.jar

USER 1001

ENTRYPOINT ["java", "-jar", "/home/default/pgk-proxy.jar"]
