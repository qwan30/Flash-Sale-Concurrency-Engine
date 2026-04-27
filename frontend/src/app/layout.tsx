import type { Metadata } from "next";

import { AppToaster } from "@/components/app-toaster";

import "./globals.css";

export const metadata: Metadata = {
  title: "Flash-sale Tickets",
  description: "Customer-facing ticket website shell with an admin flash-sale lab.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="h-full antialiased">
      <body className="flex min-h-full flex-col">
        {children}
        <AppToaster />
      </body>
    </html>
  );
}
