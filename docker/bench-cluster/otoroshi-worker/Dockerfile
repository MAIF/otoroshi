FROM openjdk:8

RUN mkdir -p /otoroshi

WORKDIR /otoroshi

COPY . /otoroshi

# RUN apt-get update -y \
#   && apt-get install -y curl bash wget \
#   && wget https://dl.bintray.com/maif/binaries/otoroshi.jar/snapshot/otoroshi.jar 

EXPOSE 8091

CMD ["java", "-Xms1g", "-Xmx3g", "-Dhttp.port=8091", "-Dotoroshi.cluster.mode=worker", "-Dotoroshi.cluster.leader.urls.0=http://otoroshi:8091", "-Dotoroshi.cluster.worker.state.pollEvery=5000", "-jar", "otoroshi.jar" ]
