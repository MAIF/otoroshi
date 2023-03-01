FROM keymetrics/pm2:14-alpine
# FROM node:14

LABEL maintainer "MAIF <oss@maif.fr>"

RUN mkdir -p /app

WORKDIR /app

COPY ./src /app/src
COPY ./node_modules /app/node_modules
COPY ./package.json /app/package.json
COPY ./yarn.lock /app/yarn.lock
COPY ./entrypoint.sh /app/entrypoint.sh 

# RUN apk --no-cache add bind-tools dnsmasq-dnssec
# RUN apt-get update && apt-get install dnsutils dnsmasq -y
# RUN yarn global add pm2

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]