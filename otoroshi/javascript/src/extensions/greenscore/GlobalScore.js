import React from 'react';
import { calculateGreenScore, getRankAndLetterFromScore } from "./util";

export function GlobalScore(props) {
    const score = props.groups.reduce((acc, group) => {
        return acc + group.routes.reduce((acc, route) => calculateGreenScore(route.rulesConfig).score + acc, 0) / group.routes.length;
    }, 0) / props.groups.length;

    const { letter, rank, ...rest } = getRankAndLetterFromScore(score);

    return <div
        className="text-center ms-2"
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
            {!props.raw && <>{letter !== "@" ? letter : '-'} <i className="fa fa-leaf"
                style={{ color: rank, fontSize: '6rem' }} />
            </>}
            {props.raw && <div>
                <span style={{ fontSize: '5rem' }}>{Math.round(rest.score)}</span>
                <span style={{ fontSize: '1rem', fontWeight: 'bold' }}>{`/${props.groups.length * 6000}`}</span>
            </div>}
        </div>
        <h3 style={{ color: 'var(--color_level2)', fontWeight: 100 }}>{props.raw ? 'Net' : 'Global'} score</h3>

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
