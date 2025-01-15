import React, { useEffect, useRef, useState } from 'react'

import './index.scss'

import {
    createApi,
    createApiBackend,
    createApiDocumentation,
    createApiFrontend,
    createApiPageLeaf,
    createApiRoute,
    createOpenApiSpecification,
    createApiDeploymentRef,
    createApiConsumer,
    createApiBackendClient,
    createApiFlows,
    createNgTarget,
    API_STATE,
    generateHourlyData,
    CONSUMER_KIND
} from './model';
import Sidebar from './Sidebar';
import { Link, Switch, Route, useParams, useHistory } from 'react-router-dom';
import { Uptime } from '../../components/Status';
import { Form, Table } from '../../components/inputs';
import { v4 as uuid, v4 } from 'uuid';
import Designer from '../RouteDesigner/Designer';
import Loader from '../../components/Loader';
import { dynamicTitleContent } from '../../components/DynamicTitleSignal';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import { nextClient } from '../../services/BackOfficeServices';
import { QueryClient, QueryClientProvider, useQuery } from 'react-query';
import { Button } from '../../components/Button';

const queryClient = new QueryClient();

// // Mock Data for NgTarget
// const ngTargetMock = createNgTarget({
//     idValue: "target1",
//     hostnameValue: "localhost",
//     portValue: 8080,
//     tlsValue: true,
//     weightValue: 10,
//     protocolValue: "HTTP_1_1",
//     predicateValue: "AlwaysMatch",
//     ipAddressValue: "192.168.0.1",
//     tlsConfigValue: { cert: "cert_path", key: "key_path" }
// });

// // Mock Data for the ApiBackend (including NgTarget in targetsValue)
// const apiBackendMock = createApiBackend({
//     nameValue: "Backend 1",
//     targetsValue: [ngTargetMock], // Properly referencing ngTargetMock here
//     rootValue: "/api",
//     rewriteValue: true,
//     clientValue: "client1",
//     loadBalancingValue: "round-robin"
// });

// // Mock Data for the ApiFrontend
// const apiFrontendMock = createApiFrontend({
//     domainsValue: ["example.com", "api.example.com"],
//     headersValue: { "Authorization": "Bearer token" },
//     queryValue: { "page": "1" },
//     methodsValue: ["GET", "POST"],
//     stripPathValue: true,
//     exactValue: false
// });

// // Mock Data for the ApiRoute
// const apiRouteMock = createApiRoute({
//     frontendValue: apiFrontendMock,
//     pluginsValue: [apiFlowsMock],
//     backendValue: apiBackendMock
// });

// // Mock Data for the ApiDeploymentRef
// const apiDeploymentRefMock = createApiDeploymentRef({
//     refValue: "deploy-001",
//     atValue: new Date().toISOString(),
//     whoValue: "admin"
// });

// // Mock Data for the ApiDocumentation
// const apiPageLeafMock = createApiPageLeaf({
//     pathName: "/home",
//     apiPageName: "Home Page",
//     apiContent: "Some content here"
// });

// const apiDocumentationMock = createApiDocumentation({
//     specificationValue: "OpenAPI 3.0",
//     homeValue: { path: "/", name: "Home" },
//     pagesValue: [apiPageLeafMock],
//     metadataValue: { author: "John Doe" },
//     logosValue: ["logo1", "logo2"]
// });

// // Mock Data for the OpenApiSpecification
// const openApiSpecMock = createOpenApiSpecification({
//     contentValue: { info: "OpenAPI 3.0 Spec" }
// });

// // Mock Data for the ApiConsumer
// const apiConsumerMock = createApiConsumer({
//     nameValue: "Consumer1",
//     descriptionValue: "API Consumer Description",
//     autoValidationValue: true,
//     kindValue: CONSUMER_KIND.APIKEY,
//     settingsValue: { key: "value" },
//     statusValue: "Active",
//     subscriptionsValue: ["sub1", "sub2"]
// });

// // Mock Data for the ApiBackendClient
// const apiBackendClientMock = createApiBackendClient({
//     nameValue: "Client1",
//     clientValue: { config: "some config" }
// });

// // Mock Data for the ApiPlugins (with proper object structure)
// const apiFlowsMock = createApiFlows({
//     idValue: 'my_first_flow',
//     nameValue: "Plugin1",
//     predicateValue: "some predicate",
//     pluginsValue: []
// });

// // Mock Data for the Api
// const apiMock = createApi({
//     locationValue: { tenant: "tenant1", teams: ["team1"] },
//     idValue: "api-123",
//     nameValue: "Forecast API",
//     descriptionValue: "This is a sample API",
//     tagsValue: ["v1", "rest", "weather", "grpc"],
//     metadataValue: { author: "John Doe" },
//     versionValue: "1.0.0",
//     debugFlowValue: true,
//     captureValue: false,
//     exportReportingValue: true,
//     stateValue: API_STATE.PUBLISHED,
//     healthValue: {
//         today: generateHourlyData(0),
//         yesterday: generateHourlyData(-1),
//         nMinus2: generateHourlyData(-2),
//     },
//     blueprintValue: "blueprint-001",
//     routesValue: [apiRouteMock], // Referencing apiRouteMock here
//     backendsValue: [apiBackendMock],
//     flowsValue: [apiFlowsMock],
//     clientsValue: [apiBackendClientMock],
//     documentationValue: apiDocumentationMock,
//     consumersValue: [apiConsumerMock],
//     deploymentsValue: [apiDeploymentRefMock]
// });
// console.log(apiMock);

export default function ApiEditor(props) {
    return <div className='editor'>
        <QueryClientProvider client={queryClient}>
            <Switch>
                <Route path="*" >
                    <Route exact path='/apis/:apiId/flows' component={componentProps => <Flows {...props} {...componentProps} />} />
                    <Route exact path='/apis/:apiId/flows/new' component={componentProps => <NewFlow {...props} {...componentProps} />} />
                    <Route exact path='/apis/:apiId/flows/:flowId/:action' component={componentProps => <FlowDesigner {...props} {...componentProps} />} />
                    <Route path='/apis/new' component={componentProps => <NewAPI {...props} {...componentProps} />} />
                    <Route path='/apis/:apiId' component={componentProps => <Dashboard {...props} {...componentProps} />} />
                    <Route path='/apis' component={componentProps => <Apis {...props} {...componentProps} />} />
                </Route>
            </Switch>
        </QueryClientProvider>
    </div >
}

function SidebarComponent(props) {
    const params = useParams()
    useEffect(() => {
        props.setSidebarContent(<Sidebar params={params} />);
        return () => props.setSidebarContent(null)
    }, [])

    return null
}

function NewFlow(props) {
    const history = useHistory()
    const params = useParams()

    useEffect(() => {
        props.setTitle("Create a new Flow")
    }, [])

    const [flow, setFlow] = useState({
        id: v4(),
        name: 'New flow name',
        plugins: []
    })

    const schema = {
        name: {
            type: 'string',
            props: { label: 'Name' },
        }
    }

    const rawAPI = useQuery(["getAPI", params.apiId],
        () => nextClient.forEntityNext(nextClient.ENTITIES.APIS).findById(params.apiId))

    const createFlow = () => {
        nextClient.forEntityNext(nextClient.ENTITIES.APIS)
            .update({
                ...rawAPI.data,
                flows: [...rawAPI.data.flows, flow]
            })
            .then(() => history.push(`/apis/${params.apiId}/flows/${flow.id}`));
    }

    return <Loader loading={rawAPI.isLoading}>
        <Form
            schema={schema}
            flow={["name"]}
            value={flow}
            onChange={setFlow}
        />
        <Button
            type="success"
            className="btn-sm ms-auto d-flex"
            onClick={createFlow}
            text="Create"
        />
    </Loader>
}

function NewAPI(props) {
    const history = useHistory()

    useEffect(() => {
        props.setTitle("Create a new API")
    }, [])

    const [value, setValue] = useState({})

    const template = useQuery(["getTemplate"],
        nextClient.forEntityNext(nextClient.ENTITIES.APIS).template, {
        onSuccess: (data) => {
            setValue(data)
        }
    });

    // version: String,
    // state: ApiState,
    // blueprint: ApiBlueprint,
    // routes: Seq[ApiRoute],
    // backends: Seq[NgBackend],
    // flows: Seq[ApiFlows],
    // clients: Seq[ApiBackendClient],
    // documentation: Option[ApiDocumentation],
    // consumers: Seq[ApiConsumer],
    // deployments: Seq[ApiDeployment]

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
        debug_flow: {
            type: 'bool',
            props: { label: 'Debug' },
        },
        export_reporting: {
            type: 'bool',
            props: { label: 'Export reports' },
        },
        capture: {
            type: 'bool',
            props: { label: 'Capture traffic' },
        }
    }
    const editionFlow = ['location', 'id', 'name', 'description', 'metadata', 'tags', 'debug_flow', 'export_reporting', 'capture']
    const flow = ['location', 'name', 'description']

    const createApi = () => {
        nextClient.forEntityNext(nextClient.ENTITIES.APIS)
            .create(value)
            .then(() => history.push(`/apis/${value.id}`));
    }

    return <Loader loading={template.isLoading}>
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
    </Loader>
}

function Apis(props) {
    const ref = useRef()
    const params = useParams()
    const history = useHistory()

    useEffect(() => {
        props.setTitle("Apis")
    }, [])

    const [fields, setFields] = useState({
        id: false,
        name: true,
    })
    const columns = [
        {
            title: 'Id',
            content: item => item.id
        },
        {
            title: 'Name',
            content: item => item.name
        }
    ];

    const fetchItems = (paginationState) => nextClient
        .forEntityNext(nextClient.ENTITIES.APIS)
        .findAllWithPagination(paginationState)

    const fetchTemplate = () => nextClient
        .forEntityNext(nextClient.ENTITIES.APIS)
        .template()

    return <>
        <SidebarComponent {...props} />
        <Table
            ref={ref}
            parentProps={{ params }}
            navigateTo={(item) => history.push(`/apis/${item.id}`)}
            navigateOnEdit={(item) => history.push(`/apis/${item.id}`)}
            selfUrl="flows"
            defaultTitle="Flow"
            itemName="Flow"
            formSchema={null}
            formFlow={null}
            columns={columns}
            fields={fields}
            deleteItem={(item) => console.log('delete item', item)}
            defaultSort="name"
            defaultSortDesc="true"
            fetchItems={fetchItems}
            fetchTemplate={fetchTemplate}
            showActions={true}
            showLink={false}
            extractKey={(item) => item.id}
            rowNavigation={true}
            hideAddItemAction={true}
            itemUrl={(i) => `/bo/dashboard/apis/${i.id}`}
            rawEditUrl={true}
            displayTrash={(item) => item.id === props.globalEnv.adminApiId}
            injectTopBar={() => (
                <div className="btn-group input-group-btn">
                    <Link className="btn btn-primary btn-sm" to="apis/new">
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

    const rawAPI = useQuery(["getAPI", params.apiId],
        () => nextClient.forEntityNext(nextClient.ENTITIES.APIS).findById(params.apiId))

    const [flow, setFlow] = useState()
    const ref = useRef(flow)

    useEffect(() => {
        ref.current = flow;
    }, [flow])

    useEffect(() => {
        if (rawAPI.data) {
            setFlow(rawAPI.data.flows.find(flow => flow.id === params.flowId))

            dynamicTitleContent.value = (
                <PageTitle
                    style={{
                        paddingBottom: 0,
                    }}
                    title={rawAPI.data.flows.find(flow => flow.id === params.flowId)?.name}
                    {...props}
                >
                    <FeedbackButton
                        type="success"
                        className="ms-2 mb-1"
                        onPress={saveFlow}
                        text={isCreation ? 'Create a new flow' : 'Save'}
                    />
                </PageTitle>
            );
        }
    }, [rawAPI.data])

    const saveFlow = () => {
        const api = rawAPI.data
        const {
            id, name, plugins
        } = ref.current.value

        return nextClient.forEntityNext(nextClient.ENTITIES.APIS)
            .update({
                ...api,
                flows: api.flows.map(item => {
                    if (item.id === id)
                        return {
                            id, name, plugins
                        }
                    return item
                })
            })
            .then(() => history.replace(`/apis/${params.apiId}/flows`))
    }

    return <Loader loading={rawAPI.isLoading}>
        <SidebarComponent {...props} />
        <div className='designer'>
            <Designer
                history={history}
                value={flow}
                setValue={value => setFlow({ value })}
                setSaveButton={() => { }}
            // setMenu={(n) => this.setState({ menu: n, menuRefreshed: Date.now() })}
            />
        </div>
    </Loader>
}

function Flows(props) {
    const ref = useRef()
    const params = useParams()
    const history = useHistory()

    const rawAPI = useQuery(["getAPI", params.apiId],
        () => nextClient.forEntityNext(nextClient.ENTITIES.APIS).findById(params.apiId))

    const [fields, setFields] = useState({
        id: false,
        name: true,
    })
    const columns = [
        {
            title: 'Name',
            content: item => item.name
        }
    ];

    useEffect(() => {
        props.setTitle(`Flows of ${rawAPI.data?.name}`)
    }, [rawAPI.data])

    const fetchItems = (paginationState) => Promise.resolve(rawAPI.data.flows)

    const fetchTemplate = () => Promise.resolve({
        id: uuid(),
        name: 'My new flow',
        plugins: []
    })

    return <Loader loading={rawAPI.isLoading}>
        <SidebarComponent {...props} />
        <Table
            ref={ref}
            parentProps={{ params }}
            navigateTo={(item) => history.push(`/apis/${params.apiId}/flows/${item.id}/edit`)}
            navigateOnEdit={(item) => history.push(`/apis/${params.apiId}/flows/${item.id}/edit`)}
            selfUrl="flows"
            defaultTitle="Flow"
            itemName="Flow"
            formSchema={null}
            formFlow={null}
            columns={columns}
            fields={fields}
            // coreFields={['id', 'name']}
            // addField={(fieldPath) => {
            //     const newFields = {
            //         ...fields,
            //         [fieldPath]: true,
            //     };
            //     setFields(newFields);
            //     onFieldsChange(newFields);
            // }}
            // removeField={(fieldPath) => {
            //     const { [fieldPath]: _, ...newFields } = fields;

            //     setFields(newFields);
            //     onFieldsChange(newFields);
            // }}
            // onToggleField={(column, enabled) => {
            //     const newFields = {
            //         ...fields,
            //         [column]: enabled,
            //     };
            //     onFieldsChange(newFields);
            //     setFields(newFields);
            // }}
            deleteItem={(item) => console.log('delete item', item)}
            defaultSort="name"
            defaultSortDesc="true"
            fetchItems={fetchItems}
            fetchTemplate={fetchTemplate}
            showActions={true}
            showLink={false}
            extractKey={(item) => item.id}
            rowNavigation={true}
            hideAddItemAction={true}
            itemUrl={(i) => `/bo/dashboard/apis/${params.apiId}/flows/${i.id}`}
            rawEditUrl={true}
            displayTrash={(item) => item.id === props.globalEnv.adminApiId}
            injectTopBar={() => (
                <div className="btn-group input-group-btn">
                    <Link className="btn btn-primary btn-sm" to="flows/new">
                        <i className="fas fa-plus-circle" /> Create new Flow
                    </Link>
                    {props.injectTopBar}
                </div>
            )}
        />
    </Loader>
}

function Dashboard(props) {
    const params = useParams()

    useEffect(() => {
        props.setTitle("Dashboard")
    }, [])

    const rawAPI = useQuery(["getAPI", params.apiId],
        () => nextClient.forEntityNext(nextClient.ENTITIES.APIS).findById(params.apiId))

    const api = rawAPI.data

    return <div className='d-flex flex-column gap-3'>
        <Loader loading={rawAPI.isLoading}>
            <SidebarComponent {...props} />
            {api && <div className='d-flex gap-3'>
                <div className='d-flex flex-column flex-grow gap-3'>
                    <ContainerBlock full highlighted>
                        <APIHeader api={api} />
                        <Uptime
                            health={api.health?.today}
                            stopTheCountUnknownStatus={false}
                        />
                        <Uptime
                            health={api.health?.yesterday}
                            stopTheCountUnknownStatus={false}
                        />
                        <Uptime
                            health={api.health?.nMinus2}
                            stopTheCountUnknownStatus={false}
                        />
                    </ContainerBlock>
                    <ContainerBlock full>
                        <SectionHeader text="Customers" description="Manage customers and subscriptions" />
                    </ContainerBlock>
                </div>
                <ContainerBlock>
                    <SectionHeader text="Build your API" description="Manage entities for this API" />
                    <Entities>
                        <FlowsCard flows={api.flows} />
                        <Backends backends={api.backends} />
                        <Routes routes={api.routes} />
                    </Entities>
                </ContainerBlock>
            </div>}
        </Loader>
    </div>
}

function ContainerBlock({ children, full, highlighted }) {
    return <div className={`container ${full ? 'container--full' : ''} ${highlighted ? 'container--highlighted' : ''}`}>
        {children}
    </div>
}

function APIHeader({ api }) {
    return <>
        <div className='d-flex align-items-center gap-3'>
            <h2 className='m-0'>{api.name}</h2>
            <APIState value={api.state} />
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
    if (value === API_STATE.STARTED)
        return <span className='badge api-status-started'>
            <i className='fas fa-rocket me-2' />
            Started
        </span>

    if (value === API_STATE.DEPRECATED)
        return <span className='badge api-status-deprecated'>
            <i className='fas fa-warning me-2' />
            Deprecated
        </span>

    if (value === API_STATE.PUBLISHED)
        return <span className='badge api-status-published'>
            <i className='fas fa-check fa-xs me-2' />
            Published
        </span>


    // TODO  - manage API_STATE.REMOVED
    return null
}

function SectionHeader({ text, description, main }) {
    return <div>
        {main ? <h1 className='m-0'>{text}</h1> :
            <h3 className='m-0'>{text}</h3>}
        <p>{description}</p>
    </div>
}

function Entities({ children }) {
    return <div className='d-flex flex-column gap-3'>
        {children}
    </div>
}

function Backends({ backends }) {
    return <Link to="backends" href="" className="cards apis-cards">
        <div
            className="cards-header"
            style={{
                background: `url(/assets/images/svgs/backend.svg)`,
            }}
        ></div>
        <div className="cards-body">
            <div className='cards-title d-flex align-items-center justify-content-between'>
                Backends <span className='badge api-status-deprecated'>
                    <i className='fas fa-microchip me-2' />
                    {backends.length}
                </span>
            </div>
            <p className="cards-description" style={{ position: 'relative' }}>
                Design robust, scalable <HighlighedBackendText plural /> with optimized performance, security, and seamless front-end integration.
                <i className='fas fa-chevron-right fa-lg navigate-icon' />
            </p>
        </div>
    </Link>
}

function Routes({ routes }) {
    return <Link to="routes" href="" className="cards apis-cards">
        <div
            className="cards-header"
            style={{
                background: `url(/assets/images/svgs/routes.svg)`,
            }}
        ></div>
        <div className="cards-body">
            <div className='cards-title d-flex align-items-center justify-content-between'>
                Routes <span className='badge api-status-deprecated'>
                    <i className='fas fa-road me-2' />
                    {routes.length}
                </span>
            </div>
            <p className="cards-description relative">
                Define your <HighlighedRouteText />: connect <HighlighedFrontendText plural /> to <HighlighedBackendText plural /> and customize behavior with <HighlighedPluginsText plural /> like authentication, rate limiting, and transformations.
                <i className='fas fa-chevron-right fa-lg navigate-icon' />
            </p>
        </div>
    </Link>
}

function FlowsCard({ flows }) {
    const params = useParams()
    return <Link to={`/apis/${params.apiId}/flows`} className="cards apis-cards">
        <div
            className="cards-header"
            style={{
                background: `url(/assets/images/svgs/plugins.svg)`,
            }}
        ></div>
        <div className="cards-body">
            <div className='cards-title d-flex align-items-center justify-content-between'>
                Flows <span className='badge api-status-deprecated'>
                    <i className='fas fa-road me-2' />
                    {flows.length}
                </span>
            </div>
            <p className="cards-description relative">
                Create flows of <HighlighedPluginsText plural /> to apply rules, transformations, and restrictions on <HighlighedRouteText plural />, enabling advanced traffic control and customization.
                <i className='fas fa-chevron-right fa-lg navigate-icon' />
            </p>
        </div>
    </Link>
}

function HighlighedPluginsText({ plural }) {
    const params = useParams()
    return <HighlighedText text={plural ? 'plugins' : "plugin"} link={`/apis/${params.apiId}/flows`} />
}

function HighlighedBackendText({ plural }) {
    return <HighlighedText text={plural ? 'backends' : "backend"} link="/apis/backends" />
}

function HighlighedFrontendText({ plural }) {
    return <HighlighedText text={plural ? 'frontends' : "frontend"} link="/apis/frontends" />
}

function HighlighedRouteText({ plural }) {
    return <HighlighedText text={plural ? 'routes' : "route"} link="/apis/routes" />
}

function HighlighedText({ text, link }) {
    return <Link to={link} className="highlighted-text">{text}</Link>
}