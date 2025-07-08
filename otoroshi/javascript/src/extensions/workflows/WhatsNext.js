import React, { use, useEffect, useRef, useState } from "react"

import { NgStringRenderer } from "../../components/nginputs"
import { Items } from "./WhatsNextItemsSelector"

function Search({ query, onChange, isOpen }) {

    const ref = useRef()

    useEffect(() => {
        if (isOpen)
            ref.current?.focus()
    }, [isOpen])

    return <div className="m-3">
        <NgStringRenderer
            value={query}
            schema={{
                props: {
                    placeholder: "Search nodes...",
                    autoFocus: true,
                    ref
                },
            }}
            label={' '}
            ngOptions={{ spread: true }}
            onChange={onChange} />
    </div>
}

export function WhatsNext({ handleSelectNode, isOpen }) {

    const [query, setQuery] = useState("")
    const [title, setTitle] = useState("What happens next ?")
    const [selectedCategory, setSelectedCategory] = useState()

    return <>
        <div className="p-3 whats-next-title">
            {selectedCategory && <i className="fas fa-chevron-left me-2" onClick={() => {
                setSelectedCategory(undefined)
                setTitle("What happens next ?")
            }} />}
            {title}
        </div>

        <Search query={query} onChange={setQuery} isOpen={isOpen} />

        <Items
            selectedCategory={selectedCategory}
            setSelectedCategory={setSelectedCategory}
            query={query}
            setTitle={setTitle}
            handleSelectNode={handleSelectNode}
            isOpen={isOpen} />
    </>
}