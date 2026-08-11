ARG BUILD_IMAGE
ARG RUNTIME_IMAGE
FROM ${BUILD_IMAGE} AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN chmod 0555 ./mvnw \
    && ./mvnw --batch-mode --no-transfer-progress verify

FROM ${RUNTIME_IMAGE} AS runtime

ARG APP_UID=10201
ARG APP_GID=10201
RUN test -x /usr/bin/curl
RUN getent group "${APP_GID}" >/dev/null 2>&1 || groupadd --system --gid "${APP_GID}" stageaccord
RUN getent passwd "${APP_UID}" >/dev/null 2>&1 || useradd --system --uid "${APP_UID}" --gid "${APP_GID}" --home-dir /nonexistent --shell /usr/sbin/nologin stageaccord

WORKDIR /opt/stageaccord
COPY --from=build /workspace/target/*-application.jar /opt/stageaccord/application.jar
COPY --from=build /workspace/target/*-worker.jar /opt/stageaccord/worker.jar
COPY container/entrypoint.sh container/healthcheck.sh /opt/stageaccord/bin/
RUN chmod 0555 /opt/stageaccord/bin/entrypoint.sh /opt/stageaccord/bin/healthcheck.sh \
    && chmod 0444 /opt/stageaccord/application.jar /opt/stageaccord/worker.jar

USER ${APP_UID}:${APP_GID}
EXPOSE 8080 8081
ENTRYPOINT ["/opt/stageaccord/bin/entrypoint.sh"]
