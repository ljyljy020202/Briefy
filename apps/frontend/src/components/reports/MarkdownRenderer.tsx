// TODO: Replace with react-markdown + rehype-sanitize once the briefings API is live.
// Install: npm install react-markdown rehype-sanitize
// Then swap in:
//   import ReactMarkdown from "react-markdown";
//   import rehypeSanitize from "rehype-sanitize";
//   return <ReactMarkdown rehypePlugins={[rehypeSanitize]}>{content}</ReactMarkdown>;
// Do NOT use dangerouslySetInnerHTML with unsanitized content.

interface MarkdownRendererProps {
  content: string;
}

export function MarkdownRenderer({ content }: MarkdownRendererProps) {
  return (
    <pre className="whitespace-pre-wrap font-sans text-sm leading-relaxed text-foreground">
      {content}
    </pre>
  );
}
