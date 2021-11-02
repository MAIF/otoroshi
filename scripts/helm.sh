HERE=`pwd`

helm package ./kubernetes/helm/otoroshi
mkdir ./docs/helm
mv otoroshi-1.0.0.tgz ./docs/helm/otoroshi-1.0.0.tgz
helm repo index ./docs/helm --url https://maif.github.io/otoroshi/helm