import { BooleanInput, CodeInput, SelectInput } from '@maif/react-forms/lib/inputs'
import React, { useState } from 'react'

const VERBS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD']

const CONTENT_TYPE = ['text', 'javascript', 'json', 'html', 'xml']

export const TryIt = ({ value }) => {

    const [selectedTab, setSelectedTab] = useState('Headers')

    const [request, setRequest] = useState({
        uri: '',
        headers: { "": "" },
        verb: VERBS[0],
        body: undefined,
        bodyContent: '',
        contentType: undefined
    })

    return (
        <div style={{ minHeight: "calc(100vh - 85px)" }}>
            <div className='d-flex'>
                <div style={{ minWidth: '120px' }}>
                    <SelectInput
                        possibleValues={VERBS}
                        value={request.verb}
                        transformer={item => ({ value: item, label: item })}
                    />
                </div>
                <input
                    type="text"
                    className="form-control mx-2"
                    placeholder="Enter request URL"
                    value={request.uri}
                    onChange={uri => setRequest({ ...request, uri })}
                />
                <button className="btn btn-success"
                    style={{ backgroundColor: "#f9b000", borderColor: '#f9b000' }}>
                    Send
                </button>
            </div>
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
            {selectedTab === "Headers" && <div className='mt-3'>
                <div className='d-flex'>
                    <span>KEY</span>
                    <span>VALUE</span>
                </div>
                <div>
                    {Object.entries(request.headers || {}).map(([key, value]) => (
                        <div className='d-flex' key={key}>
                            <input type="text" value={key} placeholder="Key" onChange={e => setRequest({
                                ...headers, [e.target.value]: request.headers[key]
                            })} />
                            <input type="text" value={value} placeholder="Value" onChange={e => setRequest({
                                ...headers, [request.headers[key]]: e.target.value
                            })} />
                        </div>
                    ))}
                </div>
            </div>}
            {selectedTab === "Body" && <div className='mt-3'>
                <div className='d-flex'>
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
            </div>}
        </div >
    )
}