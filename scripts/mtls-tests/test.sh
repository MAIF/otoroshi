sh ./certs.sh
go run backend.go &
go run clientbackend.go > clientbackend.out
# TODO: assert clientbackend.out content
cp ../../otoroshi/target/scala-2.12/otoroshi.jar ./otoroshi.jar
java -Dapp.domain=oto.tools -jar otoroshi.jar &
sleep 10
# TODO: delete existing certs in otoroshi 
# TODO: inject certs in otoroshi
# TODO: create service
sleep 10
go run clientfrontend.go > clientfrontend.out
# TODO: assert clientfrontend.out content
killall go >> /dev/null
killall java >> /dev/null
