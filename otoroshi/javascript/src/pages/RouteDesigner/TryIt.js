import React, { Suspense, useEffect, useState } from 'react';
import range from 'lodash/range';
import { BooleanInput } from '../../components/inputs';
import {
  tryIt,
  fetchAllApikeys,
  findAllCertificates,
  routeEntries,
} from '../../services/BackOfficeServices';
import { firstLetterUppercase } from '../../util';
import { useLocation } from 'react-router-dom';

import { Provider } from 'react-redux';
import { Playground, store, getSettings, setSettingsString } from 'graphql-playground-react';
import { NgSelectRenderer } from '../../components/nginputs';
import { PillButton } from '../../components/PillButton';

const CodeInput = React.lazy(() => Promise.resolve(require('../../components/inputs/CodeInput')));

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'];

const CONTENT_TYPE = ['text', 'javascript', 'json', 'html', 'xml'];

const roundNsTo = (ns) => Number.parseFloat(round(ns) / 1000000).toFixed(3);
const round = (num) => Math.round((num + Number.EPSILON) * 100000) / 100000;

export default function ({ route, hide }) {
  const [selectedTab, setSelectedTab] = useState('Headers');
  const [selectedResponseTab, setSelectedResponseTab] = useState('Body');
  const [headersStatus, setHeadersStatus] = useState('down');
  const { pathname } = useLocation();

  const [search, setSearch] = useState('');
  const [unit, setUnit] = useState('ms');
  const [sort, setSort] = useState('flow');
  const [flow, setFlow] = useState('all');

  const [lastQuery, setLastQuery] = useState();
  const [testingRouteHistory, setTestingRouteHistory] = useState();

  const [request, updateRequest] = useState({
    path: '/',
    headers: { [Date.now()]: { key: '', value: '' } },
    method: METHODS[0],
    body: undefined,
    bodyContent: '',
    contentType: undefined,
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
  const [rawResponse, setRawResponse] = useState();
  const [response, setReponse] = useState();
  const [responseBody, setResponseBody] = useState();
  const [loading, setLoading] = useState(false);

  const [playgroundUrl, setPlaygroundUrl] = useState();
  const [testerView, setTesterView] = useState('rest');

  // patch weird react graphql playground behaviour
  // https://github.com/graphql/graphql-playground/issues/1037
  useEffect(() => {
    const state = store.getState();
    const settings = getSettings(state);
    settings['schema.polling.enable'] = false;
    store.dispatch(setSettingsString(JSON.stringify(settings, null, 2)));
  }, []);

  useEffect(() => {
    if (route && route.id) {
      updateRequest({
        ...request,
        route_id: route.id,
      });

      routeEntries(route.id).then((data) => {
        if (data.entries) setPlaygroundUrl(data.entries[0]);
      });

      setTesterView(
        route &&
          route.plugins.find((f) => f.plugin.includes('GraphQLBackend')) &&
          route.plugins.find((f) => f.plugin.includes('GraphQLBackend')).enabled &&
          playgroundUrl &&
          lastQuery
      );
    }
  }, [route]);

  useEffect(() => {
    if (testerView === 'graphql' && route) {
      hidePlaygroundStuff(route);
    }
  }, [route, testerView]);

  useEffect(() => {
    loadLastQuery();
    loadTestingRouteHistory();
  }, []);

  const setRequest = (newReq) => {
    saveTestingRouteHistory(newReq);
    updateRequest(newReq);
  };

  useEffect(() => {
    if (lastQuery) localStorage.removeItem('graphql-playground');
  }, [lastQuery]);

  const loadLastQuery = () => {
    try {
      const storedData = JSON.parse(localStorage.getItem('graphql-playground'));
      const query = Object.entries(Object.entries(storedData.workspaces)[1][1].history)[0][1].query;
      setLastQuery(query);
      localStorage.setItem('otoroshi-graphql-last-query', query);
    } catch (_) {
      const query = localStorage.getItem('otoroshi-graphql-last-query');
      setLastQuery(query || '{}');
    }
  };

  const loadTestingRouteHistory = () => {
    try {
      const storedData = JSON.parse(localStorage.getItem('testers'));
      const r = storedData.routes.find((r) => r.id === route.id);
      if (storedData && r) {
        setRequest(r);
      }
    } catch (_) {}
  };

  const saveTestingRouteHistory = (request) => {
    const storedData = JSON.parse(localStorage.getItem('testers'));
    if (!storedData)
      localStorage.setItem(
        'testers',
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
          'testers',
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
          'testers',
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

  const hidePlaygroundStuff = (route, retry) => {
    if (!route) {
      setTimeout(() => hidePlaygroundStuff(route, true), 500);
    } else if (route.plugins.find((f) => f.plugin.includes('GraphQLBackend'))) {
      const input = document.querySelector('.playground input');
      if (!input && !retry) {
        setTimeout(() => hidePlaygroundStuff(route, true), 100);
      } else {
        ['.playground > div', '.graphiql-wrapper > div > div > div  '].forEach((path) => {
          const element = document.querySelector(path);
          if (element.textContent) element.style.display = 'none';
        });

        const prettifyButton = [
          ...document.querySelectorAll('.graphiql-wrapper > div > div > div > button'),
        ].find((f) => f.textContent === 'Prettify');

        if (prettifyButton) {
          prettifyButton.className = 'btn btn-sm btn-success tryit-prettify-button';

          document.querySelector('.CodeMirror').appendChild(prettifyButton);
        }

        [...document.querySelectorAll('.playground svg')]
          .filter((svg) => svg.textContent === 'Settings')
          .forEach((svg) => (svg.style.display = 'none'));

        input.style.display = 'none';
      }
    }
  };

  useEffect(() => {
    fetchAllApikeys().then(r => setApikeys(r.data));
    findAllCertificates().then(res => setCertificates(res.data));
  }, []);

  const send = () => {
    setLoading(true);
    setRawResponse(undefined);
    setHeadersStatus('up');

    tryIt(
      {
        ...request,
        headers: Object.values(request.headers)
          .filter((d) => d.key.length > 0)
          .reduce((a, c) => ({ ...a, [c.key]: c.value }), {}),
      },
      pathname.includes('route-compositions') ? 'service' : 'route'
    )
      .then((res) => {
        setRawResponse(res);
        return res.json();
      })
      .then((res) => {
        setReponse(res);
        setLoading(false);

        if (res.status > 300) setSelectedResponseTab('Body');

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

  const saveResponse = (e) => {
    e.preventDefault();

    const blob = new Blob([JSON.stringify(response, null, 4)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.download = `tryit-${route.name}-${Date.now()}.json`;
    a.href = url;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
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
            },
          }
        : {
            'Otoroshi-Client-Id': { key: 'Otoroshi-Client-Id', value: clientId },
            'Otoroshi-Client-Secret': { key: 'Otoroshi-Client-Secret', value: clientSecret },
          }),
    };
  };

  const receivedResponse = rawResponse && response;

  return (
    <div className="graphql-form flex-column">
      <div className="d-flex-between m-2 mb-0">
        <h3>Testing</h3>
        {route &&
          route.plugins.find((f) => f.plugin === 'cp:otoroshi.next.plugins.GraphQLBackend') && (
            <PillButton
              style={{
                padding: '5px',
                borderRadius: '24px',
                backgroundColor: '#373735',
                position: 'relative',
              }}
              rightEnabled={!testerView || testerView === 'rest'}
              onLeftClick={() => setTesterView('rest')}
              onRightClick={() => setTesterView('graphql')}
              leftText="REST Tester"
              rightText="GraphQL Tester"
            />
          )}
        <button className="btn btn-sm" type="button" style={{ minWidth: '36px' }} onClick={hide}>
          <i className="fas fa-times dark-background" />
        </button>
      </div>
      {testerView === 'graphql' ? (
        <div style={{ minHeight: 'calc(100vh - 162px)' }}>
          <Provider store={store}>
            <Playground
              settings={{
                'schema.polling.enable': false,
                introspection: true,
              }}
              codeTheme={{
                editorBackground: '#3c3c3c',
                resultBackground: '#494948',
                textInactive: '#fff !important',
                executeButtonBorder: 'transparent',
              }}
              tabs={[
                {
                  endpoint: `/bo/api/graphqlproxy?url=${encodeURIComponent(playgroundUrl)}`,
                  query: lastQuery,
                  name: Date.now(),
                  // variables?: string
                  // responses?: string[]
                  // headers?: { [key: string]: string }
                },
              ]}
            />
          </Provider>
        </div>
      ) : (
        <div className="sub-container">
          <div className="d-flex">
            <div style={{ minWidth: '200px' }}>
              <NgSelectRenderer
                options={METHODS}
                value={request.method}
                ngOptions={{
                  spread: true,
                }}
                onChange={(method) => setRequest({ ...request, method })}
                optionsTransformer={(arr) =>
                  (arr || []).map((item) => ({ value: item, label: item }))
                }
              />
            </div>
            <input
              type="text"
              className="form-control mx-2"
              placeholder="Enter request URL"
              value={request.path}
              onChange={(e) => setRequest({ ...request, path: e.target.value })}
            />
            <button
              className="btn btn-success"
              style={{ backgroundColor: '#f9b000', borderColor: '#f9b000' }}
              onClick={send}>
              Send
            </button>
          </div>
          <div
            style={{
              height: headersStatus === 'down' ? '400px' : 'initial',
              flexDirection: 'column',
              overflowY: 'hidden',
              paddingBottom: headersStatus === 'down' ? '120px' : 0,
            }}>
            <div className="d-flex-between mt-3">
              <div className="d-flex">
                {[
                  { label: 'Authorization', value: 'Authorization' },
                  {
                    label: 'Headers',
                    value: `Headers (${Object.keys(request.headers || {}).length})`,
                  },
                  { label: 'Body', value: 'Body' },
                ].map(({ label, value }) => (
                  <button
                    onClick={() => {
                      setHeadersStatus('down');
                      setSelectedTab(label);
                    }}
                    className="pb-2 me-3"
                    style={{
                      padding: 0,
                      border: 0,
                      borderBottom: selectedTab === label ? '2px solid #f9b000' : 'transparent',
                      background: 'none',
                    }}>
                    {value}
                  </button>
                ))}
              </div>
              <i
                className={`tab fas fa-chevron-${headersStatus === 'up' ? 'down' : 'up'}`}
                onClick={() => setHeadersStatus(headersStatus === 'up' ? 'down' : 'up')}
              />
            </div>
            {selectedTab === 'Authorization' && headersStatus === 'down' && (
              <div className="d-flex">
                <div className="mt-3 flex">
                  <div className="d-flex-between pe-3" style={{ flex: 0.5 }}>
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
                  </div>
                  {request.useApikey && (
                    <div className="flex">
                      <div className="d-flex-between">
                        <div className="flex mt-2">
                          <NgSelectRenderer
                            value={request.apikey}
                            onChange={(k) => {
                              setRequest({
                                ...request,
                                apikey: k,
                                headers: apikeyToHeader(request.apikeyFormat, k),
                              });
                            }}
                            ngOptions={{
                              spread: true,
                            }}
                            options={apikeys}
                            optionsTransformer={(arr) =>
                              arr.map((item) => ({ value: item.clientId, label: item.clientName }))
                            }
                          />
                        </div>
                      </div>
                      {request.apikey && (
                        <div className="pt-3 mt-3" style={{ borderTop: '2px solid #494849' }}>
                          <div className="d-flex-between">
                            <span className="me-3">Apikey format</span>
                            <div className="flex">
                              <NgSelectRenderer
                                options={[
                                  { value: 'basic', label: 'Basic header' },
                                  { value: 'credentials', label: 'Client ID/Secret headers' },
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
                            </div>
                          </div>
                          {request.apikeyFormat === 'basic' && (
                            <div className="d-flex-between mt-3">
                              <span className="flex">Add to header</span>
                              <input
                                type="text"
                                className="form-control flex"
                                onChange={(e) => {
                                  setRequest({
                                    ...request,
                                    apikeyHeader: e.target.value,
                                    headers: apikeyToHeader(
                                      request.apikeyFormat,
                                      undefined,
                                      e.target.value
                                    ),
                                  });
                                }}
                                value={request.apikeyHeader}
                              />
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  )}
                </div>
                <div className="mt-3 ms-3 flex">
                  <div className="d-flex-between pe-3" style={{ flex: 0.5 }}>
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
                  </div>
                  {request.useCertificate && (
                    <div className="flex mt-2">
                      <div className="d-flex-between">
                        <div className="flex">
                          <NgSelectRenderer
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
                              arr.map((item) => ({ value: item.id, label: item.name }))
                            }
                          />
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            )}
            {selectedTab === 'Headers' && headersStatus === 'down' && (
              <Headers
                headers={request.headers}
                onKeyChange={(id, v) => {
                  const updatedRequest = {
                    ...request,
                    headers: {
                      ...request.headers,
                      [id]: { key: v, value: request.headers[id].value },
                    },
                  };
                  let item = {};
                  if (
                    Object.values(updatedRequest.headers).every(
                      (r) => r.key.length > 0 || r.value.length > 0
                    )
                  )
                    item = { [Date.now()]: { key: '', value: '' } };

                  setRequest({
                    ...updatedRequest,
                    headers: { ...updatedRequest.headers, ...item },
                  });
                }}
                onValueChange={(id, v) => {
                  const updatedRequest = {
                    ...request,
                    headers: {
                      ...request.headers,
                      [id]: { key: request.headers[id].key, value: v },
                    },
                  };

                  let item = {};
                  if (
                    Object.values(updatedRequest.headers).every(
                      (r) => r.key.length > 0 || r.value.length > 0
                    )
                  )
                    item = { [Date.now()]: { key: '', value: '' } };

                  setRequest({
                    ...updatedRequest,
                    headers: { ...updatedRequest.headers, ...item },
                  });
                }}
              />
            )}
            {selectedTab === 'Body' && headersStatus === 'down' && (
              <div className="mt-3" style={{ overflow: 'hidden' }}>
                <BooleanInput
                  label="Use a body"
                  value={request.body === 'raw' ? true : false}
                  onChange={() => {
                    const enabled = request.body === 'raw';
                    if (enabled) setRequest({ ...request, body: undefined });
                    else setRequest({ ...request, body: 'raw', contentType: 'json' });
                  }}
                />
                {request.body === 'raw' && (
                  <>
                    <NgSelectRenderer
                      label="Type of content"
                      options={CONTENT_TYPE}
                      value={request.contentType}
                      onChange={(contentType) => setRequest({ ...request, contentType })}
                      optionsTransformer={(arr) =>
                        arr.map((item) => ({ value: item, label: item }))
                      }
                    />
                    <Suspense fallback={<div>Loading ...</div>}>
                      <CodeInput
                        label="Content"
                        value={request.bodyContent}
                        mode={request.contentType}
                        onChange={(bodyContent) => setRequest({ ...request, bodyContent })}
                      />
                    </Suspense>
                  </>
                )}
              </div>
            )}
          </div>
          {receivedResponse && (
            <div className="d-flex flex-row-center mt-3">
              <div className="d-flex flex-row-center justify-content-between flex">
                <div>
                  {[
                    { label: 'Report', value: 'Report' },
                    { label: 'Body', value: 'Body' },
                    { label: 'Cookies', value: 'Cookies' },
                    {
                      label: 'Headers',
                      value: `Headers (${([...rawResponse.headers] || []).length})`,
                    },
                  ].map(({ label, value }) => (
                    <button
                      onClick={() => setSelectedResponseTab(label)}
                      className="pb-2 me-3"
                      style={{
                        padding: 0,
                        border: 0,
                        borderBottom:
                          selectedResponseTab === label ? '2px solid #f9b000' : 'transparent',
                        background: 'none',
                      }}>
                      {value}
                    </button>
                  ))}
                </div>
                <div className="d-flex flex-row-center">
                  <div className="d-flex flex-row-center me-3">
                    <span className="me-1">Status:</span>
                    <span style={{ color: 'var(--bs-success)' }}>{response.status}</span>
                  </div>
                  <div className="d-flex flex-row-center me-3">
                    <span className="me-1">Time:</span>
                    <span style={{ color: 'var(--bs-success)' }}>
                      {roundNsTo(response.report?.duration_ns)} ms
                    </span>
                  </div>
                  <div className="d-flex flex-row-center me-3">
                    <span className="me-1">Size:</span>
                    <span style={{ color: 'var(--bs-success)' }}>
                      {bytesToSize(rawResponse.headers.get('content-length'))}
                    </span>
                  </div>
                  <button
                    className="btn btn-sm btn-success"
                    style={{ backgroundColor: '#f9b000', borderColor: '#f9b000' }}
                    onClick={saveResponse}>
                    Save Response
                  </button>
                </div>
              </div>
            </div>
          )}
          {receivedResponse && selectedResponseTab === 'Headers' && (
            <Headers
              headers={[...rawResponse.headers].reduce(
                (acc, [key, value], index) => ({
                  ...acc,
                  [`${Date.now()}-${index}`]: { key, value },
                }),
                {}
              )}
            />
          )}

          {receivedResponse && responseBody && selectedResponseTab === 'Body' && (
            <div className="mt-3">
              {responseBody.startsWith('<!') ? (
                <iframe
                  srcDoc={responseBody}
                  style={{ flex: 1, minHeight: '750px', width: '100%' }}
                />
              ) : (
                <CodeInput readOnly={true} value={responseBody} width="-1" />
              )}
            </div>
          )}
          {!receivedResponse && !loading && (
            <div className="mt-3 text-center">
              <span>Enter the URL and click Send to get a response</span>
            </div>
          )}
          {loading && (
            <div className="d-flex justify-content-center">
              <i className="fas fa-cog fa-spin" style={{ fontSize: '40px' }} />
            </div>
          )}

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
        </div>
      )}
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

  const getNextFlowName = () =>
    firstLetterUppercase(flow === 'all' ? 'internal' : flow === 'internal' ? 'user' : 'all');
  const getNextSortName = () => (sort === 'flow' ? 'duration' : 'flow');
  const getUnitButtonClass = (enabled) => `btn btn-sm btn-${enabled ? 'success' : 'dark'}`;

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

  const spaces = range(0, 50)
    .map((i) => '          ')
    .join('');
  return (
    <div className="d-flex mt-3 flex reportview">
      <div className="main-view me-2" style={{ flex: 0.5, minWidth: '250px' }}>
        <div
          onClick={() => setSelectedStep(-1)}
          className="d-flex-between py-2"
          style={{ width: '100%' }}>
          <input
            type="text"
            className="form-control"
            value={search}
            placeholder="Search a step"
            onChange={(e) => setSearch(e.target.value)}
          />
          <div className="d-flex-between ms-1">
            <button className={getUnitButtonClass(unit === 'ns')} onClick={() => setUnit('ns')}>
              ns
            </button>
            <button
              className={`${getUnitButtonClass(unit === 'ms')} mx-1`}
              onClick={() => setUnit('ms')}>
              ms
            </button>
            <button className={getUnitButtonClass(unit === '%')} onClick={() => setUnit('%')}>
              %
            </button>
          </div>
        </div>
        <div className="d-flex-between mb-2" style={{ width: '100%' }}>
          <button
            className="btn btn-sm btn-success"
            onClick={() =>
              setFlow(flow === 'internal' ? 'user' : flow === 'user' ? 'all' : 'internal')
            }>
            {getNextFlowName()} flow
          </button>
          <button className="btn btn-sm btn-success" onClick={() => setSort(getNextSortName())}>
            Sort by {getNextSortName()}
          </button>
        </div>
        <div
          onClick={() => setSelectedStep(-1)}
          className={`d-flex-between mt-1 px-3 py-2 report-step btn btn-${
            informations.state === 'Successful' ? 'success' : 'danger'
          }`}>
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
                  className={`d-flex-between mt-1 px-3 py-2 report-step ${
                    step.task === selectedStep ? 'btn-dark' : ''
                  }`}>
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
                          style={{ width: 'calc(100% - 12px)', marginLeft: '12px' }}
                          onClick={() => setSelectedPlugin(plugin.name)}
                          className={`d-flex-between mt-1 px-3 py-2 report-step ${
                            step.task === selectedStep && plugin.name === selectedPlugin
                              ? 'btn-dark'
                              : ''
                          }`}>
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
      <CodeInput
        readOnly={true}
        width="100%"
        editorOnly={true}
        ace_config={{
          maxLines: Infinity,
        }}
        value={
          JSON.stringify(
            selectedPlugin === -1
              ? selectedStep === -1
                ? informations
                : steps.find((t) => t.task === selectedStep)
              : steps
                  .find((t) => t.task === selectedStep)
                  ?.ctx?.plugins.find((f) => f.name === selectedPlugin),
            null,
            4
          ) +
          '\n' +
          spaces
        }
      />
    </div>
  );
};

const Headers = ({ headers, onKeyChange, onValueChange }) => (
  <div
    className="mt-2 w-100 div-overflowy pb-3"
    style={{
      height: onKeyChange ? '100%' : 'initial',
      overflowY: 'scroll',
    }}>
    <div className="d-flex-between">
      <span className="flex py-1" style={{ fontWeight: 'bold' }}>
        KEY
      </span>
      <span className="flex py-1" style={{ fontWeight: 'bold' }}>
        VALUE
      </span>
    </div>
    <div>
      {Object.entries(headers || {})
        .reduce((acc, curr) => (curr[1].key.length === 0 ? [...acc, curr] : [curr, ...acc]), [])
        .map(([id, { key, value }]) => (
          <div className="d-flex-between" key={id}>
            <input
              type="text"
              disabled={!onKeyChange}
              className="form-control flex mb-1 me-1"
              value={key}
              placeholder="Key"
              onChange={(e) => onKeyChange(id, e.target.value)}
            />
            <input
              type="text"
              disabled={!onKeyChange}
              className="form-control flex mb-1 me-1"
              value={value}
              placeholder="Value"
              onChange={(e) => onValueChange(id, e.target.value)}
            />
          </div>
        ))}
    </div>
  </div>
);
