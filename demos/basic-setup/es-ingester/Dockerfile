FROM node:10.6.0

RUN mkdir -p /server
WORKDIR /server

COPY . /server

RUN yarn install

EXPOSE 5432

CMD [ "yarn", "start" ]