import { parseNamuMark } from "./namumark";
import { parseMediaWiki } from "./mediawiki";

export type WikiFormat = "namumark" | "mediawiki" | "plain";

export interface WikiParseResult {
  html: string;
  categories: string[];
  footnotes: string[];
  format: WikiFormat;
}

/**
 * Detect whether raw content is NamuMark or MediaWiki markup.
 *
 * NamuMark indicators:
 * - [목차], [각주], [include(...)]
 * - {{{+1, {{{-1, {{{#hex (formatting blocks)
 * - ||cell||cell|| (table syntax)
 * - [* footnote] style footnotes
 * - [br] macro
 * - [[분류:]] (Korean category, shared with MW but more common in Namu)
 *
 * MediaWiki indicators:
 * - {| ... |} table syntax
 * - {{ template }} (double braces, not triple)
 * - <ref>...</ref>
 * - <references/>
 * - [[Category:]] (English category)
 * - == Heading == with no = h1 = style
 * - <nowiki>, <source>, <syntaxhighlight>
 * - #REDIRECT or #넘겨주기
 * - <!-- HTML comments -->
 */
export function detectFormat(content: string): WikiFormat {
  if (!content || content.length < 10) return "plain";

  let namuScore = 0;
  let mwScore = 0;

  // NamuMark signals
  if (/\[목차\]/.test(content)) namuScore += 3;
  if (/\[각주\]/.test(content)) namuScore += 3;
  if (/\[include\(/.test(content)) namuScore += 3;
  if (/\{\{\{[+\-]\d/.test(content)) namuScore += 3;
  if (/\{\{\{#[0-9a-fA-F]/.test(content)) namuScore += 3;
  if (/\|\|.+\|\|/.test(content)) namuScore += 2;
  if (/\[\*\s/.test(content)) namuScore += 2;
  if (/\[br\]/i.test(content)) namuScore += 2;
  if (/^={1}\s+.+\s+={1}\s*$/m.test(content)) namuScore += 2; // = H1 = (not used in MW)
  if (/~~.+?~~/.test(content)) namuScore += 1;
  if (/__[^_]+__/.test(content)) namuScore += 1;

  // MediaWiki signals
  if (/\{\|/.test(content)) mwScore += 3;
  if (/\|\}/.test(content)) mwScore += 3;
  if (/<ref[\s>]/i.test(content)) mwScore += 3;
  if (/<references/i.test(content)) mwScore += 3;
  if (/<nowiki>/i.test(content)) mwScore += 3;
  if (/<source[\s>]/i.test(content)) mwScore += 2;
  if (/<syntaxhighlight/i.test(content)) mwScore += 2;
  if (/\{\{[^{]/.test(content)) mwScore += 2;
  if (/\[\[Category:/i.test(content)) mwScore += 2;
  if (/<!--/.test(content)) mwScore += 1;
  if (/#(?:REDIRECT|넘겨주기)/i.test(content)) mwScore += 2;
  if (/^#{1,}\s/m.test(content)) mwScore += 1; // # ordered list (MW style)

  if (namuScore === 0 && mwScore === 0) return "plain";
  if (namuScore > mwScore) return "namumark";
  if (mwScore > namuScore) return "mediawiki";

  // Tie-break: MediaWiki is more common in Wikipedia context
  return "mediawiki";
}

export function parseWikiContent(content: string): WikiParseResult {
  const format = detectFormat(content);

  if (format === "namumark") {
    const result = parseNamuMark(content);
    return { ...result, format };
  }

  if (format === "mediawiki") {
    const result = parseMediaWiki(content);
    return { ...result, format };
  }

  // Plain text fallback
  const escaped = content
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
  const html = escaped
    .split("\n\n")
    .filter((p) => p.trim())
    .map((p) => `<p>${p.replace(/\n/g, "<br/>")}</p>`)
    .join("\n");

  return { html, categories: [], footnotes: [], format: "plain" };
}
