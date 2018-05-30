echo "Sleeping"
sleep 20
echo "Warm up ..."
wrk -t6 -c200 -d40s -H "Host: test.foo.bar" --latency http://otoroshi:8091/
echo "Warm up done !"
sleep 10
echo "Bench ..."
wrk -t80 -c800 -d60s -H "Host: test.foo.bar" --latency http://otoroshi:8091/
