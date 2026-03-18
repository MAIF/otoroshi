import React from 'react';
import clsx from 'clsx';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import useBaseUrl from '@docusaurus/useBaseUrl';
import styles from './index.module.css';

const features = [
  {
    title: 'Blazing Fast Reverse Proxy',
    icon: '⚡',
    description: 'Handle tens of thousands of concurrent routes with a high-performance engine supporting HTTP/1.1, HTTP/2, HTTP/3 (QUIC), WebSocket, gRPC, and GraphQL.',
  },
  {
    title: 'Enterprise-Grade Security',
    icon: '🛡️',
    description: (<>Built-in mTLS, API keys with quotas, JWT token validation, Eclipse Biscuit validation<a href="#ecosystem-note" style={{ fontWeight: 'bold' }}>*</a>, OAuth2/OIDC, LDAP, SAML, WASM-based auth, powerful WAF<a href="#ecosystem-note" style={{ fontWeight: 'bold' }}>*</a> (using OWASP CoreRuleSet), and fine-grained RBAC.</>),
  },
  {
    title: '200+ Built-in Plugins',
    icon: '🧩',
    description: 'Circuit breakers, rate limiting, CORS, body transformation, caching, compression, traffic mirroring, and much more — all configurable at runtime.',
  },
  {
    title: (<>AI Gateway<a href="#ecosystem-note" style={{ fontWeight: 'bold' }}>*</a></>),
    icon: '🤖',
    description: (<>Turn Otoroshi into a full-featured AI Gateway<a href="#ecosystem-note" style={{ fontWeight: 'bold' }}>*</a>. Connect 50+ LLM providers through a unified OpenAI-compatible API with load balancing, fallback, guardrails, prompt engineering, semantic caching, cost tracking, and MCP (Model Context Protocol) support.</>),
  },
  {
    title: 'Dynamic Hot Configuration',
    icon: '🔄',
    description: 'Change any configuration at runtime without restarts or reloads. Every setting is instantly propagated across your cluster.',
  },
  {
    title: 'Full-Featured PKI',
    icon: '🔐',
    description: 'Internal certificate authority with ACME/Let\'s Encrypt, OCSP, on-the-fly certificate generation, and JWKS exposition.',
  },
  {
    title: 'Kubernetes Native',
    icon: '☸️',
    description: 'Ingress controller, CRD support, Gateway API, admission webhooks, sidecar injection, and bidirectional TLS sync.',
  },
  {
    title: 'Powerful Observability',
    icon: '📊',
    description: 'Export events to Elasticsearch, Kafka, Datadog, Prometheus, OpenTelemetry, and 15+ other backends. Real-time metrics and analytics.',
  },
  {
    title: 'Truely Extensible',
    icon: '🔌',
    description: 'Write custom plugins in Scala or any language compiled to WebAssembly. Extend auth, transformations, and traffic policies with full flexibility.',
  },
  {
    title: 'No-Code Workflows',
    icon: '🔀',
    description: 'Build automation pipelines and no-code plugins with a visual workflow editor. Chain HTTP calls, transformations, and logic without writing a single line of code.',
  },
  {
    title: 'GitOps Ready',
    icon: '🚀',
    description: 'Remote Catalogs with reconciliation from GitHub, GitLab, Bitbucket, S3, and more. Declarative config with webhook-triggered deployments.',
  },
];

const useCases = [
  {
    // title: 'API Gateway',
    title: 'API Control Plane',
    description: 'Centralize API traffic management with authentication, rate limiting, and monitoring.',
    icon: '🌐',
  },
  {
    // title: 'Service Mesh',
    title: 'Traffic Orchestration',
    description: 'Manage inter-service communication with mTLS, circuit breakers, and retry policies.',
    icon: '🕸️',
  },
  {
    title: 'Multi-Cloud Proxy',
    description: 'Route traffic across cloud providers with relay routing and network tunnels.',
    icon: '☁️',
  },
  {
    title: 'Developer Portal',
    description: 'Combine with Daikoku for a complete API marketplace with self-service onboarding.',
    icon: '👩‍💻',
  },
  {
    title: (<>AI Gateway<a href="#ecosystem-note" style={{ fontWeight: 'bold' }}>*</a></>),
    description: (<>Secure and manage LLM access with guardrails, cost controls, MCP integration, and a unified API for 50+ providers<a href="#ecosystem-note" style={{ fontWeight: 'bold' }}>*</a>.</>),
    icon: '🤖',
  },
];

const stats = [
  { value: '200+', label: 'Built-in Plugins' },
  { value: '15+', label: 'Event Exporters' },
  { value: '10+', label: 'Auth Protocols' },
  { value: '50+', label: 'LLM Providers supported' },
  //{ value: '6', label: 'Storage Backends' },
];

function HeroBanner() {
  const {siteConfig} = useDocusaurusContext();
  const logoUrl = useBaseUrl('/img/otoroshi-logo.png');
  return (
    <header className={styles.heroBanner}>
      <div className="container">
        <div className={styles.heroContent}>
          <div className={styles.heroText}>
            <Heading as="h1" className={styles.heroTitle}>
              The Cloud Native <span className={styles.highlight}>API & AI Gateway</span> for Modern Architectures
            </Heading>
            <p className={styles.heroSubtitle}>
              Otoroshi is a lightweight, high-performance reverse proxy and API gateway with dynamic hot configuration,
              enterprise security, and deep observability — built for teams who need control without complexity.
            </p>
            <div className={styles.heroButtons}>
              <Link className="button button--primary button--lg" to="/docs/getting-started">
                Get Started
              </Link>
              <Link className="button button--outline button--lg" to="/docs/">
                Read the Docs
              </Link>
              <Link
                className={clsx("button button--outline button--lg", styles.githubButton)}
                href="https://github.com/MAIF/otoroshi">
                GitHub
              </Link>
            </div>
            <div className={styles.heroMeta}>
              <div className={styles.heroInstall}>
                <code>docker run -p "8080:8080" maif/otoroshi</code>
              </div>
              <a
                className={styles.githubStarsBadge}
                href="https://github.com/MAIF/otoroshi"
                target="_blank"
                rel="noopener noreferrer"
              >
                <img
                  src="https://img.shields.io/github/stars/MAIF/otoroshi?style=social"
                  alt="GitHub stars"
                />
              </a>
            </div>
          </div>
          <div className={styles.heroImage}>
            <img src={logoUrl} alt="Otoroshi" width="300" />
          </div>
        </div>
      </div>
    </header>
  );
}

function StatsSection() {
  return (
    <section className={styles.statsSection}>
      <div className="container">
        <div className={styles.statsGrid}>
          {stats.map((stat, idx) => (
            <div key={idx} className={styles.statItem}>
              <div className={styles.statValue}>{stat.value}</div>
              <div className={styles.statLabel}>{stat.label}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function FeaturesSection() {
  return (
    <section className={styles.featuresSection}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <Heading as="h2">Everything You Need for API Management</Heading>
          <p>A single binary that replaces your API gateway, reverse proxy, and service mesh — with zero external dependencies.</p>
        </div>
        <div className={styles.featuresGrid}>
          {features.map((feature, idx) => (
            <div key={idx} className={styles.featureCard}>
              <div className={styles.featureIcon}>{feature.icon}</div>
              <Heading as="h3">{feature.title}</Heading>
              <p>{feature.description}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function AIGatewaySection() {
  return (
    <section className={styles.aiGatewaySection}>
      <div className="container">
        <div className={styles.aiGatewayCard}>
          <div className={styles.aiGatewayIntro}>
            <div className={styles.aiGatewayEyebrow}>AI capabilities</div>
            <Heading as="h2">AI Gateway built-in<a href="#ecosystem-note" style={{ fontWeight: 'bold' }}>*</a></Heading>
            <p>
              Connect, secure and control 50+ LLM providers with a unified API<a href="#ecosystem-note" style={{ fontWeight: 'bold' }}>*</a>.
            </p>
          </div>
          <div className={styles.aiGatewayListWrap}>
            <ul className={styles.aiGatewayList}>
              <li>Multi-modal</li>
              <li>Guardrails</li>
              <li>Cost tracking & Bugets management</li>
              <li>MCP support</li>
              <li>Observability</li>
            </ul>
          </div>
        </div>
      </div>
    </section>
  );
}

function UseCasesSection() {
  return (
    <section className={styles.useCasesSection}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <Heading as="h2">Built for Real-World Use Cases</Heading>
          <p>From startups to enterprises, Otoroshi adapts to your architecture.</p>
        </div>
        <div className={styles.useCasesGrid}>
          {useCases.map((useCase, idx) => (
            <div key={idx} className={styles.useCaseCard}>
              <div className={styles.useCaseIcon}>{useCase.icon}</div>
              <Heading as="h3">{useCase.title}</Heading>
              <p>{useCase.description}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function QuickStartSection() {
  return (
    <section className={styles.quickStartSection}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <Heading as="h2">Up and Running in Seconds</Heading>
          <p>One command. That's all it takes.</p>
        </div>
        <div className={styles.quickStartCards}>
          <div className={styles.quickStartCard}>
            <Heading as="h4">Using Docker</Heading>
            <pre><code>docker run -p "8080:8080" maif/otoroshi</code></pre>
          </div>
          <div className={styles.quickStartCard}>
            <Heading as="h4">Using Java</Heading>
            <pre><code>{`curl -L -o otoroshi.jar \\
  'https://github.com/MAIF/otoroshi/releases/latest/download/otoroshi.jar'
java -jar otoroshi.jar`}</code></pre>
          </div>
        </div>
        <div className={styles.quickStartCta}>
          <Link className="button button--primary button--lg" to="/docs/install/get-otoroshi">
            View all installation options
          </Link>
        </div>
      </div>
    </section>
  );
}

function ComparisonSection() {
  return (
    <section className={styles.comparisonSection}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <Heading as="h2">Why Otoroshi?</Heading>
          <p>What makes Otoroshi different from other API gateways. Unlike traditional API gateways, Otoroshi is a runtime programmable gateway that lets you execute logic directly on traffic.</p>
        </div>
        <div className={styles.comparisonGrid}>
          <div className={styles.comparisonItem}>
            <Heading as="h4">Single Binary</Heading>
            <p>No external database required. Run with in-memory storage, Redis, PostgreSQL, Cassandra, S3, or HTTP — your choice.</p>
          </div>
          <div className={styles.comparisonItem}>
            <Heading as="h4">True Hot Reload</Heading>
            <p>Unlike Nginx or HAProxy, every configuration change is instant. No process restarts, no dropped connections.</p>
          </div>
          <div className={styles.comparisonItem}>
            <Heading as="h4">Admin UI Included</Heading>
            <p>A beautiful, full-featured admin dashboard out of the box. No separate tooling or complex CLI workflows needed.</p>
          </div>
          <div className={styles.comparisonItem}>
            <Heading as="h4">Developer Friendly</Heading>
            <p>Complete REST API, expression language, WASM plugins in any language, visual workflow editor, and comprehensive documentation.</p>
          </div>
        </div>
        <div className={styles.comparisonTableWrapper}>
          <table className={styles.comparisonTable}>
            <thead>
              <tr>
                <th>Traditional gateways</th>
                <th>Otoroshi</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>Configure APIs</td>
                <td><strong>Program your gateway</strong></td>
              </tr>
              <tr>
                <td>Static policies</td>
                <td><strong>Dynamic runtime logic</strong></td>
              </tr>
              <tr>
                <td>API-only</td>
                <td><strong>HTTP + API + AI + workflows</strong></td>
              </tr>
              <tr>
                <td>Reload to change</td>
                <td><strong>Change instantly</strong></td>
              </tr>
              <tr>
                <td>Limited extensibility</td>
                <td><strong>Unlimited (WASM + Workflows + plugins)</strong></td>
              </tr>
              <tr>
                <td>Vendor lock-in</td>
                <td><strong>Open & sovereign</strong></td>
              </tr>
              <tr>
                <td>Manage endpoints</td>
                <td><strong>Control flows</strong></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>
  );
}

function FootnoteSection() {
  return (
    <section id="ecosystem-note" className={styles.footnoteSection}>
      <div className="container">
        <p className={styles.footnoteText}>
          * These features require open source extensions from the{' '}
          <Link to="/ecosystem">Otoroshi ecosystem</Link>.
        </p>
      </div>
    </section>
  );
}

function SovereigntySection() {
  return (
    <section className={styles.sovereigntySection}>
      <div className="container">
        <div className={styles.sectionHeader}>
          <Heading as="h2">Open Source &amp; Sovereign by Design</Heading>
          <p>Otoroshi is built from the ground up as an open source project. Your infrastructure, your rules — no strings attached.</p>
        </div>
        <div className={styles.sovereigntyGrid}>
          <div className={styles.sovereigntyCard}>
            <div className={styles.sovereigntyIcon}>🔓</div>
            <Heading as="h4">No Vendor Lock-in</Heading>
            <p>Apache 2.0 licensed, community-driven. Switch, fork, or extend — you're always in control.</p>
          </div>
          <div className={styles.sovereigntyCard}>
            <div className={styles.sovereigntyIcon}>🌍</div>
            <Heading as="h4">Deploy Anywhere</Heading>
            <p>On-premises, any cloud, Kubernetes, bare metal, or edge. Otoroshi runs wherever you need it — no call-home, no telemetry walls.</p>
          </div>
          <div className={styles.sovereigntyCard}>
            <div className={styles.sovereigntyIcon}>🗄️</div>
            <Heading as="h4">Full Control Over Your Data</Heading>
            <p>Every request, every config, every secret stays in your perimeter. Choose your own storage backend and export events on your terms.</p>
          </div>
        </div>
      </div>
    </section>
  );
}

function CTASection() {
  return (
    <section className={styles.ctaSection}>
      <div className="container">
        <Heading as="h2">Ready to Get Started?</Heading>
        <p>Join the community and start managing your APIs with Otoroshi today.</p>
        <div className={styles.ctaButtons}>
          <Link className="button button--primary button--lg" to="/docs/getting-started">
            Quick Start Guide
          </Link>
          <Link className="button button--outline button--lg" href="https://discord.gg/dmbwZrfpcQ">
            Join Discord
          </Link>
        </div>
      </div>
    </section>
  );
}

export default function Home() {
  const {siteConfig} = useDocusaurusContext();
  return (
    <Layout
      title="Cloud Native API & AI Gateway"
      description="Otoroshi is a lightweight, blazing fast API gateway and reverse proxy with dynamic hot configuration, enterprise security, 200+ plugins, and deep observability.">
      <HeroBanner />
      <main>
        <StatsSection />
        <FeaturesSection />
        <AIGatewaySection />
        <ComparisonSection />
        <UseCasesSection />
        <QuickStartSection />
        <SovereigntySection />
        <CTASection />
        <FootnoteSection />
      </main>
    </Layout>
  );
}
