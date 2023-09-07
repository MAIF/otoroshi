import React from 'react';
// import { calculateGreenScore, getRankAndLetterFromScore } from "./util";

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

export function GlobalScore({ tag = "static", letter, rank, color, score, ...props }) {

    function showDynamicThresholds() {
        return <div>

        </div>
    }

    function showNetScore() {
        return <div>
            <span style={{ fontSize: '5rem' }}>{Math.round(score)}</span>
            <span style={{ fontSize: '1rem', fontWeight: 'bold' }}>{`/${props.groups.length * 6000}`}</span>
        </div>
    }

    function showGlobalScore() {
        return <>
            {letter !== "@" ? letter : '-'} <i className="fa fa-leaf"
                style={{ color, fontSize: '5rem' }} />
        </>
    }

    console.log(props)

    return <div
        className="text-center p-3"
        style={{
            flex: .5,
            maxWidth: 250,
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
            {props.dynamic ? showDynamicThresholds() : <>
                {!props.raw && showGlobalScore()}
                {props.raw && showNetScore()}
            </>}
        </div>
        <h3 style={{ color: 'var(--color_level2)', fontWeight: 100 }} className='m-0'>
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
            <i className={`fas fa-${props.raw ? 'seedling' : 'spa'}`} style={{ fontSize: 'initial' }} />
        </div>
    </div>
};
