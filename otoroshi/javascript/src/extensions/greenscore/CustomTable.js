import React from 'react';
import { Link } from 'react-router-dom';
import { firstLetterUppercase } from '../../util';
import { GREEN_SCORE_GRADES, getColor, getLetter } from './util';

function Headings() {
    return <div className='mb-2' style={{
        padding: '0rem 1rem',
        display: 'grid',
        gridTemplateColumns: '62px 2fr repeat(4, 1fr) 64px'
    }}>
        <div />
        <div style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }}>
            Name
        </div>
        <div style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }} className='text-center'>
            Notation
        </div>
        <div style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }} className='text-center'>
            Net score
        </div>
        <div style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }} className='text-center'>
            Produced data
        </div>
        <div style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }} className='text-center'>
            Dynamic score
        </div>
    </div>
}

export default class CustomTable extends React.Component {

    state = {
        items: this.props.items
    }

    componentDidUpdate(prevProps) {
        if (prevProps.items.length === 0 && this.props.items.length > 0)
            this.setState({
                items: this.props.items
            })
    }

    openScore = group => {
        this.setState({
            items: this.state.items.map(g => {
                if (g.id === group.id) {
                    return {
                        ...g,
                        opened: !g.opened
                    }
                }
                return g;
            })
        })
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

    render() {
        console.log(this.props)
        const { scores } = this.props;
        const { items } = this.state;

        return <div>
            <Headings />
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
                            gridTemplateColumns: '62px 2fr repeat(4, 1fr) 64px',
                            alignItems: 'center'
                        }}>
                            <div style={{
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
                            </div>
                            <span style={{ minWidth: '30%', color: 'var(--text)' }}>
                                {firstLetterUppercase(group.name)}
                            </span>

                            <span className='mx-3 text-center'>
                                {getLetter(scores[i].score)}
                                <i className="fa fa-leaf ms-1"
                                    style={{ color: getColor(scores[i].score), fontSize: '1.1rem' }} />
                            </span>
                            <div className='text-center d-flex justify-content-center'>
                                <div style={{ minWidth: 40 }}>
                                    <span>{scores[i].score}</span>
                                </div>
                                <span style={{ borderLeft: '2px solid var(--bg-color_level3)' }} className='ps-1'>6000</span>
                            </div>
                            <span className='text-center'>
                                {String.fromCharCode(65 + (1 - scores[i].dynamic_score) * 5)}
                                <i className="fa fa-leaf ms-1"
                                    style={{ color: Object.keys(GREEN_SCORE_GRADES)[Math.round((1 - scores[i].dynamic_score) * 5)], fontSize: '1.1rem' }} />
                            </span>
                            <span className='text-center'>{parseFloat(scores[i].dynamic_score * 100, 2).toFixed(2)}%</span>

                            <ItemActions unfold={group.openedActions}
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

function ItemActions({ unfold, openAction, editLink }) {
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
                }}>
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