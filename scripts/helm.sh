HERE=`pwd`
helm package --dependency-update ./kubernetes/helm/otoroshi
mkdir -p ./docs/helm
mv otoroshi-$1.tgz ./docs/helm/otoroshi-$1.tgz
helm repo index ./docs/helm --url https://maif.github.io/otoroshi/helm