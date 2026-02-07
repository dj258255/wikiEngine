/**
 * NamuMark → HTML parser
 *
 * Supports:
 * - Headings: = h1 =, == h2 ==, ... ====== h6 ======
 * - Bold: '''text'''
 * - Italic: ''text''
 * - Strikethrough: ~~text~~ or --text--
 * - Underline: __text__
 * - Superscript: ^^text^^
 * - Subscript: ,,text,,
 * - Links: [[page]], [[page|display]], [[url]]
 * - External links: [url], [url text]
 * - Images: [[파일:name]] / [[file:name]]
 * - Categories: [[분류:name]] (hidden, collected)
 * - Size: {{{+n text}}}, {{{-n text}}}
 * - Color: {{{#hex text}}}
 * - Code blocks: {{{code}}} (inline), {{{#!syntax lang\n...\n}}} (block)
 * - Tables: ||cell||cell||
 * - Lists: * (unordered), 1. (ordered)
 * - Quotes: > text
 * - Horizontal rule: ----
 * - Footnotes: [* text] / [*A text]
 * - Macros: [include(...)], [br], [date], [age(...)]
 * - Literal nowiki: {{{text}}} when no formatting prefix
 */

const escapeHtml = (s: string): string =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#039;");

interface ParseResult {
  html: string;
  categories: string[];
  footnotes: string[];
}

export function parseNamuMark(raw: string): ParseResult {
  const categories: string[] = [];
  const footnotes: string[] = [];
  let footnoteIndex = 0;

  // Normalize line endings
  let text = raw.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

  // Extract and process multi-line code blocks first (preserve content)
  const codeBlocks: string[] = [];
  text = text.replace(/\{\{\{#!syntax\s+(\w+)\n([\s\S]*?)\}\}\}/g, (_m, lang, code) => {
    const idx = codeBlocks.length;
    codeBlocks.push(
      `<pre class="wiki-codeblock" data-lang="${escapeHtml(lang)}"><code>${escapeHtml(code.trimEnd())}</code></pre>`
    );
    return `\x00CODEBLOCK${idx}\x00`;
  });

  // Multi-line raw blocks {{{ ... }}}
  text = text.replace(/\{\{\{([\s\S]*?)\}\}\}/g, (_m, content: string) => {
    // Check for formatting prefixes
    const colorMatch = content.match(/^#([0-9a-fA-F]{3,6})\s/);
    if (colorMatch) {
      const color = colorMatch[1];
      const inner = content.slice(colorMatch[0].length);
      return `<span style="color:#${escapeHtml(color)}">${escapeHtml(inner)}</span>`;
    }
    const sizeMatch = content.match(/^([+-]\d)\s/);
    if (sizeMatch) {
      const delta = parseInt(sizeMatch[1]);
      const size = Math.max(0.6, Math.min(3, 1 + delta * 0.2));
      const inner = content.slice(sizeMatch[0].length);
      return `<span style="font-size:${size}em">${escapeHtml(inner)}</span>`;
    }
    // Plain monospace
    if (!content.includes("\n")) {
      return `<code class="wiki-inline-code">${escapeHtml(content)}</code>`;
    }
    const idx = codeBlocks.length;
    codeBlocks.push(`<pre class="wiki-codeblock"><code>${escapeHtml(content.trimEnd())}</code></pre>`);
    return `\x00CODEBLOCK${idx}\x00`;
  });

  const lines = text.split("\n");
  const outputLines: string[] = [];
  let inTable = false;
  let tableRows: string[] = [];
  let inList = false;
  let listType = "";
  let listItems: string[] = [];
  let inBlockquote = false;
  let quoteLines: string[] = [];

  const flushTable = () => {
    if (tableRows.length > 0) {
      outputLines.push(`<table class="wiki-table">${tableRows.join("")}</table>`);
      tableRows = [];
    }
    inTable = false;
  };

  const flushList = () => {
    if (listItems.length > 0) {
      const tag = listType === "ol" ? "ol" : "ul";
      outputLines.push(
        `<${tag} class="wiki-list">${listItems.map((li) => `<li>${li}</li>`).join("")}</${tag}>`
      );
      listItems = [];
    }
    inList = false;
    listType = "";
  };

  const flushBlockquote = () => {
    if (quoteLines.length > 0) {
      outputLines.push(
        `<blockquote class="wiki-quote">${quoteLines.join("<br/>")}</blockquote>`
      );
      quoteLines = [];
    }
    inBlockquote = false;
  };

  const processInline = (line: string): string => {
    // Categories
    line = line.replace(/\[\[분류:([^\]|]+)(?:\|[^\]]*)?\]\]/g, (_m, cat) => {
      categories.push(cat.trim());
      return "";
    });
    line = line.replace(/\[\[(?:Category):([^\]|]+)(?:\|[^\]]*)?\]\]/gi, (_m, cat) => {
      categories.push(cat.trim());
      return "";
    });

    // Images/files
    line = line.replace(
      /\[\[(?:파일|file|이미지|image):([^\]|]+)(?:\|([^\]]*))?\]\]/gi,
      (_m, src, opts) => {
        const alt = opts || src;
        return `<figure class="wiki-image"><img src="${escapeHtml(src.trim())}" alt="${escapeHtml(alt.trim())}" loading="lazy"/></figure>`;
      }
    );

    // Internal links
    line = line.replace(/\[\[([^\]|]+)\|([^\]]+)\]\]/g, (_m, href, display) => {
      return `<a class="wiki-link" href="/wiki/${encodeURIComponent(href.trim())}">${escapeHtml(display.trim())}</a>`;
    });
    line = line.replace(/\[\[([^\]]+)\]\]/g, (_m, href) => {
      if (href.startsWith("http://") || href.startsWith("https://")) {
        return `<a class="wiki-ext-link" href="${escapeHtml(href.trim())}" rel="noopener noreferrer">${escapeHtml(href.trim())}</a>`;
      }
      return `<a class="wiki-link" href="/wiki/${encodeURIComponent(href.trim())}">${escapeHtml(href.trim())}</a>`;
    });

    // External links [url text]
    line = line.replace(/\[(https?:\/\/[^\s\]]+)\s+([^\]]+)\]/g, (_m, url, text) => {
      return `<a class="wiki-ext-link" href="${escapeHtml(url)}" rel="noopener noreferrer">${escapeHtml(text)}</a>`;
    });
    line = line.replace(/\[(https?:\/\/[^\s\]]+)\]/g, (_m, url) => {
      return `<a class="wiki-ext-link" href="${escapeHtml(url)}" rel="noopener noreferrer">${escapeHtml(url)}</a>`;
    });

    // Footnotes [* text] / [*A text]
    line = line.replace(/\[\*([A-Za-z]?)\s+([^\]]+)\]/g, (_m, _label, content) => {
      footnoteIndex++;
      const idx = footnoteIndex;
      footnotes.push(content.trim());
      return `<sup class="wiki-footnote-ref"><a href="#fn-${idx}" id="fnref-${idx}">[${idx}]</a></sup>`;
    });

    // Macros
    line = line.replace(/\[br\]/gi, "<br/>");
    line = line.replace(/\[date\]/gi, new Date().toLocaleDateString("ko-KR"));
    line = line.replace(/\[include\([^\)]*\)\]/gi, "");
    line = line.replace(/\[age\([^\)]*\)\]/gi, "");
    line = line.replace(/\[youtube\([^\)]*\)\]/gi, '<span class="wiki-placeholder">[YouTube]</span>');

    // Bold '''text'''
    line = line.replace(/'''(.+?)'''/g, "<strong>$1</strong>");
    // Italic ''text''
    line = line.replace(/''(.+?)''/g, "<em>$1</em>");
    // Strikethrough ~~text~~
    line = line.replace(/~~(.+?)~~/g, "<del>$1</del>");
    // Strikethrough --text--
    line = line.replace(/--(.+?)--/g, "<del>$1</del>");
    // Underline __text__
    line = line.replace(/__(.+?)__/g, "<u>$1</u>");
    // Superscript ^^text^^
    line = line.replace(/\^\^(.+?)\^\^/g, "<sup>$1</sup>");
    // Subscript ,,text,,
    line = line.replace(/,,(.+?),,/g, "<sub>$1</sub>");

    return line;
  };

  for (let i = 0; i < lines.length; i++) {
    let line = lines[i];

    // Code block placeholder - pass through
    if (line.match(/^\x00CODEBLOCK\d+\x00$/)) {
      flushTable();
      flushList();
      flushBlockquote();
      outputLines.push(line);
      continue;
    }

    // Horizontal rule
    if (/^-{4,}\s*$/.test(line)) {
      flushTable();
      flushList();
      flushBlockquote();
      outputLines.push('<hr class="wiki-hr"/>');
      continue;
    }

    // Headings: = H1 = through ====== H6 ======
    const headingMatch = line.match(/^(={1,6})\s*(.+?)\s*={1,6}\s*$/);
    if (headingMatch) {
      flushTable();
      flushList();
      flushBlockquote();
      const level = headingMatch[1].length;
      const content = processInline(headingMatch[2]);
      const id = headingMatch[2].replace(/\s+/g, "-").replace(/[^\w가-힣-]/g, "");
      outputLines.push(`<h${level} id="${escapeHtml(id)}" class="wiki-heading wiki-h${level}">${content}</h${level}>`);
      continue;
    }

    // Blockquote
    if (line.startsWith(">")) {
      flushTable();
      flushList();
      inBlockquote = true;
      quoteLines.push(processInline(line.replace(/^>\s?/, "")));
      continue;
    } else if (inBlockquote) {
      flushBlockquote();
    }

    // Table rows: ||cell||cell||
    if (line.startsWith("||") && line.endsWith("||")) {
      flushList();
      flushBlockquote();
      inTable = true;
      const cells = line.slice(2, -2).split("||");
      const row = cells
        .map((cell) => {
          let cls = "";
          let processed = cell;
          // Table cell alignment
          if (processed.startsWith(" ") && processed.endsWith(" ")) cls = "text-center";
          else if (processed.endsWith(" ")) cls = "text-left";
          else if (processed.startsWith(" ")) cls = "text-right";
          processed = processInline(processed.trim());
          return `<td class="${cls}">${processed}</td>`;
        })
        .join("");
      tableRows.push(`<tr>${row}</tr>`);
      continue;
    } else if (inTable) {
      flushTable();
    }

    // Unordered list
    if (/^\s*\*\s+/.test(line)) {
      flushTable();
      flushBlockquote();
      if (inList && listType !== "ul") flushList();
      inList = true;
      listType = "ul";
      listItems.push(processInline(line.replace(/^\s*\*\s+/, "")));
      continue;
    }

    // Ordered list
    if (/^\s*\d+\.\s+/.test(line) || /^\s*#\s+/.test(line)) {
      flushTable();
      flushBlockquote();
      if (inList && listType !== "ol") flushList();
      inList = true;
      listType = "ol";
      listItems.push(processInline(line.replace(/^\s*(?:\d+\.|#)\s+/, "")));
      continue;
    }

    if (inList) {
      flushList();
    }

    // Empty line → paragraph break
    if (line.trim() === "") {
      flushTable();
      flushList();
      flushBlockquote();
      outputLines.push("");
      continue;
    }

    // Regular paragraph
    outputLines.push(`<p>${processInline(line)}</p>`);
  }

  flushTable();
  flushList();
  flushBlockquote();

  let html = outputLines.join("\n");

  // Restore code blocks
  codeBlocks.forEach((block, idx) => {
    html = html.replace(`\x00CODEBLOCK${idx}\x00`, block);
    // Also handle wrapped in <p>
    html = html.replace(`<p>\x00CODEBLOCK${idx}\x00</p>`, block);
  });

  // Build footnotes section
  if (footnotes.length > 0) {
    html += `\n<section class="wiki-footnotes"><hr class="wiki-hr"/><ol>`;
    footnotes.forEach((note, idx) => {
      html += `<li id="fn-${idx + 1}"><a href="#fnref-${idx + 1}">↑</a> ${escapeHtml(note)}</li>`;
    });
    html += `</ol></section>`;
  }

  // Remove empty paragraphs
  html = html.replace(/<p>\s*<\/p>/g, "");

  return { html, categories, footnotes };
}
