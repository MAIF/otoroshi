import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import moment from 'moment';
import { useSignalValue } from 'signals-react-safe';
import { nextClient, routePorts } from '../../services/BackOfficeServices';
import { Uptime } from '../../components/Status';
import { Button } from '../../components/Button';
import { JsonObjectAsCodeInput } from '../../components/inputs/CodeInput';
import { HTTP_COLORS } from '../RouteDesigner/MocksDesigner';
import { unsecuredCopyToClipboard } from '../../util';
import { ApiStats } from './ApiStats';
import { ApiQualityCard } from './ApiQualityCard';
import { API_STATE } from './model';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';
import { VersionManager } from './VersionManager';
import { publishAPI } from './Actions';
import { signalVersion } from './VersionSignal';
import { signalHighlight } from './HighlightSignal';
import { fetchWrapperNext } from '../../services/BackOfficeServices';

export function ContainerBlock({ children, full, highlighted, style = {} }) {
  return (
    <div
      className={`container ${full ? 'container--full' : ''} ${highlighted ? 'container--highlighted' : ''}`}
      style={{
        margin: 0,
        position: 'relative',
        height: 'fit-content',
        ...style,
      }}
    >
      {children}
    </div>
  );
}

export function APIState({ value }) {
  if (value === API_STATE.STAGING)
    return (
      <span className="badge custom-badge api-status-started">
        <i className="fas fa-rocket me-2" />
        Staging
      </span>
    );

  if (value === API_STATE.DEPRECATED)
    return (
      <span className="badge custom-badge api-status-deprecated">
        <i className="fas fa-warning me-2" />
        Deprecated
      </span>
    );

  if (value === API_STATE.PUBLISHED)
    return (
      <span className="badge custom-badge api-status-published">
        <i className="fas fa-check fa-xs me-2" />
        Published
      </span>
    );

  // TODO  - manage API_STATE.REMOVED
  return null;
}

function SectionHeader({ text, description, main, actions, icon }) {
  return (
    <div className="dashboard-section-header">
      <div className="d-flex align-items-center justify-content-between">
        <div className="d-flex align-items-center gap-2">
          {icon && <i className={`${icon} dashboard-section-icon`} />}
          {main ? <h1 className="m-0">{text}</h1> : <h3 className="m-0">{text}</h3>}
        </div>
        {actions}
      </div>
      {description && <p className="dashboard-section-description">{description}</p>}
    </div>
  );
}

function Entities({ children }) {
  return <div className="d-flex flex-column gap-3">{children}</div>;
}

function QuickStat({ icon, label, value, onClick }) {
  return (
    <div className="dashboard-quick-stat" onClick={onClick}>
      <div className="dashboard-quick-stat-icon">
        <i className={icon} />
      </div>
      <div className="dashboard-quick-stat-body">
        <span className="dashboard-quick-stat-value">{value}</span>
        <span className="dashboard-quick-stat-label">{label}</span>
      </div>
    </div>
  );
}

function APIHeader({ api, version, draft }) {
  const history = useHistory();

  const updateAPI = (newAPI) => {
    return nextClient.forEntityNext(nextClient.ENTITIES.APIS).update(newAPI);
  };

  return (
    <>
      <div className="d-flex align-items-center gap-3">
        <h2 className="m-0">{api.name}</h2>
        <span
          className="badge custom-badge api-status-started"
          style={{
            fontSize: '.75rem',
          }}
        >
          {api.version}
        </span>
        {version !== 'Published' && (
          <span className="badge custom-badge api-status-started d-flex align-items-center gap-2">
            <div
              className={`testing-dot ${draft.testing?.enabled ? 'testing-dot--enabled' : 'testing-dot--disabled'}`}
            ></div>
            {draft.testing?.enabled ? 'Testing enabled' : 'Testing disabled'}
          </span>
        )}
        <APIState value={api.state} />
      </div>
      <div className="d-flex align-items-center gap-1 mb-3">
        <p className="m-0 me-2">{api.description}</p>
        {api.tags.map((tag) => (
          <span className="tag" key={tag}>
            {tag}
          </span>
        ))}
      </div>
    </>
  );
}

function HighlighedText({ text, link }) {
  const location = useLocation();
  return (
    <Link
      to={{
        pathname: link,
        search: location.search,
      }}
      className="highlighted-text"
    >
      {text}
    </Link>
  );
}

function HighlighedPluginsText({ plural }) {
  const params = useParams();
  return (
    <HighlighedText
      text={plural ? 'plugins' : 'plugin'}
      link={`/apis/${params.apiId}/plugin-chains`}
    />
  );
}

function HighlighedBackendText({ plural }) {
  const params = useParams();
  return (
    <HighlighedText
      text={plural ? 'backends' : 'backend'}
      link={`/apis/${params.apiId}/backends`}
    />
  );
}

function HighlighedFrontendText({ plural }) {
  const params = useParams();
  return (
    <HighlighedText
      text={plural ? 'frontends' : 'frontend'}
      link={`/apis/${params.apiId}/frontends`}
    />
  );
}

function HighlighedEndpointText({ plural }) {
  const params = useParams();
  return (
    <HighlighedText
      text={plural ? 'endpoints' : 'endpoint'}
      link={`/apis/${params.apiId}/endpoints`}
    />
  );
}

function HighlighedPluginChainsText({ plural }) {
  const params = useParams();
  return (
    <HighlighedText
      text={plural ? 'plugin chains' : 'plugin chain'}
      link={`/apis/${params.apiId}/plugin-chains`}
    />
  );
}

function BackendsCard({ backends }) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  return (
    <div
      onClick={() => historyPush(history, location, `/apis/${params.apiId}/backends`)}
      className="cards apis-cards"
    >
      <div className="cards-body">
        <div className="cards-title d-flex align-items-center justify-content-between">
          Backends{' '}
          <span className="badge custom-badge api-status-deprecated">
            <i className="fas fa-microchip me-2" />
            {backends.length}
          </span>
        </div>
        <p className="cards-description" style={{ position: 'relative' }}>
          Design robust, scalable <HighlighedBackendText plural /> with optimized performance,
          security, and seamless front-end integration.
          <i className="fas fa-chevron-right fa-lg navigate-icon" />
        </p>
      </div>
    </div>
  );
}

function EndpointsCard({ routes }) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  return (
    <div
      onClick={() => historyPush(history, location, `/apis/${params.apiId}/endpoints`)}
      className="cards apis-cards"
    >
      <div className="cards-body">
        <div className="cards-title d-flex align-items-center justify-content-between">
          Endpoints{' '}
          <span className="badge custom-badge api-status-deprecated">
            <i className="fas fa-road me-2" />
            {routes.length}
          </span>
        </div>
        <p className="cards-description relative">
          Define your <HighlighedEndpointText />: connect <HighlighedFrontendText plural /> to{' '}
          <HighlighedBackendText plural /> and customize behavior with{' '}
          <HighlighedPluginChainsText plural /> like authentication, rate limiting, and
          transformations.
          <i className="fas fa-chevron-right fa-lg navigate-icon" />
        </p>
      </div>
    </div>
  );
}

function PluginChainsCard({ flows }) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  return (
    <div
      onClick={() => historyPush(history, location, `/apis/${params.apiId}/plugin-chains`)}
      className="cards apis-cards"
    >
      <div className="cards-body">
        <div className="cards-title d-flex align-items-center justify-content-between">
          Plugin chains{' '}
          <span className="badge custom-badge api-status-deprecated">
            <i className="fas fa-road me-2" />
            {flows.length}
          </span>
        </div>
        <p className="cards-description relative">
          Create groups of <HighlighedPluginsText plural /> to apply rules, transformations, and
          restrictions on <HighlighedEndpointText plural />, enabling advanced traffic control and
          customization.
          <i className="fas fa-chevron-right fa-lg navigate-icon" />
        </p>
      </div>
    </div>
  );
}

function RouteItem({ item, api, ports, isDraft }) {
  const { frontend } = item;

  const params = useParams();
  const location = useLocation();
  const history = useHistory();

  const version = useSignalValue(signalVersion);

  const routeEntries = (idx) => {
    const isSecured = api.flows.some((r) =>
      r.plugins.find((p) => p.plugin.includes('ForceHttpsTraffic'))
    );

    const domain = item.frontend.domains[idx];

    const scheme = isSecured ? 'https://' : 'http://';

    return `${scheme}${api.domain}:${ports.http}${api.contextPath}${domain}`;
  };

  const rawMethods = (frontend.methods || []).filter((m) => m.length);

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
      : [<span className="badge bg-success">ALL</span>];

  const goTo = (idx) => window.open(routeEntries(idx), '_blank');

  return frontend.domains.map((domain, idx) => {
    const [copyIconName, setCopyIconName] = useState('fas fa-copy');

    const copy = (value, method, setCopyIconName) => {
      let command = value;

      if (version === 'Draft' || version === 'staging') {
        command = `curl ${method ? `-X ${method}` : ''} ${value} -H '${api.testing?.headerKey}: ${api.testing?.headerValue}'`;
      } else {
        command = `curl ${method ? `-X ${method}` : ''} ${value}`;
      }

      if (window.isSecureContext && navigator.clipboard) {
        navigator.clipboard.writeText(command);
      } else {
        unsecuredCopyToClipboard(command);
      }
      setCopyIconName('fas fa-check');

      setTimeout(() => {
        setCopyIconName('fas fa-copy');
      }, 2000);
    };

    const exact = frontend.exact;
    const end = exact ? '' : domain.indexOf('/') < 0 ? '/*' : '*';
    const start = 'http://';
    return allMethods.map((method, i) => {
      return (
        <div className="short-table-row routes-table-row" key={`allmethods-${i}`}>
          <div>{item.name}</div>
          <span
            style={{
              whiteSpace: 'nowrap',
              textOverflow: 'ellipsis',
              overflow: 'hidden',
              maxWidth: 310,
            }}
          >
            {routeEntries(idx)}
            {end}
          </span>
          <div style={{ minWidth: 60 }}>{method}</div>
          {isDraft && !api.testing.enabled && (
            <Button
              type="primaryColor"
              className="btn btn-sm"
              onClick={() => historyPush(history, location, `/apis/${params.apiId}/testing`)}
            >
              Enable API testing
            </Button>
          )}
          {(!isDraft || api.testing.enabled) && (
            <div className="d-flex align-items-center justify-content-start">
              <Button
                className="btn btn-sm"
                type="primaryColor"
                title="Copy URL"
                onClick={() => copy(routeEntries(idx), rawMethods[i], setCopyIconName)}
              >
                <i className={copyIconName} />
              </Button>
              {rawMethods[i] === 'GET' && version === 'Published' && (
                <Button
                  className="btn btn-sm ms-1"
                  type="primaryColor"
                  title={`Go to ${start}${domain}`}
                  onClick={() => goTo(idx)}
                >
                  <i className="fas fa-external-link-alt" />
                </Button>
              )}
            </div>
          )}
        </div>
      );
    });
  });
}

export function RoutesView({ api, isDraft }) {
  const ports = useQuery(['getPorts'], routePorts);

  if (ports.isLoading) return <SimpleLoader />;

  return (
    <div>
      <div className="short-table-row routes-table-row">
        <div>Name</div>
        <div>Frontend</div>
        <div>Methods</div>
        <div>Actions</div>
      </div>
      {api.routes.map((route) => (
        <RouteItem item={route} api={api} key={route.id} ports={ports.data} isDraft={isDraft} />
      ))}
    </div>
  );
}

function Subscription({ subscription }) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const [open, setOpen] = useState(false);

  return (
    <div
      key={subscription.id}
      className="short-table-row"
      style={{
        backgroundColor: 'hsla(184, 9%, 62%, 0.18)',
        borderColor: 'hsla(184, 9%, 62%, 0.4)',
        borderRadius: '.5rem',
        gridTemplateColumns: open ? '1fr' : 'repeat(3, 1fr) 54px 32px',
        position: 'relative',
      }}
      onClick={() => {
        if (!open) setOpen(true);
      }}
    >
      {open && (
        <div className="d-flex justify-content-between gap-2 align-items-center">
          <div style={{ position: 'relative', flex: 1 }}>
            <DraftOnly>
              <Button
                type="primaryColor"
                className="btn-sm"
                text="Edit"
                onClick={(e) => {
                  e.stopPropagation();
                  historyPush(
                    history,
                    location,
                    `/apis/${params.apiId}/subscriptions/${subscription.id}/edit`
                  );
                }}
                style={{
                  position: 'absolute',
                  top: '.5rem',
                  right: '.5rem',
                  zIndex: 100,
                }}
              />
            </DraftOnly>
            <JsonObjectAsCodeInput
              editorOnly
              showGutter={false}
              label={undefined}
              value={subscription}
            />
          </div>
          <i
            style={{ minWidth: 40 }}
            className="fas fa-chevron-up fa-lg short-table-navigate-icon"
            onClick={() => setOpen(false)}
          />
        </div>
      )}
      {!open && (
        <>
          <div>{subscription.name}</div>
          <div>{subscription.description}</div>
          <div>{moment(new Date(subscription.dates.created_at)).format('DD/MM/YY hh:mm')}</div>
          <div className="badge custom-badge bg-success" style={{ border: 'none' }}>
            {subscription.subscription_kind}
          </div>
          <i className="fas fa-chevron-right fa-lg short-table-navigate-icon" />
        </>
      )}
    </div>
  );
}

function SubscriptionsView({ api }) {
  const [subscriptions, setSubscriptions] = useState([]);

  useEffect(() => {
    nextClient
      .forEntityNext(nextClient.ENTITIES.API_SUBSCRIPTIONS)
      .findAllWithPagination({
        page: 1,
        pageSize: 5,
        filtered: [
          {
            id: 'api_ref',
            value: api.id,
          },
        ],
        sorted: [
          {
            id: 'dates.created_at',
            desc: false,
          },
        ],
      })
      .then((raw) => setSubscriptions(raw.data));
  }, []);
  return (
    <div>
      <div
        className="short-table-row"
        style={{
          gridTemplateColumns: 'repeat(3, 1fr) 54px 32px',
        }}
      >
        <div>Name</div>
        <div>Description</div>
        <div>Created At</div>
        <div>Kind</div>
      </div>
      {subscriptions.map((subscription) => {
        return <Subscription subscription={subscription} key={subscription.id} />;
      })}
    </div>
  );
}

function DashboardTitle({ item, api, draftWrapper, draft, ...props }) {
  const history = useHistory();
  const highlight = useSignalValue(signalHighlight);

  // Publish CTA visible for any state except `removed` (a removed API cannot be
  // redeployed). `<DraftOnly>` already scopes to draft / staging — you publish
  // FROM the draft, not from the prod view.
  const canPublish = item.state !== 'removed';
  const isFirstPublish = item.state === 'staging';

  // Spotlight effect: when the stepper "Deploy" step is clicked, it sets
  // signalHighlight to 'publish-this-version'. We scroll the button into view,
  // pulse it, then clear the signal.
  useEffect(() => {
    if (highlight !== 'publish-this-version') return;
    const btn = document.querySelector('[data-testid="publish-this-version"]');
    if (!btn) return;
    btn.scrollIntoView({ behavior: 'smooth', block: 'center' });
    btn.classList.add('gs-spotlight');
    const timer = setTimeout(() => {
      btn.classList.remove('gs-spotlight');
      signalHighlight.value = null;
    }, 3000);
    return () => {
      clearTimeout(timer);
      btn.classList.remove('gs-spotlight');
    };
  }, [highlight]);

  const onPublishClick = () => {
    if (isFirstPublish) {
      // No version bump for first publish — straight deployment of the draft.
      publishAPI(draft, item, history);
      return;
    }
    // Re-publish (published / deprecated): open the version manager wizard.
    nextClient
      .forEntityNext(nextClient.ENTITIES.APIS)
      .findById(props.params.apiId)
      .then((freshApi) => {
        window
          .wizard(
            'Version manager',
            (ok, cancel, state, setState) => (
              <VersionManager
                api={freshApi}
                draft={draftWrapper}
                owner={props.globalEnv.user}
                setState={setState}
              />
            ),
            {
              style: { width: '100%' },
              noCancel: false,
              okClassName: 'ms-2',
              okLabel: 'Publish this version',
            }
          )
          .then((deployment) => {
            if (deployment) {
              fetchWrapperNext(
                `/${nextClient.ENTITIES.APIS}/${freshApi.id}/deployments`,
                'POST',
                deployment,
                'apis.otoroshi.io'
              ).then(() => {
                history.push(`/apis/${freshApi.id}`);
              });
            }
          });
      });
  };

  return (
    <div className="page-header_title d-flex align-item-center justify-content-between mb-3">
      <div className="d-flex">
        <h3 className="m-0 d-flex align-items-center">
          {item?.name}
          <VersionBadge />
        </h3>
      </div>
      <div className="d-flex align-item-center justify-content-between">
        <DraftOnly>
          {canPublish && (
            <div className="d-flex align-items-center">
              <Button
                text={isFirstPublish ? 'Publish API' : 'Publish this version'}
                className="btn-sm mx-2"
                type="primaryColor"
                style={{
                  borderColor: 'var(--color-primary)',
                }}
                data-testid="publish-this-version"
                onClick={onPublishClick}
              />
            </div>
          )}
        </DraftOnly>
      </div>
    </div>
  );
}

export function Dashboard(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  const { item, draft, draftWrapper, version, api, isDraft } = useDraftOfAPI();

  if (!draft || !item) return <SimpleLoader />;

  // Flags still used by the layout (Endpoints / Subscriptions cards below).
  // The full getting-started progress is now owned by GettingStartedStepper.
  const hasCreateRoute = item.routes.length > 0;
  const hasCreatePlan = item.plans?.length > 0;

  // TODO
  const totalSubscriptions = 0; // item.consumers.flatMap((c) => c.subscriptions).length;

  return (
    <>
      <DashboardTitle
        {...props}
        params={params}
        item={item}
        api={api}
        draftWrapper={draftWrapper}
        draft={draft}
      />
      <div className="dashboard-layout">
        {/* API Header */}
        <ContainerBlock full highlighted>
          <APIHeader api={item} version={version} draft={draft} />
        </ContainerBlock>

        {/* Quick Stats Row */}
        <div className="dashboard-stats-row">
          <QuickStat
            icon="fas fa-road"
            label="Endpoints"
            value={item.routes.length}
            onClick={() => historyPush(history, location, `/apis/${params.apiId}/endpoints`)}
          />
          <QuickStat
            icon="fas fa-key"
            label="Subscriptions"
            value={totalSubscriptions}
            onClick={() => historyPush(history, location, `/apis/${params.apiId}/subscriptions`)}
          />
          <QuickStat
            icon="fas fa-project-diagram"
            label="Plugin chains"
            value={item.flows.length}
            onClick={() => historyPush(history, location, `/apis/${params.apiId}/plugin-chains`)}
          />
          <QuickStat
            icon="fas fa-microchip"
            label="Backends"
            value={item.backends.length}
            onClick={() => historyPush(history, location, `/apis/${params.apiId}/backends`)}
          />
        </div>

        {/* Stepper is rendered globally (floating bottom-right) — see below */}

        {/* Main two-column grid */}
        <div className="dashboard-grid">
          <div className="dashboard-main">
            {/* Live Metrics */}
            <ContainerBlock full>
              <SectionHeader
                text="Live Metrics"
                description="Real-time traffic overview"
                icon="fas fa-chart-line"
              />
              <ApiStats
                url={
                  version === 'Published'
                    ? `/bo/api/proxy/apis/apis.otoroshi.io/v1/apis/${item.id}/live?every=2000`
                    : `/bo/api/proxy/apis/proxy.otoroshi.io/v1/drafts/${item.id}/live?every=2000`
                }
              />
            </ContainerBlock>

            {/* API Quality */}
            <ContainerBlock full>
              <SectionHeader
                text="API Quality"
                description="How complete and precise this API definition is"
                icon="fas fa-clipboard-check"
                actions={
                  <Link
                    to={{ pathname: `/apis/${params.apiId}/quality`, search: location.search }}
                    className="btn btn-sm btn-primaryColor"
                  >
                    View details
                  </Link>
                }
              />
              <ApiQualityCard api={item} />
            </ContainerBlock>

            {/* Health */}
            <ContainerBlock full>
              <SectionHeader
                text="Health"
                description="Uptime over the last 3 days"
                icon="fas fa-heartbeat"
              />
              <div className="dashboard-health-grid">
                <div className="dashboard-health-day">
                  <span className="dashboard-health-label">Today</span>
                  <Uptime health={item.health?.today} stopTheCountUnknownStatus={false} />
                </div>
                <div className="dashboard-health-day">
                  <span className="dashboard-health-label">Yesterday</span>
                  <Uptime health={item.health?.yesterday} stopTheCountUnknownStatus={false} />
                </div>
                <div className="dashboard-health-day">
                  <span className="dashboard-health-label">2 days ago</span>
                  <Uptime health={item.health?.nMinus2} stopTheCountUnknownStatus={false} />
                </div>
              </div>
            </ContainerBlock>

            {/* Endpoints Table */}
            {hasCreateRoute && hasCreatePlan && (
              <ContainerBlock full>
                <SectionHeader
                  text="Endpoints"
                  description="Exposed endpoints for this API"
                  icon="fas fa-road"
                />
                <RoutesView api={item} isDraft={isDraft} />
              </ContainerBlock>
            )}

            {/* Subscriptions Table */}
            {hasCreatePlan && (
              <ContainerBlock full>
                <SectionHeader
                  text="Subscriptions"
                  description={totalSubscriptions <= 0 ? 'Subscriptions will appear here' : ''}
                  icon="fas fa-key"
                  actions={
                    <DraftOnly>
                      <Button
                        type="primaryColor"
                        text="Subscribe"
                        className="btn-sm"
                        onClick={() =>
                          historyPush(history, location, `/apis/${params.apiId}/subscriptions/new`)
                        }
                      />
                    </DraftOnly>
                  }
                />
                <SubscriptionsView api={item} />
              </ContainerBlock>
            )}
          </div>

          {/* Sidebar: entity nav cards */}
          {item.flows.length > 0 && item.routes.length > 0 && (
            <div className="dashboard-sidebar">
              <ContainerBlock full>
                <SectionHeader
                  text="Build your API"
                  description="Manage entities"
                  icon="fas fa-cubes"
                />
                <Entities>
                  <PluginChainsCard flows={item.flows} />
                  <BackendsCard backends={item.backends} />
                  <EndpointsCard routes={item.routes} />
                </Entities>
              </ContainerBlock>
            </div>
          )}
        </div>
      </div>
    </>
  );
}
