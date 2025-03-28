import React, { useEffect, useRef, useState } from 'react'

import './index.scss'

import { API_STATE } from './model';
import Sidebar from './Sidebar';
import { Link, Switch, Route, useParams, useHistory, useLocation } from 'react-router-dom';
import { Uptime } from '../../components/Status';
import { Form, Table } from '../../components/inputs';
import { v4 as uuid, v4 } from 'uuid';
import Designer from '../RouteDesigner/Designer';
import SimpleLoader from './SimpleLoader';
import { dynamicTitleContent } from '../../components/DynamicTitleSignal';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import { fetchWrapperNext, nextClient, routePorts } from '../../services/BackOfficeServices';
import { QueryClient, QueryClientProvider, useQuery } from 'react-query';
import { Button } from '../../components/Button';
import NgBackend from '../../forms/ng_plugins/NgBackend';
import { NgDotsRenderer, NgForm, NgSelectRenderer } from '../../components/nginputs';
import { BackendForm } from '../RouteDesigner/BackendNode';
import NgFrontend from '../../forms/ng_plugins/NgFrontend';

import moment from 'moment';
import semver from 'semver'

import { ApiStats } from './ApiStats';
import { PublisDraftModalContent, queryClient } from '../../components/Drafts/DraftEditor';
import { mergeData } from '../../components/Drafts/Compare/utils';
import { useSignalValue } from 'signals-react-safe';
import { signalVersion } from './VersionSignal';
import JwtVerificationOnly from '../../forms/ng_plugins/JwtVerificationOnly';
import { JsonObjectAsCodeInput } from '../../components/inputs/CodeInput';
import NgClientCredentialTokenEndpoint from '../../forms/ng_plugins/NgClientCredentialTokenEndpoint';
import NgHasClientCertMatchingValidator from '../../forms/ng_plugins/NgHasClientCertMatchingValidator';
import { components } from 'react-select';
import { HTTP_COLORS } from '../RouteDesigner/MocksDesigner';
import { unsecuredCopyToClipboard } from '../../util';

const RouteWithProps = ({ component: Component, ...rest }) => (
    <Route
        {...rest}
        component={(routeProps) => <Component {...routeProps} {...rest.props} />}
    />
);

const MAX_WIDTH = 720

export default function ApiEditor(props) {
    useEffect(() => {
        document.getElementById("otoroshi-toasts")?.remove()
    }, [])

    return <div className='editor'>
        <QueryClientProvider client={queryClient}>
            <SidebarComponent {...props} />

            <Switch>
                <RouteWithProps exact path='/apis/:apiId/routes' component={Routes} props={props} />
                <RouteWithProps exact path='/apis/:apiId/routes/new' component={NewRoute} props={props} />
                <RouteWithProps exact path='/apis/:apiId/routes/:routeId/:action' component={RouteDesigner} props={props} />

                <RouteWithProps exact path='/apis/:apiId/consumers' component={Consumers} props={props} />
                <RouteWithProps exact path='/apis/:apiId/consumers/new' component={NewConsumer} props={props} />
                <RouteWithProps exact path='/apis/:apiId/consumers/:consumerId/:action' component={ConsumerDesigner} props={props} />

                <RouteWithProps exact path='/apis/:apiId/subscriptions' component={Subscriptions} props={props} />
                <RouteWithProps exact path='/apis/:apiId/subscriptions/new' component={NewSubscription} props={props} />
                <RouteWithProps exact path='/apis/:apiId/subscriptions/:subscriptionId/:action' component={SubscriptionDesigner} props={props} />

                <RouteWithProps exact path='/apis/:apiId/flows' component={Flows} props={props} />
                <RouteWithProps exact path='/apis/:apiId/flows/new' component={NewFlow} props={props} />
                <RouteWithProps exact path='/apis/:apiId/flows/:flowId/designer' component={FlowDesigner} props={props} />
                <RouteWithProps exact path='/apis/:apiId/flows/:flowId/:action' component={EditFlow} props={props} />

                <RouteWithProps exact path='/apis/:apiId/backends' component={Backends} props={props} />
                <RouteWithProps exact path='/apis/:apiId/backends/new' component={NewBackend} props={props} />
                <RouteWithProps exact path='/apis/:apiId/backends/:backendId/:action' component={EditBackend} props={props} />

                <RouteWithProps exact path='/apis/:apiId/deployments' component={Deployments} props={props} />
                <RouteWithProps exact path='/apis/:apiId/testing' component={Testing} props={props} />

                <RouteWithProps exact path='/apis/:apiId/new' component={NewAPI} props={props} />

                <RouteWithProps path='/apis/:apiId/informations' component={Informations} props={props} />
                <RouteWithProps exact path='/apis' component={Apis} props={props} />
                <RouteWithProps path='/apis/:apiId' component={Dashboard} props={props} />
            </Switch>
        </QueryClientProvider>
    </div>
}

function useDraftOfAPI() {
    const params = useParams()
    const version = useSignalValue(signalVersion)

    const [draft, setDraft] = useState()
    const [api, setAPI] = useState()

    const [draftWrapper, setDraftWrapper] = useState()

    const draftClient = nextClient
        .forEntityNext(nextClient.ENTITIES.DRAFTS);

    const isDraft = version && (version === 'Draft' || version === 'staging')

    const rawAPI = useQuery(["getAPI", params.apiId],
        () => nextClient.forEntityNext(nextClient.ENTITIES.APIS).findById(params.apiId), {
        enabled: !api,
        onSuccess: setAPI
    })

    const query = useQuery(['findDraftById', params.apiId, version], () => nextClient
        .forEntityNext(nextClient.ENTITIES.DRAFTS)
        .findById(params.apiId),
        {
            retry: 0,
            enabled: !draft && !!api,
            onSuccess: data => {
                if (data.error) {
                    Promise.all([
                        nextClient
                            .forEntityNext(nextClient.ENTITIES.APIS)
                            .template(),
                        draftClient.template()
                    ])
                        .then(([rawTemplate, template]) => {
                            const apiTemplate = api ? api : rawTemplate

                            const draftApi = {
                                ...apiTemplate,
                                id: params.apiId
                            }

                            const newDraft = {
                                ...template,
                                kind: apiTemplate.id.split('_')[1],
                                id: params.apiId,
                                name: apiTemplate.name,
                                content: draftApi
                            }

                            draftClient.create(newDraft)
                                .then(() => {
                                    setDraft(draftApi)
                                    setDraftWrapper(newDraft)
                                })
                        })
                } else {
                    setDraftWrapper(data)
                    setDraft(data.content)
                }
            }
        })

    const updateDraft = (optDraft) => {
        return nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS)
            .update({
                ...draftWrapper,
                content: optDraft ? optDraft : draft
            })
            .then(() => setDraft(optDraft ? optDraft : draft))
    }

    const updateAPI = (optAPI) => {
        return nextClient.forEntityNext(nextClient.ENTITIES.APIS)
            .update(optAPI ? optAPI : api)
            .then(() => setAPI(optAPI ? optAPI : api))
    }

    return {
        api,
        item: isDraft ? draft : api,
        draft,
        draftWrapper,
        version,
        tag: version === 'Published' ? 'PROD' : 'DEV',
        setItem: isDraft ? setDraft : setAPI,
        updateItem: isDraft ? updateDraft : updateAPI,
    }
}

function Subscriptions(props) {
    const history = useHistory()
    const params = useParams()
    const location = useLocation()

    const columns = [
        {
            title: 'Name',
            filterId: 'name',
            content: (item) => item.name,
        }
    ];

    useEffect(() => {
        props.setTitle({
            value: 'Subscriptions',
            noThumbtack: true,
            children: <VersionBadge />
        })

        return () => props.setTitle(undefined)
    }, [])

    const client = nextClient.forEntityNext(nextClient.ENTITIES.API_CONSUMER_SUBSCRIPTIONS)

    const rawSubscriptions = useQuery(["getSubscriptions"], () => {
        return client.findAllWithPagination({
            page: 1,
            pageSize: 15,
            filtered: [{
                id: 'api_ref',
                value: params.apiId
            }]
        })
    })

    const deleteItem = item => client.delete(item)
        .then(() => window.location.reload())

    if (rawSubscriptions.isLoading || !item)
        return <SimpleLoader />

    return <Table
        parentProps={{ params }}
        navigateTo={(item) => historyPush(history, location, `/apis/${params.apiId}/subscriptions/${item.id}/edit`)}
        navigateOnEdit={(item) => historyPush(history, location, `/apis/${params.apiId}/subscriptions/${item.id}/edit`)}
        selfUrl="subscriptions"
        defaultTitle="Subscription"
        itemName="Subscription"
        columns={columns}
        deleteItem={deleteItem}
        fetchTemplate={client.template}
        fetchItems={() => Promise.resolve(rawSubscriptions.data || [])}
        defaultSort="name"
        defaultSortDesc="true"
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${params.apiId}/subscriptions/${i.id}/edit`)}
        rawEditUrl={true}

        injectTopBar={() => (
            <div className="btn-group input-group-btn">
                <Link className="btn btn-primary btn-sm"
                    to={{
                        pathname: "subscriptions/new",
                        search: location.search
                    }}>
                    <i className="fas fa-plus-circle" /> Create new subscription
                </Link>
                {props.injectTopBar}
            </div>
        )} />
}

function SubscriptionDesigner(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const [subscription, setSubscription] = useState()

    const { item } = useDraftOfAPI()

    const rawSubscription = useQuery(["getSubscription", params.subscriptionId],
        () => nextClient.forEntityNext(nextClient.ENTITIES.API_CONSUMER_SUBSCRIPTIONS).findById(params.subscriptionId),
        {
            onSuccess: setSubscription
        }
    )

    const updateSubscription = () => {
        return nextClient
            .forEntityNext(nextClient.ENTITIES.API_CONSUMER_SUBSCRIPTIONS)
            .update(subscription)
            .then(() => historyPush(history, location, `/apis/${params.apiId}/subscriptions`))
    }

    if (!item || rawSubscription.isLoading)
        return <SimpleLoader />

    return <>
        <PageTitle title={subscription.name} {...props}>
            <FeedbackButton
                type="success"
                className="d-flex ms-auto"
                onPress={updateSubscription}
                text={<div className='d-flex align-items-center'>
                    Update <VersionBadge size="xs" />
                </div>}
            />
        </PageTitle>
        <div style={{
            maxWidth: MAX_WIDTH,
            margin: 'auto'
        }}>
            <NgForm
                value={subscription}
                schema={SUBSCRIPTION_FORM_SETTINGS.schema(item)}
                flow={SUBSCRIPTION_FORM_SETTINGS.flow}
                onChange={setSubscription} />
        </div>
    </>
}

const SUBSCRIPTION_FORM_SETTINGS = {
    schema: item => ({
        location: {
            type: 'location'
        },
        name: {
            type: 'string',
            label: 'Name'
        },
        description: {
            type: 'string',
            label: 'Description'
        },
        enabled: {
            type: 'boolean',
            label: 'Enabled'
        },
        owner_ref: {
            type: 'string',
            label: 'Owner'
        },
        consumer_ref: {
            type: 'select',
            label: 'Published consumer',
            props: {
                options: item.consumers.filter(consumer => consumer.status === 'published'),
                noOptionsMessage: ({ children, ...props }) => {
                    return <components.NoOptionsMessage {...props}>
                        No consumers are published
                    </components.NoOptionsMessage>
                },
                optionsTransformer: {
                    value: 'id',
                    label: 'name'
                }
            }
        },
        token_refs: {
            array: true,
            label: 'Token refs',
            type: 'string'
        }
    }),
    flow: [
        'location',
        {
            type: 'group',
            name: 'Informations',
            collapsable: false,
            fields: ['name', 'description', 'enabled'],
        },
        {
            type: 'group',
            name: 'Ownership',
            collapsable: false,
            fields: ['owner_ref', 'consumer_ref', 'token_refs'],
        },
    ]
}

function NewSubscription(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const [subscription, setSubscription] = useState()
    const [error, setError] = useState()

    const { item, version } = useDraftOfAPI()

    const templatesQuery = useQuery(["getTemplate"],
        () => nextClient.forEntityNext(nextClient.ENTITIES.API_CONSUMER_SUBSCRIPTIONS).template(),
        {
            enabled: !!item,
            onSuccess: sub => setSubscription({
                ...sub,
                consumer_ref: item.consumers?.length > 0 ? item.consumers[0]?.id : undefined
            })
        }
    )

    if (!item || !subscription)
        return <SimpleLoader />

    const updateSubscription = () => {
        const consumer = item.consumers.find(consumer => consumer.id === subscription.consumer_ref)

        if (consumer.state === 'staging' || consumer.state === 'closed') {
            return alert('attention on est en staging')
        }

        return nextClient
            .forEntityNext(nextClient.ENTITIES.API_CONSUMER_SUBSCRIPTIONS)
            .create({
                ...subscription,
                api_ref: params.apiId,
                draft: version === 'staging' || version === 'draft'
            })
            .then(res => {
                if (res && res.error) {
                    if (res.error.includes('wrong status')) {
                        setError("You can't subscribe to an unpublished consumer")
                    } else {
                        setError(res.error)
                    }
                    throw res.error
                } else {
                    historyPush(history, location, `/apis/${params.apiId}/subscriptions`)
                }
            })
    }

    return <>
        <PageTitle title={subscription.name} {...props} />
        <div style={{
            maxWidth: MAX_WIDTH,
            margin: 'auto'
        }}>
            <NgForm
                value={subscription}
                schema={SUBSCRIPTION_FORM_SETTINGS.schema(item)}
                flow={SUBSCRIPTION_FORM_SETTINGS.flow}
                onChange={newSub => {
                    setSubscription(newSub)
                    setError(undefined)
                }} />

            {error && <div
                className="mt-3 p-3"
                style={{
                    borderLeft: '2px solid #D5443F',
                    background: '#D5443F',
                    color: 'var(--text)',
                    borderRadius: '.25rem'
                }}>
                {error}
            </div>}
            <FeedbackButton
                type="success"
                className="d-flex ms-auto mt-3 d-flex align-items-center"
                onPress={updateSubscription}
                text={<>
                    Create <VersionBadge size="xs" />
                </>}
            />
        </div>
    </>
}

function RouteDesigner(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const [route, setRoute] = useState()
    const [schema, setSchema] = useState()

    const { item, updateItem } = useDraftOfAPI()

    const [backends, setBackends] = useState([])

    const backendsQuery = useQuery(['getBackends'],
        () => nextClient.forEntityNext(nextClient.ENTITIES.BACKENDS).findAll(),
        {
            enabled: backends.length <= 0,
            onSuccess: setBackends
        })

    useEffect(() => {
        if (item && backendsQuery.data !== undefined) {
            setRoute(item.routes.find(route => route.id === params.routeId))
            setSchema(ROUTE_FORM_SETTINGS.schema(item, backends))
        }
    }, [item, backendsQuery.data])

    const updateRoute = () => {
        return updateItem({
            ...item,
            routes: item.routes.map(item => {
                if (item.id === route.id)
                    return route
                return item
            })
        })
            .then(() => historyPush(history, location, `/apis/${params.apiId}/routes`))
    }

    if (!route || !item || !schema)
        return <SimpleLoader />

    return <>
        <PageTitle title={route.name || "Update the route"} {...props}>
            <FeedbackButton
                type="success"
                className="d-flex ms-auto"
                onPress={updateRoute}
                disabled={!route.flow_ref}
                text={<div className='d-flex align-items-center'>
                    Update <VersionBadge size="xs" />
                </div>}
            />
        </PageTitle>
        <div style={{
            maxWidth: MAX_WIDTH,
            margin: 'auto'
        }}>
            <NgForm
                value={route}
                flow={ROUTE_FORM_SETTINGS.flow}
                schema={schema}
                onChange={newValue => setRoute(newValue)} />
        </div>
    </>
}

const ROUTE_FORM_SETTINGS = {
    schema: (item, backends) => {
        return {
            name: {
                type: 'string',
                label: 'Route name',
                placeholder: 'My users route'
            },
            frontend: {
                type: 'form',
                label: 'Frontend',
                schema: NgFrontend.schema,
                flow: NgFrontend.flow
            },
            flow_ref: {
                type: 'select',
                label: 'Flow',
                props: {
                    options: item.flows,
                    optionsTransformer: {
                        label: 'name',
                        value: 'id',
                    }
                },
            },
            backend: {
                renderer: props => {
                    return <div className="row mb-3">
                        <label className="col-xs-12 col-sm-2 col-form-label" style={{ textAlign: 'right' }}>Backend</label>
                        <div className="col-sm-10">
                            <NgSelectRenderer
                                id="backend_select"
                                value={props.rootValue.backend_ref || props.rootValue.backend}
                                placeholder="Select an existing backend"
                                label={' '}
                                ngOptions={{
                                    spread: true,
                                }}
                                isClearable
                                onChange={backend_ref => {
                                    props.rootOnChange({
                                        ...props.rootValue,
                                        usingExistingBackend: true,
                                        backend: backend_ref
                                    })
                                }}
                                components={{
                                    Option: props => {
                                        return <div className='d-flex align-items-center m-0 p-2' style={{ gap: '.5rem' }} onClick={() => {
                                            props.selectOption(props.data)
                                        }}>
                                            <span className={`badge ${props.data.value?.startsWith('backend_') ? 'bg-warning' : 'bg-success'}`}>
                                                {props.data.value?.startsWith('backend_') ? 'GLOBAL' : 'LOCAL'}
                                            </span>{props.data.label}
                                        </div>
                                    },
                                    SingleValue: (props) => {
                                        return <div className='d-flex align-items-center m-0' style={{ gap: '.5rem' }}>
                                            <span className={`badge ${props.data.value?.startsWith('backend_') ? 'bg-warning' : 'bg-success'}`}>
                                                {props.data.value?.startsWith('backend_') ? 'GLOBAL' : 'LOCAL'}
                                            </span>{props.data.label}
                                        </div>
                                    }
                                }}
                                options={[...item.backends, ...backends]}
                                optionsTransformer={(arr) =>
                                    arr.map((item) => ({ label: item.name, value: item.id }))
                                }
                            />
                        </div>
                    </div>
                }
            }
        }
    },
    flow: [
        {
            type: 'group',
            collapsable: true,
            collapsed: false,
            name: '1. Add your domains',
            fields: ['frontend'],
            summaryFields: ['domains']
        },
        {
            type: 'group',
            collapsable: true,
            collapsed: true,
            name: '2. Add plugins to your route by selecting a flow',
            fields: ['flow_ref'],
        },
        {
            type: 'group',
            collapsable: true,
            collapsed: true,
            name: '3. Configure the backend',
            fields: ['backend'],
        },
        {
            type: 'group',
            collapsable: true,
            collapsed: true,
            name: '4. Additional informations',
            fields: ['name'],
        }
    ]
}

function NewRoute(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const [route, setRoute] = useState()
    const [schema, setSchema] = useState()

    const [backends, setBackends] = useState([])

    const backendsQuery = useQuery(['getBackends'],
        () => nextClient.forEntityNext(nextClient.ENTITIES.BACKENDS).findAll(),
        {
            enabled: backends.length <= 0,
            onSuccess: setBackends
        })

    const { item, updateItem } = useDraftOfAPI()

    useEffect(() => {
        if (item && !backendsQuery.isLoading && !schema) {
            setSchema(ROUTE_FORM_SETTINGS.schema(item, backends))
        }
    }, [item, backendsQuery])


    const saveRoute = () => {
        return updateItem({
            ...item,
            routes: [
                ...item.routes, {
                    ...route,
                    id: v4()
                }
            ]
        })
            .then(() => historyPush(history, location, `/apis/${params.apiId}`))
    }

    useEffect(() => {
        if (item) {
            nextClient.forEntityNext(nextClient.ENTITIES.ROUTES)
                .template()
                .then(({ frontend }) => {
                    setRoute({
                        ...route,
                        name: 'My first route',
                        frontend,
                        backend: item.backends.length && item.backends[0].id,
                        usingExistingBackend: true,
                        flow_ref: item.flows.length && item.flows[0].id,
                    })
                })
        }
    }, [item])

    if (!schema || !route)
        return <SimpleLoader />


    return <>
        <PageTitle title="New Route" {...props} style={{ paddingBottom: 0 }} />
        <div style={{
            maxWidth: MAX_WIDTH,
            margin: 'auto'
        }}>
            <NgForm
                flow={ROUTE_FORM_SETTINGS.flow}
                schema={schema}
                value={route}
                onChange={setRoute}
            />
            <FeedbackButton
                type="success"
                className="d-flex mt-3 ms-auto"
                onPress={saveRoute}
                disabled={!route.flow_ref}
                text={<div className='d-flex align-items-center'>
                    Create <VersionBadge size="xs" />
                </div>}
            />
        </div>
    </>
}

function Consumers(props) {
    const history = useHistory()
    const params = useParams()
    const location = useLocation()

    const columns = [
        {
            title: 'Name',
            filterId: 'name',
            content: (item) => item.name,
        }
    ];

    const { item, updateItem } = useDraftOfAPI()

    useEffect(() => {
        props.setTitle({
            value: 'Consumers',
            noThumbtack: true,
            children: <VersionBadge />
        })
        return () => props.setTitle('')
    }, [])

    const deleteItem = newItem => updateItem({
        ...item,
        consumers: item.consumers.filter(f => f.id !== newItem.id)
    })

    if (!item)
        return <SimpleLoader />

    return <>
        <Table
            parentProps={{ params }}
            navigateTo={(item) => historyPush(history, location, `/apis/${params.apiId}/consumers/${item.id}/edit`)}
            navigateOnEdit={(item) => historyPush(history, location, `/apis/${params.apiId}/consumers/${item.id}/edit`)}
            selfUrl="consumers"
            defaultTitle="Consumer"
            itemName="Consumer"
            columns={columns}
            deleteItem={deleteItem}
            fetchTemplate={() => Promise.resolve({
                id: v4(),
                name: "New consumer",
                consumer_kind: "apikey",
                config: {}
            })}
            fetchItems={() => Promise.resolve(item.consumers || [])}
            defaultSort="name"
            defaultSortDesc="true"
            showActions={true}
            showLink={false}
            extractKey={(item) => item.id}
            rowNavigation={true}
            hideAddItemAction={true}
            itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${params.apiId}/consumers/${i.id}/edit`)}
            rawEditUrl={true}

            injectTopBar={() => (
                <div className="btn-group input-group-btn">
                    <Link className="btn btn-primary btn-sm"
                        to={{
                            pathname: "consumers/new",
                            search: location.search
                        }}>
                        <i className="fas fa-plus-circle" /> Create new consumer
                    </Link>
                    {props.injectTopBar}
                </div>
            )} />
    </>
}

const TEMPLATES = {
    apikey: {
        wipe_backend_request: true,
        validate: true,
        mandatory: true,
        pass_with_user: false,
        update_quotas: true,
    },
    mtls: {
        serialNumbers: [],
        subjectDNs: [],
        issuerDNs: [],
        regexSubjectDNs: [],
        regexIssuerDNs: [],
    },
    keyless: {},
    oauth2: {
        expiration: 3600000,
        default_key_pair: "otoroshi-jwt-signing"
    },
    jwt: {
        verifier: undefined,
        fail_if_absent: false
    }
}

function NewConsumerSettingsForm(props) {
    return <NgForm
        value={props.value}
        onChange={settings => {
            if (settings && JSON.stringify(props.value, null, 2) !== JSON.stringify(settings, null, 2))
                props.onChange(settings)
        }}
        schema={props.schema}
        flow={props.flow}
    />
}

const CONSUMER_FORM_SETTINGS = {
    schema: {
        name: {
            type: 'string',
            label: 'Name'
        },
        consumer_kind: {
            renderer: props => {
                return <div className="row mb-3">
                    <label className="col-xs-12 col-sm-2 col-form-label" style={{ textAlign: 'right' }}>Consumer kind</label>
                    <div className="col-sm-10">
                        <NgDotsRenderer
                            value={props.value}
                            options={['keyless', 'apikey', 'mtls', 'oauth2', 'jwt']}
                            ngOptions={{
                                spread: true
                            }}
                            onChange={newType => {
                                props.rootOnChange({
                                    ...props.rootValue,
                                    settings: TEMPLATES[newType],
                                    consumer_kind: newType
                                })
                            }}
                        />
                    </div>
                </div>
            }
        },
        status: {
            type: 'dots',
            label: "Status",
            props: {
                options: ['staging', 'published', 'deprecated', 'closed'],
            },
        },
        description: {
            renderer: ({ rootValue }) => {
                const descriptions = {
                    staging: "This is the initial phase of a plan, where it exists in draft mode. You can configure the plan, but it won’t be visible or accessible to users",
                    published: "When your plan is finalized, you can publish it to allow API consumers to view and subscribe to it via the APIM Portal. Once published, consumers can use the API through the plan. Published plans remain editable",
                    deprecated: "Deprecating a plan makes it unavailable on the APIM Portal, preventing new subscriptions. However, existing subscriptions remain unaffected, ensuring no disruption to current API consumers",
                    closed: "Closing a plan terminates all associated subscriptions, and this action is irreversible. API consumers previously subscribed to the plan will no longer have access to the API"
                };

                return <div className="row mb-3" style={{ marginTop: "-1rem" }}>
                    <label className="col-xs-12 col-sm-2 col-form-label" />
                    <div className="col-sm-10" style={{ fontStyle: 'italic' }}>
                        {descriptions[rootValue?.status]}
                    </div>
                </div>
            }
        },
        auto_validation: {
            type: 'box-bool',
            label: 'Auto-validation',
            props: {
                description: "When creating a customer, you can enable subscription auto-validation to immediately approve subscription requests. If Auto validate subscription is disabled, the API publisher must approve all subscription requests."
            }
        },
        settings: {
            renderer: props => {
                const kind = props.rootValue.consumer_kind

                const kinds = {
                    jwt: {
                        schema: JwtVerificationOnly.config_schema,
                        flow: JwtVerificationOnly.config_flow,
                    },
                    oauth2: {
                        schema: NgClientCredentialTokenEndpoint.config_schema,
                        flow: NgClientCredentialTokenEndpoint.config_flow,
                    },
                    mtls: {
                        schema: NgHasClientCertMatchingValidator.config_schema,
                        flow: NgHasClientCertMatchingValidator.config_flow,
                    },
                    apikey: {
                        schema: {
                            wipe_backend_request: {
                                label: 'Wipe backend request',
                                type: 'box-bool',
                                props: {
                                    description: 'Remove the apikey fromcall made to downstream service',
                                },
                            },
                            update_quotas: {
                                label: 'Update quotas',
                                type: 'box-bool',
                                props: {
                                    description: 'Each call with an apikey will update its quota',
                                },
                            },
                            pass_with_user: {
                                label: 'Pass with user',
                                type: 'box-bool',
                                props: {
                                    description: 'Allow the path to be accessed via an Authentication module',
                                },
                            },
                            mandatory: {
                                label: 'Mandatory',
                                type: 'box-bool',
                                props: {
                                    description:
                                        'Allow an apikey and and authentication module to be used on a same path. If disabled, the route can be called without apikey.',
                                },
                            },
                            validate: {
                                label: 'Validate',
                                type: 'box-bool',
                                props: {
                                    description:
                                        'Check that the api key has not expired, has not reached its quota limits and is authorized to call the Otoroshi service',
                                },
                            },
                        }
                    }
                }

                const onChange = settings => {
                    if (settings)
                        props.rootOnChange({
                            ...props.rootValue,
                            settings
                        })
                }

                if (kinds[kind])
                    return <NewConsumerSettingsForm
                        schema={kinds[kind].schema}
                        flow={kinds[kind].flow}
                        value={props.rootValue.settings}
                        onChange={onChange}
                    />

                return <JsonObjectAsCodeInput
                    label='Additional informations'
                    onChange={onChange}
                    value={props.rootValue.settings} />
            }
        }
    },
    flow: [{
        type: 'group',
        collapsable: false,
        name: 'Plan',
        fields: ['name',
            'consumer_kind',
            'status',
            'description',
            'auto_validation'
        ],
    },
    {
        type: 'group',
        collapsable: false,
        name: 'Configuration',
        fields: ['settings'],
    }]
}

function NewConsumer(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const [consumer, setConsumer] = useState({
        id: v4(),
        name: "New consumer",
        consumer_kind: "keyless",
        settings: TEMPLATES.keyless,
        status: "staging",
        subscriptions: []
    })

    const { item, updateItem } = useDraftOfAPI()

    const savePlan = () => {
        return updateItem({
            ...item,
            consumers: [...item.consumers, consumer]
        })
            .then(() => historyPush(history, location, `/apis/${params.apiId}`))
    }

    if (!item)
        return <SimpleLoader />

    return <>
        <PageTitle title="New Plan" {...props} style={{ paddingBottom: 0 }} />

        <div style={{
            maxWidth: MAX_WIDTH,
            margin: 'auto'
        }}>
            <NgForm
                value={consumer}
                flow={CONSUMER_FORM_SETTINGS.flow}
                schema={CONSUMER_FORM_SETTINGS.schema}
                onChange={newValue => setConsumer(newValue)} />
            <Button
                type="success"
                className="btn-sm ms-auto d-flex align-items-center"
                onClick={savePlan}
            >
                Create <VersionBadge size="xs" className="ms-2" />
            </Button>
        </div>
    </>
}

function ConsumerDesigner(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const [consumer, setConsumer] = useState()

    const { item, updateItem } = useDraftOfAPI()

    useEffect(() => {
        if (item && !consumer) {
            setConsumer(item.consumers.find(item => item.id === params.consumerId))
        }
    }, [item])

    const updatePlan = () => {
        return updateItem({
            ...item,
            consumers: item.consumers.map(item => {
                if (item.id === consumer.id)
                    return consumer
                return item
            })
        })
            .then(() => historyPush(history, location, `/apis/${params.apiId}`))
    }

    if (!item || !consumer)
        return <SimpleLoader />


    return <>
        <PageTitle title={`Update ${consumer?.name}`} {...props} style={{ paddingBottom: 0 }}>
            <FeedbackButton
                type="success"
                className="ms-2 mb-1 d-flex align-items-center"
                onPress={updatePlan}
                text={<>
                    Update <VersionBadge size="xs" className="ms-2" />
                </>}
            />
        </PageTitle>

        <div style={{
            maxWidth: MAX_WIDTH,
            margin: 'auto'
        }}>
            <NgForm
                value={consumer}
                flow={CONSUMER_FORM_SETTINGS.flow}
                schema={CONSUMER_FORM_SETTINGS.schema}
                onChange={newValue => setConsumer(newValue)} />
        </div>
    </>
}


function Routes(props) {
    const history = useHistory()
    const params = useParams()
    const location = useLocation()

    const columns = [
        {
            title: 'Name',
            filterId: 'name',
            content: (item) => item.name,
        },
        {
            title: 'Frontend',
            filterId: 'frontend.domains.0',
            cell: (item, a) => {
                return (
                    <>
                        {item.frontend.domains[0] || '-'}{' '}
                        {item.frontend.domains.length > 1 && (
                            <span
                                className="badge bg-secondary"
                                style={{ cursor: 'pointer' }}
                                title={item.frontend.domains.map((v) => ` - ${v}`).join('\n')}
                            >
                                {item.frontend.domains.length - 1} more
                            </span>
                        )}
                    </>
                );
            },
        },
    ];

    const { item, updateItem } = useDraftOfAPI()

    useEffect(() => {
        props.setTitle({
            value: 'Routes',
            noThumbtack: true,
            children: <VersionBadge />
        })

        return () => props.setTitle(undefined)
    }, [])

    const client = nextClient.forEntityNext(nextClient.ENTITIES.APIS)

    const deleteItem = newItem => {
        return updateItem({
            ...item,
            routes: item.routes.filter(f => f.id !== newItem.id)
        })
    }

    if (!item)
        return <SimpleLoader />

    return <Table
        parentProps={{ params }}
        navigateTo={(item) => historyPush(history, location, `/apis/${params.apiId}/routes/${item.id}/edit`)}
        navigateOnEdit={(item) => historyPush(history, location, `/apis/${params.apiId}/routes/${item.id}/edit`)}
        selfUrl="routes"
        defaultTitle="Route"
        itemName="Route"
        columns={columns}
        deleteItem={deleteItem}
        fetchTemplate={client.template}
        fetchItems={() => Promise.resolve(item.routes || [])}
        defaultSort="name"
        defaultSortDesc="true"
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${params.apiId}/routes/${i.id}/edit`)}
        rawEditUrl={true}

        injectTopBar={() => (
            <div className="btn-group input-group-btn">
                <Link className="btn btn-primary btn-sm"
                    to={{
                        pathname: "routes/new",
                        search: location.search
                    }}>
                    <i className="fas fa-plus-circle" /> Create new route
                </Link>
                {props.injectTopBar}
            </div>
        )} />
}

function Backends(props) {
    const history = useHistory()
    const params = useParams()
    const location = useLocation()

    const columns = [
        {
            title: 'Name',
            filterId: 'name',
            content: (item) => item.name,
        },
        {
            title: 'Backend',
            filterId: 'backend.targets.0.hostname',
            cell: (item) => {
                return (
                    <>
                        {item.backend.targets[0]?.hostname || '-'}{' '}
                        {item.backend.targets.length > 1 && (
                            <span
                                className="badge bg-secondary"
                                style={{ cursor: 'pointer' }}
                                title={item.backend.targets
                                    .map((v) => ` - ${v.tls ? 'https' : 'http'}://${v.hostname}:${v.port}`)
                                    .join('\n')}
                            >
                                {item.backend.targets.length - 1} more
                            </span>
                        )}
                    </>
                );
            }
        }
    ];

    const { item, updateItem } = useDraftOfAPI()

    useEffect(() => {
        props.setTitle({
            value: 'Backends',
            noThumbtack: true,
            children: <VersionBadge />
        })

        return () => props.setTitle('')
    }, [])

    const client = nextClient.forEntityNext(nextClient.ENTITIES.BACKENDS)

    const deleteItem = newItem => updateItem({
        ...item,
        backends: item.backends.filter(f => f.id !== newItem.id)
    })

    if (!item)
        return <SimpleLoader />

    return <Table
        parentProps={{ params }}
        navigateTo={(item) => historyPush(history, location, `/apis/${params.apiId}/backends/${item.id}/edit`)}
        navigateOnEdit={(item) => historyPush(history, location, `/apis/${params.apiId}/backends/${item.id}/edit`)}
        selfUrl="backends"
        defaultTitle="Backend"
        itemName="Backend"
        columns={columns}
        deleteItem={deleteItem}
        fetchTemplate={client.template}
        fetchItems={() => Promise.resolve(item.backends || [])}
        defaultSort="name"
        defaultSortDesc="true"
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${params.apiId}/backends/${i.id}/edit`)}
        rawEditUrl={true}
        hideEditButton={item => item.name === 'default_backend'}
        injectTopBar={() => (
            <div className="btn-group input-group-btn">
                <Link className="btn btn-primary btn-sm" to={{
                    pathname: "backends/new",
                    search: location.search
                }}>
                    <i className="fas fa-plus-circle" /> Create new backend
                </Link>
                {props.injectTopBar}
            </div>
        )} />
}

function NewBackend(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const [backend, setBackend] = useState()

    const { item, updateItem } = useDraftOfAPI()

    const saveBackend = () => {
        return updateItem({
            ...item,
            backends: [...item.backends, {
                ...backend,
                ...backend.backend
            }]
        })
            .then(() => historyPush(history, location, `/apis/${params.apiId}/backends`))
    }

    useQuery(["getTemplate"],
        nextClient.forEntityNext(nextClient.ENTITIES.BACKENDS).template, {
        retry: 0,
        onSuccess: (data) => setBackend({
            id: v4(),
            name: 'My new backend',
            backend: data.backend
        })
    });

    if (!backend || !item)
        return <SimpleLoader />

    return <>
        <PageTitle title="New Backend" {...props} style={{ paddingBottom: 0 }}>
            <FeedbackButton
                type="success"
                className="ms-2 mb-1 d-flex align-items-center"
                onPress={saveBackend}
                text={<>
                    Create <VersionBadge size="xs" />
                </>}
            />
        </PageTitle>

        <div style={{
            maxWidth: MAX_WIDTH,
            margin: 'auto'
        }}>
            <BackendForm
                state={{
                    form: {
                        schema: {
                            name: {
                                label: 'Name',
                                type: 'string',
                                placeholder: 'New backend'
                            },
                            backend: {
                                type: 'form',
                                schema: NgBackend.schema,
                                flow: NgBackend.flow
                            }
                        },
                        flow: ['name', 'backend'],
                        value: backend
                    }
                }}
                onChange={setBackend} />
        </div>
    </>
}

function EditBackend(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const { item, updateItem } = useDraftOfAPI()

    const [backend, setBackend] = useState()

    useEffect(() => {
        if (item && !backend) {
            setBackend(item.backends.find(item => item.id === params.backendId))
        }
    }, [item])

    const updateBackend = () => {
        return updateItem({
            ...item,
            backends: item.backends.map(item => {
                if (item.id === backend.id)
                    return backend
                return item
            })
        })
            .then(() => historyPush(history, location, `/apis/${params.apiId}/backends`))
    }

    if (!item)
        return <SimpleLoader />

    return <>
        <PageTitle title="Update Backend" {...props} style={{ paddingBottom: 0 }}>
            <FeedbackButton
                type="success"
                className="ms-2 mb-1 d-flex align-items-center"
                onPress={updateBackend}
                text={<>
                    Update <VersionBadge size="xs" />
                </>}
            />
        </PageTitle>

        <div style={{
            maxWidth: MAX_WIDTH,
            margin: 'auto'
        }}>
            <BackendForm
                state={{
                    form: {
                        schema: {
                            name: {
                                label: 'Name',
                                type: 'string',
                                placeholder: 'New backend'
                            },
                            backend: {
                                type: 'form',
                                schema: NgBackend.schema,
                                flow: NgBackend.flow
                            }
                        },
                        flow: ['name', 'backend'],
                        value: backend
                    }
                }}
                onChange={setBackend} />
        </div>
    </>
}

function TestingConfiguration(props) {
    return <div className="row mb-3">
        <label className="col-xs-12 col-sm-2 col-form-label" style={{ textAlign: 'right' }}>
            Configuration
        </label>
        <div className="col-sm-10">
            Enable testing on your API to allow you to call all enabled routes. You just need to pass the following specific header when making the calls. This security measure, enforced by Otoroshi, prevents unauthorized users from accessing your draft API.
            <div className='d-flex flex-column gap-2 mt-3'>
                <input className="form-control" readOnly type="text" value={props.rootValue?.headerKey} />
                <div className="input-group">
                    <input className="form-control" disabled type="text" value={props.rootValue?.headerValue} />

                    <span
                        className="input-group-text"
                        style={{ cursor: 'pointer' }}
                        title="copy bearer"
                        onClick={() => {
                            props.onSecretRotation({
                                ...props.item.testing,
                                headerValue: v4()
                            })
                        }}
                    >
                        <i className='fas fa-rotate' />
                    </span>
                </div>
            </div>
        </div>
    </div>
}

function Testing(props) {
    const { item, version, updateItem } = useDraftOfAPI()

    useEffect(() => {
        props.setTitle('Testing mode')

        return () => props.setTitle(undefined)
    }, [])

    if (!item)
        return <SimpleLoader />

    const schema = {
        enabled: {
            type: 'box-bool',
            label: 'Enabled',
            props: {
                description: 'When enabled, this option allows draft routes to be exposed. These routes can be accessed using a specific header, ensuring they remain available only for testing purposes.',
            },
        },
        config: {
            renderer: props => <TestingConfiguration {...props} item={item} onSecretRotation={testing => {
                updateItem({
                    ...item,
                    testing
                })
            }} />
        },
        routes: {
            renderer: () => {
                return <div className="row mb-3">
                    <label className="col-xs-12 col-sm-2 col-form-label" style={{ textAlign: 'right' }}>
                        Configuration
                    </label>
                    <div className="col-sm-10">
                        <div className='relative'>
                            <RoutesView api={item} />
                        </div>
                    </div>
                </div>
            }
        }
    }

    let flow = [
        "enabled",
        "config",
    ]

    if (item.testing.enabled)
        flow = [...flow, "routes"]

    if (version === 'Published')
        return <div className='alert alert-warning'>
            Testing mode is only available in the draft version.
        </div>

    if (item.consumers.length === 0)
        return <>
            <div className='alert alert-secondary' role="alert" style={{
                background: 'var(--bg-color_level2)',
                borderColor: 'var(--bg-color_level2)'
            }}>
                <p style={{ color: 'var(--text)' }}>
                    Testing mode can't be enabled until you have created consumers.
                </p>
                <hr />
                <ConsumerCard item={item} />
            </div>
        </>

    return <>
        <div style={{
            maxWidth: MAX_WIDTH,
            margin: 'auto'
        }}>
            <NgForm
                value={item.testing}
                onChange={testing => {
                    if (testing)
                        updateItem({
                            ...item,
                            testing
                        })
                }}
                schema={schema}
                flow={flow}
            />
            {/* <FeedbackButton
                type="success"
                className="d-flex mt-3 ms-auto"
                onPress={() => updateItem()}
                text={<div className='d-flex align-items-center'>
                    Update <VersionBadge size="xs" />
                </div>}
            /> */}
        </div>
    </>
}

function Deployments(props) {
    const params = useParams()

    const columns = [
        {
            title: 'Version',
            filterId: 'version',
            content: (item) => item.version,
        },
        {
            title: 'Deployed At',
            filterId: 'at',
            content: (item) => moment(item.at).format('YYYY-MM-DD HH:mm:ss.SSS')
        },
        {
            title: 'Owner',
            filterId: 'owner',
            content: (item) => item.owner
        },
    ];

    const { item } = useDraftOfAPI()

    useEffect(() => {
        props.setTitle({
            value: 'Deployments',
            noThumbtack: true,
            children: <VersionBadge />
        })
        return () => props.setTitle(undefined)
    }, [])

    if (!item)
        return <SimpleLoader />

    return <Table
        navigateTo={item =>
            window.wizard('Version', () => <PublisDraftModalContent
                draft={item}
                currentItem={item} />, {
                noCancel: true,
                okLabel: 'Close'
            })
        }
        parentProps={{ params }}
        selfUrl="deployments"
        defaultTitle="Deployment"
        itemName="Deployment"
        columns={columns}
        fetchTemplate={() => Promise.resolve({})}
        fetchItems={() => Promise.resolve(item.deployments || [])}
        defaultSort="version"
        defaultSortDesc="true"
        showActions={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
    />
}

function SidebarWithVersion({ params, state }) {
    const queryParams = new URLSearchParams(window.location.search)
    const queryVersion = state === 'staging' ? 'staging' : (queryParams.get('version') ? queryParams.get('version') : 'Published')

    useEffect(() => {
        if (queryVersion) {
            updateQueryParams(queryVersion)
            updateSignal(queryVersion)
        }
    }, [queryVersion])

    const updateSignal = version => {
        signalVersion.value = version
    }

    const updateQueryParams = version => {
        const queryParams = new URLSearchParams(window.location.search);
        queryParams.set("version", version);
        history.replaceState(null, null, "?" + queryParams.toString());
    }

    return <Sidebar params={params} />
}

function SidebarComponent(props) {
    const params = useParams()
    const location = useLocation()

    const [state, setState] = useState()

    useQuery(["getAPI", params.apiId],
        () => nextClient.forEntityNext(nextClient.ENTITIES.APIS).findById(params.apiId), {
        onSuccess: api => {
            setState(api.state)
        }
    })

    useEffect(() => {
        if (location.pathname !== '/apis') {
            props.setSidebarContent(<SidebarWithVersion params={params} state={state} />);
        }
        return () => props.setSidebarContent(null)
    }, [params, state])

    return null
}

function EditFlow(props) {
    const history = useHistory()
    const params = useParams()
    const location = useLocation()

    const { item, updateItem } = useDraftOfAPI()

    const [flow, setFlow] = useState()

    useEffect(() => {
        if (item && !flow) {
            const currentFlow = item.flows.find(flow => flow.id === params.flowId)

            if (currentFlow) {
                props.setTitle({
                    value: `Update ${currentFlow.name}`,
                    noThumbtack: true,
                    children: <VersionBadge />
                })

                setFlow({
                    ...currentFlow,
                    consumers: item.consumers.reduce((acc, item) => ({
                        ...acc,
                        [item.id]: currentFlow.consumers.includes(item.id)
                    }), {})
                })
            }
        }
        return () => props.setTitle(undefined)
    }, [item])

    const updateFlow = () => {
        return updateItem({
            ...item,
            flows: item.flows.map(ite => {
                if (ite.id === params.flowId) {
                    return {
                        ...flow,
                        consumers: Object.entries(flow.consumers).filter(f => f[1]).map(f => f[0])
                    }
                } else {
                    return ite
                }
            })
        })
            .then(() => historyPush(history, location, `/apis/${params.apiId}/flows`));
    }

    if (!item)
        return <SimpleLoader />

    return <div style={{
        maxWidth: MAX_WIDTH,
        margin: 'auto'
    }}>
        <NgForm
            schema={FLOW_FORM_SETTINGS.schema(item)}
            flow={FLOW_FORM_SETTINGS.flow}
            value={flow}
            onChange={setFlow}
        />
        <Button
            type="success"
            className="btn-sm ms-auto d-flex align-items-center"
            onClick={updateFlow}
        >
            Update <VersionBadge size="xs" className="ms-2" />
        </Button>
    </div>
}

const FLOW_FORM_SETTINGS = {
    schema: item => ({
        name: {
            type: 'string',
            props: { label: 'Name' },
        },
        consumers: {
            type: 'form',
            label: 'Enabled consumers',
            schema: item.consumers.reduce((acc, item) => ({
                ...acc,
                [item.id]: {
                    type: 'box-bool',
                    label: item.name,
                    props: {
                        description: item.description || item.consumer_kind
                    }
                }
            }), {}),
        }
    }),
    flow: [
        {
            type: 'group',
            name: 'Informations',
            collapsable: false,
            fields: ["name"]
        },
        'consumers'
    ]
}

function NewFlow(props) {
    const history = useHistory()
    const params = useParams()
    const location = useLocation()

    useEffect(() => {
        props.setTitle({
            value: "Create a new Flow",
            noThumbtack: true,
            children: <VersionBadge />
        })

        return () => props.setTitle(undefined)
    }, [])

    const [flow, setFlow] = useState({
        id: v4(),
        name: 'New flow name',
        plugins: [],
        consumers: []
    })

    const { item, updateItem } = useDraftOfAPI()

    const createFlow = () => {
        return updateItem({
            ...item,
            flows: [...item.flows, {
                ...flow,
                consumers: Object.entries(flow.consumers).filter(f => f[1]).map(f => f[0])
            }]
        })
            .then(() => historyPush(history, location, `/apis/${params.apiId}/flows/${flow.id}/designer`));
    }

    if (!item)
        return <SimpleLoader />

    return <div style={{
        maxWidth: MAX_WIDTH,
        margin: 'auto'
    }}>
        <NgForm
            schema={FLOW_FORM_SETTINGS.schema(item)}
            flow={FLOW_FORM_SETTINGS.flow}
            value={flow}
            onChange={setFlow}
        />
        <Button
            type="success"
            className="btn-sm ms-auto d-flex align-items-center"
            onClick={createFlow}
        >
            Create <VersionBadge size="xs" className="ms-2" />
        </Button>
    </div>
}

function NewAPI(props) {
    const history = useHistory()
    const location = useLocation()
    const params = useParams()

    useEffect(() => {
        props.setTitle({
            value: "Create a new API",
            noThumbtack: true,
            children: <VersionBadge />
        })
        return () => props.setTitle(undefined)
    }, [])

    const [value, setValue] = useState({})

    useQuery(["getTemplate"],
        nextClient.forEntityNext(nextClient.ENTITIES.APIS).template, {
        retry: 0,
        onSuccess: (data) => setValue({
            ...data,
            id: params.apiId
        })
    })

    const schema = {
        location: {
            type: 'location',
            props: {},
        },
        id: { type: 'string', disabled: true, props: { label: 'id', placeholder: '---' } },
        name: {
            type: 'string',
            props: { label: 'Name' },
        },
        description: {
            type: 'string',
            props: { label: 'Description' },
        },
        metadata: {
            type: 'object',
            props: { label: 'metadata' },
        },
        tags: {
            type: 'array',
            props: { label: 'tags' },
        },
        capture: {
            type: 'bool',
            label: 'Capture route traffic',
            props: {
                labelColumn: 3,
            },
        },
        debug_flow: {
            type: 'bool',
            label: 'Debug the route',
            props: {
                labelColumn: 3,
            },
        },
        export_reporting: {
            type: 'bool',
            label: 'Export reporting',
            props: {
                labelColumn: 3,
            },
        },
    }

    const flow = ['location', 'id', 'name', 'description']

    const createApi = () => {
        nextClient.forEntityNext(nextClient.ENTITIES.APIS)
            .create(value)
            .then(() => historyPush(history, location, `/apis/${value.id}`));
    }

    if (!value || !item)
        return <SimpleLoader />

    return <>
        <Form
            schema={schema}
            flow={flow}
            value={value}
            onChange={setValue}
        />
        <Button
            type="success"
            className="btn-sm ms-auto d-flex"
            onClick={createApi}
            text="Create"
        />
    </>
}

function Apis(props) {
    const ref = useRef()
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    useEffect(() => {
        props.setTitle('Apis')
        return () => props.setTitle(undefined)
    }, [])

    const columns = [
        {
            title: 'Name',
            content: item => item.name
        },
        {
            title: 'Description',
            content: item => item.description
        },
        {
            title: 'Enabled',
            id: 'enabled',
            style: { textAlign: 'center', width: 90 },
            notFilterable: true,
            cell: (enabled) =>
                enabled ? <span className="fas fa-check-circle" style={{ color: 'var(--color-green)' }} />
                    : <span className="fas fa-times" style={{ color: 'var(--color-red)' }} />
        },
        {
            title: 'State',
            content: item => item.state,
            notFilterable: true,
            cell: (value) => <APIState value={value} />
        },
    ];

    const fetchItems = (paginationState) => nextClient
        .forEntityNext(nextClient.ENTITIES.APIS)
        .findAllWithPagination(paginationState)

    const fetchTemplate = () => nextClient
        .forEntityNext(nextClient.ENTITIES.APIS)
        .template()

    return <>

        <Table
            ref={ref}
            parentProps={{ params }}
            navigateTo={(item) => historyPush(history, location, `/apis/${item.id}`)}
            navigateOnEdit={(item) => historyPush(history, location, `/apis/${item.id}`)}
            selfUrl="apis"
            defaultTitle="Api"
            itemName="Api"
            formSchema={null}
            formFlow={null}
            columns={columns}
            deleteItem={(item) => nextClient
                .forEntityNext(nextClient.ENTITIES.APIS).deleteById(item.id)
            }
            defaultSort="name"
            defaultSortDesc="true"
            fetchItems={fetchItems}
            fetchTemplate={fetchTemplate}
            showActions={true}
            showLink={false}
            extractKey={(item) => item.id}
            rowNavigation={true}
            hideAddItemAction={true}
            itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${i.id}`)}
            rawEditUrl={true}
            injectTopBar={() => (
                <div className="btn-group input-group-btn">
                    <Link
                        className="btn btn-primary btn-sm"
                        onClick={() => {
                            nextClient.forEntityNext(nextClient.ENTITIES.APIS)
                                .template()
                                .then(api => {
                                    history.push(`apis/${api.id}/new?version=staging`)
                                })
                        }}>
                        <i className="fas fa-plus-circle" /> Create new API
                    </Link>
                    {props.injectTopBar}
                </div>
            )} />
    </>
}

function FlowDesigner(props) {
    const history = useHistory()
    const params = useParams()

    const isCreation = params.action === 'new';

    const { item, updateItem } = useDraftOfAPI()

    const [flow, setFlow] = useState()
    const ref = useRef(flow)

    useEffect(() => {
        ref.current = flow;
    }, [flow])

    useEffect(() => {
        if (item && !flow) {
            setFlow(item.flows.find(flow => flow.id === params.flowId))

            dynamicTitleContent.value = (
                <PageTitle
                    style={{
                        paddingBottom: 0,
                    }}
                    title={item.flows.find(flow => flow.id === params.flowId)?.name}
                    {...props}
                >
                    <FeedbackButton
                        type="success"
                        className="ms-2 mb-1 d-flex align-items-center"
                        onPress={saveFlow}
                        text={<>
                            {isCreation ? 'Create a new flow' : 'Save'} <VersionBadge size="xs" className="ms-2" />
                        </>}
                    />
                </PageTitle>
            );
        }
    }, [item])

    const saveFlow = () => {
        const {
            id, name, plugins
        } = ref.current.value

        return updateItem({
            ...item,
            flows: item.flows.map(flow => {
                if (flow.id === id)
                    return {
                        id, name, plugins
                    }
                return flow
            })
        })
            .then(() => history.replace(`/apis/${params.apiId}/flows`))
    }

    if (!item || !flow)
        return <SimpleLoader />

    return <div className='designer'>
        <Designer
            history={history}
            value={flow}
            setValue={value => setFlow({ value })}
            setSaveButton={() => { }}
        />
    </div>
}

function historyPush(history, location, link) {
    history.push({
        pathname: link,
        search: location.search
    })
}

function linkWithQuery(link) {
    return `${link}${window.location.search}`
}

function Flows(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const { item, updateItem } = useDraftOfAPI()

    const columns = [
        {
            title: 'Name',
            content: item => item.name
        },
        {
            title: 'Designer',
            style: {
                textAlign: 'center',
                width: 90
            },
            notFilterable: true,
            cell: (_, item) => {
                if (item.name === 'default_flow')
                    return null

                return <Button className="btn-sm" onClick={() => {
                    historyPush(history, location, `/apis/${params.apiId}/flows/${item.id}/designer`)
                }}>
                    <i className='fas fa-pencil-ruler' />
                </Button>
            }
        }
    ];

    useEffect(() => {
        props.setTitle({
            value: 'Flows',
            noThumbtack: true,
            children: <VersionBadge />
        })

        return () => props.setTitle(undefined)
    }, [])

    const fetchItems = (_) => Promise.resolve(item.flows)

    const fetchTemplate = () => Promise.resolve({
        id: uuid(),
        name: 'My new flow',
        plugins: []
    })

    const deleteItem = deletedFlow => {
        return updateItem({
            ...item,
            flows: item.flows.filter(flow => flow.id !== deletedFlow.id)
        })
    }

    if (!item)
        return <SimpleLoader />

    return <Table
        parentProps={{ params }}
        navigateTo={(item) => historyPush(history, location, `/apis/${params.apiId}/flows/${item.id}/edit`)}
        navigateOnEdit={(item) => historyPush(history, location, `/apis/${params.apiId}/flows/${item.id}/edit`)}
        selfUrl={`/apis/${params.apiId}/flows`}
        defaultTitle="Flow"
        itemName="Flow"
        columns={columns}
        deleteItem={deleteItem}
        defaultSort="name"
        defaultSortDesc="true"
        fetchItems={fetchItems}
        fetchTemplate={fetchTemplate}
        showActions={true}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${params.apiId}/flows/${i.id}`)}
        rawEditUrl={true}
        hideEditButton={(item) => item.name == 'default_flow'}
        injectTopBar={() => (
            <div className="btn-group input-group-btn">
                <Link className="btn btn-primary btn-sm" to={{
                    pathname: "flows/new",
                    search: location.search
                }}>
                    <i className="fas fa-plus-circle" /> Create new Flow
                </Link>
                {props.injectTopBar}
            </div>
        )}
    />
}

function VersionManager({ api, draft, owner, setState }) {

    const [deployment, setDeployment] = useState({
        location: {},
        apiRef: api.id,
        owner,
        at: Date.now(),
        apiDefinition: {
            ...draft.content,
            deployments: []
        },
        draftId: draft.id,
        action: 'patch',
        version: semver.inc(api.version, 'patch')
    })

    const getCompareStep = (field) => ({
        renderer: () => {
            return <PublisDraftModalContent
                draft={draft.content[field]}
                currentItem={api[field]} />
        }
    })

    const schema = {
        location: {
            type: 'location'
        },
        version: {
            type: 'string',
            label: 'Version'
        },
        action: {
            renderer: (props) => {
                const version = props.rootValue?.version

                const nextVersions = {
                    [semver.inc(api.version, 'patch')]: 'patch',
                    [semver.inc(api.version, 'minor')]: 'minor',
                    [semver.inc(api.version, 'major')]: 'major'
                }

                return <div>
                    <NgDotsRenderer
                        value={nextVersions[version]}
                        options={['patch', 'minor', 'major']}
                        schema={{
                            props: {
                                label: 'Action'
                            }
                        }}
                        onChange={action => {
                            if (action === 'patch') {
                                props.rootOnChange({
                                    ...props.rootValue,
                                    version: semver.inc(api.version, 'patch')
                                })
                            } else if (action === 'minor') {
                                props.rootOnChange({
                                    ...props.rootValue,
                                    version: semver.inc(api.version, 'minor')
                                })
                            } else {
                                props.rootOnChange({
                                    ...props.rootValue,
                                    version: semver.inc(api.version, 'major')
                                })
                            }
                        }}
                    />
                </div>
            }
        },
        apiRef: {
            type: 'string',
            props: {
                readOnly: true
            }
        },
        owner: {
            type: 'string',
            label: 'Owner'
        },
        at: {
            type: 'datetime'
        },
        routes: getCompareStep('routes'),
        flows: getCompareStep('flows'),
        backends: getCompareStep('flows'),
        consumers: getCompareStep('consumers'),
        subscriptions: getCompareStep('subscriptions'),
        deployments: getCompareStep('deployments'),
        apiDefinition: {
            renderer: () => {
                return <PublisDraftModalContent
                    draft={draft.content}
                    currentItem={api} />
            }
        }
    }

    const getCompareFlowGroup = name => ({
        type: 'group',
        name: `${name} ${!mergeData(api[name], draft.content[name]).changed ? '' : `: has changed`}`,
        collapsed: true,
        fields: [name],
    })

    const flow = [
        {
            type: 'group',
            name: 'Informations',
            collapsable: false,
            fields: ['version', 'action', 'owner']
        },
        getCompareFlowGroup('routes'),
        getCompareFlowGroup('flows'),
        getCompareFlowGroup('backends'),
        getCompareFlowGroup('consumers'),
        getCompareFlowGroup('subscriptions'),
        getCompareFlowGroup('deployments'),
        {
            type: 'group',
            name: `Global: ${!mergeData(api[name], draft.content[name]) ? 'No changes' : `has changed`}`,
            collapsed: true,
            fields: ['apiDefinition'],
        }
    ]

    return <div className='d-flex flex-column flex-grow gap-3' style={{ maxWidth: 820 }}>
        <NgForm
            value={deployment}
            onChange={data => {
                setDeployment(data)
                setState(data)
            }}
            schema={schema}
            flow={flow} />
        {/* } */}
    </div>
}

function Informations(props) {
    const history = useHistory()
    const location = useLocation()

    const { item, setItem, updateItem } = useDraftOfAPI()

    const schema = {
        location: {
            type: 'location',
            props: {},
        },
        id: { type: 'string', disabled: true, props: { label: 'id', placeholder: '---' } },
        name: {
            type: 'string',
            props: { label: 'Name' },
        },
        description: {
            type: 'string',
            props: { label: 'Description' },
        },
        metadata: {
            type: 'object',
            label: 'Metadata'
        },
        tags: {
            type: 'array',
            label: 'Tags'
        },
        capture: {
            type: 'bool',
            label: 'Capture route traffic',
            props: {
                labelColumn: 3,
            },
        },
        debug_flow: {
            type: 'bool',
            label: 'Debug the route',
            props: {
                labelColumn: 3,
            },
        },
        export_reporting: {
            type: 'bool',
            label: 'Export reporting',
            props: {
                labelColumn: 3,
            },
        },
        danger_zone: {
            renderer: (inputProps) => {
                return (
                    <div className="row mb-3">
                        <label className="col-xs-12 col-sm-2 col-form-label" style={{ textAlign: 'right' }}>
                            Delete this API
                        </label>
                        <div className="col-sm-10">
                            <div style={{ display: 'flex', flexDirection: 'column' }}>
                                <p>Once you delete an API, there is no going back. Please be certain.</p>
                                <Button
                                    style={{ width: 'fit-content' }}
                                    disabled={inputProps.rootValue?.id === props.globalEnv.adminApiId} // TODO
                                    type="danger"
                                    onClick={() => {
                                        window
                                            .newConfirm('Are you sure you want to delete this entity ?')
                                            .then((ok) => {
                                                if (ok) {
                                                    nextClient
                                                        .forEntityNext(nextClient.ENTITIES.APIS)
                                                        .deleteById(inputProps.rootValue?.id)
                                                        .then(() => {
                                                            historyPush(history, location, '/');
                                                        });
                                                }
                                            });
                                    }}
                                >
                                    Delete this API
                                </Button>
                            </div>
                        </div>
                    </div>
                );
            },
        },
    }
    const flow = ['location',
        {
            type: 'group',
            name: 'Route',
            fields: [
                'name',
                'description',
            ],
        },
        {
            type: 'group',
            name: 'Misc.',
            collapsed: true,
            fields: ['tags', 'metadata',
                {
                    type: 'grid',
                    name: 'Flags',
                    fields: ['debug_flow', 'export_reporting', 'capture'],
                }
            ],
        },
        {
            type: 'group',
            name: 'Danger zone',
            collapsed: true,
            fields: ['danger_zone'],
        },]

    const updateAPI = () => {
        updateItem()
            .then(() => historyPush(history, location, `/apis/${item.id}`));
    }

    useEffect(() => {
        if (item) {
            props.setTitle({
                value: 'Informations',
                noThumbtack: true,
                children: <VersionBadge />
            })

            return () => props.setTitle(undefined)
        }
    }, [item])

    if (!item)
        return <SimpleLoader />

    return <>
        <NgForm
            schema={schema}
            flow={flow}
            value={item}
            onChange={setItem}
        />
        <Button
            type="success"
            className="btn-sm ms-auto d-flex align-items-center"
            onClick={updateAPI}>
            Update <VersionBadge size="xs" className="ms-2" />
        </Button>
    </>
}

function VersionBadge({ size, className }) {
    const version = useSignalValue(signalVersion)
    return <div className={className ? className : 'm-0 ms-2'} style={{ fontSize: size === 'xs' ? '.75rem' : '1rem' }}>
        <span className={`badge bg-xs ${version === 'Published' ? 'bg-danger' : 'bg-warning'}`}>
            {version === 'Published' ? 'PROD' : 'DEV'}
        </span>
    </div>
}

function DashboardTitle({ api, draftWrapper, draft, step, ...props }) {
    const version = useSignalValue(signalVersion)

    console.log(step)

    return <div className="page-header_title d-flex align-item-center justify-content-between mb-3">
        <div className="d-flex">
            <h3 className="m-0 d-flex align-items-center">{api?.name}
                <VersionBadge />
            </h3>
        </div>
        <div className="d-flex align-item-center justify-content-between">
            {(version !== 'Published' && step > 3) &&
                <div className='d-flex align-items-center'>
                    <Button
                        text="Publish new version"
                        className="btn-sm mx-2"
                        type="primaryColor"
                        style={{
                            borderColor: 'var(--color-primary)',
                        }}
                        onClick={() => {
                            nextClient
                                .forEntityNext(nextClient.ENTITIES.APIS)
                                .findById(props.params.apiId)
                                .then(api => {
                                    window
                                        .wizard('Version manager', (ok, cancel, state, setState) => {
                                            return <VersionManager
                                                api={api}
                                                draft={draftWrapper}
                                                owner={props.globalEnv.user}
                                                setState={setState} />
                                        }, {
                                            style: { width: '100%' },
                                            noCancel: false,
                                            okClassName: 'ms-2',
                                            okLabel: 'I want to publish this API',
                                        })
                                        .then(deployment => {
                                            if (deployment) {
                                                fetchWrapperNext(`/${nextClient.ENTITIES.APIS}/${api.id}/deployments`, 'POST', deployment, 'apis.otoroshi.io')
                                                    .then(res => {
                                                        console.log(res)
                                                    })
                                            }
                                        })
                                })
                        }}
                    />
                    <Button
                        text="Reset draft"
                        className="btn-sm"
                        type="danger"
                        onClick={() => {
                            window.newConfirm('Are you sure you reset the draft content to match the published version? All your modifications will be discarded.')
                                .then((ok) => {
                                    if (ok) {
                                        nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS)
                                            .deleteById(draftWrapper.id)
                                            .then(() => window.location.reload())
                                    }
                                })
                        }}
                    />
                </div>}
        </div>
    </div>
}

function Dashboard(props) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const { item, draft, draftWrapper, version, api } = useDraftOfAPI()

    const hasCreateFlow = item && item.flows.filter(f => f.name !== 'default_flow').length > 0
    const hasCreateBackend = item && item.backends.filter(f => f.name !== 'default_backend').length > 0
    const hasCreateRoute = item && item.routes.length > 0
    const hasCreateConsumer = item && item.consumers.length > 0
    const hasTestingEnabled = item && item.testing.enabled

    const isStaging = item && item.state === API_STATE.STAGING
    const showGettingStarted = !hasCreateFlow || !hasCreateConsumer || !hasCreateRoute || isStaging

    const getStep = () => {
        return Number(hasCreateFlow) +
            Number(hasCreateRoute) +
            Number(hasCreateBackend) +
            Number(hasCreateConsumer) +
            Number(hasTestingEnabled) +
            Number(item?.state === API_STATE.PUBLISHED)
    }

    useEffect(() => {
        if (draft && api) {
            props.setTitle(<DashboardTitle {...props}
                params={params}
                api={api}
                draftWrapper={draftWrapper}
                draft={draft}
                step={getStep()} />)
        }

        return () => props.setTitle(undefined)
    }, [api, draft])

    if (!draft || !item)
        return <SimpleLoader />

    const currentStep = getStep()

    return <div className='d-flex flex-column gap-3 pb-10' style={{ maxWidth: 1280 }}>
        {showGettingStarted && <ProgressCard step={currentStep}>

            {!hasCreateConsumer && hasCreateRoute && <ObjectiveCard
                to={`/apis/${params.apiId}/consumers/new`}
                title="Create a consumer"
                description={<p className='objective-link'>Consumers apply security measures to the API</p>}
                icon={<i className='fas fa-list' />}
            />}

            {!hasCreateRoute && <ObjectiveCard
                to={`/apis/${params.apiId}/routes/new`}
                title="Your own route"
                description={<p className='objective-link'>Create a new Route</p>}
                icon={<i className='fas fa-road' />}
            />}

            {hasCreateRoute && !hasCreateBackend && currentStep >= 4 && <ObjectiveCard
                to={`/apis/${params.apiId}/backends/new`}
                title="Your own backend"
                description={<p className='objective-link'>Create a new Backend</p>}
                icon={<i className='fas fa-road' />}
            />}

            {hasCreateRoute && hasCreateConsumer && !item.testing.enabled && <ObjectiveCard
                to={`/apis/${params.apiId}/testing`}
                title="Test your API"
                description={<p className='objective-link'>Learn about testing API</p>}
                icon={<i className='fas fa-road' />}
            />}

            {!hasCreateFlow && item.state === API_STATE.PUBLISHED && <ObjectiveCard
                to={`/apis/${params.apiId}/flows/new`}
                title="Create a flow"
                description={<p className='objective-link'>
                    Create group of plugins to apply rules
                </p>}
                icon={<i className='fas fa-project-diagram' />}
            />}

            {currentStep >= 3 && item?.state !== API_STATE.PUBLISHED && <ObjectiveCard
                onClick={() => publishAPI(item)}
                title="Deploy your API"
                description={<p className='objective-link'>
                    Publish your API to the production
                </p>}
                icon={<i className='fas fa-rocket' />}
            />}
        </ProgressCard>}
        {item && <>
            <div className='d-flex gap-3'>
                <div className='d-flex flex-column flex-grow gap-3' style={{ flex: 1 }}>
                    <ContainerBlock full highlighted>
                        <APIHeader api={item} version={version} draft={draft} />

                        <ApiStats url={version === 'Published' ?
                            `/bo/api/proxy/apis/apis.otoroshi.io/v1/apis/${item.id}/live?every=2000` :
                            `/bo/api/proxy/apis/proxy.otoroshi.io/v1/drafts/${item.id}/live?every=2000`
                        } />

                        <Uptime
                            health={item.health?.today}
                            stopTheCountUnknownStatus={false}
                        />
                        <Uptime
                            health={item.health?.yesterday}
                            stopTheCountUnknownStatus={false}
                        />
                        <Uptime
                            health={item.health?.nMinus2}
                            stopTheCountUnknownStatus={false}
                        />
                    </ContainerBlock>

                    {hasCreateRoute && hasCreateConsumer && <ContainerBlock style={{
                        width: 'initial'
                    }}>
                        <SectionHeader text="Routes" description="This API exposes the following routes" />
                        <RoutesView api={item} />
                    </ContainerBlock>}

                    {hasCreateConsumer && <ContainerBlock style={{
                        width: 'initial'
                    }}>
                        <SectionHeader
                            text="Subscriptions"
                            description={item.consumers.flatMap(c => c.subscriptions).length <= 0 ? 'Souscriptions will appear here' : ''}
                            actions={<Button
                                type="primaryColor"
                                text="Subscribe"
                                className='btn-sm'
                                onClick={() => historyPush(history, location, `/apis/${params.apiId}/subscriptions/new`)} />} />

                        <SubscriptionsView api={item} />
                    </ContainerBlock>}

                    {hasCreateConsumer && <ContainerBlock style={{
                        width: 'initial'
                    }}>
                        <SectionHeader text="Consumers"
                            description={item.consumers.length <= 0 ? 'API consumers will appear here' : ''}
                            actions={<Button
                                type="primaryColor"
                                text="New Consumer"
                                className='btn-sm'
                                onClick={() => historyPush(history, location, `/apis/${params.apiId}/consumers/new`)} />} />
                        <ApiConsumersView api={item} />
                    </ContainerBlock>}
                </div>
                {item.flows.length > 0 && item.routes.length > 0 && <ContainerBlock style={{
                    // flex: .5
                }}>
                    <SectionHeader text="Build your API" description="Manage entities for this API" />
                    <Entities>
                        <FlowsCard flows={item.flows} />
                        <BackendsCard backends={item.backends} />
                        <RoutesCard routes={item.routes} />
                    </Entities>
                </ContainerBlock>}
            </div>
        </>}
    </div>
}

function ApiConsumersView({ api }) {
    return <div>
        <div className='short-table-row' style={{
            gridTemplateColumns: 'repeat(3, 1fr) 54px 32px'
        }}>
            <div>Name</div>
            <div>Description</div>
            <div>Status</div>
            <div>Kind</div>
        </div>
        {api.consumers.map(consumer => {
            return <Consumer key={consumer.id} consumer={consumer} />
        })}
    </div>
}

function Consumer({ consumer }) {
    const history = useHistory()
    const params = useParams()
    const location = useLocation()
    const [open, setOpen] = useState(false)

    const CONSUMER_STATUS_COLORS = {
        staging: 'info',
        published: 'success',
        deprecated: 'warning',
        closed: 'danger',
    }

    return <div className='short-table-row'
        style={{
            backgroundColor: 'hsla(184, 9%, 62%, 0.18)',
            borderColor: 'hsla(184, 9%, 62%, 0.4)',
            borderRadius: '.5rem',
            gridTemplateColumns: open ? '1fr' : 'repeat(3, 1fr) 54px 32px'
        }}
        onClick={() => {
            if (!open)
                setOpen(true)
        }}>
        {open && <div className="d-flex justify-content-between gap-2 align-items-center">
            <div style={{ position: 'relative', flex: 1 }}>
                <Button type="primaryColor" className="btn-sm" text="Edit"
                    onClick={e => {
                        e.stopPropagation()
                        historyPush(history, location, `/apis/${params.apiId}/consumers/${consumer.id}/edit`)
                    }} style={{
                        position: 'absolute',
                        top: '.5rem',
                        right: '.5rem',
                        zIndex: 100
                    }} />
                <JsonObjectAsCodeInput
                    editorOnly
                    showGutter={false}
                    label={undefined}
                    value={consumer} />
            </div>
            <i style={{ minWidth: 40 }} className="fas fa-chevron-up fa-lg short-table-navigate-icon" onClick={() => setOpen(false)} />
        </div>}
        {!open && <>
            <div>{consumer.name}</div>
            <div>{consumer.description}</div>
            <div className={`badge custom-badge bg-${CONSUMER_STATUS_COLORS[consumer.status]}`} style={{
                width: 'fit-content',
                border: 'none'
            }}>{consumer.status}</div>
            <div className="badge custom-badge bg-success" style={{
                border: 'none'
            }}>{consumer.consumer_kind}</div>
            <i className="fas fa-chevron-right fa-lg short-table-navigate-icon" />
        </>}
    </div>
}

function RouteItem({ item, api, ports }) {
    const { frontend } = item

    const params = useParams()
    const location = useLocation()
    const history = useHistory()

    const version = useSignalValue(signalVersion)

    const routeEntries = (idx) => {
        const isSecured = api.flows.some(r => r.plugins.find((p) => p.plugin.includes('ForceHttpsTraffic')));

        const domain = item.frontend.domains[idx];

        const domainParts = domain.split('/');
        const hasPath = domainParts.length > 1;

        if (isSecured)
            return `https://${domainParts[0]}:${ports.https}${hasPath ? '/' : ''}${domainParts.slice(1).join('/')}`;

        return `http://${domainParts[0]}:${ports.http}${hasPath ? '/' : ''}${domainParts.slice(1).join('/')}`;
    }

    const rawMethods = (frontend.methods || []).filter((m) => m.length)

    const allMethods =
        rawMethods && rawMethods.length > 0
            ? rawMethods.map((m, i) => (
                <span
                    key={`frontendmethod-${i}`}
                    className={`badge me-1`}
                    style={{ backgroundColor: HTTP_COLORS[m] }}
                >
                    {m}
                </span>
            ))
            : [<span className="badge bg-success">ALL</span>]

    const copy = (value, method, setCopyIconName) => {
        let command = value

        if (version === 'Draft' || version === 'staging') {
            command = `curl ${method ? `-X ${method}` : ''} ${value} -H '${api.testing?.headerKey}: ${api.testing?.headerValue}'`
        } else {
            command = `curl ${method ? `-X ${method}` : ''} ${value}`
        }

        if (window.isSecureContext && navigator.clipboard) {
            navigator.clipboard.writeText(command)
        } else {
            unsecuredCopyToClipboard(command)
        }
        setCopyIconName('fas fa-check')

        setTimeout(() => {
            setCopyIconName('fas fa-copy')
        }, 2000)
    }

    const goTo = (idx) => window.open(routeEntries(idx), '_blank')

    return frontend.domains.map((domain, idx) => {
        const [copyIconName, setCopyIconName] = useState('fas fa-copy')
        const exact = frontend.exact
        const end = exact ? '' : domain.indexOf('/') < 0 ? '/*' : '*'
        const start = 'http://';
        return allMethods.map((method, i) => {
            return <div className='short-table-row routes-table-row' key={`allmethods-${i}`}>
                <div>{item.name}</div>
                <span style={{
                    whiteSpace: 'nowrap',
                    textOverflow: 'ellipsis',
                    overflow: 'hidden',
                    maxWidth: 310
                }}>
                    {routeEntries(idx)}
                    {end}
                </span>
                <div style={{ minWidth: 60 }}>{method}</div>
                {!api.testing.enabled ?
                    <Button type="primaryColor"
                        className='btn btn-sm'
                        onClick={() => historyPush(history, location, `/apis/${params.apiId}/testing`)}>
                        Enable API testing
                    </Button> :
                    <div className="d-flex align-items-center justify-content-start">
                        <Button
                            className="btn btn-sm"
                            type="primaryColor"
                            title="Copy URL"
                            onClick={() => copy(routeEntries(idx), rawMethods[i], setCopyIconName)}
                        >
                            <i className={copyIconName} />
                        </Button>
                        {rawMethods[i] === 'GET' && <Button
                            className="btn btn-sm ms-1"
                            type="primaryColor"
                            title={`Go to ${start}${domain}`}
                            onClick={() => goTo(idx)}
                        >
                            <i className="fas fa-external-link-alt" />
                        </Button>}
                    </div>}
            </div>
        });
    })
}

function RoutesView({ api }) {
    const ports = useQuery(['getPorts'], routePorts)

    if (ports.isLoading)
        return <SimpleLoader />

    return <div>
        <div className='short-table-row routes-table-row'>
            <div>Name</div>
            <div>Frontend</div>
            <div>Methods</div>
            <div>Actions</div>
        </div>
        {api.routes.map(route => <RouteItem item={route} api={api} key={route.id} ports={ports.data} />)}
    </div>
}

function SubscriptionsView({ api }) {
    const [subscriptions, setSubscriptions] = useState([])

    useEffect(() => {
        nextClient
            .forEntityNext(nextClient.ENTITIES.API_CONSUMER_SUBSCRIPTIONS)
            .findAllWithPagination({
                page: 1,
                pageSize: 5,
                filtered: [{
                    id: 'api_ref',
                    value: api.id
                }],
                sorted: [{
                    id: 'dates.created_at',
                    desc: false
                }]
            })
            .then(raw => setSubscriptions(raw.data))
    }, [])
    return <div>
        <div className='short-table-row'
            style={{
                gridTemplateColumns: 'repeat(3, 1fr) 54px 32px'
            }}>
            <div>Name</div>
            <div>Description</div>
            <div>Created At</div>
            <div>Kind</div>
        </div>
        {subscriptions.map(subscription => {
            return <Subscription subscription={subscription} key={subscription.id} />
        })}
    </div>
}

function Subscription({ subscription }) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    const [open, setOpen] = useState(false)

    return <div key={subscription.id}
        className='short-table-row'
        style={{
            backgroundColor: 'hsla(184, 9%, 62%, 0.18)',
            borderColor: 'hsla(184, 9%, 62%, 0.4)',
            borderRadius: '.5rem',
            gridTemplateColumns: open ? '1fr' : 'repeat(3, 1fr) 54px 32px',
            position: 'relative'
        }}
        onClick={() => {
            if (!open)
                setOpen(true)
        }}>
        {open && <div className="d-flex justify-content-between gap-2 align-items-center">
            <div style={{ position: 'relative', flex: 1 }}>
                <Button type="primaryColor" className="btn-sm" text="Edit"
                    onClick={e => {
                        e.stopPropagation()
                        historyPush(history, location, `/apis/${params.apiId}/subscriptions/${subscription.id}/edit`)
                    }} style={{
                        position: 'absolute',
                        top: '.5rem',
                        right: '.5rem',
                        zIndex: 100
                    }} />
                <JsonObjectAsCodeInput
                    editorOnly
                    showGutter={false}
                    label={undefined}
                    value={subscription} />
            </div>
            <i
                style={{ minWidth: 40 }}
                className="fas fa-chevron-up fa-lg short-table-navigate-icon"
                onClick={() => setOpen(false)} />
        </div>}
        {!open && <>
            <div>{subscription.name}</div>
            <div>{subscription.description}</div>
            <div>{moment(new Date(subscription.dates.created_at)).format('DD/MM/YY hh:mm')}</div>
            <div className='badge custom-badge bg-success' style={{ border: 'none' }}>{subscription.subscription_kind}</div>
            <i className="fas fa-chevron-right fa-lg short-table-navigate-icon" />
        </>}
    </div>
}

function ContainerBlock({ children, full, highlighted, style = {} }) {
    return <div className={`container ${full ? 'container--full' : ''} ${highlighted ? 'container--highlighted' : ''}`}
        style={{
            margin: 0,
            position: 'relative',
            height: 'fit-content',
            ...style
        }}>
        {children}
    </div>
}

function publishAPI(api) {
    window.newConfirm(<>
        Upgrading an API from staging to production makes it fully available to clients without testing headers.
        <p>Clients will use the API with real data in a reliable environment.</p>
    </>,
        {
            title: 'Production environment',
            yesText: 'Publish and expose to the world',
        })
        .then((ok) => {
            if (ok) {
                nextClient
                    .forEntityNext(nextClient.ENTITIES.APIS)
                    .update({
                        ...api,
                        state: API_STATE.PUBLISHED
                    })
                    .then(() => nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS)
                        .deleteById(api.id))
                    .then(() => {
                        const queryParams = new URLSearchParams(window.location.search);
                        queryParams.delete("version");
                        history.replaceState(null, null, "?" + queryParams.toString());
                        window.location.reload()
                    })
            }
        })
}

function APIHeader({ api, version, draft }) {
    const updateAPI = newAPI => {
        return nextClient
            .forEntityNext(nextClient.ENTITIES.APIS)
            .update(newAPI)
    }

    return <>
        <div className='d-flex align-items-center gap-3'>
            <h2 className='m-0'>{api.name}</h2>
            <span className='badge custom-badge api-status-started' style={{
                fontSize: '.75rem'
            }}>
                {api.version}
            </span>
            {version !== 'Published' && <span className='badge custom-badge api-status-started d-flex align-items-center gap-2'>
                <div className={`testing-dot ${draft.testing?.enabled ? 'testing-dot--enabled' : 'testing-dot--disabled'}`}></div>
                {draft.testing?.enabled ? 'Testing enabled' : 'Testing disabled'}
            </span>}
            <APIState value={api.state} />

            {version === 'Published' && <>
                {api.state === API_STATE.STAGING && <Button
                    type='primaryColor'
                    onClick={() => publishAPI(api)}
                    className='btn-sm ms-auto'
                    text="Start you API" />}
                {(api.state === API_STATE.PUBLISHED || api.state === API_STATE.DEPRECATED) &&
                    <Button
                        type='primaryColor'
                        onClick={() => {
                            window.newConfirm(api.state === API_STATE.PUBLISHED ? `New clients will be not allowed to subscribe to any consumers` :
                                `API will be available again`,
                                {
                                    title: api.state === API_STATE.PUBLISHED ? 'Confirm API deprecation' : 'Confirm API publication',
                                    yesText: api.state === API_STATE.PUBLISHED ? 'Deprecate the API' : 'Publish the API'
                                }
                            )
                                .then(ok => {
                                    if (ok) {
                                        updateAPI({
                                            ...api,
                                            state: api.state === API_STATE.PUBLISHED ? API_STATE.DEPRECATED : API_STATE.PUBLISHED
                                        })
                                            .then(() => window.location.reload())
                                    }
                                })
                        }}
                        className='btn-sm ms-auto'
                        text={api.state === API_STATE.PUBLISHED ? "Deprecate your API" : "Publish your API"} />}
                {/* {(api.state === API_STATE.PUBLISHED || api.state === API_STATE.DEPRECATED) &&
                <Button
                    type='danger'
                    onClick={() => {
                        updateAPI({
                            ...api,
                            state: API_STATE.DEPRECATED
                        })
                            .then(() => window.location.reload())
                    }}
                    className='btn-sm ms-auto'
                    text="Close your API" />} */}
            </>}
        </div>
        <div className='d-flex align-items-center gap-1 mb-3'>
            <p className='m-0 me-2'>{api.description}</p>
            {api.tags.map(tag => <span className='tag' key={tag}>
                {tag}
            </span>)}
        </div>
    </>
}

function APIState({ value }) {
    if (value === API_STATE.STAGING)
        return <span className='badge custom-badge api-status-started'>
            <i className='fas fa-rocket me-2' />
            Staging
        </span>

    if (value === API_STATE.DEPRECATED)
        return <span className='badge custom-badge api-status-deprecated'>
            <i className='fas fa-warning me-2' />
            Deprecated
        </span>

    if (value === API_STATE.PUBLISHED)
        return <span className='badge custom-badge api-status-published'>
            <i className='fas fa-check fa-xs me-2' />
            Published
        </span>


    // TODO  - manage API_STATE.REMOVED
    return null
}

function SectionHeader({ text, description, main, actions }) {
    return <div>
        <div className='d-flex align-items-center justify-content-between'>
            {main ? <h1 className='m-0'>{text}</h1> :
                <h3 className='m-0'>{text}</h3>}
            {actions}
        </div>
        <p>{description}</p>
    </div>
}

function Entities({ children }) {
    return <div className='d-flex flex-column gap-3'>
        {children}
    </div>
}

function ProgressCard({ children, step }) {
    const steps = 6

    const ref = useRef()

    useEffect(() => {
        const handleWheel = (event) => {
            event.preventDefault();

            ref.current.scrollBy({
                left: event.deltaY < 0 ? -35 : 35,

            });
        }

        ref.current?.addEventListener('wheel', handleWheel)

        return () => {
            ref.current?.removeEventListener('wheel', handleWheel)
        }
    }, [ref])

    return <ContainerBlock full style={{ minWidth: '100%' }}>
        <div className='d-flex'>
            <div className='me-4'>
                <div className='cards-title d-flex align-items-center justify-content-between'>
                    Get started with API
                </div>
                <div className='d-flex align-items-center gap-2 mb-3 mt-1'>
                    <div className='progress'>
                        <div className='progress-bar' style={{
                            right: `${(1 - (step / steps)) * 100}%`
                        }}></div>
                    </div>
                    <span>{step}/{steps}</span>
                </div>
                <p className="cards-description" style={{ position: 'relative' }}>
                    <i className='fas fa-hand-spock fa-lg me-1' style={{
                        color: 'var(--color-primary)'
                    }} /> Let's build your first API!
                </p>
            </div>
            <div className='d-flex progress-childs' ref={ref}>
                {children}
            </div>
        </div>
    </ContainerBlock>
}

function ObjectiveCard({ title, description, icon, to, onClick }) {
    const history = useHistory()
    const location = useLocation()

    return <div
        className="objective-card">
        <div className='objective-card-icon'>
            {icon}
        </div>
        <div className='objective-card-body'>
            <p>{title}</p>
            <p onClick={() => {
                onClick ? onClick() : historyPush(history, location, to)
            }}>{description}</p>
        </div>
    </div>
}

function BackendsCard({ backends }) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    return <div onClick={() => historyPush(history, location, `/apis/${params.apiId}/backends`)} className="cards apis-cards">
        <div className="cards-body">
            <div className='cards-title d-flex align-items-center justify-content-between'>
                Backends <span className='badge custom-badge api-status-deprecated'>
                    <i className='fas fa-microchip me-2' />
                    {backends.length}
                </span>
            </div>
            <p className="cards-description" style={{ position: 'relative' }}>
                Design robust, scalable <HighlighedBackendText plural /> with optimized performance, security, and seamless front-end integration.
                <i className='fas fa-chevron-right fa-lg navigate-icon' />
            </p>
        </div>
    </div>
}

function ConsumerCard({ item }) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    return <div onClick={() => historyPush(history, location, `/apis/${params.apiId}/consumers/new`)} className="cards apis-cards">
        <div className="cards-body">
            <div className='cards-title d-flex align-items-center justify-content-between'>
                Consumers <span className='badge custom-badge api-status-deprecated'>
                    <i className='fas fa-list me-2' />
                    {item.consumers.length}
                </span>
            </div>
            <p className="cards-description" style={{ position: 'relative' }}>
                Defines usage limits, features, and access levels for consuming your API.
                <i className='fas fa-chevron-right fa-lg navigate-icon' />
            </p>
        </div>
    </div>
}

function RoutesCard({ routes }) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    return <div onClick={() => historyPush(history, location, `/apis/${params.apiId}/routes`)} className="cards apis-cards">
        <div className="cards-body">
            <div className='cards-title d-flex align-items-center justify-content-between'>
                Routes <span className='badge custom-badge api-status-deprecated'>
                    <i className='fas fa-road me-2' />
                    {routes.length}
                </span>
            </div>
            <p className="cards-description relative">
                Define your <HighlighedRouteText />: connect <HighlighedFrontendText plural /> to <HighlighedBackendText plural /> and customize behavior with <HighlighedFlowsText plural /> like authentication, rate limiting, and transformations.
                <i className='fas fa-chevron-right fa-lg navigate-icon' />
            </p>
        </div>
    </div>
}

function FlowsCard({ flows }) {
    const params = useParams()
    const history = useHistory()
    const location = useLocation()

    return <div onClick={() => historyPush(history, location, `/apis/${params.apiId}/flows`)} className="cards apis-cards">
        <div className="cards-body">
            <div className='cards-title d-flex align-items-center justify-content-between'>
                Flows <span className='badge custom-badge api-status-deprecated'>
                    <i className='fas fa-road me-2' />
                    {flows.length}
                </span>
            </div>
            <p className="cards-description relative">
                Create groups of <HighlighedPluginsText plural /> to apply rules, transformations, and restrictions on <HighlighedRouteText plural />, enabling advanced traffic control and customization.
                <i className='fas fa-chevron-right fa-lg navigate-icon' />
            </p>
        </div>
    </div>
}

function HighlighedPluginsText({ plural }) {
    const params = useParams()
    return <HighlighedText text={plural ? 'plugins' : "plugin"} link={`/apis/${params.apiId}/flows`} />
}

function HighlighedBackendText({ plural }) {
    const params = useParams()
    return <HighlighedText text={plural ? 'backends' : "backend"} link={`/apis/${params.apiId}/backends`} />
}

function HighlighedFrontendText({ plural }) {
    const params = useParams()
    return <HighlighedText text={plural ? 'frontends' : "frontend"} link={`/apis/${params.apiId}/frontends`} />
}

function HighlighedRouteText({ plural }) {
    const params = useParams()
    return <HighlighedText text={plural ? 'routes' : "route"} link={`/apis/${params.apiId}/routes`} />
}

function HighlighedFlowsText({ plural }) {
    const params = useParams()
    return <HighlighedText text={plural ? 'flows' : "flow"} link={`/apis/${params.apiId}/flows`} />
}

function HighlighedText({ text, link }) {
    const location = useLocation()
    return <Link to={{
        pathname: link,
        search: location.search
    }} className="highlighted-text">{text}</Link>
}