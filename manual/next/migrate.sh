#!/bin/bash
# Migration script: convert Paradox syntax to Docusaurus MDX-compatible Markdown

SRC="/Users/mathieuancelin/projects/otoroshi/manual/src/main/paradox"
DST="/Users/mathieuancelin/projects/otoroshi/manual/next/docs"

# Copy all markdown files preserving directory structure
copy_md_files() {
    local src_dir="$1"
    local dst_dir="$2"
    local rel_path="$3"

    for file in "$src_dir"/*.md; do
        [ -f "$file" ] || continue
        local basename=$(basename "$file")
        # Skip includes directory files
        if [ "$rel_path" = "includes" ]; then
            continue
        fi
        cp "$file" "$dst_dir/$basename"
    done

    for dir in "$src_dir"/*/; do
        [ -d "$dir" ] || continue
        local dirname=$(basename "$dir")
        # Skip special directories
        if [[ "$dirname" == "imgs" || "$dirname" == "code" || "$dirname" == "snippets" || "$dirname" == "_template" || "$dirname" == "includes" ]]; then
            continue
        fi
        mkdir -p "$dst_dir/$dirname"
        copy_md_files "$dir" "$dst_dir/$dirname" "$dirname"
    done
}

copy_md_files "$SRC" "$DST" ""

echo "Files copied. Now converting Paradox syntax..."

# Convert all .md files
find "$DST" -name "*.md" -not -path "*/snippets/*" | while read -r file; do
    # Create temp file
    tmpfile=$(mktemp)

    # Process line by line with perl for complex transformations
    perl -0777 -pe '
        # Remove @@@ index blocks entirely (including content)
        s/\@\@\@ index\n(.*?)\n\@\@\@//gs;

        # Convert @ref:[text](path) to [text](path)
        s/\@ref:\[([^\]]*)\]\(([^)]*)\)/[$1]($2)/g;

        # Convert @link:[text](url) { open=new } to [text](url)
        s/\@link:\[([^\]]*)\]\(([^)]*)\)\s*\{[^}]*\}/[$1]($2)/g;

        # Convert @@@ warning ... @@@ to :::warning ... :::
        s/\@\@\@ warning\s*\n/:::warning\n/g;

        # Convert @@@ note ... @@@ to :::note ... :::
        s/\@\@\@ note\s*\n/:::note\n/g;

        # Convert standalone @@@ (closing) to ::: BUT only for admonitions
        # We need to be careful here - only close admonitions

        # Convert @@@ div { .centered-img } blocks - replace with plain content
        s/\@\@\@ div \{ \.centered-img \}\n(.*?)\n\@\@\@/<div style={{textAlign: "center"}}>\n$1\n<\/div>/gs;

        # Convert @@@ div { .swagger-frame } blocks
        s/\@\@\@ div \{ \.swagger-frame \}\n\n*\n*\@\@\@//gs;

        # Convert @@@div { .entities } blocks to simple links
        # These are card-style entity references
        s/\@\@\@div \{ \.entities \}\n<img[^>]*>\n<div>\n<span>([^<]*)<\/span>\n<span>([^<]*)<\/span>\n<\/div>\n\[([^\]]*)\]\(([^)]*)\)\n\@\@\@/- **[$1]($4)**: $2/gs;

        # Convert @@@div { .plugin .platform... } blocks
        s/\@\@\@div \{ \.plugin[^}]* \}\s*\n/\n/g;

        # Convert @@@ div { .break } blocks
        s/\@\@\@ div \{ \.break \}\n(.*?)\n\@\@\@/$1/gs;

        # Remove remaining @@@ closers (for divs that were opened)
        # But preserve ::: closers for admonitions
        s/^\@\@\@\s*$/:::/gm;

        # Convert @@snip [label](path) { #tag } to a note about included code
        s/\@\@snip \[([^\]]*)\]\(([^)]*)\)(\s*\{[^}]*\})?/> **Included file**: `$2`/g;

        # Fix image paths: ./imgs/ -> /img/docs/
        s/\.\/imgs\//\/img\/docs\//g;
        s/\.\.\/imgs\//\/img\/docs\//g;

        # Fix .html extensions in links to .md
        # Actually for docusaurus, links should use .md or no extension
        # Keep .md as-is

    ' "$file" > "$tmpfile"

    mv "$tmpfile" "$file"

    echo "  Converted: $file"
done

echo "Migration complete!"
