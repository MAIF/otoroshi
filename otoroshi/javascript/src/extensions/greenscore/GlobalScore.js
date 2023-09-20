import React from 'react';
import Wrapper from './Wrapper';

import { getColorFromLetter } from './util'

function Tag({ value }) {
    return <div style={{
        position: 'absolute',
        top: '.75rem',
        left: '.75rem',
        color: '#fff',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontWeight: '500',
        fontSize: '.75rem',
        lineHeight: '1.2rem',
        letterSpacing: '.125em',
        textTransform: 'uppercase',
        color: '#f9b000',
        marginBottom: '10px',
        display: 'block'
    }}>
        {value}
    </div>
}

export function GlobalScore(allProps) {
    const { tag = "static", letter, rank, color, score, maxScore, ...props } = allProps;

    function showDynamicRawScore() {
        return <div>
            <span style={{
                fontSize: '5rem',
                '--dynamic-raw-score-to': Math.round(score)
            }}
                className='dynamic-raw-score'></span>
            <span style={{ fontSize: '1rem', fontWeight: 'bold' }}>{'%'}</span>
        </div>
    }

    function showNetScore() {
        return <div style={{ position: 'relative' }}>
            <span style={{
                fontSize: `${5 - ((String(score).length) * .1)}rem`,
                '--net-score-to': Math.round(score)
            }} className='net-score'></span>
            <div style={{
                fontSize: '1rem', fontWeight: 'bold',
                position: 'absolute',
                bottom: '2rem',
                left: 0,
                right: 0,
                overflow: 'hidden',
                whiteSpace: 'nowrap'
            }}>{`/ ${maxScore}`}</div>
        </div >
    }

    function showGlobalScore() {
        return <>
            {letter !== "@" ? letter : '-'} <i className="fa fa-leaf scale-in-ver-top"
                style={{ color: color || getColorFromLetter(letter), fontSize: '5rem' }} />
        </>
    }

    return <Wrapper loading={props.loading}>
        <div
            className="text-center p-3"
            style={{
                flex: .5,
                maxWidth: 250,
                minWidth: 230,
                background: 'var(--bg-color_level2)',
                borderRadius: '.2rem',
                padding: '0 .5rem',
                fontSize: '10rem',
                position: 'relative'
            }}>
            <div style={{
                display: 'flex',
                alignItems: 'baseline',
                justifyContent: 'center'
            }}>
                {props.dynamic ? (props.raw ? showDynamicRawScore() : showGlobalScore()) :
                    props.raw ? showNetScore() : showGlobalScore()}
            </div>

            <h3 style={{
                color: 'var(--color_level2)',
                fontWeight: 100,
            }} className='m-0'>
                {props.title ? props.title : props.raw ? 'Net score' : 'Global score'}
            </h3>

            <Tag value={tag} />

            <div style={{
                position: 'absolute',
                top: 6,
                right: 6,
                borderRadius: '50%',
                background: 'rgba(249, 176, 0, 0.46)',
                color: '#fff',
                width: 32,
                height: 32,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center'
            }}>
                <i className={`fas fa-${props.dynamic ? 'bolt' : 'spa'}`} style={{ fontSize: 'initial' }} />
            </div>
        </div>
    </Wrapper>
};
