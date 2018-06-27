LOCATION=`pwd`
cd ../../otoroshi
rm -rf ./target/universal
sbt dist
cp ./target/universal/otoroshi-1.2.0-dev.zip ../docker/build/otoroshi-dist.zip
cd ../docker/build
docker build --no-cache -t otoroshi .
docker tag otoroshi "maif/otoroshi:1.2.0-dev"
rm -rf ./otoroshi-dist.zip
cd $LOCATION
docker-compose down
docker-compose build
docker-compose up
docker-compose down
