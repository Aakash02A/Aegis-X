// Loopback-only Razorpay Orders stub for the offline Phase 11 flow.
// It creates order records only; it does not simulate or execute a payment.
import crypto from 'node:crypto';
import http from 'node:http';

const port = Number(process.argv[2]);
if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('A valid loopback mock port is required');
}

const server = http.createServer((request, response) => {
  if (request.method === 'GET' && request.url === '/healthz') {
    response.writeHead(200, { 'Content-Type': 'application/json' }).end('{"status":"UP"}');
    return;
  }
  if (request.method === 'GET' && request.url?.startsWith('/v1/orders?')) {
    response.writeHead(200, { 'Content-Type': 'application/json' }).end('{"items":[]}');
    return;
  }
  if (request.method === 'POST' && request.url === '/v1/orders') {
    let body = '';
    request.setEncoding('utf8');
    request.on('data', chunk => { body += chunk; });
    request.on('end', () => {
      let order;
      try { order = JSON.parse(body); } catch {
        response.writeHead(400).end();
        return;
      }
      if (!Number.isSafeInteger(order.amount) || typeof order.currency !== 'string'
          || typeof order.receipt !== 'string' || !order.receipt) {
        response.writeHead(400).end();
        return;
      }
      const id = `order_e2e_${crypto.createHash('sha256').update(order.receipt).digest('hex').slice(0, 20)}`;
      response.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({
        id,
        entity: 'order',
        amount: order.amount,
        currency: order.currency,
        receipt: order.receipt,
        status: 'created',
      }));
    });
    return;
  }
  response.writeHead(404).end();
});

server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`loopback Razorpay Orders stub ready on port ${port}\n`);
});
