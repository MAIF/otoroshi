# Simple deployment

here we only deploy 2 replicas of the same otoroshi instance using redis. 

It uses a service of type `LoadBalancer` so it's intended to run in a kubernetes cluster where external loadbalancer are supported