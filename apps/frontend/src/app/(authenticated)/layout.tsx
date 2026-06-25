import { AuthProvider } from "@/contexts/AuthContext";
import { Header } from "@/components/layout/Header";

export default function AuthenticatedLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <AuthProvider redirectOnUnauthenticated="/">
      <div className="min-h-screen flex flex-col">
        <Header />
        <main className="flex-1 max-w-5xl mx-auto w-full px-4 py-8">
          {children}
        </main>
      </div>
    </AuthProvider>
  );
}
