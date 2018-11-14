echo "Sleeping"
sleep 30
echo "Warm up ..."
wrk -R 1000 -t6 -c200 -d40s -H "Host: test.foo.bar" --latency http://nginx:8080/
echo "Warm up done !"
sleep 10
echo "Bench ..."
wrk -R 8000 -t80 -c800 -d120s -H "Host: test.foo.bar" --latency http://nginx:8080/