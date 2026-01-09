ARG IMG_FROM=eclipse-temurin:21

FROM ${IMG_FROM}

LABEL maintainer="MAIF <oss@maif.fr>"
LABEL maintainer="Cloud APIM <contact@cloud-apim.com>"

# user/group (for volumes)
ARG OTOROSHI_UID=10001
ARG OTOROSHI_GID=10001

RUN set -eux; \
  groupadd -g "${OTOROSHI_GID}" -r otoroshi; \
  useradd  -u "${OTOROSHI_UID}" -r -g otoroshi -d /usr/app -s /usr/sbin/nologin otoroshi; \
  mkdir -p /usr/app; \
  chown -R otoroshi:otoroshi /usr/app

WORKDIR /usr/app

COPY --chown=otoroshi:otoroshi ./otoroshi.jar /usr/app/otoroshi.jar
COPY --chown=otoroshi:otoroshi ./entrypoint-jar-nr.sh /usr/app/entrypoint-jar.sh

RUN chmod +x /usr/app/entrypoint-jar.sh

USER otoroshi

ENTRYPOINT ["./entrypoint-jar.sh"]

EXPOSE 8080 8443
STOPSIGNAL SIGINT
CMD [""]