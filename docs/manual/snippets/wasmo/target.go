package main

// (1)
import (
    "github.com/extism/go-pdk"
    "github.com/buger/jsonparser"
)

// (2)
//export execute
func execute() int32 {
    input := pdk.Input() 

    // (3)
    var headers, dataType, offset, err = jsonparser.Get(input, "request", "headers")

    _ = dataType
    _ = offset

    if err != nil {}

    // (4)
    output := `{"headers":` +  string(headers) + `, "body": {\"foo\": \"bar\"}, "status": 200 }`
    mem := pdk.AllocateString(output)
    pdk.OutputMemory(mem)

    return 0
}

func main() {}
