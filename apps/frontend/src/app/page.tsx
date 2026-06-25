import { GoogleLoginButton } from "@/components/auth/GoogleLoginButton";

export default function LandingPage() {
  return (
    <main className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-b from-background to-muted/30 p-8">
      <div className="max-w-sm w-full text-center space-y-10">
        <div className="space-y-4">
          <h1 className="text-5xl font-bold tracking-tight">Briefy</h1>
          <p className="text-lg text-muted-foreground leading-relaxed">
            Your personalized AI daily briefing. Stay informed, effortlessly.
          </p>
        </div>

        <div className="space-y-4">
          <ul className="text-sm text-muted-foreground space-y-1 text-left list-none">
            <li>✦ Select topics that matter to you</li>
            <li>✦ Add custom keywords for deeper focus</li>
            <li>✦ Get a curated briefing every morning</li>
          </ul>
          <GoogleLoginButton />
        </div>
      </div>
    </main>
  );
}
