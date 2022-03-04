import React, { useEffect, useState } from 'react'
import { BooleanInput, CodeInput, SelectInput } from '@maif/react-forms/lib/inputs'
import { Loader } from '../../components/Loader'
import { tryIt } from '../../services/BackOfficeServices'

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD']

const CONTENT_TYPE = ['text', 'javascript', 'json', 'html', 'xml']

export const TryIt = ({ route }) => {

    const [selectedTab, setSelectedTab] = useState('Headers')
    const [selectedResponseTab, setSelectedResponseTab] = useState('Body')

    const [request, setRequest] = useState({
        path: '/',
        headers: { [Date.now()]: { key: "", value: "" } },
        method: METHODS[0],
        body: undefined,
        bodyContent: '',
        contentType: undefined,
        route: undefined,
        route_id: undefined
    })

    const [rawResponse, setRawResponse] = useState()
    const [response, setReponse] = useState()
    const [loading, setLoading] = useState(false)

    useEffect(() => {
        if (route && route.id)
            setRequest({
                ...request,
                route_id: route.id
            })
    }, [route])

    const send = () => {
        setLoading(true)
        setRawResponse(undefined)
        tryIt({
            ...request, headers: Object.fromEntries(Object.entries(Object.fromEntries(Object.values(request.headers)))
                .filter(([k, _]) => k.length > 0))
        })
            .then(res => {
                setRawResponse(res)
                return res.json()
            })
            .then(res => {
                setReponse(res)
                setLoading(false)
            })
    }

    const bytesToSize = bytes => {
        const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB']
        if (bytes == 0)
            return '0 Byte'
        const i = parseInt(Math.floor(Math.log(bytes) / Math.log(1024)))
        return Math.round(bytes / Math.pow(1024, i), 2) + ' ' + sizes[i]
    }

    const saveResponse = e => {
        e.preventDefault()

        const blob = new Blob([JSON.stringify(response, null, 4)], { type: 'application/json' })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.download = `tryit-${route.name}-${Date.now()}.json`
        a.href = url
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
    }
    const receivedResponse = rawResponse && response

    console.log(rawResponse)

    return <div className='h-100' style={{ flexDirection: 'column' }}>
        <div className='d-flex'>
            <div style={{ minWidth: '120px' }}>
                <SelectInput
                    possibleValues={METHODS}
                    value={request.method}
                    transformer={item => ({ value: item, label: item })}
                />
            </div>
            <input
                type="text"
                className="form-control mx-2"
                placeholder="Enter request URL"
                value={request.path}
                onChange={e => setRequest({ ...request, path: e.target.value })}
            />
            <button className="btn btn-success"
                style={{ backgroundColor: "#f9b000", borderColor: '#f9b000' }} onClick={send}>
                Send
            </button>
        </div>
        <div style={{ height: '225px', flexDirection: "column", overflowY: 'hidden' }} className="pb-3">
            <div className='d-flex mt-3'>
                {[
                    { label: 'Headers', value: `Headers (${Object.keys(request.headers || {}).length})` },
                    { label: 'Body', value: 'Body' }
                ].map(({ label, value }) => (
                    <button onClick={() => setSelectedTab(label)} className='pb-2 me-3' style={{
                        padding: 0,
                        border: 0,
                        borderBottom: selectedTab === label ? '2px solid #f9b000' : 'transparent',
                        background: 'none'
                    }}>{value}</button>
                ))}
            </div>
            {selectedTab === "Headers" && <Headers
                headers={request.headers}
                onKeyChange={(id, v) => {
                    const updatedRequest = {
                        ...request,
                        headers: {
                            ...request.headers,
                            [id]: { key: v, value: request.headers[id].value },
                            ...item
                        }
                    }

                    let item = {}
                    if (Object.values(updatedRequest.headers).every(r => r.key?.length > 0))
                        item = { [Date.now()]: { key: '', value: '' } }

                    setRequest(updatedRequest)
                }}
                onValueChange={(id, v) => {
                    const updatedRequest = {
                        ...request,
                        headers: {
                            ...request.headers,
                            [id]: { key: request.headers[id].key, value: v },
                            ...item
                        }
                    }

                    let item = {}
                    if (Object.keys(updatedRequest.headers).every(r => r.length > 0))
                        item = { [Date.now()]: { key: '', value: '' } }

                    setRequest(updatedRequest)
                }} />}
            {selectedTab === "Body" && <div className='mt-3'>
                <div className='d-flex align-items-center mb-3'>
                    <div className='d-flex'>
                        <BooleanInput value={!request.body} onChange={() => setRequest({ ...request, body: undefined })} />
                        <span className='ms-1'>none</span>
                    </div>
                    <div className='d-flex mx-2'>
                        <BooleanInput value={request.body === 'raw'} onChange={() => setRequest({ ...request, body: 'raw', contentType: 'json' })} />
                        <span className='ms-1'>raw</span>
                    </div>
                    {request.body === 'raw' && <div style={{ minWidth: '120px' }} ><SelectInput
                        possibleValues={CONTENT_TYPE}
                        value={request.contentType}
                        onChange={contentType => setRequest({ ...request, contentType })}
                        transformer={item => ({ label: item, value: item })}
                    />
                    </div>}
                </div>
                {request.body === 'raw' && <CodeInput
                    value={request.bodyContent}
                    mode={request.contentType}
                    onChange={bodyContent => setRequest({ ...request, bodyContent })}
                />}
            </div>
            }
        </div >
        {receivedResponse && <div className='d-flex flex-row mt-3'>
            <div className='d-flex flex-row justify-content-between flex'>
                <div>
                    {[
                        { label: 'Body', value: 'Body' },
                        { label: 'Cookies', value: 'Cookies' },
                        { label: 'Headers', value: `Headers (${([...rawResponse.headers] || []).length})` },
                    ].map(({ label, value, tab }) => (
                        <button onClick={() => setSelectedResponseTab(label)} className='pb-2 me-3' style={{
                            padding: 0,
                            border: 0,
                            borderBottom: selectedResponseTab === label ? '2px solid #f9b000' : 'transparent',
                            background: 'none'
                        }}>{value}</button>
                    ))}
                </div>
                <div className='d-flex flex-row'>
                    <div className='d-flex flex-row me-3'>
                        <span className='me-1'>Status:</span>
                        <span style={{ color: 'var(--bs-success)' }}>{response.status}</span>
                    </div>
                    <div className='d-flex flex-row me-3'>
                        <span className='me-1'>Time:</span>
                        <span style={{ color: 'var(--bs-success)' }}>{response.report?.duration} ms</span>
                    </div>
                    <div className='d-flex flex-row me-3'>
                        <span className='me-1'>Size:</span>
                        <span style={{ color: 'var(--bs-success)' }}>{bytesToSize(rawResponse.headers.get("content-length"))}</span>
                    </div>
                    <button className="btn btn-sm btn-success"
                        style={{ backgroundColor: "#f9b000", borderColor: '#f9b000' }}
                        onClick={saveResponse}>Save Response</button>
                </div>
            </div>
        </div>}
        {receivedResponse && selectedResponseTab === "Headers" && <Headers headers={[...rawResponse.headers].reduce((acc, [key, value], index) => ({
            ...acc,
            [`${Date.now()}-${index}`]: { key, value }
        }), {})} />}

        {receivedResponse && selectedResponseTab === "Body" && <div className='mt-3'>
            <CodeInput
                readOnly={true}
                value={JSON.stringify(response, null, 4)}
                width="-1"
            />
        </div>}
        {!receivedResponse && !loading && <div className="d-flex align-items-center justify-content-center">
            <span>Enter the URL and click Send to get a response</span>
        </div>}
        {loading && <div className='d-flex justify-content-center'><i className='fas fa-cog fa-spin' style={{ fontSize: "40px" }} /></div>}
    </div>
}

const Headers = ({ headers, onKeyChange, onValueChange }) => <div className='mt-2 w-50 div-overflowy pb-3' style={{
    height: onKeyChange ? '100%' : 'initial',
    overflowY: 'scroll'
}}>
    <div className='d-flex-between'>
        <span className='flex py-1' style={{ fontWeight: 'bold' }}>KEY</span>
        <span className='flex py-1' style={{ fontWeight: 'bold' }}>VALUE</span>
    </div>
    <div>
        {Object.entries(headers || {}).map(([id, { key, value }]) => (
            <div className='d-flex-between' key={id}>
                <input type="text"
                    disabled={!onKeyChange}
                    className='form-control flex mb-1 me-1'
                    value={key}
                    placeholder="Key"
                    onChange={e => onKeyChange(id, e.target.value)} />
                <input type="text"
                    disabled={!onKeyChange}
                    className='form-control flex mb-1 me-1'
                    value={value}
                    placeholder="Value"
                    onChange={e => onValueChange(id, e.target.value)} />
            </div>
        ))}
    </div>
</div>