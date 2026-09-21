import React from 'react';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import Link from '@docusaurus/Link';
import styles from './ecosystem.module.css';

const openSourceProjects = [
  {
    name: 'otoroshi-llm-extension',
    description: 'Turn Otoroshi into an AI Gateway. Connect, setup, secure and seamlessly manage AI models using a Universal/OpenAI compatible API.',
    github_url: 'https://github.com/cloud-apim/otoroshi-llm-extension',
    url: 'https://cloud-apim.github.io/otoroshi-llm-extension/',
    tags: ['AI', 'AI Gateway', 'LLM', 'Cloud APIM'],
  },
  {
    name: 'Otoroshi Threat Protection Suite',
    description: 'Every request judged before it reaches your backend. ModSecurity WAF, OWASP Core Rule Set, threat intelligence and bot defense, running natively inside Otoroshi. No third party in the request path',
    github_url: 'https://github.com/cloud-apim/otoroshi-waf-extension',
    url : 'https://cloud-apim.github.io/otoroshi-waf-extension/',
    tags: ['Security', 'WAF', 'Bot protection', 'Threat intelligence', 'Cloud APIM'],
  },
  {
    name: 'otoroshi-biscuit-studio',
    description: 'Biscuit studio Extension for Otoroshi. Create, manage and validate Eclipse Biscuit authorization tokens.',
    url: 'https://cloud-apim.github.io/otoroshi-biscuit-studio/',
    github_url: 'https://github.com/cloud-apim/otoroshi-biscuit-studio',
    tags: ['Security', 'Authorization', 'authz', 'token', 'Cloud APIM'],
  },
  {
    name: 'otoroshi-plugin-dynamic-js-modules',
    description: 'Execute WASM plugins written in JavaScript without any compilation phase.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-dynamic-js-modules',
    tags: ['Plugin', 'WASM', 'Cloud APIM'],
  },
  {
    name: 'otoroshictl',
    description: 'A CLI to manage your Otoroshi clusters with style. Import, export, sync and automate operations.',
    github_url: 'https://github.com/cloud-apim/otoroshictl',
    url: 'https://cloud-apim.github.io/otoroshictl/',
    tags: ['CLI', 'Tools', 'Cloud APIM'],
  },
  {
    name: 'otoroshi-plugin-webhook-validator',
    description: 'Webhook validation plugin for Otoroshi. Validate incoming webhook signatures and payloads.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-webhook-validator',
    tags: ['Plugin', 'Webhook', 'Cloud APIM'],
  },
  {
    name: 'otoroshi-plugin-mailer',
    description: 'An Otoroshi plugin to send emails through an asynchronous REST API.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-mailer',
    tags: ['Plugin', 'Email', 'Cloud APIM'],
  },
  {
    name: 'otoroshi-plugin-couchbase',
    description: 'Couchbase storage backend plugin for Otoroshi.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-couchbase',
    tags: ['Plugin', 'Storage', 'Cloud APIM'],
  },
  {
    name: 'otoroshi-plugin-spiffe',
    description: 'SPIFFE/SPIRE integration plugin for Otoroshi. Manage workload identity with zero-trust networking.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-spiffe',
    tags: ['Security', 'Zero Trust', 'Cloud APIM'],
  },
  {
    name: 'otoroshi-plugin-moesif',
    description: 'Moesif API analytics integration plugin for Otoroshi.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-moesif',
    tags: ['Plugin', 'Analytics', 'Cloud APIM'],
  },
  {
    name: 'otoroshi-plugin-curity-phantom-token',
    description: 'Curity Phantom Token integration plugin for Otoroshi.',
    url: 'https://github.com/cloud-apim/otoroshi-plugin-curity-phantom-token',
    tags: ['Plugin', 'Auth', 'Cloud APIM'],
  }
];

const devPortals = [
  {
    name: 'Daikoku',
    description: 'An open source developer portal built by MAIF. Daikoku provides API catalog, subscription management, documentation, and usage analytics for your Otoroshi APIs.',
    url: 'https://maif.github.io/daikoku/',
    tags: ['Open Source', 'API Catalog', 'Subscriptions', 'MAIF'],
  },
  {
    name: 'Cloud APIM API Portal',
    description: 'A developer portal for Otoroshi APIs by Cloud APIM. Expose and document your APIs for external consumers.',
    url: 'https://github.com/cloud-apim/otoroshi-api-portal',
    tags: ['Open Source', 'Portal', 'Plugin', 'Cloud APIM'],
  },
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

function GithubIcon() {
  return (
    <svg viewBox="0 0 16 16" width="20" height="20" fill="currentColor" aria-hidden="true">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  );
}

// The whole card links to `url` through a stretched title link (nested <a> elements are invalid HTML),
// the GitHub icon sits above that overlay and links to `github_url` when there is one.
function ProjectCard({ project }) {
  return (
    <div className={styles.projectCard}>
      <div className={styles.projectCardHeader}>
        <h3>
          <a className={styles.projectCardLink} href={project.url} target="_blank" rel="noopener noreferrer">
            {project.name}
          </a>
        </h3>
        {project.github_url && (
          <a
            className={styles.githubLink}
            href={project.github_url}
            target="_blank"
            rel="noopener noreferrer"
            title="Sources on GitHub"
            aria-label={`${project.name} sources on GitHub`}
          >
            <GithubIcon />
          </a>
        )}
      </div>
      <p>{project.description}</p>
      <div className={styles.tags}>
        {project.tags.map((tag, tidx) => (
          <span key={tidx} className={styles.tag}>{tag}</span>
        ))}
      </div>
    </div>
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
            <ProjectCard key={idx} project={project} />
          ))}
        </div>
      </div>
    </section>
  );
}

function DevPortalsSection() {
  return (
    <section className={styles.sectionAlt}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <Heading as="h2">Dev Portals</Heading>
          <p>
            Developer portals that integrate with Otoroshi to expose, document, and manage API access for your consumers.
          </p>
        </div>
        <div className={styles.providersGrid}>
          {devPortals.map((portal, idx) => (
            <ProjectCard key={idx} project={portal} />
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
                  Visit {provider.name} website
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
                Community support (Discord)
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
        <DevPortalsSection />
        <ManagedProvidersSection />
        <SupportSection />
      </main>
    </Layout>
  );
}
