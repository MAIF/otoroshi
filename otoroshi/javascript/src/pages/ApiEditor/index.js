import React, { useEffect } from 'react'

import './index.css'

import {
    createApi,
    createApiBackend,
    createApiDocumentation,
    createApiFrontend,
    createApiPageLeaf,
    createApiRoute,
    createApiState,
    createOpenApiSpecification,
    createApiDeploymentRef,
    createApiConsumer,
    createApiBackendClient,
    createApiPlugins,
    createNgTarget
} from './model';
import Sidebar from './Sidebar';
import { Link } from 'react-router-dom';
import { Button } from '../../components/Button';

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

// Mock Data for ApiState
const apiStateMock = createApiState({
    startedValue: true,
    publishedValue: true,
    publicStateValue: true,
    deprecatedValue: true
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
    nameValue: "My API",
    descriptionValue: "This is a sample API",
    tagsValue: ["v1", "rest"],
    metadataValue: { author: "John Doe" },
    versionValue: "1.0.0",
    debugFlowValue: true,
    captureValue: false,
    exportReportingValue: true,
    stateValue: apiStateMock, // Using apiStateMock here
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

    return <div className='p-3'>

        <h2>{api.name}</h2>
        <div className='d-flex gap-2'>
            {api.state.started && <span className='api-status api-status-started'>
                <i className='fas fa-rocket me-2' />
                Started
            </span>}

            {api.state.deprecated && <span className='api-status api-status-deprecated'>
                <i className='fas fa-warning me-2' />
                Deprecated
            </span>}

            {api.state.published && <span className='api-status api-status-published'>
                <i className='fas fa-check fa-xs me-2' />
                Published
            </span>}
        </div>

        <Backends />
    </div>
}

function Backends() {
    return <Link to="backends" href="" className="cards apis-cards">
        <div
            className="cards-header"
            style={{
                background: `url(/assets/images/svgs/backend.svg)`,
            }}
        ></div>
        <div className="cards-body">
            <div className='cards-title'>
                Backend
            </div>
            <div className="cards-description">
                <p>
                    Design robust, scalable backends with optimized performance, security, and seamless front-end integration.
                </p>
            </div>
        </div>
    </Link>
}