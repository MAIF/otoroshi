import React from 'react';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import Link from '@docusaurus/Link';
import styles from './ecosystem.module.css';

const openSourceProjects = [
  {
    name: 'otoroshi-llm-extension',
    description: 'Connect, setup, secure and seamlessly manage AI models using a Universal/OpenAI compatible API.',
    url: 'https://github.com/cloud-apim/otoroshi-llm-extension',
    tags: ['AI', 'AI Gateway', 'LLM'],
  },
  {
    name: 'otoroshi-waf-extension',
    description: 'A Web Application Firewall (WAF) extension for Otoroshi with ModSecurity SecLang and OWASP CRS.',
    url: 'https://github.com/cloud-apim/otoroshi-waf-extension',
    tags: ['Security', 'WAF'],
  },
  {
    name: 'otoroshi-biscuit-studio',
    description: 'Biscuit token studio for Otoroshi. Create, manage and validate Biscuit authorization tokens.',
    url: 'https://github.com/cloud-apim/otoroshi-biscuit-studio',
    tags: ['Security', 'Auth'],
  },
  {
    name: 'otoroshi-plugin-dynamic-js-modules',
    description: 'Execute WASM plugins written in JavaScript without any compilation phase.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-dynamic-js-modules',
    tags: ['Plugin', 'WASM'],
  },
  {
    name: 'otoroshictl',
    description: 'A CLI to manage your Otoroshi clusters with style. Import, export, sync and automate operations.',
    url: 'https://github.com/cloud-apim/otoroshictl',
    tags: ['CLI', 'Tools'],
  },
  {
    name: 'otoroshi-plugin-webhook-validator',
    description: 'Webhook validation plugin for Otoroshi. Validate incoming webhook signatures and payloads.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-webhook-validator',
    tags: ['Plugin', 'Webhook'],
  },
  {
    name: 'otoroshi-plugin-mailer',
    description: 'An Otoroshi plugin to send emails through an asynchronous REST API.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-mailer',
    tags: ['Plugin', 'Email'],
  },
  {
    name: 'otoroshi-api-portal',
    description: 'A developer portal for Otoroshi APIs. Expose and document your APIs for external consumers.',
    url: 'https://github.com/cloud-apim/otoroshi-api-portal',
    tags: ['Portal'],
  },
  {
    name: 'otoroshi-plugin-couchbase',
    description: 'Couchbase storage backend plugin for Otoroshi.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-couchbase',
    tags: ['Plugin', 'Storage'],
  },
  {
    name: 'otoroshi-plugin-spiffe',
    description: 'SPIFFE/SPIRE integration plugin for Otoroshi. Manage workload identity with zero-trust networking.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-spiffe',
    tags: ['Security', 'Zero Trust'],
  },
  {
    name: 'otoroshi-plugin-moesif',
    description: 'Moesif API analytics integration plugin for Otoroshi.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-moesif',
    tags: ['Plugin', 'Analytics'],
  },
  {
    name: 'otoroshi-plugin-curity-phantom-token',
    description: 'Curity Phantom Token integration plugin for Otoroshi.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-curity-phantom-token',
    tags: ['Plugin', 'Auth'],
  }
];

const managedProviders = [
  {
    name: 'Cloud APIM',
    description: 'Cloud APIM provides a fully managed Otoroshi platform with enterprise features, dedicated infrastructure, monitoring, and professional support. Deploy production-ready Otoroshi instances with zero operational overhead.',
    url: 'https://www.cloud-apim.com',
    features: ['Managed Otoroshi instances', 'Enterprise features', 'Dedicated infrastructure', 'Monitoring & alerting', 'Professional support'],
  },
  {
    name: 'Clever Cloud',
    description: 'Clever Cloud is a European cloud provider that offers managed Otoroshi instances as part of their platform. Deploy Otoroshi alongside your applications with automatic scaling and high availability.',
    url: 'https://www.clever-cloud.com',
    features: ['European cloud hosting', 'Auto-scaling', 'High availability', 'Integrated deployment', 'Pay-as-you-go'],
  },
];

function HeroSection() {
  return (
    <header className={styles.hero}>
      <div className="container">
        <Heading as="h1" className={styles.heroTitle}>
          Otoroshi <span className={styles.highlight}>Ecosystem</span>
        </Heading>
        <p className={styles.heroSubtitle}>
          Discover open source projects, managed providers, and professional support around Otoroshi.
        </p>
      </div>
    </header>
  );
}

function OpenSourceSection() {
  return (
    <section className={styles.section}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <Heading as="h2">Open Source Projects</Heading>
          <p>
            Community maintained open source projects that extend Otoroshi with new capabilities. Either with simple plugins or more complex extensions.
          </p>
        </div>
        <div className={styles.projectsGrid}>
          {openSourceProjects.map((project, idx) => (
            <a
              key={idx}
              className={styles.projectCard}
              href={project.url}
              target="_blank"
              rel="noopener noreferrer"
            >
              <div className={styles.projectCardHeader}>
                <h3>{project.name}</h3>
              </div>
              <p>{project.description}</p>
              <div className={styles.tags}>
                {project.tags.map((tag, tidx) => (
                  <span key={tidx} className={styles.tag}>{tag}</span>
                ))}
              </div>
            </a>
          ))}
        </div>
      </div>
    </section>
  );
}

function ManagedProvidersSection() {
  return (
    <section className={styles.sectionAlt}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <Heading as="h2">Managed Otoroshi Providers</Heading>
          <p>
            Don't want to manage Otoroshi yourself? These providers offer fully managed Otoroshi instances
            so you can focus on building your APIs.
          </p>
        </div>
        <div className={styles.providersGrid}>
          {managedProviders.map((provider, idx) => (
            <div key={idx} className={styles.providerCard}>
              <h3>{provider.name}</h3>
              <p>{provider.description}</p>
              <ul className={styles.featureList}>
                {provider.features.map((feature, fidx) => (
                  <li key={fidx}>{feature}</li>
                ))}
              </ul>
              <div className={styles.providerAction}>
                <a href={provider.url} target="_blank" rel="noopener noreferrer">
                  Visit {provider.name}
                </a>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function SupportSection() {
  return (
    <section className={styles.section}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <Heading as="h2">Professional Support</Heading>
          <p>
            Need help with your Otoroshi deployment? Get professional support from the experts.
          </p>
        </div>
        <div className={styles.supportCard}>
          <div className={styles.supportContent}>
            <h3>Support by Cloud APIM</h3>
            <p>
              <a href="https://www.cloud-apim.com" target="_blank" rel="noopener noreferrer">Cloud APIM</a>{' '}
              provides professional support for Otoroshi, including:
            </p>
            <ul>
              <li>Assistance with installation, configuration, and upgrades</li>
              <li>Performance tuning and optimization</li>
              <li>Custom plugin development</li>
              <li>Architecture review and best practices</li>
              <li>Incident response and troubleshooting</li>
              <li>Training and workshops</li>
            </ul>
            <div className={styles.supportActions}>
              <a
                className={styles.supportButton}
                href="https://www.cloud-apim.com"
                target="_blank"
                rel="noopener noreferrer"
              >
                Contact Cloud APIM
              </a>
              <a
                className={styles.supportButtonOutline}
                href="https://discord.gg/dmbwZrfpcQ"
                target="_blank"
                rel="noopener noreferrer"
              >
                Community Discord
              </a>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

export default function Ecosystem() {
  return (
    <Layout
      title="Ecosystem"
      description="Discover the Otoroshi ecosystem: open source projects, managed providers, and professional support."
    >
      <HeroSection />
      <main>
        <OpenSourceSection />
        <ManagedProvidersSection />
        <SupportSection />
      </main>
    </Layout>
  );
}
