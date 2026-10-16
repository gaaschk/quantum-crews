import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "Quantum Crews", description: "Build AI teams that help you get work done. By Quantum Horizon Innovations." };
export default function Layout({ children }: { children: React.ReactNode }) { return <html lang="en"><body>{children}</body></html>; }
