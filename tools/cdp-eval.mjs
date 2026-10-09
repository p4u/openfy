// Evaluate a JS expression in the shell's WebView via the DevTools protocol.
// Usage: node tools/cdp-eval.mjs <port> '<expression>' [gesture]
// `gesture` evaluates with a user activation (needed to call play() on a test element).
const [port, expr, gesture] = process.argv.slice(2);
const pages = await (await fetch(`http://localhost:${port}/json`)).json();
const ws = new WebSocket(pages.find(p => p.type === 'page').webSocketDebuggerUrl);
ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate',
  params: { expression: expr, awaitPromise: true, returnByValue: true, userGesture: gesture === 'gesture' } }));
ws.onmessage = (m) => { const r = JSON.parse(m.data); if (r.id === 1) {
  console.log(JSON.stringify(r.result?.result?.value ?? r.result, null, 1)); ws.close(); } };
