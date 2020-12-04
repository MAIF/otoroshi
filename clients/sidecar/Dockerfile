FROM node:14

LABEL maintainer "MAIF <oss@maif.fr>"

RUN mkdir -p /app

WORKDIR /app

COPY ./src /app/src
COPY ./node_modules /app/node_modules
COPY ./package.json /app/package.json
COPY ./yarn.lock /app/yarn.lock
COPY ./entrypoint.sh /app/entrypoint.sh 

RUN ls -ahl /app

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]