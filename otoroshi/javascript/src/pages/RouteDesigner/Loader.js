import React, { useEffect, useState } from 'react'

export default function Loader({ loading, children, minLoaderTime = 150 }) {
    const [internalLoading, setInternalLoading] = useState(false)
    const [startingTime, setStartingTime] = useState(undefined)

    useEffect(() => {
        if (loading) {
            setInternalLoading(true)
            setStartingTime(Date.now())
        }
        else if (internalLoading) {
            const delay = minLoaderTime - (Date.now() - startingTime)
            if (delay <= 0)
                setInternalLoading(false)
            else
                setTimeout(() => {
                    setInternalLoading(false)
                }, delay)
        }
    }, [loading])

    return <div className='d-flex justify-content-center'>
        {internalLoading ? <i className='fas fa-cog fa-spin' style={{ fontSize: "40px" }} /> : children}
    </div>
} 