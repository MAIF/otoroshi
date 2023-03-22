package example

default can_access = false

can_access {
    input.request.headers.foo == "bar"
}
