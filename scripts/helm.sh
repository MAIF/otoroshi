HERE=`pwd`
helm package --dependency-update ./kubernetes/helm/otoroshi
mkdir -p ./docs/helm
mv otoroshi-$1.tgz ./docs/helm/otoroshi-$1.tgz
helm repo index ./docs/helm --url https://maif.github.io/otoroshi/helm
git add ./docs/helm/otoroshi-$1.tgz
git commit -m 'Add helm package'
# master can move while the release runs, so push through concurrent pushes instead of failing
bash "$(dirname "$0")/release/git-push-master.sh" "v$1"