import React, { useEffect, useState } from 'react'
import { getNodeFromKind, NODES, NODES_BY_CATEGORIES } from './models/Functions'

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

function UnFoldedCategory({ onClick, nodes }) {
    if (nodes.length === 0)
        return <p className='text-center m-0'>No results found</p>

    return nodes
        .map(node => <Node node={node} onClick={() => onClick(node)} key={node.name} />)
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

    const items = NODES_BY_CATEGORIES(docs)
        .map(category => {
            return {
                ...category,
                nodes: category.nodes.map(kind => getNodeFromKind(docs, kind))
            }
        })

    if (query.length > 0) {
        const lowercaseQuery = query.toLowerCase()
        return items.flatMap(category => category.nodes)
            .filter(value => value.name.toLowerCase().includes(lowercaseQuery) ||
                value.description.toLowerCase().includes(lowercaseQuery) ||
                value.kind.toLowerCase().includes(lowercaseQuery))
            .reduce((acc, node) => acc.find(f => f.kind === node.kind) ? acc : [...acc, node], [])
            .map((node, i) => <Node
                node={node}
                onClick={() => handleSelectNode(node)}
                key={`${node.name}-${node.kind}-${i}`} />)
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