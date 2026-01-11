import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Automata',
  description: 'Automata - Jira Webhook Handler',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

