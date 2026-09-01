FROM keymetrics/pm2:18-alpine

RUN mkdir -p /app

WORKDIR /app

COPY ./src /app/src
COPY ./node_modules /app/node_modules
COPY ./package.json /app/package.json
COPY ./package-lock.json /app/package-lock.json
COPY ./entrypoint.sh /app/entrypoint.sh 

# RUN apk --no-cache add bind-tools dnsmasq-dnssec
# RUN apt-get update && apt-get install dnsutils dnsmasq -y
# RUN npm install -g pm2

ENTRYPOINT ["./entrypoint.sh"]

CMD [""]