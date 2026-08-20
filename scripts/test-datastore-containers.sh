BASE=$(pwd)

cd $BASE/otoroshi
sbt 'testOnly functional.PgDatastoreSpec functional.LettuceDatastoreSpec'

#sbt 'testOnly functional.PgDatastoreSpec'
#sbt 'testOnly functional.LettuceDatastoreSpec'
cd $BASE
