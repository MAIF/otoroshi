import {visit} from 'unist-util-visit';

/**
 * Rehype plugin that prepends the site baseUrl to local <img> src attributes.
 * This ensures HTML <img> tags work correctly regardless of the configured baseUrl.
 */
function rehypeImgBaseUrl(options) {
  const baseUrl = (options && options.baseUrl) || '/';
  return (tree) => {
    visit(tree, 'element', (node) => {
      if (node.tagName === 'img' && node.properties && node.properties.src) {
        const src = node.properties.src;
        // Only rewrite absolute local paths (starting with /), not external URLs
        if (src.startsWith('/') && !src.startsWith('//') && !src.startsWith(baseUrl)) {
          node.properties.src = baseUrl + src.slice(1);
        }
      }
    });
  };
}

export default rehypeImgBaseUrl;
