ARG IMG_FROM

FROM $IMG_FROM

LABEL maintainer "MAIF <oss@maif.fr>"
LABEL maintainer="Cloud APIM <contact@cloud-apim.com>"

RUN mkdir -p /usr/app

WORKDIR /usr/app

COPY ./otoroshi.jar /usr/app
COPY ./entrypoint-jar.sh /usr/app/

ENTRYPOINT ["./entrypoint-jar.sh"]

EXPOSE 8080

STOPSIGNAL SIGINT

CMD [""]