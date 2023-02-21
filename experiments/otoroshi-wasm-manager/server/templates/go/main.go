package main

import (
    "github.com/extism/go-pdk"
    "github.com/buger/jsonparser"
)

//export execute
func execute() int32 {
    input := pdk.Input() 

    var foo = jsonparser.Get(input, "request", "headers", "foo")

    output := `{"count":` +  foo + "}"
    mem := pdk.AllocateString(output)
    pdk.OutputMemory(mem)

    return 0
}

func main() {}