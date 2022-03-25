import React from 'react'
import { type, format, constraints, SingleLineCode } from '@maif/react-forms';

export const PLUGIN_INFORMATIONS_SCHEMA = {
    enabled: {
        visibleOnCollapse: true,
        type: type.bool,
        label: 'Enabled'
    },
    debug: {
        type: type.bool,
        label: 'Debug'
    },
    include: {
        label: 'Include',
        format: "singleLineCode",
        type: type.string,
        array: true,
        createOption: true
    },
    exclude: {
        label: 'Exclude',
        format: "singleLineCode",
        type: type.string,
        array: true,
        createOption: true
    }
}

export const EXCLUDED_PLUGINS = {
    plugin_visibility: ['internal'],
    ids: ['otoroshi.next.proxy.ProxyEngine']
}

export const LEGACY_PLUGINS_WRAPPER = {
    "app": "otoroshi.next.plugins.wrappers.RequestTransformerWrapper",
    "transformer": "otoroshi.next.plugins.wrappers.RequestTransformerWrapper",
    "validator": "otoroshi.next.plugins.wrappers.AccessValidatorWrapper",
    "preroute": "otoroshi.next.plugins.wrappers.PreRoutingWrapper",
    "sink": "otoroshi.next.plugins.wrappers.RequestSinkWrapper",
    "composite": "otoroshi.next.plugins.wrappers.CompositeWrapper",
    "listener": "",
    "job": "",
    "exporter": "",
    "request-handler": ""
}

export const DEFAULT_FLOW = {
    Frontend: {
        id: 'Frontend',
        icon: 'eye',
        plugin_steps: ["PreRoute"],
        description: "Exposition",
        default: true,
        field: 'frontend',
        onInputStream: true,
        config_schema: {
            domains: {
                type: type.string,
                array: true,
                format: 'singleLineCode',
                label: 'Domains'
            }
        },
        config_flow: [
            'domains',
            'stripPath',
            'exact',
            'headers',
            'methods',
            'query',
        ]
    },
    Backend: {
        id: 'Backend',
        icon: 'bullseye',
        group: 'Targets',
        default: true,
        onTargetStream: true,
        field: 'backend',
        config_schema: generatedSchema => ({
            ...generatedSchema,
            targets: {
                ...generatedSchema.targets,
                schema: {
                    custom_target: {
                        label: 'Target',
                        type: 'string',
                        render: ({ value, onChange, setValue, index }) => {
                            return <SingleLineCode
                                value={value}
                                onChange={e => {
                                    // TODO - not working, fix react-forms to support setValue with dotted notation
                                    try {
                                        const hasProtocol = ['http://', 'https://', 'udp://', 'tcp://']
                                            .filter((p) => e.toLowerCase().startsWith(p)).length > 0;
                                        if (hasProtocol) {
                                            const parts = e.split('://');
                                            const scheme = parts[0];
                                            const afterScheme = parts[1];
                                            const afterSchemeParts = afterScheme.split('/');
                                            const domain = afterSchemeParts[0];
                                            afterSchemeParts.shift();
                                            const pathname = '/' + afterSchemeParts.join('/');
                                            const url = `${scheme}://${domain}`

                                            console.log(url)
                                            onChange(url)

                                            if (url.indexOf('://') > -1)
                                                setValue('hostname', afterScheme);

                                            console.log(pathname)
                                            setValue('root', pathname);
                                        } else {
                                            onChange(e)
                                        }
                                    } catch (ex) {
                                        console.log(ex)
                                        onChange(e)
                                    }
                                }} />
                        }
                    },
                    expert_mode: {
                        type: 'bool',
                        label: null,
                        render: ({ value, onChange }) => {
                            return <button className='btn btn-sm btn-success me-3 mb-3' onClick={() => onChange(!value)}>
                                {!!value ? 'Show less' : 'Show more'}
                            </button>
                        }
                    },
                    ...Object.fromEntries(Object.entries(generatedSchema.targets.schema).map(([key, value]) => {
                        return [key, {
                            ...value,
                            visible: {
                                ref: 'plugin',
                                test: (v, idx) => !!v.targets[idx].value?.expert_mode
                            }
                        }]
                    }))
                },
                flow: [
                    'custom_target',
                    'expert_mode',
                    ...generatedSchema.targets.flow
                ]
            }
        }),
        config_flow: [
            "root",
            "targets",
            "healthCheck",
            "targetRefs",
            "client",
            "rewrite",
            "loadBalancing"
        ]
    }
}