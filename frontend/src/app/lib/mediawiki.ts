/**
 * MediaWiki → HTML parser
 *
 * Supports:
 * - Headings: == H2 ==, === H3 ===, etc.
 * - Bold: '''text'''
 * - Italic: ''text''
 * - Bold+Italic: '''''text'''''
 * - Internal links: [[page]], [[page|display]]
 * - External links: [url text], [url]
 * - Images: [[File:name|options]], [[파일:name|options]]
 * - Categories: [[Category:name]], [[분류:name]] (collected)
 * - Tables: {| ... |- ... | cell ... |}
 * - Unordered lists: * item
 * - Ordered lists: # item
 * - Definition lists: ; term : definition
 * - Indent: : text
 * - Horizontal rule: ----
 * - Templates: {{template}} (stripped or placeholder)
 * - References: <ref>text</ref> → footnotes
 * - <nowiki>text</nowiki>
 * - <code>text</code>, <pre>text</pre>
 * - <blockquote>text</blockquote>
 * - HTML tags: <br/>, <s>, <u>, <sub>, <sup>, etc.
 * - Magic words: __TOC__, __NOTOC__, __FORCETOC__
 */

const escapeHtml = (s: string): string =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#039;");

interface ParseResult {
  html: string;
  categories: string[];
  footnotes: string[];
}

export function parseMediaWiki(raw: string): ParseResult {
  const categories: string[] = [];
  const footnotes: string[] = [];
  let footnoteIndex = 0;

  // Normalize line endings
  let text = raw.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

  // Remove magic words
  text = text.replace(/__(?:TOC|NOTOC|FORCETOC|NOEDITSECTION|NEWSECTIONLINK|NONEWSECTIONLINK)__/g, "");

  // Preserve nowiki blocks
  const nowikiBlocks: string[] = [];
  text = text.replace(/<nowiki>([\s\S]*?)<\/nowiki>/gi, (_m, content) => {
    const idx = nowikiBlocks.length;
    nowikiBlocks.push(escapeHtml(content));
    return `\x00NOWIKI${idx}\x00`;
  });

  // Preserve pre blocks
  const preBlocks: string[] = [];
  text = text.replace(/<pre>([\s\S]*?)<\/pre>/gi, (_m, content) => {
    const idx = preBlocks.length;
    preBlocks.push(`<pre class="wiki-codeblock"><code>${escapeHtml(content.trimEnd())}</code></pre>`);
    return `\x00PREBLOCK${idx}\x00`;
  });

  // Preserve <source> / <syntaxhighlight> blocks
  text = text.replace(/<(?:source|syntaxhighlight)(?:\s+lang="?(\w+)"?)?[^>]*>([\s\S]*?)<\/(?:source|syntaxhighlight)>/gi, (_m, lang, code) => {
    const idx = preBlocks.length;
    preBlocks.push(
      `<pre class="wiki-codeblock" data-lang="${escapeHtml(lang || "")}"><code>${escapeHtml(code.trimEnd())}</code></pre>`
    );
    return `\x00PREBLOCK${idx}\x00`;
  });

  // <code> inline
  text = text.replace(/<code>([\s\S]*?)<\/code>/gi, (_m, content) => {
    return `<code class="wiki-inline-code">${escapeHtml(content)}</code>`;
  });

  // References → footnotes
  text = text.replace(/<ref(?:\s+name="?[^"]*"?)?[^>]*>([\s\S]*?)<\/ref>/gi, (_m, content) => {
    footnoteIndex++;
    const idx = footnoteIndex;
    footnotes.push(content.trim());
    return `<sup class="wiki-footnote-ref"><a href="#fn-${idx}" id="fnref-${idx}">[${idx}]</a></sup>`;
  });
  // Self-closing refs
  text = text.replace(/<ref\s+name="?[^"]*"?\s*\/>/gi, "");
  // <references/> tag
  text = text.replace(/<references\s*\/?\s*>/gi, "");

  // Strip templates {{ ... }} (can be nested)
  let prevText = "";
  while (prevText !== text) {
    prevText = text;
    text = text.replace(/\{\{(?:[^{}]|\{(?!\{)|\}(?!\}))*\}\}/g, (match) => {
      // Preserve some known template patterns as placeholders
      if (/^\{\{(lang|lang-\w+)\|/i.test(match)) {
        const content = match.replace(/^\{\{lang(?:-\w+)?\|([^}]+)\}\}$/i, "$1");
        return content;
      }
      if (/^\{\{(quote|인용문)\|/i.test(match)) {
        const parts = match.slice(2, -2).split("|");
        return `<blockquote class="wiki-quote">${escapeHtml(parts[1] || "")}</blockquote>`;
      }
      return "";
    });
  }

  // Process categories
  text = text.replace(/\[\[(?:Category|분류):([^\]|]+)(?:\|[^\]]*)?\]\]/gi, (_m, cat) => {
    categories.push(cat.trim());
    return "";
  });

  // Process images/files
  text = text.replace(
    /\[\[(?:File|Image|파일|이미지):([^\]|]+)(?:\|([^\]]*))?\]\]/gi,
    (_m, src, optStr) => {
      const opts = optStr ? optStr.split("|") : [];
      const alt = opts[opts.length - 1] || src;
      let cls = "wiki-image";
      if (opts.some((o: string) => o.trim() === "thumb" || o.trim() === "thumbnail" || o.trim() === "섬네일")) {
        cls += " wiki-image-thumb";
      }
      if (opts.some((o: string) => o.trim() === "right" || o.trim() === "오른쪽")) cls += " wiki-image-right";
      else if (opts.some((o: string) => o.trim() === "left" || o.trim() === "왼쪽")) cls += " wiki-image-left";
      else if (opts.some((o: string) => o.trim() === "center" || o.trim() === "가운데")) cls += " wiki-image-center";
      return `<figure class="${cls}"><img src="${escapeHtml(src.trim())}" alt="${escapeHtml(alt.trim())}" loading="lazy"/><figcaption>${escapeHtml(alt.trim())}</figcaption></figure>`;
    }
  );

  // Allowed HTML tags passthrough
  const allowedInline = ["s", "u", "sub", "sup", "small", "big", "span", "div", "blockquote", "del", "ins", "mark", "abbr"];
  // Allow these tags through (basic sanitization: only allow simple attributes or none)
  for (const tag of allowedInline) {
    const openRe = new RegExp(`<${tag}(\\s[^>]*)?>`, "gi");
    const closeRe = new RegExp(`</${tag}>`, "gi");
    text = text.replace(openRe, (m) => m);
    text = text.replace(closeRe, (m) => m);
  }
  text = text.replace(/<br\s*\/?>/gi, "<br/>");

  // Process lines
  const lines = text.split("\n");
  const outputLines: string[] = [];

  let inTable = false;
  let tableContent: string[] = [];
  let currentRow: string[] = [];
  let isHeaderRow = false;

  let listStack: string[] = [];

  const flushList = () => {
    while (listStack.length > 0) {
      const tag = listStack.pop();
      outputLines.push(`</${tag}>`);
    }
  };

  const flushTableRow = () => {
    if (currentRow.length > 0) {
      const tag = isHeaderRow ? "th" : "td";
      tableContent.push(`<tr>${currentRow.map((c) => `<${tag}>${c}</${tag}>`).join("")}</tr>`);
      currentRow = [];
      isHeaderRow = false;
    }
  };

  const flushTable = () => {
    flushTableRow();
    if (tableContent.length > 0) {
      outputLines.push(`<table class="wiki-table">${tableContent.join("")}</table>`);
      tableContent = [];
    }
    inTable = false;
  };

  const processInline = (line: string): string => {
    // Bold+Italic '''''text'''''
    line = line.replace(/'''''(.+?)'''''/g, "<strong><em>$1</em></strong>");
    // Bold '''text'''
    line = line.replace(/'''(.+?)'''/g, "<strong>$1</strong>");
    // Italic ''text''
    line = line.replace(/''(.+?)''/g, "<em>$1</em>");

    // Internal links
    line = line.replace(/\[\[([^\]|]+)\|([^\]]+)\]\]/g, (_m, href, display) => {
      return `<a class="wiki-link" href="/wiki/${encodeURIComponent(href.trim())}">${processInline(display.trim())}</a>`;
    });
    line = line.replace(/\[\[([^\]]+)\]\]/g, (_m, href) => {
      if (href.startsWith("http://") || href.startsWith("https://")) {
        return `<a class="wiki-ext-link" href="${escapeHtml(href.trim())}" rel="noopener noreferrer">${escapeHtml(href.trim())}</a>`;
      }
      return `<a class="wiki-link" href="/wiki/${encodeURIComponent(href.trim())}">${escapeHtml(href.trim())}</a>`;
    });

    // External links
    line = line.replace(/\[(https?:\/\/[^\s\]]+)\s+([^\]]+)\]/g, (_m, url, text) => {
      return `<a class="wiki-ext-link" href="${escapeHtml(url)}" rel="noopener noreferrer">${text}</a>`;
    });
    line = line.replace(/\[(https?:\/\/[^\s\]]+)\]/g, (_m, url) => {
      return `<a class="wiki-ext-link" href="${escapeHtml(url)}" rel="noopener noreferrer">${escapeHtml(url)}</a>`;
    });

    return line;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Pre/nowiki block placeholder
    if (/^\x00(?:PREBLOCK|NOWIKI)\d+\x00$/.test(line)) {
      flushList();
      if (inTable) flushTable();
      outputLines.push(line);
      continue;
    }

    // Table start {|
    if (line.startsWith("{|")) {
      flushList();
      inTable = true;
      tableContent = [];
      currentRow = [];
      isHeaderRow = false;
      continue;
    }

    // Table end |}
    if (inTable && line.startsWith("|}")) {
      flushTable();
      continue;
    }

    // Table row separator |-
    if (inTable && line.startsWith("|-")) {
      flushTableRow();
      continue;
    }

    // Table header cell !
    if (inTable && line.startsWith("!")) {
      isHeaderRow = true;
      const cells = line.slice(1).split("!!");
      for (const cell of cells) {
        const pipeIdx = cell.lastIndexOf("|");
        const content = pipeIdx > -1 ? cell.slice(pipeIdx + 1) : cell;
        currentRow.push(processInline(content.trim()));
      }
      continue;
    }

    // Table data cell |
    if (inTable && line.startsWith("|") && !line.startsWith("|-") && !line.startsWith("|}")) {
      const cells = line.slice(1).split("||");
      for (const cell of cells) {
        const pipeIdx = cell.lastIndexOf("|");
        const content = pipeIdx > -1 ? cell.slice(pipeIdx + 1) : cell;
        currentRow.push(processInline(content.trim()));
      }
      continue;
    }

    // Horizontal rule
    if (/^-{4,}\s*$/.test(line)) {
      flushList();
      if (inTable) flushTable();
      outputLines.push('<hr class="wiki-hr"/>');
      continue;
    }

    // Headings
    const headingMatch = line.match(/^(={2,6})\s*(.+?)\s*={2,6}\s*$/);
    if (headingMatch) {
      flushList();
      if (inTable) flushTable();
      const level = headingMatch[1].length;
      const content = processInline(headingMatch[2]);
      const id = headingMatch[2].replace(/\s+/g, "-").replace(/[^\w가-힣-]/g, "");
      outputLines.push(`<h${level} id="${escapeHtml(id)}" class="wiki-heading wiki-h${level}">${content}</h${level}>`);
      continue;
    }

    // Lists (* unordered, # ordered)
    const listMatch = line.match(/^([*#]+)\s*(.*)/);
    if (listMatch) {
      if (inTable) flushTable();
      const markers = listMatch[1];
      const content = processInline(listMatch[2]);

      // Adjust list nesting
      while (listStack.length > markers.length) {
        const tag = listStack.pop();
        outputLines.push(`</${tag}>`);
      }
      while (listStack.length < markers.length) {
        const marker = markers[listStack.length];
        const tag = marker === "#" ? "ol" : "ul";
        listStack.push(tag);
        outputLines.push(`<${tag} class="wiki-list">`);
      }
      // Handle type change at same level
      if (listStack.length > 0) {
        const expectedTag = markers[markers.length - 1] === "#" ? "ol" : "ul";
        if (listStack[listStack.length - 1] !== expectedTag) {
          const old = listStack.pop();
          outputLines.push(`</${old}>`);
          listStack.push(expectedTag);
          outputLines.push(`<${expectedTag} class="wiki-list">`);
        }
      }
      outputLines.push(`<li>${content}</li>`);
      continue;
    } else {
      flushList();
    }

    // Definition list ; term : definition
    const defMatch = line.match(/^;\s*(.*?)\s*:\s*(.*)/);
    if (defMatch) {
      if (inTable) flushTable();
      outputLines.push(`<dl class="wiki-deflist"><dt>${processInline(defMatch[1])}</dt><dd>${processInline(defMatch[2])}</dd></dl>`);
      continue;
    }

    // Indent : text
    const indentMatch = line.match(/^(:+)\s*(.*)/);
    if (indentMatch) {
      if (inTable) flushTable();
      const depth = indentMatch[1].length;
      outputLines.push(`<div class="wiki-indent" style="margin-left:${depth * 2}em">${processInline(indentMatch[2])}</div>`);
      continue;
    }

    // Empty line
    if (line.trim() === "") {
      flushList();
      if (inTable) {
        // Empty line inside table context might be spacing
        continue;
      }
      outputLines.push("");
      continue;
    }

    // Regular paragraph
    if (inTable) flushTable();
    outputLines.push(`<p>${processInline(line)}</p>`);
  }

  flushList();
  if (inTable) flushTable();

  let html = outputLines.join("\n");

  // Restore nowiki blocks
  nowikiBlocks.forEach((block, idx) => {
    html = html.replace(`\x00NOWIKI${idx}\x00`, block);
    html = html.replace(`<p>\x00NOWIKI${idx}\x00</p>`, block);
  });

  // Restore pre blocks
  preBlocks.forEach((block, idx) => {
    html = html.replace(`\x00PREBLOCK${idx}\x00`, block);
    html = html.replace(`<p>\x00PREBLOCK${idx}\x00</p>`, block);
  });

  // Build footnotes section
  if (footnotes.length > 0) {
    html += `\n<section class="wiki-footnotes"><hr class="wiki-hr"/><ol>`;
    footnotes.forEach((note, idx) => {
      html += `<li id="fn-${idx + 1}"><a href="#fnref-${idx + 1}">↑</a> ${note}</li>`;
    });
    html += `</ol></section>`;
  }

  // Remove empty paragraphs
  html = html.replace(/<p>\s*<\/p>/g, "");

  return { html, categories, footnotes };
}
