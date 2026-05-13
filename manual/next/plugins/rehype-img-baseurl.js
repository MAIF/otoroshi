import {visit} from 'unist-util-visit';

/**
 * Rehype plugin that prepends the site baseUrl to local <img> src attributes.
 * This ensures HTML <img> tags work correctly regardless of the configured baseUrl.
 *
 * Handles both:
 * - Regular HTML elements (node.type === 'element') in .md files
 * - JSX elements (node.type === 'mdxJsxFlowElement' / 'mdxJsxTextElement') in .mdx files
 */
function rehypeImgBaseUrl(options) {
  const baseUrl = (options && options.baseUrl) || '/';

  function rewriteSrc(src) {
    if (typeof src === 'string' && src.startsWith('/') && !src.startsWith('//') && !src.startsWith(baseUrl)) {
      return baseUrl + src.slice(1);
    }
    return null;
  }

  return (tree) => {
    visit(tree, (node) => {
      // Standard HTML elements (parsed in .md files)
      if (node.type === 'element' && node.tagName === 'img' && node.properties && node.properties.src) {
        const newSrc = rewriteSrc(node.properties.src);
        if (newSrc) node.properties.src = newSrc;
      }

      // MDX JSX elements (parsed in .mdx files)
      if (
        (node.type === 'mdxJsxFlowElement' || node.type === 'mdxJsxTextElement') &&
        node.name === 'img'
      ) {
        const attrs = node.attributes || [];
        for (const attr of attrs) {
          if (attr.type === 'mdxJsxAttribute' && attr.name === 'src' && typeof attr.value === 'string') {
            const newSrc = rewriteSrc(attr.value);
            if (newSrc) attr.value = newSrc;
          }
        }
      }
    });
  };
}

export default rehypeImgBaseUrl;
