import React, { useEffect, useState } from 'react'

export default function Loader({ loading, children, minLoaderTime = 150 }) {
    const [internalLoading, setInternalLoading] = useState(false)
    const [startingTime, setStartingTime] = useState(undefined)
    const [step, setStep] = useState(0)
    const [timeout, sTimeout] = useState()

    useEffect(() => {
        if (loading) {
            setInternalLoading(true)
            setStartingTime(Date.now())
        }
        else if (internalLoading) {
            const delay = minLoaderTime - (Date.now() - startingTime)
            if (delay <= 0)
                setInternalLoading(false)
            else {
                setStep(0)
                sTimeout(delay / 100.0)
            }
        }
    }, [loading])

    useEffect(() => {
        if (timeout) {
            if (step < 100) {
                setTimeout(() => setStep(step => step + 1), timeout)
            }
            else
                setInternalLoading(false)
        }
    }, [step, timeout])

    return <div className='d-flex justify-content-center align-items-center'>
        {internalLoading ? <div className="" style={{
            width: '256px',
            height: '256px',
            position: 'relative'
        }}>
            <img src={"/__otoroshi_assets/images/otoroshi-logo-bw.png"} alt="prop" style={{
                opacity: .2,
                height: '100%',
                position: 'absolute',
                margin: 'auto',
                left: 0,
                right: 0,
                top: 0,
                bottom: 0
            }} />
            <img src={"/__otoroshi_assets/images/otoroshi-logo-bw.png"} alt="prop"
                style={{
                    clipPath: `polygon(0% ${100 - step}%, 100% ${100 - step}%, 100% 100%, 0 100%)`,
                    position: 'absolute',
                    height: '100%',
                    margin: 'auto',
                    left: 0,
                    right: 0,
                    top: 0,
                    bottom: 0
                }} />
        </div> : children}
    </div>
} 