BASE=$(pwd)

cd $BASE/otoroshi
sbt ';testOnly OtoroshiTests;testOnly ExpressionLanguageTests;testOnly BackendMtlsTests;testOnly FrontendTlsTests;testOnly functional.Http3Spec;testOnly functional.PluginsTestSpec -- -l Browser - Docker'
cd $BASE