FROM=$(pwd);
sbt 'paradox';
cd $FROM/target/paradox/site/main
npx -y pagefind --site . --output-subdir ./search
python3 -mhttp.server 8000
cd $FROM
