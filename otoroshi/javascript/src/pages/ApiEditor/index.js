import React, { useEffect } from 'react'

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
    createApiPlugins,
    createNgTarget,
    API_STATE,
    generateHourlyData
} from './model';
import Sidebar from './Sidebar';
import { Link } from 'react-router-dom';
import { Button } from '../../components/Button';
import { SmoothUptime, Uptime } from '../../components/Status';

// Mock Data for NgTarget
const ngTargetMock = createNgTarget({
    idValue: "target1",
    hostnameValue: "localhost",
    portValue: 8080,
    tlsValue: true,
    weightValue: 10,
    protocolValue: "HTTP_1_1",
    predicateValue: "AlwaysMatch",
    ipAddressValue: "192.168.0.1",
    tlsConfigValue: { cert: "cert_path", key: "key_path" }
});

// Mock Data for the ApiBackend (including NgTarget in targetsValue)
const apiBackendMock = createApiBackend({
    nameValue: "Backend 1",
    targetsValue: [ngTargetMock], // Properly referencing ngTargetMock here
    rootValue: "/api",
    rewriteValue: true,
    clientValue: "client1",
    loadBalancingValue: "round-robin"
});

// Mock Data for the ApiFrontend
const apiFrontendMock = createApiFrontend({
    domainsValue: ["example.com", "api.example.com"],
    headersValue: { "Authorization": "Bearer token" },
    queryValue: { "page": "1" },
    methodsValue: ["GET", "POST"],
    stripPathValue: true,
    exactValue: false
});

// Mock Data for the ApiRoute
const apiRouteMock = createApiRoute({
    frontendValue: apiFrontendMock,
    pluginsValue: [apiPluginsMock],
    backendValue: apiBackendMock
});

// Mock Data for the ApiDeploymentRef
const apiDeploymentRefMock = createApiDeploymentRef({
    refValue: "deploy-001",
    atValue: new Date().toISOString(),
    whoValue: "admin"
});

// Mock Data for the ApiDocumentation
const apiPageLeafMock = createApiPageLeaf({
    pathName: "/home",
    apiPageName: "Home Page",
    apiContent: "Some content here"
});

const apiDocumentationMock = createApiDocumentation({
    specificationValue: "OpenAPI 3.0",
    homeValue: { path: "/", name: "Home" },
    pagesValue: [apiPageLeafMock],
    metadataValue: { author: "John Doe" },
    logosValue: ["logo1", "logo2"]
});

// Mock Data for the OpenApiSpecification
const openApiSpecMock = createOpenApiSpecification({
    contentValue: { info: "OpenAPI 3.0 Spec" }
});

// Mock Data for the ApiConsumer
const apiConsumerMock = createApiConsumer({
    nameValue: "Consumer1",
    descriptionValue: "API Consumer Description",
    autoValidationValue: true,
    kindValue: "Apikey",
    settingsValue: { key: "value" },
    statusValue: "Active",
    subscriptionsValue: ["sub1", "sub2"]
});

// Mock Data for the ApiBackendClient
const apiBackendClientMock = createApiBackendClient({
    nameValue: "Client1",
    clientValue: { config: "some config" }
});

// Mock Data for the ApiPlugins (with proper object structure)
const apiPluginsMock = createApiPlugins({
    nameValue: "Plugin1",
    predicateValue: "some predicate",
    pluginsValue: ["plugin1", "plugin2"]
});

// Mock Data for the Api
const apiMock = createApi({
    locationValue: { tenant: "tenant1", teams: ["team1"] },
    idValue: "api-123",
    nameValue: "Forecast API",
    descriptionValue: "This is a sample API",
    tagsValue: ["v1", "rest"],
    metadataValue: { author: "John Doe" },
    versionValue: "1.0.0",
    debugFlowValue: true,
    captureValue: false,
    exportReportingValue: true,
    stateValue: API_STATE.PUBLISHED,
    healthValue: {
        today: generateHourlyData(0),
        yesterday: generateHourlyData(-1),
        nMinus2: generateHourlyData(-2),
    },
    blueprintValue: "blueprint-001",
    routesValue: [apiRouteMock], // Referencing apiRouteMock here
    backendsValue: [apiBackendMock],
    pluginsValue: [apiPluginsMock],
    clientsValue: [apiBackendClientMock],
    documentationValue: apiDocumentationMock,
    consumersValue: [apiConsumerMock],
    deploymentsValue: [apiDeploymentRefMock]
});
console.log(apiMock);

export default function ApiEditor(props) {

    useEffect(() => {
        props.setSidebarContent(<Sidebar api={apiMock} />);

        return () => props.setSidebarContent(null)
    }, [])

    const api = apiMock

    return <div className='editor p-3'>

        <div className='d-flex flex-column gap-3'>
            <div className='d-flex gap-3'>
                <div className='d-flex flex-column flex-grow gap-3'>
                    <ContainerBlock full highlighted>
                        <APIHeader api={api} />
                        <Uptime
                            health={api.health.today}
                            stopTheCountUnknownStatus={false}
                        />
                        <Uptime
                            health={api.health.yesterday}
                            stopTheCountUnknownStatus={false}
                        />
                        <Uptime
                            health={api.health.nMinus2}
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
                        <Backends backends={api.backends} />
                        <Routes routes={api.routes} />
                    </Entities>
                </ContainerBlock>
            </div>
        </div>
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
        <p>{api.description}</p>
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

function HighlighedPluginsText({ plural }) {
    return <HighlighedText text={plural ? 'plugins' : "plugin"} link="/apis/flows" />
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