export const API_STATE = {
    "STARTED": 'started',
    "PUBLISHED": 'published',
    "DEPRECATED": 'deprecated',
    "REMOVED": 'removed',
}

// export function for creating ApiBackend
export function createApiBackend({
    nameValue,
    targetsValue,
    rootValue,
    rewriteValue,
    clientValue,
    loadBalancingValue
}) {
    let name = nameValue;
    let targets = targetsValue;
    let root = rootValue;
    let rewrite = rewriteValue;
    let client = clientValue;
    let loadBalancing = loadBalancingValue;

    return {
        name,
        targets,
        root,
        rewrite,
        client,
        loadBalancing,
    };
}

// export function for creating ApiFrontend
export function createApiFrontend({
    domainsValue,
    headersValue,
    queryValue,
    methodsValue,
    stripPathValue,
    exactValue
}) {
    let domains = domainsValue;
    let headers = headersValue;
    let query = queryValue;
    let methods = methodsValue;
    let stripPath = stripPathValue;
    let exact = exactValue;

    return {
        domains,
        headers,
        query,
        methods,
        stripPath,
        exact,
    };
}

// export function for creating ApiRoute
export function createApiRoute({
    frontendValue,
    pluginsValue,
    backendValue
}) {
    let frontend = frontendValue;
    let plugins = pluginsValue;
    let backend = backendValue;

    return {
        frontend,
        plugins,
        backend,
    };
}

// export function for creating ApiDeploymentRef
export function createApiDeploymentRef({
    refValue,
    atValue,
    whoValue
}) {
    let ref = refValue;
    let at = atValue;
    let who = whoValue;

    return {
        ref,
        at,
        who,
    };
}

// export function for creating ApiDocumentation
export function createApiDocumentation({
    specificationValue,
    homeValue,
    pagesValue,
    metadataValue,
    logosValue
}) {
    let specification = specificationValue;
    let home = homeValue;
    let pages = pagesValue;
    let metadata = metadataValue;
    let logos = logosValue;

    return {
        specification,
        home,
        pages,
        metadata,
        logos,
    };
}

// export function for creating ApiPageLeaf
export function createApiPageLeaf({
    pathName,
    apiPageName,
    apiContent
}) {
    let path = pathName;
    let name = apiPageName;
    let content = apiContent;

    return {
        type: "ApiPageLeaf",
        path,
        name,
        content, // ByteString
    };
}

// export function for creating OpenApiSpecification
export function createOpenApiSpecification({
    contentValue
}) {
    let content = contentValue;

    return {
        type: "OpenApiSpecification",
        content,
    };
}

// export function for creating ApiConsumer
export function createApiConsumer({
    nameValue,
    descriptionValue,
    autoValidationValue,
    kindValue,
    settingsValue,
    statusValue,
    subscriptionsValue
}) {
    let name = nameValue;
    let description = descriptionValue;
    let autoValidation = autoValidationValue;
    let kind = kindValue;
    let settings = settingsValue;
    let status = statusValue;
    let subscriptions = subscriptionsValue;

    return {
        name,
        description,
        autoValidation,
        kind,
        settings,
        status,
        subscriptions,
    };
}

// export function for creating ApiBackendClient
export function createApiBackendClient({
    nameValue,
    clientValue
}) {
    let name = nameValue;
    let client = clientValue;

    return {
        name,
        client,
    };
}

// export function for creating ApiPlugins
export function createApiPlugins({
    nameValue,
    predicateValue,
    pluginsValue
}) {
    let name = nameValue;
    let predicate = predicateValue;
    let plugins = pluginsValue;

    return {
        name,
        predicate,
        plugins,
    };
}

// export function for creating Api
export function createApi({
    locationValue,
    idValue,
    nameValue,
    descriptionValue,
    tagsValue,
    metadataValue,
    versionValue,
    debugFlowValue,
    captureValue,
    exportReportingValue,
    stateValue,
    healthValue,
    blueprintValue,
    routesValue,
    backendsValue,
    pluginsValue,
    clientsValue,
    documentationValue,
    consumersValue,
    deploymentsValue
}) {
    let location = locationValue;
    let id = idValue;
    let name = nameValue;
    let description = descriptionValue;
    let tags = tagsValue;
    let metadata = metadataValue;
    let version = versionValue;
    let debugFlow = debugFlowValue;
    let capture = captureValue;
    let exportReporting = exportReportingValue;
    let state = stateValue;
    let health = healthValue;
    let blueprint = blueprintValue;
    let routes = routesValue;
    let backends = backendsValue;
    let plugins = pluginsValue;
    let clients = clientsValue;
    let documentation = documentationValue;
    let consumers = consumersValue;
    let deployments = deploymentsValue;

    return {
        location,
        id,
        name,
        description,
        tags,
        metadata,
        version,
        debugFlow,
        capture,
        exportReporting,
        state,
        health,
        blueprint,
        routes,
        backends,
        plugins,
        clients,
        documentation,
        consumers,
        deployments,
    };
}
export function createNgTarget({
    idValue, hostnameValue, portValue, tlsValue, weightValue = 1,
    protocolValue = "HTTP_1_1", predicateValue = "AlwaysMatch",
    ipAddressValue = null, tlsConfigValue = { cert: "cert_path", key: "key_path" }
}) {
    let id = idValue;
    let hostname = hostnameValue;
    let port = portValue;
    let tls = tlsValue;
    let weight = weightValue;
    let protocol = protocolValue;
    let predicate = predicateValue;
    let ipAddress = ipAddressValue;
    let tlsConfig = tlsConfigValue;

    return {
        id,
        hostname,
        port,
        tls,
        weight,
        protocol,
        predicate,
        ipAddress,
        tlsConfig
    };
}


export function generateHourlyData(day) {
    const statuses = ['GREEN', 'YELLOW'];
    const result = { dates: [] };

    const baseDate = new Date();
    baseDate.setDate(baseDate.getDate() + day); // Increment day for 3 consecutive days

    for (let hour = 0; hour < 24; hour++) {
        const date = new Date(baseDate);
        date.setHours(hour);

        result.dates.push({
            status: [
                {
                    health: statuses[Math.floor(Math.random() * statuses.length)], // Randomly 'GREEN' or 'YELLOW'
                    percentage: Math.floor(Math.random() * 3 + 98) // Random number between 0 and 100
                }
            ],
            dateAsString: date.toISOString() // Full ISO date-time string
        });
    }

    return result;
}
