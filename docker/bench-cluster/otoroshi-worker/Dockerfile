FROM openjdk:8

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

# RUN apt-get update -y \
#   && apt-get install -y curl bash \
#   && curl -L -o otoroshi.jar https://github.com/MAIF/otoroshi/releases/download/v1.4.10/otoroshi.jar

EXPOSE 8091

CMD ["java", "-Xms1g", "-Xmx3g", "-Dhttp.port=8091", "-Dotoroshi.cluster.mode=worker", "-Dotoroshi.cluster.leader.urls.0=http://otoroshi:8091", "-Dotoroshi.cluster.worker.state.pollEvery=5000", "-jar", "otoroshi.jar" ]
