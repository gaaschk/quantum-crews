import Link from "next/link";
import Canvas from "../team-canvas";
export default async function Workspace({ searchParams }: { searchParams: Promise<{ manual?: string }> }) {
 const manual = (await searchParams).manual === "1";
 return <main><header><Link href="/" className="brand">Quantum <strong>Crews</strong><span className="brand-dot" aria-hidden="true">✳</span></Link><Link href="/" className="text-button">← Back to home</Link></header><div className="workspace-page"><Canvas manual={manual}/></div><footer><span>by Quantum Horizon Innovations</span><span>Quantum Crews workspace</span></footer></main>;
}
