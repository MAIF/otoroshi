sh ./certs.sh
go run backend.go &
go run clientbackend.go > clientbackend.out
# TODO: assert clientbackend.out content
# TODO: get otoroshi.jar here
# TODO: java -Dapp.domain=oto.tools -jar otoroshi.jar &
# TODO: delete existing certs in otoroshi 
# TODO: inject certs in otoroshi
# TODO: waits 10sec
# TODO: go run clientfrontend.go > clientfrontend.out
# TODO: assert clientfrontend.out content
killall go >> /dev/null
killall java >> /dev/null
