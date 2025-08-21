import React, { useEffect, useState } from 'react'
import { NODES } from './models/Functions'

function Category(item) {
    const { name, description, onClick } = item

    return <div className='whats-new-category d-flex-center justify-content-between px-3 py-2' onClick={() => onClick(item)}>
        <div className='whats-next-category-informations'>
            <p className='m-0'>{name}</p>
            <p className='m-0'>{description}</p>
        </div>
        <i className='fas fa-arrow-right' />
    </div>
}

function Node({ node, onClick }) {
    return <div
        className='whats-news-category d-flex align-items-center px-3 py-2'
        style={{ cursor: 'pointer' }}
        onClick={() => onClick(node)}>
        <div className='d-flex-center' style={{
            minWidth: 32,
            fontSize: '1.15rem'
        }}>
            <i className={node.label || node.icon} />
        </div>
        <div className=' d-flex flex-column px-2'>
            <p className='m-0' style={{
                fontWeight: 'bold'
            }}>{node.display_name || node.name}</p>
            <p className='m-0'>{node.description}</p>
        </div>
    </div>
}

function UnFoldedCategory({ nodes, onClick }) {

    const filteredNodes = Object.entries(nodes)

    if (filteredNodes.length === 0)
        return <p className='text-center m-0'>No results found</p>

    return filteredNodes
        .map(([_, node], i) => <Node
            node={node}
            onClick={() => onClick(node)}
            key={`${node.label}-${i}`} />)
}

export function Items({ setTitle, handleSelectNode, isOpen, query, selectedCategory, setSelectedCategory, docs }) {

    const onClick = item => {
        setSelectedCategory(item)
        setTitle(item.name)
    }

    useEffect(() => {
        setSelectedCategory(undefined)
    }, [isOpen])

    useEffect(() => {
        if (query.length === 0)
            setSelectedCategory(undefined)
    }, [query])

    console.log(docs)
    const items = Object.values(NODES(docs)).reduce((categories, node) => {

        const hasCategory = !categories.find(cat => cat.id === node.category)

        return categories.map(category => {
            if (!hasCategory && category.id === 'others')
                return {
                    ...category,
                    nodes: [...category.nodes, node]
                }
            if (category.id === node.category) {
                return {
                    ...category,
                    nodes: [...category.nodes, node]
                }
            }
            return category
        })
    }, docs.categories)

    if (query.length > 0) {
        const lowercaseQuery = query.toLowerCase()
        return items.flatMap(category => Object.entries(category.nodes))
            .filter(([_key, value]) => {
                return value.name.toLowerCase().includes(lowercaseQuery) ||
                    value.description.toLowerCase().includes(lowercaseQuery) ||
                    value.kind.toLowerCase().includes(lowercaseQuery)
            })
            .map(([_, node], i) => <Node
                node={node}
                onClick={() => handleSelectNode(node)}
                key={`${node.label}-${i}`} />)
    }

    if (selectedCategory)
        return <UnFoldedCategory {...selectedCategory} onClick={item => handleSelectNode(item)} />

    const categories = items
        .filter(category => Object.entries(category.nodes))

    if (categories.length === 0)
        return <p className='text-center m-0'>No results found</p>

    return categories.map(category => <Category {...category}
        id={category.name}
        key={category.name}
        onClick={onClick} />)
}