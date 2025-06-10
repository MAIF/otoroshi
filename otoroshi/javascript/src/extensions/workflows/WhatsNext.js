import React, { useState } from "react"

import { NgStringRenderer } from "../../components/nginputs"
import { Items } from "./WhatsNextItemsSelector"

function Search({ query, onChange }) {
    return <div className="m-3">
        <NgStringRenderer
            value={query}
            schema={{
                props: {
                    placeholder: "Search nodes..."
                }
            }}
            label={' '}
            ngOptions={{ spread: true }}
            onChange={onChange} />
    </div>
}

export function WhatsNext({ handleSelectNode, isOpen }) {

    const [query, setQuery] = useState("")
    const [title, setTitle] = useState("What happens next ?")

    return <>
        <div className="p-3 whats-next-title">
            {title}
        </div>

        <Search query={query} onChange={setQuery} />

        <Items setTitle={setTitle} handleSelectNode={handleSelectNode} isOpen={isOpen} />
    </>
}