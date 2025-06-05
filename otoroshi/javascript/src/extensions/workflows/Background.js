import React, { useContext } from 'react';

import { TransformWrapper, TransformComponent } from "react-zoom-pan-pinch";
import { SidebarContext } from '../../apps/BackOfficeApp';

export function Background({ children }) {

    const sidebarContext = useContext(SidebarContext);

    const { width } = sidebarContext;

    const centerX = (window.innerWidth - width()) / 2
    const centerY = window.innerHeight / 2

    return <TransformWrapper
        initialPositionX={-centerX}
        initialPositionY={-centerY}>
        <TransformComponent
            wrapperStyle={{
                width: `calc(100vw - ${width()}px)`,
                height: '100vh',
            }}
            contentStyle={{
                width: `calc(200vw - ${width()}px)`,
                height: '200vh',
            }}>
            <div style={{
                position: 'relative',
                flex: 1
            }} className='d-flex justify-content-center align-items-center'>
                {children}
                <DottedBackground />
            </div>
        </TransformComponent>
    </TransformWrapper >
}

function DottedBackground() {
    return <svg style={{
        position: 'absolute',
        width: '100%',
        height: '100%',
        top: 0,
        bottom: 0,
        left: 0,
        right: 0,
    }}>
        <pattern id="pattern-__EMPTY__" x="5" y="11.5" width="20" height="20" patternTransform="translate(-11,-11)" patternUnits="userSpaceOnUse">
            <circle cx="0.5" cy="0.5" r="0.5" fill="#aaa"></circle>
        </pattern>
        <rect x="0" y="0" width="100%" height="100%" fill="url(#pattern-__EMPTY__)"></rect>
    </svg>
}