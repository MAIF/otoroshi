FROM openjdk:8

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

EXPOSE 8091

CMD ["java", "-Dhttp.port=8091", "-Dapp.importFrom=http://backend:8100/otoroshi.json", "-jar", "otoroshi.jar" ]

# CMD ["java", "-Xms8g", "-Xmx16g", "-XX:+UseParallelGC", "-XX:+UseParallelOldGC", "-XX:ParallelGCThreads=4", "-XX:M$
