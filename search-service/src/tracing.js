const process = require('process');

const { diag, DiagConsoleLogger, DiagLogLevel } = require('@opentelemetry/api');
const { NodeSDK } = require('@opentelemetry/sdk-node');
const { getNodeAutoInstrumentations } = require('@opentelemetry/auto-instrumentations-node');
const { OTLPTraceExporter } = require('@opentelemetry/exporter-trace-otlp-http');
const { resourceFromAttributes } = require('@opentelemetry/resources');

if ((process.env.OTEL_DIAGNOSTIC_LOG_LEVEL || '').toLowerCase() === 'debug') {
  diag.setLogger(new DiagConsoleLogger(), DiagLogLevel.DEBUG);
}

function buildOtlpHttpUrl() {
  const endpoint = process.env.OTEL_EXPORTER_OTLP_ENDPOINT || 'http://localhost:4318';
  const normalized = endpoint.replace(/\/+$/, '');
  return `${normalized}/v1/traces`;
}

const traceExporter = new OTLPTraceExporter({
  url: buildOtlpHttpUrl(),
});

const sdk = new NodeSDK({
  resource: resourceFromAttributes({
    'service.name': process.env.OTEL_SERVICE_NAME || 'search-service',
    'service.version': process.env.npm_package_version || 'unknown',
  }),
  traceExporter,
  instrumentations: [
    getNodeAutoInstrumentations({
      '@opentelemetry/instrumentation-fs': { enabled: false },
    }),
  ],
});

try {
  // sdk.start() may be sync or async depending on sdk version.
  const maybePromise = sdk.start();
  if (maybePromise && typeof maybePromise.then === 'function') {
    maybePromise.catch((err) => {
      // eslint-disable-next-line no-console
      console.error('OpenTelemetry SDK failed to start:', err);
    });
  }
} catch (err) {
  // eslint-disable-next-line no-console
  console.error('OpenTelemetry SDK failed to start:', err);
}

async function shutdown() {
  try {
    await sdk.shutdown();
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('OpenTelemetry SDK failed to shutdown:', err);
  }
}

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
