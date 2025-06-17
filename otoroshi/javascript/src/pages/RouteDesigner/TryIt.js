import React, { Suspense, useEffect, useState } from 'react';
import { tryIt, findAllCertificates, fetchApiKeysForPage } from '../../services/BackOfficeServices';
import { firstLetterUppercase } from '../../util';

import { NgCodeRenderer, NgSelectRenderer } from '../../components/nginputs';
import { Row } from '../../components/Row';
import Loader from '../../components/Loader';

const CodeInput = React.lazy(() => Promise.resolve(require('../../components/inputs/CodeInput')));

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'];

const LOCAL_STORAGE_KEY = 'global-tester';

const roundNsTo = (ns) => Number.parseFloat(round(ns) / 1000000).toFixed(3);
const round = (num) => Math.round((num + Number.EPSILON) * 100000) / 100000;

export default function ({ route }) {
  const [selectedTab, setSelectedTab] = useState('Headers');
  const [selectedResponseTab, setSelectedResponseTab] = useState('Preview');
  const [headersStatus, setHeadersStatus] = useState('down');

  const [search, setSearch] = useState('');
  const [unit, setUnit] = useState('ms');
  const [sort, setSort] = useState('flow');
  const [flow, setFlow] = useState('all');

  const [lastQuery, setLastQuery] = useState();

  const [request, updateRequest] = useState({
    path: '/',
    headers: { [Date.now()]: { key: '', value: '' } },
    method: METHODS[0],
    body: undefined,
    bodyContent: '',
    contentType: 'No Body',
    route: undefined,
    route_id: undefined,
    useApikey: false,
    apikey: undefined,
    apikeyFormat: 'basic',
    apikeyHeader: 'Authorization',
    useCertificate: false,
    client_cert: undefined,
  });

  const [apikeys, setApikeys] = useState([]);
  const [certificates, setCertificates] = useState([]);
  const [response, setReponse] = useState();
  const [responseBody, setResponseBody] = useState();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (route && route.id) {
      updateRequest({
        ...request,
        route_id: route.id,
      });
    }
  }, [route]);

  useEffect(() => {
    loadTestingRouteHistory();
  }, []);

  const setRequest = (newReq) => {
    saveTestingRouteHistory(newReq);
    updateRequest(newReq);
  };

  const loadTestingRouteHistory = () => {
    try {
      const storedData = JSON.parse(localStorage.getItem(LOCAL_STORAGE_KEY));
      const r = storedData.routes.find((r) => r.id === route.id);
      if (storedData && r) {
        setRequest(r);
      }
    } catch (_) {}
  };

  const saveTestingRouteHistory = (request) => {
    const storedData = JSON.parse(localStorage.getItem(LOCAL_STORAGE_KEY));
    if (!storedData)
      localStorage.setItem(
        LOCAL_STORAGE_KEY,
        JSON.stringify({
          routes: [
            {
              id: route.id,
              ...request,
            },
          ],
        })
      );
    else {
      if (storedData.routes.find((r) => r.id === route.id)) {
        localStorage.setItem(
          LOCAL_STORAGE_KEY,
          JSON.stringify({
            routes: (storedData.routes || []).map((r) => {
              if (r.id === route.id)
                return {
                  id: route.id,
                  ...request,
                };
              return r;
            }),
          })
        );
      } else {
        localStorage.setItem(
          LOCAL_STORAGE_KEY,
          JSON.stringify({
            routes: [
              ...(storedData.routes || []),
              {
                id: route.id,
                ...request,
              },
            ],
          })
        );
      }
    }
  };

  useEffect(() => {
    fetchApiKeysForPage(route.id).then(setApikeys);
    findAllCertificates().then((res) => setCertificates(res.data));
  }, []);

  const send = () => {
    setLoading(true);
    setHeadersStatus('up');

    tryIt({
      ...request,
      headers: Object.values(request.headers)
        .filter((d) => d.key.length > 0 && d.checked)
        .reduce((a, c) => ({ ...a, [c.key]: c.value }), {}),
      body:
        request.contentType === 'GRAPHQL'
          ? JSON.stringify({ query: request.bodyContent }, null, 2)
          : request.bodyContent,
    })
      .then((res) => res.json())
      .then((res) => {
        setReponse(res);
        setLoading(false);

        try {
          setResponseBody(JSON.stringify(JSON.parse(atob(res.body_base_64)), null, 4));
        } catch (err) {
          setResponseBody(atob(res.body_base_64).replace(/\n/g, '').trimStart());
        }
      });
  };

  const bytesToSize = (bytes) => {
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
    if (bytes == 0) return '0 Byte';
    const i = parseInt(Math.floor(Math.log(bytes) / Math.log(1024)));
    return Math.round(bytes / Math.pow(1024, i), 2) + ' ' + sizes[i];
  };

  const apikeyToHeader = (format, apikey, apikeyHeader) => {
    if (!(apikey || request.apikey)) return request.headers;

    const { clientId, clientSecret } = apikey || request.apikey;

    return {
      ...Object.fromEntries(
        Object.entries(request.headers).filter(
          ([id, _]) =>
            !['authorization-header', 'Otoroshi-Client-Id', 'Otoroshi-Client-Secret'].includes(id)
        )
      ),
      ...(format === 'basic'
        ? {
            'authorization-header': {
              key: apikeyHeader || request.apikeyHeader,
              value: `Basic ${btoa(`${clientId}:${clientSecret}`)}`,
              checked: true,
            },
          }
        : {
            'Otoroshi-Client-Id': {
              key: 'Otoroshi-Client-Id',
              value: clientId,
              checked: true,
            },
            'Otoroshi-Client-Secret': {
              key: 'Otoroshi-Client-Secret',
              value: clientSecret,
              checked: true,
            },
          }),
    };
  };

  const receivedResponse = response;

  const isAuthorizationTabVisible = selectedTab === 'Authorization' && headersStatus === 'down';

  return (
    <div className="d-flex flex-column tryIt" style={{ flex: 1 }}>
      <div className="d-flex">
        <div style={{ minWidth: '8rem' }}>
          <NgSelectRenderer
            options={METHODS}
            value={request.method}
            ngOptions={{
              spread: true,
            }}
            onChange={(method) => setRequest({ ...request, method })}
            optionsTransformer={(arr) => (arr || []).map((item) => ({ value: item, label: item }))}
            styles={{
              control: (baseStyles, _) => {
                return {
                  ...baseStyles,
                  border: '1px solid var(--bg-color_level3)',
                  color: 'var(--text)',
                  backgroundColor: 'var(--bg-color_level2)',
                  boxShadow: 'none',
                  borderRadius: 0,
                };
              },
            }}
          />
        </div>
        <input
          type="text"
          className="form-control"
          style={{
            borderRadius: 0,
            border: '1px solid var(--bg-color_level3)',
            borderRight: 0,
            borderLeft: 0,
          }}
          placeholder="Enter request URL"
          value={request.path}
          onChange={(e) => setRequest({ ...request, path: e.target.value })}
        />
        <button
          className="btn btn-primaryColor"
          onClick={send}
          style={{
            borderRadius: 0,
          }}
        >
          Send
        </button>
      </div>
      <div className="border-l border-r">
        <div
          style={{
            flexDirection: 'column',
            // paddingBottom: headersStatus === 'down' ? '120px' : 0,
          }}
        >
          <div className="d-flex align-items-center" style={{ minHeight: 30 }}>
            {[
              { label: 'Authorization', value: 'Authorization' },
              {
                label: 'Headers',
                value: (
                  <div className="tab-with-number">
                    Headers <span>{Object.keys(request.headers || {}).length} </span>
                  </div>
                ),
              },
              { label: 'Body', value: 'Body' },
            ].map(({ label, value }, i) => {
              return (
                <button
                  onClick={() => {
                    setHeadersStatus('down');
                    setSelectedTab(label);
                  }}
                  className="p-2 px-3"
                  style={{
                    padding: 0,
                    border: 'none',
                    borderRight: i === 1 || i === 2 ? '1px solid var(--bg-color_level3)' : 0,
                    borderLeft: i === 1 ? '1px solid var(--bg-color_level3)' : 0,
                    boxShadow:
                      headersStatus === 'up'
                        ? 'none'
                        : selectedTab === label
                          ? '0 1px 0 0 var(--bg-color_level1)'
                          : 'none',
                    background: 'none',
                  }}
                >
                  {value}
                </button>
              );
            })}
            <i
              className={`ms-auto px-3 tab fas fa-chevron-${headersStatus === 'up' ? 'down' : 'up'}`}
              style={{
                cursor: 'pointer',
              }}
              onClick={() => setHeadersStatus(headersStatus === 'up' ? 'down' : 'up')}
            />
          </div>
          {isAuthorizationTabVisible && (
            <div className="border-t">
              <Row title="Apikey" containerClassName="py-2 m-0">
                {/* <div className="d-flex-between pe-3" style={{ flex: 0.5 }}>
                      <BooleanInput
                        flex={true}
                        label="Use an apikey"
                        value={request.useApikey}
                        onChange={() =>
                          setRequest({
                            ...request,
                            useApikey: !request.useApikey,
                            headers: apikeyToHeader(),
                          })
                        }
                      />
                    </div> */}
                {/* {request.useApikey && ( */}
                <NgSelectRenderer
                  value={request.apikey}
                  isClearable
                  onChange={(k) => {
                    setRequest({
                      ...request,
                      apikey: k,
                      headers: apikeyToHeader(
                        request.apikeyFormat,
                        apikeys.find((a) => a.clientId === k)
                      ),
                    });
                  }}
                  ngOptions={{
                    spread: true,
                  }}
                  options={apikeys}
                  optionsTransformer={(arr) =>
                    arr.map((item) => ({
                      value: item.clientId,
                      label: item.clientName,
                    }))
                  }
                />
              </Row>
              {request.apikey && (
                <Row title="Apikey format" containerClassName="py-2 m-0">
                  <NgSelectRenderer
                    options={[
                      { value: 'basic', label: 'Basic header' },
                      {
                        value: 'credentials',
                        label: 'Client ID/Secret headers',
                      },
                    ]}
                    ngOptions={{
                      spread: true,
                    }}
                    value={request.apikeyFormat}
                    onChange={(k) =>
                      setRequest({
                        ...request,
                        apikeyFormat: k,
                        headers: apikeyToHeader(k),
                      })
                    }
                  />
                </Row>
              )}
              {request.apikey && request.apikeyFormat === 'basic' && (
                <Row title="Header name" containerClassName="py-2 m-0">
                  <input
                    type="text"
                    className="form-control flex"
                    onChange={(e) => {
                      setRequest({
                        ...request,
                        apikeyHeader: e.target.value,
                        headers: apikeyToHeader(request.apikeyFormat, undefined, e.target.value),
                      });
                    }}
                    value={request.apikeyHeader}
                  />
                </Row>
              )}
            </div>
          )}
          {isAuthorizationTabVisible && (
            <Row title="Certificate client" containerClassName="py-2 border-b m-0">
              {/* <div className="d-flex-between pe-3" style={{ flex: 0.5 }}>
                  <BooleanInput
                    flex={true}
                    label="Use a certificate client"
                    value={request.useCertificate}
                    onChange={() =>
                      setRequest({
                        ...request,
                        useCertificate: !request.useCertificate,
                      })
                    }
                  />
                </div> */}
              <NgSelectRenderer
                isClearable
                ngOptions={{
                  spread: true,
                }}
                options={certificates}
                value={request.client_cert}
                onChange={(client_cert) =>
                  setRequest({
                    ...request,
                    client_cert,
                  })
                }
                optionsTransformer={(arr) =>
                  arr.map((item) => ({
                    value: item.id,
                    label: item.name,
                  }))
                }
              />
            </Row>
          )}
          {selectedTab === 'Headers' && headersStatus === 'down' && (
            <div className="border-b">
              <Headers
                className="border-b-0"
                headers={request.headers}
                deleteHeader={(key) => {
                  const { [key]: value, ...newHeaders } = request.headers;

                  setRequest({
                    ...request,
                    headers: newHeaders,
                  });
                }}
                addNewHeader={() => {
                  setRequest({
                    ...request,
                    headers: {
                      ...request.headers,
                      [Date.now()]: { key: '', value: '', checked: true },
                    },
                  });
                }}
                deleteAllHeaders={() => {
                  setRequest({
                    ...request,
                    headers: {},
                  });
                }}
                onKeyChange={(id, v) => {
                  const updatedRequest = {
                    ...request,
                    headers: {
                      ...request.headers,
                      [id]: {
                        key: v,
                        value: request.headers[id].value,
                        checked: request.headers[id].checked,
                      },
                    },
                  };

                  setRequest({
                    ...updatedRequest,
                    headers: { ...updatedRequest.headers },
                  });
                }}
                onValueChange={(id, v) => {
                  const updatedRequest = {
                    ...request,
                    headers: {
                      ...request.headers,
                      [id]: {
                        key: request.headers[id].key,
                        value: v,
                        checked: request.headers[id].checked,
                      },
                    },
                  };

                  setRequest({
                    ...updatedRequest,
                    headers: { ...updatedRequest.headers },
                  });
                }}
                onCheckedChange={(id) => {
                  const updatedRequest = {
                    ...request,
                    headers: {
                      ...request.headers,
                      [id]: { ...request.headers[id], checked: !request.headers[id].checked },
                    },
                  };

                  setRequest({
                    ...updatedRequest,
                    headers: { ...updatedRequest.headers },
                  });
                }}
              />
            </div>
          )}
          {selectedTab === 'Body' && headersStatus === 'down' && (
            <div className="border-t">
              <Row title="Format" containerClassName="py-2 m-0">
                <NgSelectRenderer
                  ngOptions={{
                    spread: true,
                  }}
                  options={['No Body', 'Plain Text', 'JSON', 'GRAPHQL', 'HTML', 'XML', 'YAML']}
                  value={request.contentType}
                  onChange={(contentType) => {
                    setRequest({ ...request, contentType });
                  }}
                  optionsTransformer={(arr) => arr.map((item) => ({ value: item, label: item }))}
                />
              </Row>
              {request.contentType !== 'No Body' && (
                <Suspense fallback={<div>Loading ...</div>}>
                  <Row
                    title={request.contentType === 'GRAPHQL' ? 'Query' : 'Content'}
                    containerClassName="m-0 pb-3"
                  >
                    <CodeInput
                      editorOnly
                      className="try-it-body"
                      value={request.bodyContent}
                      // mode={request.contentType === 'GRAPHQL' ? 'graphqlschema' : request.contentType?.toLowerCase()}
                      mode="graphqlschema"
                      onChange={(bodyContent) => setRequest({ ...request, bodyContent })}
                    />
                  </Row>
                </Suspense>
              )}
            </div>
          )}
        </div>
      </div>
      {receivedResponse && (
        <div className="d-flex flex-row-center border border-b-0">
          <div className="d-flex justify-content-between align-items-flex-end" style={{ flex: 1 }}>
            <div className="d-flex flex-row-center">
              {[
                { label: 'Report', value: 'Report' },
                { label: 'Preview', value: 'Preview' },
                {
                  label: 'Headers',
                  value: (
                    <div className="tab-with-number">
                      Headers <span>{Object.keys(response.headers).length} </span>
                    </div>
                  ),
                },
              ].map(({ label, value }, i) => (
                <button
                  onClick={() => setSelectedResponseTab(label)}
                  className="p-2 px-3"
                  style={{
                    padding: 0,
                    border: 'none',
                    borderRight: i === 1 || i === 2 ? '1px solid var(--bg-color_level3)' : 0,
                    borderLeft: i === 1 ? '1px solid var(--bg-color_level3)' : 0,
                    boxShadow:
                      selectedResponseTab === label && i !== 1
                        ? '0 1px 0 0 var(--bg-color_level1)'
                        : 'none',
                    background: 'none',
                  }}
                >
                  {value}
                </button>
              ))}
            </div>
            <div className="d-flex flex-row-center align-items-center">
              <span className="report-information">{response.status}</span>
              <span className="report-information">
                {roundNsTo(response.report?.duration_ns)} ms
              </span>
              <span className="report-information">
                {bytesToSize(response.headers['content-length'])}
              </span>
            </div>
          </div>
        </div>
      )}
      {receivedResponse && selectedResponseTab === 'Headers' && (
        <Headers
          className="pt-3"
          headers={Object.entries(response.headers).reduce(
            (acc, [key, value], index) => ({
              ...acc,
              [`${Date.now()}-${index}`]: { key, value },
            }),
            {}
          )}
        />
      )}

      {receivedResponse && responseBody && selectedResponseTab === 'Preview' && (
        <div className="border" style={{ flex: 1 }}>
          {responseBody.startsWith('<!') ? (
            <iframe srcDoc={responseBody} style={{ flex: 1, minHeight: '750px', width: '100%' }} />
          ) : (
            <NgCodeRenderer
              ngOptions={{
                spread: true,
              }}
              rawSchema={{
                props: {
                  ace_config: {
                    maxLines: Infinity,
                    fontSize: 14,
                  },
                  editorOnly: true,
                  height: '100%',
                  mode: 'json',
                },
              }}
              value={responseBody}
              onChange={() => {}}
            />
          )}
        </div>
      )}
      {!receivedResponse && !loading && (
        <div className="py-3 text-center border border-t-0">
          <span>
            Enter the URL and click{' '}
            <button
              className="btn btn-primaryColor"
              onClick={send}
              style={{
                borderRadius: 0,
              }}
            >
              Send
            </button>{' '}
            to receive a response
          </span>
        </div>
      )}

      <Loader loading={loading}>
        {receivedResponse && selectedResponseTab === 'Report' ? (
          response.report ? (
            <ReportView
              report={response.report}
              search={search}
              setSearch={setSearch}
              unit={unit}
              setUnit={setUnit}
              sort={sort}
              setSort={setSort}
              flow={flow}
              setFlow={setFlow}
            />
          ) : (
            <span className="mt-3">No report is available</span>
          )
        ) : null}
      </Loader>
    </div>
  );
}

const ReportView = ({ report, search, setSearch, unit, setUnit, sort, setSort, flow, setFlow }) => {
  const [selectedStep, setSelectedStep] = useState(-1);
  const [selectedPlugin, setSelectedPlugin] = useState(-1);
  const [steps, setSteps] = useState([]);
  const [informations, setInformations] = useState({});

  useEffect(() => {
    const { steps, ...informations } = report;
    setSteps(report.steps);
    setInformations(informations);
  }, [report]);

  const isOnFlow = (step) => {
    if (flow === 'all') return true;
    else if (flow === 'internal')
      return (step.task || !step.ctx?.plugins) && step.task !== 'call-backend';
    // user flow
    else return step.ctx?.plugins?.length > 0 || step.task === 'call-backend';
  };

  const isMatchingSearchWords = (step) =>
    search.length <= 0
      ? true
      : step.task.includes(search) ||
        [...(step?.ctx?.plugins || [])].find((plugin) =>
          search.length <= 0 ? true : plugin.name.includes(search)
        );

  const isPluginNameMatchingSearch = (plugin) =>
    search.length <= 0 ? true : plugin.name.includes(search);

  const sortByFlow = (a, b) => (sort === 'flow' ? 0 : a.duration_ns < b.duration_ns ? 1 : -1);

  const durationInPercentage = (pluginDuration, totalDuration) =>
    Number.parseFloat((pluginDuration / totalDuration) * 100).toFixed(3);

  const reportDuration = () => {
    if (flow === 'all')
      return unit === 'ms'
        ? roundNsTo(report.duration_ns)
        : unit === 'ns'
          ? report.duration_ns
          : 100;
    else {
      const value = [...steps]
        .filter(isOnFlow)
        .filter(isMatchingSearchWords)
        .reduce((acc, step) => {
          const userPluginsFlow =
            step.ctx && step.ctx.plugins
              ? [...(step.ctx?.plugins || [])]
                  .filter(isPluginNameMatchingSearch)
                  .reduce((subAcc, step) => subAcc + step.duration_ns, 0)
              : 0;

          if (flow === 'user')
            return acc + (step.task === 'call-backend' ? step.duration_ns : 0) + userPluginsFlow;
          else return acc + step.duration_ns - userPluginsFlow;
        }, 0);

      if (unit === '%') return durationInPercentage(value, report.duration_ns);
      else if (unit === 'ns') return value;
      else return roundNsTo(value);
    }
  };

  return (
    <div className="d-flex border" style={{ flex: 1 }}>
      <div className="main-view px-2 border-r pt-2" style={{ flex: 0.5, minWidth: '300px' }}>
        <NgSelectRenderer
          margin="mb-1"
          value={unit}
          label="Unit"
          onChange={setUnit}
          options={['ns', 'ms', '%'].map((item) => ({ label: item, value: item }))}
        />

        <NgSelectRenderer
          margin="mb-1"
          value={flow}
          label="Flow"
          onChange={setFlow}
          options={['internal', 'user', 'all'].map((item) => ({
            label: firstLetterUppercase(item),
            value: item,
          }))}
        />

        <NgSelectRenderer
          margin="mb-1"
          value={sort}
          label="Sort"
          onChange={setSort}
          options={['flow', 'duration'].map((item) => ({ label: `by ${item}`, value: item }))}
        />

        <input
          name="step"
          type="text"
          className="form-control my-1"
          value={search}
          placeholder="Search a step"
          onChange={(e) => setSearch(e.target.value)}
        />
        <div className="d-flex flex-column">
          <div
            onClick={() => {
              setSelectedStep(-1);
              setSelectedPlugin(-1);
            }}
            className={`d-flex-between report-step ${selectedStep === -1 && selectedPlugin === -1 ? 'btn-primaryColor' : 'btn-quiet'}`}
          >
            <span>Report</span>
            <span>
              {reportDuration()} {unit}
            </span>
          </div>
          {[...steps]
            .filter(isOnFlow)
            .filter(isMatchingSearchWords)
            .sort(sortByFlow)
            .map((step) => {
              const name = step.task.replace(/-/g, ' ');
              const percentage = durationInPercentage(step.duration_ns, report.duration_ns);
              const displaySubList = step.ctx?.plugins?.length > 0 && flow !== 'internal';

              return (
                <div key={step.task} style={{ width: '100%' }}>
                  <div
                    onClick={() => {
                      setSelectedPlugin(-1);
                      setSelectedStep(step.task);
                    }}
                    className={`d-flex-between report-step ${step.task === selectedStep && selectedPlugin === -1 ? 'btn-primaryColor' : 'btn-quiet'}`}
                  >
                    <div className="d-flex align-items-center">
                      {displaySubList && (
                        <i
                          className={`fas fa-chevron-${
                            step.open || flow === 'user' ? 'down' : 'right'
                          } me-1`}
                          onClick={() =>
                            setSteps(
                              steps.map((s) =>
                                s.task === step.task ? { ...s, open: !step.open } : s
                              )
                            )
                          }
                        />
                      )}
                      <span>{firstLetterUppercase(name)}</span>
                    </div>
                    {(flow !== 'user' || step.task === 'call-backend') && (
                      <span style={{ maxWidth: '100px', textAlign: 'right' }}>
                        {unit === 'ms'
                          ? roundNsTo(step.duration_ns)
                          : unit === 'ns'
                            ? step.duration_ns
                            : percentage}{' '}
                        {unit}
                      </span>
                    )}
                  </div>
                  {(step.open || flow === 'user') &&
                    flow !== 'internal' &&
                    [...(step?.ctx?.plugins || [])]
                      .filter(isPluginNameMatchingSearch)
                      .sort(sortByFlow)
                      .map((plugin) => {
                        const pluginName = plugin.name.replace(/-/g, ' ').split('.').pop();
                        const pluginPercentage = durationInPercentage(
                          plugin.duration_ns,
                          report.duration_ns
                        );

                        return (
                          <div
                            key={plugin.name}
                            style={{
                              width: 'calc(100% - 12px)',
                              marginLeft: '12px',
                            }}
                            onClick={() => {
                              setSelectedStep(step.task);
                              setSelectedPlugin(plugin.name);
                            }}
                            className={`d-flex-between report-step ${step.task === selectedStep && plugin.name === selectedPlugin ? 'btn-primary' : 'btn-quiet'}`}
                          >
                            <span>{firstLetterUppercase(pluginName)}</span>
                            <span style={{ maxWidth: '100px', textAlign: 'right' }}>
                              {unit === 'ms'
                                ? roundNsTo(plugin.duration_ns)
                                : unit === 'ns'
                                  ? plugin.duration_ns
                                  : pluginPercentage}{' '}
                              {unit}
                            </span>
                          </div>
                        );
                      })}
                </div>
              );
            })}
        </div>
      </div>
      <NgCodeRenderer
        ngOptions={{ spread: true }}
        rawSchema={{
          props: {
            ace_config: {
              maxLines: 35,
              fontSize: 14,
            },
            editorOnly: true,
            height: '100%',
            mode: 'json',
          },
        }}
        onChange={() => {}}
        value={JSON.stringify(
          selectedPlugin === -1
            ? selectedStep === -1
              ? informations
              : steps.find((t) => t.task === selectedStep)
            : steps
                .find((t) => t.task === selectedStep)
                ?.ctx?.plugins.find((f) => f.name === selectedPlugin),
          null,
          4
        )}
      />
    </div>
  );
};

const Headers = ({
  headers,
  onKeyChange,
  onValueChange,
  onCheckedChange,
  addNewHeader,
  deleteAllHeaders,
  deleteHeader,
  className,
}) => (
  <div
    className={`w-100 div-overflowy border pb-3 ${className}`}
    style={{ height: onKeyChange ? '100%' : 'initial' }}
  >
    {addNewHeader && (
      <div className="d-flex align-items-center border-b mb-3">
        <div className="p-2 border-r" style={{ cursor: 'pointer' }} onClick={addNewHeader}>
          + Add
        </div>
        <div className="p-2 border-r" style={{ cursor: 'pointer' }} onClick={deleteAllHeaders}>
          Delete all
        </div>
      </div>
    )}
    {Object.entries(headers || {})
      .reduce((acc, curr) => (curr[1].key?.length === 0 ? [...acc, curr] : [curr, ...acc]), [])
      .map(([id, { key, value, checked }]) => (
        <div className="d-flex align-items-center mb-2 gap-2 px-2" key={id}>
          <input
            type="text"
            disabled={!onKeyChange}
            className="form-control flex"
            style={{
              borderRadius: 0,
              color: 'var(--text)',
            }}
            value={key}
            placeholder="Key"
            onChange={(e) => onKeyChange(id, e.target.value)}
          />
          <input
            type="text"
            style={{ borderRadius: 0, color: 'var(--text)' }}
            disabled={!onKeyChange}
            className="form-control flex"
            value={value}
            placeholder="Value"
            onChange={(e) => onValueChange(id, e.target.value)}
          />
          {deleteHeader && (
            <input
              type="checkbox"
              className="mx-1"
              checked={checked}
              onChange={() => onCheckedChange(id)}
            />
          )}
          {deleteHeader && <i className="fa fa-trash mx-1" onClick={() => deleteHeader(id)} />}
        </div>
      ))}
  </div>
);
