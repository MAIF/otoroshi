# generates documentation screenshots automatically

```sh
watchexec -e md -w ../src -- node ./src/index.js --parse-md-files-from="../src/main/paradox" --debug --screenshot-path="../src/main/paradox/imgs
```