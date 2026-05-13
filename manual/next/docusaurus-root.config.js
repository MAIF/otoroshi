import config from './docusaurus.config';

const newConfig = { 
  ...config, 
  baseUrl: '/',
  url: 'https://www.otoroshi.io',
};

export default newConfig