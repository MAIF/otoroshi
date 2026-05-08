import {visit} from 'unist-util-visit';
import fs from 'fs';
import path from 'path';
import { version } from '../version.js';

/**
 * Remark plugin that fills in code blocks with file content at build time.
 *
 * Usage in markdown/mdx:
 *
 *   ```conf include=../snippets/datastores/inmemory.conf
 *   ```
 *
 * The plugin reads the file (path relative to the doc file) and injects its
 * content into the code block. The `include=…` meta is stripped from the output.
 *
 * Variable substitution: occurrences of `@{version}` in the included content
 * are replaced with the current version from `version.js`.
 * Add more variables to the `variables` map below as needed.
 */

const variables = {
  version,
};

function applyVariables(content) {
  return content.replace(/\@\{(\w+)\}/g, (match, name) => {
    return name in variables ? variables[name] : match;
  });
}

function remarkFileInclude() {
  return (tree, vfile) => {
    const docPath = vfile.path || (vfile.history && vfile.history[vfile.history.length - 1]) || '';
    const docDir = path.dirname(docPath);

    visit(tree, 'code', (node) => {
      if (!node.meta) return;

      const match = node.meta.match(/include=(\S+)/);
      if (!match) return;

      const relPath = match[1];
      const filePath = path.resolve(docDir, relPath);

      try {
        node.value = applyVariables(fs.readFileSync(filePath, 'utf-8').trim());
      } catch {
        node.value = `// File not found: ${relPath}`;
      }

      // Remove include= from meta so it doesn't leak into the rendered HTML
      node.meta = node.meta.replace(/include=\S+/, '').trim() || null;
    });
  };
}

export default remarkFileInclude;
