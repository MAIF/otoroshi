BASE=$(pwd)

cd $BASE/otoroshi
sbt dependencyUpdates
sbt dependencyCheck
cd $BASE