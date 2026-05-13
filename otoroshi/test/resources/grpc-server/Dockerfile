FROM node:20-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY server.js .
COPY helloworld.proto .

EXPOSE 8082

CMD ["node", "server.js"]