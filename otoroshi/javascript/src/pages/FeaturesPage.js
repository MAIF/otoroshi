import React, { Component } from 'react';
import { Link } from 'react-router-dom';

const graph = [
  {
    title: 'Tooling',
    description: 'Some tools to help you with otoroshi',
    features: [
      {
        title: 'Resource loader',
        description: 'Load one or more resources from text or files in one shot'
      },
      {
        title: 'Snow Monkey',
        description: 'Create chaos in your routes and test your resilience'
      }
    ]
  },
  {
    title: 'Create',
    description: 'Create otoroshi resources',
    features: [
      { title: 'Services', description: 'All your service descriptors', img: 'service', display: true },
      { title: 'Routes', description: 'All your routes', img: 'routes', display: true },
      { title: 'Backends', description: 'All your route backends', img: 'backend', display: true },
      { title: 'Apikeys', description: 'All your apikeys', img: 'apikeys', display: true },
      { title: 'Certificates', description: 'All your certificates', img: 'certificates', display: true },
      { title: 'JWT verifiers', description: 'All your jwt verifiers', img: 'jwt', display: true },
      { title: 'Auth. modules', description: 'All your authentication modules', img: 'private-app', display: true },
      { title: 'TCP services', description: 'All your TCP services', img: 'tcp', display: true },
      { title: 'Organizations', description: 'All your organizations', img: 'orga', display: true },
      { title: 'Teams', description: 'All your temas', img: 'teams', display: true },
      { title: 'Groups', description: 'All your service/route groups', img: 'groups', display: true },
      { title: 'Data exporters', description: 'All your data exporters', img: 'exporters', display: true },
      { title: 'Administrators', description: 'All your otoroshi administrators', img: 'admins', display: true },
      { title: 'Scripts', description: 'All your live scripts', img: 'scripts', display: false },
      { title: 'Route compositions', description: 'routescomp', img: 'routescomp', display: false },
    ]
  },
  {
    title: 'Analytics',
    description: 'Everything about everything on your otoroshi cluster',
    features: [
      {
        title: 'Analytics',
        description: 'All the traffic of your otoroshi cluster visualized in one place',
        img: 'analytics'
      },
      {
        title: 'Global Status',
        description: 'Availability of your services over time',
        img: 'global-status'
      },
      {
        title: 'Events log',
        description: 'Everything that is happening on your otoroshi cluster',
        img: 'events'
      },
      {
        title: 'Audit log',
        description: 'List all administrator actions on your otoroshi cluster',
        img: 'audit'
      },
      {
        title: 'Alerts log',
        description: 'List all alerts happening on your otoroshi cluster',
        img: 'alerts'
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
        img: 'admin-sessions'
      },
      {
        title: 'Auth. module sessions',
        description: 'List all the connected user sessions from auth. modules',
        img: 'auth-sessions'
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
        img: 'private-app'
      },
      {
        title: 'Jwt verifiers',
        description: 'Manage how you want to verify and forge jwt tokens',
        img: 'jwt'
      },
      {
        title: 'Certificates',
        description: 'Manage and generate certificates for call and expose your services',
        img: 'certificates'
      },
      {
        title: 'Apikeys',
        description: 'Manage all your apikeys to access all your services',
        img: 'apikeys'
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
      },
      {
        title: 'Cluster view',
        description: 'List all the nodes of your otoroshi cluster',
        img: 'cluster',
      },
      {
        title: 'Eureka servers',
        description: 'List all the nodes registered in the local eureka server',
        img: 'eureka',
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
        img: 'danger-zone'
      }
    ]
  }
]
const Feature = ({ title, description, img, link }) => {
  return <Link to="#"
    className="d-flex"
    style={{
      backgroundColor: '#efefef',
      boxShadow: '0 1px 3px rgba(25,25,25,.75)',
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
      minHeight: 150
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
    <div className='d-flex flex-wrap' style={{ gap: 12 }}>
      {children}
    </div>
  </div>
}

export class FeaturesPage extends Component {
  componentDidMount() {
    this.props.setTitle(`Features and funtionalities Otoroshi`);
  }

  render() {
    return <>
      {graph.map(({ title, description, features = [] }) => {
        return <Features title={title} description={description} key={title}>
          {features.map(({
            title = "A module",
            description = 'A dummy description just to check the view',
            img,
            link = "" }) => <Feature
              title={title}
              description={description}
              img={`/assets/images/svgs/${img}.svg`}
              link={link}
            />)}
        </Features>
      })}
    </>
  }
}
