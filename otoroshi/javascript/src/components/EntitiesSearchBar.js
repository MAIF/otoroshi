import React, { useMemo } from 'react';

import Async from 'react-select/async';
import debounce from 'lodash/debounce';
import _ from 'lodash';
import { searchNextServices } from '../services/BackOfficeServices';

export function EntitiesSearchBar({ value, setValue }) {

    const searchServicesOptions = (query) => {
        return searchNextServices(query)
            .then((r) => r.json())
            .then((results) => results.map((v) => ({
                type: v.type,
                label: v.name,
                value: v.serviceId,
                env: v.env,
                action: () => setValue(v.serviceId)
            })))
    };

    const debouncedLoadOptions = useMemo(() =>
        debounce((input, callback) => {
            searchServicesOptions(input)
                .then(callback)
        }, 300), []);

    return <Async
        placeholder="Type to search routes and apis"
        loadOptions={debouncedLoadOptions}
        defaultOptions
        onChange={i => i?.action()}
        isClearable
        styles={{
            control: (baseStyles, state) => ({
                ...baseStyles,
                border: '1px solid rgba(var(--raw-text), .25)',
                color: 'var(--text)',
                background: 'transparent',
                boxShadow: 'none',
            }),
            placeholder: (provided) => ({
                ...provided,
                color: 'var(--text)',
                opacity: 0.5,
            }),
            menu: (baseStyles) => ({
                ...baseStyles,
                margin: 0,
                borderTopLeftRadius: 0,
                borderTopRightRadius: 0,
                backgroundColor: 'var(--bg-color_level2)',
                color: 'var(--text)',
            }),
            input: (provided) => ({
                ...provided,
                color: 'var(--text)',
            }),
        }}
        components={{
            ValueContainer: ({ children }) => {
                return (
                    <div className="flex align-items-center" style={{ display: 'flex' }}>
                        <div style={{ maxHeight: 22, display: 'flex' }}>
                            <svg
                                xmlns="http://www.w3.org/2000/svg"
                                fill="none"
                                viewBox="0 0 24 24"
                                strokeWidth={1.5}
                                className="mx-2"
                                stroke="currentColor"
                                style={{
                                    opacity: 0.5,
                                    height: 18,
                                }}
                            >
                                <path
                                    strokeLinecap="round"
                                    strokeLinejoin="round"
                                    d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z"
                                />
                            </svg>
                        </div>

                        {children}
                    </div>
                );
            },
            NoOptionsMessage: () => null,
            SingleValue: props => <Option {...props} singleValue />,
            Option
        }}
    />
}

function Option({ singleValue, ...props }) {
    const p = props.data;
    return (
        <div
            style={{
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                padding: singleValue ? 0 : '.5rem',
                background: singleValue ? 'none' : props.isFocused
                    ? 'var(--bg-color_level2)'
                    : 'var(--bg-color_level3)',
            }}
            ref={props.innerRef}
            {...props.innerProps}
        >
            <div style={{ fontSize: '.75rem', width: singleValue ? 42 : 60, textTransform: 'uppercase', textAlign: 'center' }}
                className='me-2'>
                <span className={`badge bg-xs ${p.type === 'route' ? 'bg-success' : 'bg-warning'} `}>
                    {p.type}
                </span>
            </div>
            <span>{p.label}</span>
        </div>
    )
}