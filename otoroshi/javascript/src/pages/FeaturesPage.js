import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import _ from 'lodash';
import { icon as snowmonkeyIcon } from '../components/SnowMonkeyConfig.js';

export const graph = (env) => {
  return [
    {
      title: 'Tooling',
      description: 'Some tools to help you with otoroshi',
      features: [
        {
          title: 'Tester',
          img: 'tester',
          description:
            'A tool for testing and validating Otoroshi components such as services, routes, and plugins.',
          display: () => true,
          link: '/tester',
          icon: () => 'fa-hammer',
        },
        {
          title: 'Resources loader',
          img: 'resource-loader',
          description: 'Load one or more resources from text or files in one shot',
          display: () => true,
          link: '/resources-loader',
          icon: () => 'fa-hammer',
        },
        {
          title: 'Snow monkey',
          absoluteImg: '/assets/images/nihonzaru.svg',
          description: 'Create chaos in your routes and test your resilience',
          display: () => env.userAdmin,
          link: '/snowmonkey',
          icon: () => snowmonkeyIcon,
        },
        {
          title: 'Clever Cloud apps',
          img: 'clever',
          description: 'Create routes from Clever Cloud apps',
          display: () => env.userAdmin,
          link: '/clever',
          icon: () => 'fa-list-alt',
        },
        {
          title: 'User manual',
          img: 'manual',
          description: 'You have a question ? Read everything you need to know about otoroshi',
          display: () => true,
          link: 'https://maif.github.io/otoroshi/manual/index.html',
          icon: () => 'fa-book',
        },
      ],
    },
    {
      title: 'Manage resources',
      description: 'Manage otoroshi resources',
      features: [
        {
          title: 'Services',
          description: 'All your service descriptors',
          img: 'services',
          display: () => true,
          icon: () => 'fa-cubes',
          link: '/services',
        },
        {
          title: 'Routes',
          description: 'All your routes',
          img: 'routes',
          display: () => true,
          icon: () => 'fa-road',
          link: '/routes',
        },
        {
          title: 'Backends',
          description: 'All your route backends',
          img: 'backend',
          display: () => true,
          icon: () => 'fa-microchip',
          link: '/backends',
        },
        {
          title: 'Apikeys',
          description: 'All your apikeys',
          img: 'apikeys',
          display: () => true,
          icon: () => 'fa-key',
          link: '/apikeys',
        },
        {
          title: 'Certificates',
          description: 'All your certificates',
          img: 'certificates',
          display: () => true,
          icon: () => 'fa-certificate',
          link: '/certificates',
        },
        {
          title: 'JWT verifiers',
          description: 'All your jwt verifiers',
          img: 'jwt',
          display: () => true,
          icon: () => 'fa-circle-check',
          link: '/jwt-verifiers',
        },
        {
          title: 'Auth. modules',
          description: 'All your authentication modules',
          img: 'private-apps',
          display: () => true,
          icon: () => 'fa-lock',
          link: '/auth-configs',
        },
        {
          title: 'TCP services',
          description: 'All your TCP services',
          img: 'tcp',
          display: () => true,
          icon: () => 'fa-cubes',
          link: '/tcp/services',
        },
        {
          title: 'Organizations',
          description: 'All your organizations',
          img: 'orga',
          display: () => env.userAdmin,
          icon: () => 'fa-folder-open',
          link: '/organizations',
        },
        {
          title: 'Teams',
          description: 'All your temas',
          img: 'teams',
          display: () => env.tenantAdmin,
          icon: () => 'fa-folder-open',
          link: '/teams',
        },
        {
          title: 'Service groups',
          description: 'All your service/route groups',
          img: 'groups',
          display: () => true,
          icon: () => 'fa-folder-open',
          link: '/groups',
        },
        {
          title: 'Data exporters',
          description: 'All your data exporters',
          img: 'exporters',
          display: () => true,
          icon: () => 'fa-paper-plane',
          link: '/exporters',
        },
        {
          title: 'Administrators',
          description: 'All your otoroshi administrators',
          img: 'admins',
          display: () => env.tenantAdmin,
          icon: () => 'fa-user',
          link: '/admins',
        },
        {
          title: 'Error Templates',
          description: 'All your route and services error templates',
          img: 'error',
          icon: () => 'fa-bomb',
          link: '/error-templates',
        },
        {
          title: 'Scripts',
          description: 'All your live scripts',
          img: 'scripts',
          display: () => false, // () => env.scriptingEnabled,
          icon: () => 'fa-book-dead',
          link: '/plugins',
        },
        {
          title: 'Wasm Plugins',
          description: 'All your wasm plugins',
          img: 'plugins',
          icon: () => 'fa-plug',
          link: '/wasm-plugins',
        },
        {
          title: 'Drafts',
          description: 'All drafts of your entities',
          img: 'drafts',
          icon: () => 'fa-pencil-ruler',
          link: '/drafts',
        },
        {
          title: 'APIs',
          description: 'All apis',
          img: 'routes',
          icon: () => 'fa-brush',
          link: '/apis',
          tag: <span className="badge bg-xs bg-warning">ALPHA</span>,
        },
      ],
    },
    {
      title: 'Extensions',
      description: 'All the features provided by your installed extensions',
      features: Otoroshi.extensions().flatMap((ext) => ext.features || []),
    },
    {
      title: 'Analytics',
      description: 'Everything about everything on your otoroshi cluster',
      features: [
        {
          title: 'Analytics',
          description: 'All the traffic of your otoroshi cluster visualized in one place',
          img: 'analytics',
          display: () => env.userAdmin || env.tenantAdmin,
          link: '/stats',
          icon: () => 'fa-signal',
        },
        {
          title: 'Global Status',
          description: 'Availability of your services over time',
          img: 'global-status',
          link: '/status',
          display: () => env.userAdmin || env.tenantAdmin,
          icon: () => 'fa-heart',
        },
        {
          title: 'Events log',
          description: 'Everything that is happening on your otoroshi cluster',
          img: 'events',
          link: '/events',
          display: () => env.userAdmin || env.tenantAdmin,
          icon: () => 'fa-list',
        },
        {
          title: 'Audit log',
          description: 'List all administrator actions on your otoroshi cluster',
          img: 'audit',
          link: '/audit',
          display: () => env.userAdmin || env.tenantAdmin,
          icon: () => 'fa-list',
        },
        {
          title: 'Alerts log',
          description: 'List all alerts happening on your otoroshi cluster',
          img: 'alerts',
          link: '/alerts',
          display: () => env.userAdmin || env.tenantAdmin,
          icon: () => 'fa-list',
        },
      ],
    },
    {
      title: 'Sessions',
      description: 'Manage the sessions of your users here',
      features: [
        {
          title: 'Admins sessions',
          description: 'List all the connected administrator sessions',
          img: 'auth-sessions',
          link: '/sessions/admin',
          display: () => env.userAdmin || env.tenantAdmin,
          icon: () => 'fa-user',
        },
        {
          title: 'Auth. module sessions',
          description: 'List all the connected user sessions from auth. modules',
          img: 'admin-sessions',
          link: '/sessions/private',
          display: () => env.userAdmin || env.tenantAdmin,
          icon: () => 'fa-lock',
        },
      ],
    },
    {
      title: 'Security',
      description: 'Everything security related',
      features: [
        {
          title: 'Auth. modules',
          description:
            'Manage the access to Otoroshi UI and protect your routes with authentication modules.',
          img: 'private-apps',
          link: '/auth-configs',
          icon: () => 'fa-lock',
          display: () => true,
        },
        {
          title: 'Jwt verifiers',
          description: 'Manage how you want to verify and forge jwt tokens',
          img: 'jwt',
          link: '/jwt-verifiers',
          icon: () => 'fa-circle-check',
          display: () => true,
        },
        {
          title: 'Certificates',
          description: 'Manage and generate certificates for call and expose your services',
          img: 'certificates',
          link: '/certificates',
          icon: () => 'fa-certificate',
          display: () => true,
        },
        {
          title: 'Apikeys',
          description: 'Manage all your apikeys to access all your services',
          img: 'apikeys',
          link: '/apikeys',
          icon: () => 'fa-key',
          display: () => true,
        },
        {
          title: 'Administrators',
          description: 'All your otoroshi administrators',
          img: 'admins',
          display: () => env.tenantAdmin,
          icon: () => 'fa-user',
          link: '/admins',
        },
      ],
    },
    {
      title: 'Networking',
      description: 'Everything network related',
      features: [
        {
          title: 'Connected tunnels',
          description: 'List all the connected tunnel to the otoroshi cluster',
          img: 'tunnels',
          link: '/tunnels',
          icon: () => 'fab fa-pied-piper-alt',
          display: () => env.userAdmin,
        },
        {
          title: 'Cluster members',
          description: 'List all the nodes of your otoroshi cluster',
          img: 'cluster',
          link: '/cluster',
          icon: () => 'fa-network-wired',
          display: () => env.userAdmin && env.clusterRole === 'Leader',
        },
        {
          title: 'Eureka servers',
          description: 'List all the nodes registered in the local eureka server',
          img: 'eureka',
          link: '/eureka-servers',
          display: () => env.userAdmin,
          icon: () => 'fa-desktop',
        },
      ],
    },
    {
      title: 'Configuration',
      description: 'Configure otoroshi',
      features: [
        {
          title: env.providerDashboardTitle,
          description: 'provider dashboard',
          absoluteImg: '/assets/images/otoroshi-logo-inverse.png',
          link: '/provider',
          display: () => env.userAdmin && env.providerDashboardUrl,
          icon: () => <img src="/assets/images/otoroshi-logo-inverse.png" width="16" />,
        },
        {
          title: 'Danger zone',
          description: 'Break stuff ;)',
          img: 'danger-zone',
          link: '/dangerzone',
          display: () => env.userAdmin,
          icon: () => 'fa-exclamation-triangle',
        },
      ],
    },
    ...Otoroshi.extensions().flatMap((ext) => ext.categories || []),
  ];
};

const AutoLink = (props) => {
  if (props.to.startsWith('http')) {
    return (
      <a {...props} href={props.to} target="_blank">
        {props.children}
      </a>
    );
  } else {
    return <Link {...props}>{props.children}</Link>;
  }
};

const Feature = ({ title, description, img, link, icon, tag }) => {
  const iconValue = icon ? icon() : null;
  const className = _.isString(iconValue)
    ? iconValue.indexOf(' ') > -1
      ? iconValue
      : `fa ${iconValue}`
    : null;
  const zeIcon = iconValue ? _.isString(iconValue) ? <i className={className} /> : iconValue : null;
  return (
    <AutoLink to={link} className="cards">
      <div
        className="cards-header"
        style={{
          background: `url(${img})`,
        }}
      ></div>
      <div className="cards-body">
        <div className="cards-title">
          {zeIcon} {title} {tag}
        </div>
        <div className="cards-description">
          <p>{description}</p>
        </div>
      </div>
    </AutoLink>
  );
};

const Features = ({ title, description, children }) => {
  if (!children || children.length === 0) {
    return null;
  }
  return (
    <div className="my-3">
      <h3 className="mb-0">{title}</h3>
      <p style={{ margin: 0, marginTop: 12, marginBottom: 12 }}>{description}</p>
      <div className="d-flex flex-wrap" style={{ gap: 12, marginBottom: 30, marginTop: 20 }}>
        {children}
      </div>
    </div>
  );
};

export class FeaturesPage extends Component {
  state = {
    shortMenu: false,
  };

  componentDidMount() {
    this.props.setTitle(`Otoroshi features`);
  }

  render() {
    const env = {
      userAdmin: window.__otoroshi__env__latest.userAdmin,
      tenantAdmin: window.__user.tenantAdmin,
      providerDashboardUrl: this.props.env.providerDashboardUrl,
      providerDashboardTitle: this.props.env.providerDashboardTitle,
      clusterRole: this.props.env.clusterRole,
      scriptingEnabled: this.props.env.scriptingEnabled,
    };
    return (
      <>
        <div
          style={{
            width: '100%',
            display: 'flex',
            flexDirection: 'row',
            justifyContent: 'flex-end',
          }}
        >
          <button
            type="button"
            className={`btn btn-sm ${this.props.shortMenu ? 'btn-success' : 'btn-danger'}`}
            onClick={this.props.toggleShortMenu}
          >
            <i className="fa fa-cog" style={{ fontSize: 'small' }} />{' '}
            {this.props.shortMenu
              ? 'Display all features in the settings menu'
              : 'Do not display all features in the settings menu'}
          </button>
        </div>
        {graph(env).map(({ title, description, features = [] }) => {
          if (features.length === 0) return null;
          return (
            <Features title={title} description={description} key={title}>
              {features
                .filter((d) => d.display === undefined || d.display())
                .sort((a, b) => a.title.toLowerCase().localeCompare(b.title.toLowerCase()))
                .map(
                  ({
                    title = 'A module',
                    description = 'A dummy description just to check the view',
                    img,
                    icon,
                    absoluteImg,
                    link = '',
                    tag,
                  }) => (
                    <Feature
                      title={title}
                      icon={icon}
                      description={description}
                      img={absoluteImg || `/assets/images/svgs/${img}.svg`}
                      link={link}
                      tag={tag}
                    />
                  )
                )}
            </Features>
          );
        })}
        <p>
          Thanks to{' '}
          <a href="https://undraw.co/" target="_blank">
            Undraw.co
          </a>{' '}
          for the cool illustrations of this page
        </p>
      </>
    );
  }
}
