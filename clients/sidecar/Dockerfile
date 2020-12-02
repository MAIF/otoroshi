FROM node:14

LABEL maintainer "MAIF <oss@maif.fr>"

RUN mkdir -p /app

WORKDIR /app

COPY ./src /app/
COPY ./node_modules /app/
COPY ./package.json /app/
COPY ./yarn.lock /app/

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]