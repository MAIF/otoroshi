import React from 'react';
import { Link } from 'react-router-dom';
import { firstLetterUppercase } from '../../util';
import { GREEN_SCORE_GRADES, getColor, getLetter } from './util';
import * as BackOfficeServices from '../../services/BackOfficeServices';

function Heading({ title, onClick, textCenter }) {
    return <div style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)', whiteSpace: 'nowrap', cursor: 'pointer' }}
        className={textCenter ? 'text-center' : ''}
        onClick={onClick}>
        {title}
    </div>
}

function Headings({ sort }) {
    return <div className='mb-2' style={{
        padding: '0rem 1rem',
        display: 'grid',
        gridTemplateColumns: '62px 1fr 2fr repeat(4, 1fr) 64px'
    }}>
        <div />
        <Heading title="Name" onClick={() => sort('name')} />
        <Heading title="Description" onClick={() => sort('description')} />
        <Heading title="Notation" onClick={() => sort('notation')} textCenter />
        <Heading title="Net Score" onClick={() => sort('net')} textCenter />
        <Heading title="Data" onClick={() => sort('data')} textCenter />
        <Heading title="Dynamic Score" onClick={() => sort('dynamic')} textCenter />
    </div>
}

export default class CustomTable extends React.Component {

    formatGroups = items => items.map((item, i) => this.formatGroup(item, i))

    formatGroup = (group, i) => {
        return {
            ...group,
            name: firstLetterUppercase(group.name),
            description: firstLetterUppercase(group.description),
            notation: getLetter(this.props.scores[i].score),
            color: getColor(this.props.scores[i].score),
            net: this.props.scores[i].score,
            data: String.fromCharCode(65 + Math.min(Math.floor((1 - this.props.scores[i].dynamic_values) * 5))),
            dynamicLetter: Object.keys(GREEN_SCORE_GRADES)[Math.min(Math.floor(((1 - this.props.scores[i].dynamic_values) * 5)))],
            dynamic: parseFloat(this.props.scores[i].dynamic_values * 100, 2).toFixed(2)
        }
    }


    state = {
        columns: {},
        items: this.formatGroups(this.props.items)
    }

    componentDidMount() {
        this.client = BackOfficeServices.apisClient(
            'green-score.extensions.otoroshi.io',
            'v1',
            'green-scores'
        )

        console.log(this.props)
    }

    componentDidUpdate(prevProps) {
        if (prevProps.items.length === 0 && this.props.items.length > 0)
            this.setState({
                items: this.formatGroups(this.props.items)
            })
    }

    openScore = group => {
        // this.setState({
        //     items: this.state.items.map(g => {
        //         if (g.id === group.id) {
        //             return {
        //                 ...g,
        //                 opened: !g.opened
        //             }
        //         }
        //         return g;
        //     })
        // })
    }

    openActions = i => {
        this.setState({
            items: this.state.items.map((g, j) => {
                if (j === i) {
                    return {
                        ...g,
                        openedActions: !g.openedActions
                    }
                }
                return {
                    ...g,
                    openedActions: false
                };
            })
        })
    }

    deleteGroup = id => {
        window
            .newConfirm('Delete this group ?', {
                title: 'Validation required',
            })
            .then((ok) => {
                if (ok) {
                    this.client.deleteById(id)
                        .then(() => {
                            this.setState({
                                items: this.state.items.filter(g => g.id !== id)
                            })
                        })
                }
            });
    }

    render() {
        const { items } = this.state;

        console.log(this.state.columns)

        return <div>
            <Headings sort={column => {
                const columnIncr = (this.state.columns[column] === undefined ? false : !this.state.columns[column]);
                this.setState({
                    columns: {
                        ...this.state.columns,
                        [column]: columnIncr
                    },
                    items: items.sort((a, b) => {
                        try {
                            return this.state.columns[column] ? a[column].localeCompare(b[column]) : b[column].localeCompare(a[column])
                        } catch (err) {
                            return this.state.columns[column] ? a[column] - b[column] : b[column] - a[column]
                        }
                    })
                })
            }} />

            {items.length === 0 && <div className='d-flex justify-content-center mt-3'>
                <Link to='/extensions/green-score/groups/new'
                    className="btn btn-sm d-flex align-items-center"
                    style={{
                        borderRadius: 6,
                        backgroundColor: 'var(--color-primary)',
                        boxShadow: `0 0 0 1px var(--color-primary,transparent)`,
                        color: 'var(--text)'
                    }}>Start New Group</Link>
            </div>}
            {
                items.map((group, i) => {
                    return <div key={group.id}
                        style={{
                            marginBottom: '.2rem',
                            background: 'var(--bg-color_level2)',
                            padding: '.5rem 1rem',
                            borderRadius: '.25rem',
                            cursor: 'pointer'
                        }} onClick={() => this.openScore(group)}>

                        <div style={{
                            display: 'grid',
                            gridTemplateColumns: '62px 1fr 2fr repeat(4, 1fr) 64px',
                            alignItems: 'center'
                        }}>
                            <div>#{i}</div>
                            {/* <div style={{
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                background: 'var(--bg-color_level1)',
                                borderRadius: '10%',
                                width: 32,
                                height: 32,
                                cursor: 'pointer',
                                marginRight: 12
                            }} onClick={() => this.openScore(group)}>
                                <i className={`fas fa-chevron-${group.opened ? 'up' : 'down'}`} />
                            </div> */}
                            <span style={{ minWidth: '30%', color: 'var(--text)' }}>
                                {group.name}
                            </span>

                            <span style={{ minWidth: '30%', color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden' }}>
                                {group.description}
                            </span>

                            <span className='mx-3 text-center'>
                                {group.notation}
                                <i className="fa fa-leaf ms-1"
                                    style={{ color: group.color, fontSize: '1.1rem' }} />
                            </span>
                            <div className='text-center d-flex justify-content-center'>
                                <div style={{ minWidth: 40 }}>
                                    <span>{group.net}</span>
                                </div>
                                <span style={{ borderLeft: '2px solid var(--bg-color_level3)' }} className='ps-1'>6000</span>
                            </div>
                            <span className='text-center'>
                                {group.data}
                                <i className="fa fa-leaf ms-1"
                                    style={{ color: group.dynamicLetter, fontSize: '1.1rem' }} />
                            </span>
                            <span className='text-center'>{group.dynamic}%</span>

                            <ItemActions unfold={group.openedActions}
                                onDelete={() => this.deleteGroup(group.id)}
                                openAction={() => this.openActions(i)}
                                editLink={`/extensions/green-score/groups/${group.id}`} />
                        </div>
                        {group.opened && <div className='mt-3'>
                            {group.routes.map(route => {
                                return <div key={route.routeId} className='mt-1' style={{ display: 'flex', justifyContent: 'space-between' }}>
                                    <span style={{ minWidth: '30%' }}>
                                    </span>


                                </div>
                            })}
                        </div>}
                    </div>
                })
            }
        </div >
    }
}

function ItemActions({ unfold, openAction, editLink, onDelete }) {
    return <div className='d-flex justify-content-center ml-auto' onClick={e => {
        e.stopPropagation()
        openAction()
    }}>
        {unfold ? <div className='d-flex'>
            <Link
                to={editLink}
                type="button"
                className="btn btn-sm me-1 date-hover"
                style={{
                    border: '1px solid var(--text)'
                }}
            >
                <i className="fas fa-pencil-alt" style={{ color: 'var(--text)' }} />
            </Link>
            <button
                type="button"
                className="btn btn-sm date-hover"
                style={{
                    border: '1px solid var(--text)'
                }} onClick={onDelete}>
                <i className="fas fa-trash" style={{ color: 'var(--text)' }} />
            </button>
        </div> : <i className="fas fa-ellipsis-vertical" style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            background: 'var(--bg-color_level1)',
            borderRadius: '10%',
            cursor: 'pointer',
            width: 32,
            height: 32,
        }} />}
    </div>
}