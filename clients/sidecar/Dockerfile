FROM keymetrics/pm2:14-alpine
# FROM node:14
# FROM ubuntu

LABEL maintainer "MAIF <oss@maif.fr>"

RUN mkdir -p /app

WORKDIR /app

COPY ./src /app/src
COPY ./node_modules /app/node_modules
COPY ./package.json /app/package.json
COPY ./yarn.lock /app/yarn.lock
COPY ./entrypoint.sh /app/entrypoint.sh 

# RUN yarn global add pm2
# RUN apt-get update -y
# RUN apt-get install -y git wget curl apt-transport-https openssl
# RUN curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.37.2/install.sh | bash
# RUN export NVM_DIR="/root/.nvm"  && . "$NVM_DIR/nvm.sh" && nvm install 14 && nvm use 14 && nvm alias default 14

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]