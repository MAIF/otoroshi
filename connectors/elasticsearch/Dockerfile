FROM node:10.6.0

RUN mkdir -p /server
WORKDIR /server

COPY . /server

RUN yarn install

ENTRYPOINT ["./entrypoint.sh"]

EXPOSE 8080

CMD