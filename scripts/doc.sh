#!/bin/sh

LOCATION=`pwd`

build_schemas () {
  sh $LOCATION/scripts/schemas.sh
}

clean () {
  rm -rf $LOCATION/manual/target/paradox
  rm -rf $LOCATION/docs/manual
}

build () {
  cd $LOCATION/manual
  cp -R $LOCATION/kubernetes $LOCATION/manual/src/main/paradox/snippets
  rm -rf $LOCATION/manual/src/main/paradox/snippets/kubernetes/.old
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/base/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/cluster/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/cluster-baremetal/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/cluster-baremetal-daemonset/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/simple/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/simple-baremetal/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/simple-baremetal-daemonset/readme.md
  node indexer.js
  rm $LOCATION/manual/src/main/paradox/snippets/reference.conf
  rm $LOCATION/manual/src/main/paradox/snippets/reference-env.conf
  touch $LOCATION/manual/src/main/paradox/snippets/reference.conf
  touch $LOCATION/manual/src/main/paradox/snippets/reference-env.conf
  cat $LOCATION/otoroshi/conf/application.conf >> $LOCATION/manual/src/main/paradox/snippets/reference.conf
  echo "\n\n" >> $LOCATION/manual/src/main/paradox/snippets/reference.conf
  cat $LOCATION/otoroshi/conf/base.conf >> $LOCATION/manual/src/main/paradox/snippets/reference.conf
  node config.js
  sbt ';clean;paradox'
  cp -r $LOCATION/manual/target/paradox/site/main $LOCATION/docs
  mv $LOCATION/docs/main $LOCATION/docs/manual
  rm $LOCATION/manual/src/main/paradox/snippets/reference.conf
  rm $LOCATION/manual/src/main/paradox/snippets/reference-env.conf
  touch $LOCATION/manual/src/main/paradox/snippets/reference.conf
  touch $LOCATION/manual/src/main/paradox/snippets/reference-env.conf
}

buildDev () {
  cd $LOCATION/otoroshi
  sbt ';clean;compile;testOnly OpenapiGeneratorTests'
  cp $LOCATION/otoroshi/public/openapi.json $LOCATION/manual/src/main/paradox/code/
  cd $LOCATION/manual
  cp -R $LOCATION/kubernetes $LOCATION/manual/src/main/paradox/snippets
  rm -rf $LOCATION/manual/src/main/paradox/snippets/kubernetes/.old
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/base/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/cluster/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/cluster-baremetal/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/cluster-baremetal-daemonset/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/simple/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/simple-baremetal/readme.md
  rm -f $LOCATION/manual/src/main/paradox/snippets/kubernetes/kustomize/overlays/simple-baremetal-daemonset/readme.md
  node indexer.js
  rm $LOCATION/manual/src/main/paradox/snippets/reference.conf
  rm $LOCATION/manual/src/main/paradox/snippets/reference-env.conf
  touch $LOCATION/manual/src/main/paradox/snippets/reference.conf
  touch $LOCATION/manual/src/main/paradox/snippets/reference-env.conf
  cat $LOCATION/otoroshi/conf/application.conf >> $LOCATION/manual/src/main/paradox/snippets/reference.conf
  echo "\n\n" >> $LOCATION/manual/src/main/paradox/snippets/reference.conf
  cat $LOCATION/otoroshi/conf/base.conf >> $LOCATION/manual/src/main/paradox/snippets/reference.conf
  node config.js
  sbt ';clean;paradox'
  rm -rf $LOCATION/docs/devmanual
  cp -r $LOCATION/manual/target/paradox/site/main $LOCATION/docs
  mv $LOCATION/docs/main $LOCATION/docs/devmanual
  rm $LOCATION/manual/src/main/paradox/snippets/reference.conf
  rm $LOCATION/manual/src/main/paradox/snippets/reference-env.conf
  touch $LOCATION/manual/src/main/paradox/snippets/reference.conf
  touch $LOCATION/manual/src/main/paradox/snippets/reference-env.conf
}

case "${1}" in
  all)
    build_schemas
    clean
    build
    ;;
  build_schemas)
    build_schemas
    ;;
  clean)
    clean
    ;;
  build)
    build
    ;;
  buildDev)
    buildDev
    ;;
  cleanbuild)
    clean
    build
    ;;
  *)
    build_schemas
    clean
    build
esac

exit ${?}
