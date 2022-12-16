import React, { Component } from 'react';
import { Link } from 'react-router-dom';

const graph = [
  {
    title: 'Tooling',
    description: 'Some tools to help you with otoroshi',
    features: [
      {
        title: 'Resource loader',
        img: 'resource-loader',
        description: 'Load one or more resources from text or files in one shot',
        link: '/resources-loader'
      },
      {
        title: 'Snow Monkey',
        absoluteImg: '/assets/images/nihonzaru.svg',
        description: 'Create chaos in your routes and test your resilience',
        link: '/snowmonkey'
      }
    ]
  },
  {
    title: 'Create',
    description: 'Create otoroshi resources',
    features: [
      { title: 'Services', description: 'All your service descriptors', img: 'services', display: true, link: '/services' },
      { title: 'Routes', description: 'All your routes', img: 'routes', display: true, link: '/routes' },
      { title: 'Backends', description: 'All your route backends', img: 'backend', display: true, link: '/backends' },
      { title: 'Apikeys', description: 'All your apikeys', img: 'apikeys', display: true, link: '/apikeys' },
      { title: 'Certificates', description: 'All your certificates', img: 'certificates', display: true, link: '/certificates' },
      { title: 'JWT verifiers', description: 'All your jwt verifiers', img: 'jwt', display: true, link: '/jwt-verifiers' },
      { title: 'Auth. modules', description: 'All your authentication modules', img: 'private-apps', display: true, link: '/auth-configs' },
      { title: 'TCP services', description: 'All your TCP services', img: 'tcp', display: true, link: '/tcp/services' },
      { title: 'Organizations', description: 'All your organizations', img: 'orga', display: true, link: '/organizations' },
      { title: 'Teams', description: 'All your temas', img: 'teams', display: true, link: '/teams' },
      { title: 'Groups', description: 'All your service/route groups', img: 'groups', display: true, link: '/groups' },
      { title: 'Data exporters', description: 'All your data exporters', img: 'exporters', display: true, link: '/exporters' },
      { title: 'Administrators', description: 'All your otoroshi administrators', img: 'admins', display: true, link: '/admins' },
      { title: 'Scripts', description: 'All your live scripts', img: 'scripts', display: false, link: '/plugins' },
      { title: 'Route compositions', description: 'routescomp', img: 'routescomp', display: false, link: '/route-compositions' },
    ]
  },
  {
    title: 'Analytics',
    description: 'Everything about everything on your otoroshi cluster',
    features: [
      {
        title: 'Analytics',
        description: 'All the traffic of your otoroshi cluster visualized in one place',
        img: 'analytics',
        link: '/stats'
      },
      {
        title: 'Global Status',
        description: 'Availability of your services over time',
        img: 'global-status',
        link: '/status'
      },
      {
        title: 'Events log',
        description: 'Everything that is happening on your otoroshi cluster',
        img: 'events',
        link: '/events'
      },
      {
        title: 'Audit log',
        description: 'List all administrator actions on your otoroshi cluster',
        img: 'audit',
        link: '/audit'
      },
      {
        title: 'Alerts log',
        description: 'List all alerts happening on your otoroshi cluster',
        img: 'alerts',
        link: '/alerts'
      }
    ]
  },
  {
    title: 'Sessions',
    description: 'Manage the sessions of your users here',
    features: [
      {
        title: 'Admins sessions',
        description: 'List all the connected administrator sessions',
        img: 'auth-sessions',
        link: '/sessions/admin'
      },
      {
        title: 'Auth. module sessions',
        description: 'List all the connected user sessions from auth. modules',
        img: 'admin-sessions',
        link: '/sessions/private'
      }
    ]
  },
  {
    title: 'Security',
    description: 'Everything security related',
    features: [
      {
        title: 'Authentication modules',
        description: 'Manage the access to Otoroshi UI and protect your routes with authentication modules.',
        img: 'private-apps',
        link: '/auth-configs'
      },
      {
        title: 'Jwt verifiers',
        description: 'Manage how you want to verify and forge jwt tokens',
        img: 'jwt',
        link: '/jwt-verifiers'
      },
      {
        title: 'Certificates',
        description: 'Manage and generate certificates for call and expose your services',
        img: 'certificates',
        link: '/certificates'
      },
      {
        title: 'Apikeys',
        description: 'Manage all your apikeys to access all your services',
        img: 'apikeys',
        link: '/apikeys'
      }
    ]
  },
  {
    title: 'Networking',
    description: 'Everything network related',
    features: [
      {
        title: 'Tunnels',
        description: 'List all the connected tunnel to the otoroshi cluster',
        img: 'tunnels',
        link: '/tunnels'
      },
      {
        title: 'Cluster view',
        description: 'List all the nodes of your otoroshi cluster',
        img: 'cluster',
        link: '/cluster'
      },
      {
        title: 'Eureka servers',
        description: 'List all the nodes registered in the local eureka server',
        img: 'eureka',
        link: '/eureka-servers'
      }
    ]
  },
  {
    title: 'Configuration',
    description: 'Configure otoroshi',
    features: [
      {
        title: 'Danger zone',
        description: 'Break things ;)',
        img: 'danger-zone',
        link: '/dangerzone'
      }
    ]
  }
]
const Feature = ({ title, description, img, link }) => {
  return <Link to={link}
    className="d-flex"
    style={{
      backgroundColor: '#efefef',
      boxShadow: '0 1px 3px rgba(225,225,225,.75)',
      margin: '5px 0px',
      height: '100%',
      borderRadius: '12px',
      maxWidth: 300,
      minWidth: 300,
      maxHeight: 300,
      minHeight: 300,
      padding: 0,
      display: 'flex',
      flexDirection: 'column',
      overflow: 'hidden'
    }}>
    <div style={{
      flex: 1,
      background: `url(${img})`,
      backgroundSize: 'contain',
      backgroundPosition: 'center',
      backgroundRepeat: 'no-repeat',
      minHeight: 150,
      margin: 12
    }}>
    </div>
    <div className="d-flex flex-column"
      style={{ flex: 1, backgroundColor: '#fff', padding: '12px' }}>
      <div
        style={{
          fontWeight: 'bold',
          marginLeft: '5px',
          marginTop: '7px',
          marginBottom: '10px',
          fontSize: 20,
          color: '#000',
          textTransform: 'capitalize'
        }}>
        {title}
      </div>
      <div className="me-1" style={{ marginLeft: '5px', marginBottom: '10px', color: '#000' }}>
        <p>{description}</p>
      </div>
    </div>
  </Link>
}

const Features = ({ title, description, children }) => {
  return <div className='mb-3'>
    <h3 className='mb-0'>{title}</h3>
    <p style={{ margin: 0, marginBottom: 12 }}>{description}</p>
    <div className='d-flex flex-wrap' style={{ gap: 12, marginBottom: 30, marginTop: 20}}>
      {children}
    </div>
  </div>
}

export class FeaturesPage extends Component {
  componentDidMount() {
    this.props.setTitle(`Otoroshi Features`);
  }

  render() {
    return <>
      {graph.map(({ title, description, features = [] }) => {
        return <Features title={title} description={description} key={title}>
          {features
            .filter(d => d.display === undefined || d.display)
            .map(({
              title = "A module",
              description = 'A dummy description just to check the view',
              img,
              absoluteImg,
              link = "" }) => <Feature
                title={title}
                description={description}
                img={absoluteImg || `/assets/images/svgs/${img}.svg`}
                link={link}
              />)}
        </Features>
      })}
    </>
  }
}
