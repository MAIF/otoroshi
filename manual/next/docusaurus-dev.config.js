import config from './docusaurus.config';

const newConfig = { 
  ...config, 
  url: 'https://maif.github.io',
  baseUrl: '/otoroshi/devmanual/',
};

export default newConfig