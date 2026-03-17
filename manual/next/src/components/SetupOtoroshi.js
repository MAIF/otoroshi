import React from 'react';
import CodeBlock from '@theme/CodeBlock';
import { version } from '@site/version';

const createRouteCommand = `curl -X POST 'http://otoroshi-api.oto.tools:8080/api/routes' \\
-H "Content-type: application/json" \\
-u admin-api-apikey-id:admin-api-apikey-secret \\
-d @- <<'EOF'
{
  "name": "my-service",
  "frontend": {
    "domains": ["myservice.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "request.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ]
  }
}
EOF`;

export default function SetupOtoroshi({ withRoute = false }) {
  return (
    <details className="foldable-block">
      <summary>Set up an Otoroshi</summary>
      <div>
        <p>
          If you already have an up and running otoroshi instance, you can skip the following instructions.
        </p>
        <p>Let's start by downloading the latest Otoroshi.</p>
        <CodeBlock language="sh">
          {`curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v${version}/otoroshi.jar'`}
        </CodeBlock>
        <p>then you can run start Otoroshi :</p>
        <CodeBlock language="sh">
          {`java -Dotoroshi.adminPassword=password -jar otoroshi.jar`}
        </CodeBlock>
        <p>
          Now you can log into Otoroshi at{' '}
          <a href="http://otoroshi.oto.tools:8080">http://otoroshi.oto.tools:8080</a>{' '}
          with <code>admin@otoroshi.io/password</code>
        </p>
        {withRoute && (
          <>
            <p>
              Create a new route, exposed on <code>http://myservice.oto.tools:8080</code>,
              which will forward all requests to the mirror <code>https://request.otoroshi.io</code>.
              Each call to this service will returned the body and the headers received by the mirror.
            </p>
            <CodeBlock language="sh">{createRouteCommand}</CodeBlock>
          </>
        )}
      </div>
    </details>
  );
}
