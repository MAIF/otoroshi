package example

default can_access = false

can_access {
    x := input.method
    x == "GET"
}
