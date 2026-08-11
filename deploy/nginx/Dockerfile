ARG EDGE_BASE_IMAGE
FROM ${EDGE_BASE_IMAGE}

USER 0
RUN test -x /usr/bin/curl \
    && test -x /docker-entrypoint.sh \
    && test -d /etc/modsecurity.d/owasp-crs/rules
COPY healthcheck.sh /opt/stageaccord/bin/healthcheck
RUN chmod 0555 /opt/stageaccord/bin/healthcheck \
    && chown -R 10101:10101 /opt/stageaccord
USER 10101:10101
