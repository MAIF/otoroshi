import React, { useState } from "react"
import { GlobalScore } from "./GlobalScore"

const CONVERTERS = {
    "overhead": 'Overhead',
    "duration": 'Duration',
    "backendDuration": 'Backend duration',
    "calls": 'Calls',
    "dataIn": 'Data in',
    "dataOut": 'Data out',
    "headersOut": 'Headers out',
    "headersIn": 'Headers in'
}

export default function DynamicScore({
    loading, title, values, ...props
}) {
    const [open, setOpen] = useState(false)

    return <div onClick={() => setOpen(!open)} style={{ flex: 1 }}>
        {open ? <div
            className="text-center p-3"
            style={{
                background: 'var(--bg-color_level2)',
                borderRadius: '.2rem',
                fontSize: '10rem',
                height: '100%'
            }}>
            <h3 style={{ textAlign: 'left', color: 'var(--text)' }}>Thresholds</h3>
            {values.map(value => <p key={value} style={{ fontSize: 'initial', color: 'var(--text)', margin: 0, textAlign: 'left' }}>{CONVERTERS[value]}</p>)}
        </div> :
            <GlobalScore
                {...props}
                loading={loading}
                score={values?.length}
                dynamic
                raw
                unit=" "
                title={title}
                tag="dynamic" />}
    </div>
}